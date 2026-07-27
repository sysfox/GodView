package dev.godview.map;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.MetricsService;
import dev.godview.util.JsonWriter;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders the live world map, on a strict tick budget.
 *
 * <p>The work splits cleanly in two, and the split is the whole design. Taking a
 * {@link ChunkSnapshot} must happen on the main thread; everything after that — walking
 * columns, shading, PNG encoding — is pure computation on a detached copy. So the main
 * thread does the minimum: at most {@code map.chunks-per-tick} snapshots, then hands them
 * to a worker and forgets about them.
 *
 * <p>That budget is the single most important number here. Snapshotting a chunk is not
 * free, and a naive map plugin that renders a whole region on demand is exactly the kind
 * of thing GodView's spike detector exists to catch. At the default of four per tick a
 * full 8x8 tile takes sixteen ticks — under a second — while costing well under a
 * millisecond of any individual tick.
 *
 * <p><b>A tile is captured once, for every zoom level at once.</b> Downsampling is done
 * from the same snapshots, because the alternative is what made the map feel broken: the
 * capture queue was keyed by scale, so zooming out asked for the same ground again at
 * sixteen times the tile count, and the budget never got anywhere. Rendering scale 1, 2
 * and 4 together costs about 1.3x rendering scale 1 alone, and zooming is then free.
 *
 * <p><b>Complete tiles are written to disk</b> via {@link TileStore}, so coverage
 * accumulates across restarts instead of expiring with the memory cache. Tiles rendered
 * while some of their chunks were unloaded are held in memory on a short lifetime and
 * redrawn later, rather than being persisted with holes in them.
 *
 * <p>Chunks that are not loaded are pulled in asynchronously when {@code map.load-unloaded}
 * is on. Reading the world off disk to draw somewhere nobody is standing is a real cost,
 * which is why it is rate-limited per second and never blocks a tick — but with it off, the
 * map can only ever show the handful of chunks that happen to be loaded right now.
 */
public final class MapService {

    /** How long a request will wait for a tile to render before answering "not yet". */
    public static final long RENDER_WAIT_MS = 2_500L;
    /** Tile renders that may be queued for the worker pool at once. */
    private static final int RENDER_QUEUE_CAPACITY = 256;
    /** Tile areas that may be awaiting capture at once, across every viewer. */
    private static final int MAX_PENDING_AREAS = 256;
    /** Downsampling factors the API accepts. Every one is produced by a single capture. */
    private static final List<Integer> SCALES = List.of(1, 2, 4);
    /** Lifetime for a tile that rendered with chunks missing, so it redraws soon. */
    private static final long INCOMPLETE_TTL_MS = 20_000L;

    private final Plugin plugin;
    private final Logger log;
    private final MetricsService metrics;

    private volatile GodViewConfig config;
    private volatile boolean running;

    private final TileCache cache = new TileCache();
    private volatile TileStore store;
    private final Map<TileArea, PendingTile> pending = new ConcurrentHashMap<>();
    private final Deque<TileArea> queue = new ArrayDeque<>();

    private BukkitTask captureTask;
    private ThreadPoolExecutor renderer;

    private final AtomicLong tilesRendered = new AtomicLong();
    private final AtomicLong tilesIncomplete = new AtomicLong();
    private final AtomicLong chunksCaptured = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong unloadedSkipped = new AtomicLong();
    private final AtomicLong areasRefused = new AtomicLong();
    private long unloadedLoadedThisSecond;
    private long unloadedWindowStart;

    public MapService(Plugin plugin, GodViewConfig config, MetricsService metrics, Logger log) {
        this.plugin = plugin;
        this.config = config;
        this.metrics = metrics;
        this.log = log;
    }

    /** A tile's identity in the caches: which world, where, at which downsampling. */
    public record TileKey(String world, int tileX, int tileZ, int scale) {

        public String describe() {
            return world + "/" + tileX + "," + tileZ + "@" + scale;
        }

        public TileArea area() {
            return new TileArea(world, tileX, tileZ);
        }
    }

    /**
     * The ground a tile covers, independent of zoom.
     *
     * <p>Capture is keyed by this rather than by {@link TileKey}: the chunks behind a tile
     * are the same at every scale, and queueing them once per zoom level is what made
     * zooming out never finish.
     */
    public record TileArea(String world, int tileX, int tileZ) {

        public TileKey at(int scale) {
            return new TileKey(world, tileX, tileZ, scale);
        }

        public String describe() {
            return world + "/" + tileX + "," + tileZ;
        }
    }

    /** An area being captured, and whoever is waiting for it. */
    private static final class PendingTile {
        final Map<Integer, ChunkSnapshot> snapshots = new HashMap<>();
        final Object lock = new Object();
        /** PNG per scale, filled in one pass once the capture finishes. */
        final Map<Integer, byte[]> results = new ConcurrentHashMap<>();
        int captured;
        int expected;
        int present;
        volatile boolean complete;
        volatile boolean done;
        volatile boolean failed;
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        if (running || !config.map.enabled()) {
            if (!config.map.enabled()) log.info("GodView's map is disabled in config.yml.");
            return;
        }
        running = true;

        cache.setBudget(config.map.tileCacheMb() * 1024L * 1024L);

        renderer = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(RENDER_QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "GodView-MapRender");
                    t.setDaemon(true);
                    // Below normal: a map redraw must never compete with the server for CPU.
                    t.setPriority(Thread.NORM_PRIORITY - 2);
                    return t;
                });

        if (config.map.persist()) {
            TileStore fresh = new TileStore(plugin.getDataFolder().toPath().resolve("map"),
                    config.map.persistDays(), log);
            store = fresh;
            // Off the main thread: this walks a directory that may hold thousands of files.
            renderer.execute(fresh::sweep);
        } else {
            store = null;
        }

        captureTask = Bukkit.getScheduler().runTaskTimer(plugin, this::captureTick, 1L, 1L);
    }

    public void stop() {
        running = false;
        if (captureTask != null) {
            captureTask.cancel();
            captureTask = null;
        }
        if (renderer != null) {
            renderer.shutdownNow();
            renderer = null;
        }
        synchronized (queue) {
            queue.clear();
        }
        // Anyone blocked in tile() is waiting on a lock nothing will ever notify now.
        for (PendingTile tile : pending.values()) {
            tile.failed = true;
            wake(tile);
        }
        pending.clear();
        cache.clear();
        store = null;
    }

    public void reload(GodViewConfig newConfig) {
        boolean wasEnabled = config.map.enabled();
        this.config = newConfig;
        // Tile size and scale are baked into cached images, so a config change invalidates
        // them outright rather than mixing two geometries in one cache.
        cache.clear();
        cache.setBudget(newConfig.map.tileCacheMb() * 1024L * 1024L);

        if (wasEnabled && !newConfig.map.enabled()) {
            stop();
            return;
        }
        if (!wasEnabled && newConfig.map.enabled()) {
            start();
            return;
        }
        // Still running: the stored geometry may no longer match what we would render now,
        // and a half-old half-new map is worse than one that fills in again.
        TileStore current = store;
        if (running && current != null) {
            current.setMaxAgeDays(newConfig.map.persistDays());
            if (!newConfig.map.persist()) {
                store = null;
            }
        } else if (running && newConfig.map.persist()) {
            TileStore fresh = new TileStore(plugin.getDataFolder().toPath().resolve("map"),
                    newConfig.map.persistDays(), log);
            store = fresh;
            if (renderer != null) renderer.execute(fresh::sweep);
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------- main thread

    /**
     * The per-tick capture budget.
     *
     * <p>Everything in here is deliberately bounded: at most {@code chunks-per-tick}
     * snapshots, taken from the front of one queue, and nothing else. No rendering, no
     * disk, no allocation beyond the snapshots themselves.
     */
    private void captureTick() {
        if (!running) return;
        // Nobody is looking at the map, so there is nothing to draw.
        if (metrics != null && metrics.suspended()) return;

        int budget = metrics == null
                ? config.map.chunksPerTick()
                : metrics.profile().scaleBudget(config.map.chunksPerTick());

        for (int taken = 0; taken < budget; ) {
            TileArea area;
            synchronized (queue) {
                area = queue.peekFirst();
            }
            if (area == null) return;

            PendingTile tile = pending.get(area);
            if (tile == null) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                continue;
            }

            taken += captureSome(area, tile, budget - taken);

            if (tile.captured >= tile.expected) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                submitRender(area, tile);
            }
        }
    }

    /** @return how many chunks were snapshotted, which may be fewer than allowed */
    private int captureSome(TileArea area, PendingTile tile, int allowance) {
        World world = Bukkit.getWorld(area.world());
        if (world == null) {
            tile.failed = true;
            pending.remove(area);
            wake(tile);
            return 0;
        }

        int perTile = config.map.chunksPerTile();
        int baseX = area.tileX() * perTile;
        int baseZ = area.tileZ() * perTile;
        int taken = 0;

        synchronized (tile.lock) {
            for (int local = tile.captured; local < tile.expected && taken < allowance; local++) {
                int localX = local / perTile;
                int localZ = local % perTile;
                int chunkX = baseX + localX;
                int chunkZ = baseZ + localZ;

                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    try {
                        // includeMaxBlockY so the renderer can start at the surface rather
                        // than walking down from the world ceiling for every column.
                        tile.snapshots.put(localX * perTile + localZ,
                                world.getChunkAt(chunkX, chunkZ)
                                        .getChunkSnapshot(true, false, false));
                        tile.present++;
                        chunksCaptured.incrementAndGet();
                    } catch (Throwable t) {
                        // A chunk that vanished mid-capture just renders as void.
                        log.log(Level.FINE, "map: could not snapshot a chunk", t);
                    }
                    taken++;
                } else if (config.map.loadUnloaded() && allowUnloadedLoad()) {
                    // Asynchronous on purpose: this is disk I/O, and the whole point of the
                    // budget is that the main thread never waits for it.
                    requestAsyncLoad(world, chunkX, chunkZ);
                    unloadedSkipped.incrementAndGet();
                } else {
                    unloadedSkipped.incrementAndGet();
                }

                tile.captured = local + 1;
            }
        }
        // Even a fully-skipped pass costs one iteration of budget, or an all-unloaded tile
        // would spin the loop until the whole per-tick allowance was gone.
        return Math.max(taken, 1);
    }

    /** Rate limit for pulling chunks off disk, per {@code map.unloaded-per-second}. */
    private boolean allowUnloadedLoad() {
        long now = System.currentTimeMillis();
        if (now - unloadedWindowStart >= 1000) {
            unloadedWindowStart = now;
            unloadedLoadedThisSecond = 0;
        }
        if (unloadedLoadedThisSecond >= config.map.unloadedPerSecond()) return false;
        unloadedLoadedThisSecond++;
        return true;
    }

    /**
     * Asks the server to load a chunk in the background.
     *
     * <p>Nothing waits for the result. The chunk becomes available for the next render of
     * this tile, which is the right trade: a tile that fills in a few seconds later is far
     * better than a tick spent blocking on disk.
     */
    private void requestAsyncLoad(World world, int chunkX, int chunkZ) {
        try {
            world.getChunkAtAsync(chunkX, chunkZ, false);
        } catch (Throwable ignored) {
            // Not a Paper server, or the request was refused. The tile stays partial.
        }
    }

    // ------------------------------------------------------------ render thread

    /**
     * Renders one captured area at every scale, then caches and stores it.
     *
     * <p>The scales are rendered from the same snapshots rather than by scaling the
     * finished image: the column walk shrinks by the square of the scale, so scales 2 and 4
     * together cost about a third of scale 1, and each is drawn from real block data
     * instead of from resampled pixels.
     */
    private void submitRender(TileArea area, PendingTile tile) {
        ThreadPoolExecutor executor = renderer;
        if (executor == null) {
            tile.failed = true;
            pending.remove(area);
            wake(tile);
            return;
        }

        World world = Bukkit.getWorld(area.world());
        int minY = world == null ? -64 : world.getMinHeight();
        int perTile = config.map.chunksPerTile();
        boolean complete = tile.present >= tile.expected;
        tile.complete = complete;
        long fullTtl = config.map.tileTtlSeconds() * 1000L;

        try {
            executor.execute(() -> {
                try {
                    for (int scale : SCALES) {
                        byte[] png = TileRenderer.render(tile.snapshots, perTile, scale, minY);
                        TileKey key = area.at(scale);
                        cache.put(key, png, complete ? fullTtl : INCOMPLETE_TTL_MS, complete);
                        tile.results.put(scale, png);

                        TileStore target = store;
                        // Only whole tiles go to disk. Persisting one with holes would make
                        // the holes outlive the reason for them.
                        if (complete && target != null) target.write(key, png);
                    }
                    tilesRendered.incrementAndGet();
                    if (!complete) tilesIncomplete.incrementAndGet();
                } catch (Throwable t) {
                    tile.failed = true;
                    log.log(Level.WARNING, "GodView could not render map tile "
                            + area.describe(), t);
                } finally {
                    // Snapshots are large; drop them the moment the PNGs exist.
                    tile.snapshots.clear();
                    tile.done = true;
                    pending.remove(area);
                    wake(tile);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The render pool is saturated. Fail this one; the panel asks again.
            tile.failed = true;
            tile.snapshots.clear();
            pending.remove(area);
            wake(tile);
        }
    }

    private static void wake(PendingTile tile) {
        synchronized (tile.lock) {
            tile.lock.notifyAll();
        }
    }

    // ----------------------------------------------------------------- request

    /** What a tile request produced. */
    public sealed interface TileResult {

        /**
         * The PNG is ready.
         *
         * @param complete false when some of the tile's chunks were not loaded, so the
         *                 picture has holes and is worth asking for again later
         */
        record Ready(byte[] png, boolean fromCache, boolean complete) implements TileResult {
        }

        /** Still being captured; the caller should retry. */
        record Pending(int capturedChunks, int totalChunks) implements TileResult {
        }

        /** The tile cannot be produced at all. */
        record Unavailable(String reason) implements TileResult {
        }
    }

    /**
     * Fetches a tile, waiting briefly for it to render.
     *
     * <p>Called on an HTTP worker, so blocking is acceptable — but only briefly. A tile
     * that is not ready inside {@link #RENDER_WAIT_MS} comes back as
     * {@link TileResult.Pending} and the panel asks again, which keeps a slow capture from
     * pinning a worker while the queue drains.
     */
    public TileResult tile(String worldName, int tileX, int tileZ, int scale) {
        if (!running) return new TileResult.Unavailable("the map is disabled");
        if (!config.map.worldEnabled(worldName)) {
            return new TileResult.Unavailable("the map is disabled for " + worldName);
        }
        if (!SCALES.contains(scale)) {
            return new TileResult.Unavailable("scale must be one of " + SCALES);
        }
        if (Bukkit.getWorld(worldName) == null) {
            return new TileResult.Unavailable("no world named " + worldName);
        }

        TileKey key = new TileKey(worldName, tileX, tileZ, scale);

        byte[] cached = cache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return new TileResult.Ready(cached, true, cache.isComplete(key));
        }

        // Disk before the tick budget. A tile somebody already looked at should cost a file
        // read, not sixty-four chunk snapshots.
        TileStore target = store;
        if (target != null) {
            byte[] stored = target.read(key);
            if (stored != null) {
                cache.put(key, stored, config.map.tileTtlSeconds() * 1000L);
                cacheHits.incrementAndGet();
                return new TileResult.Ready(stored, true, true);
            }
        }
        cacheMisses.incrementAndGet();

        TileArea area = key.area();
        int perTile = config.map.chunksPerTile();
        PendingTile existing = pending.get(area);
        if (existing == null && pending.size() >= MAX_PENDING_AREAS) {
            // A viewer panning fast can outrun the capture budget by orders of magnitude.
            // Refusing here keeps the queue bounded; the panel retries with a backoff.
            areasRefused.incrementAndGet();
            return new TileResult.Pending(0, perTile * perTile);
        }

        PendingTile tile = pending.computeIfAbsent(area, a -> {
            PendingTile fresh = new PendingTile();
            fresh.expected = perTile * perTile;
            synchronized (queue) {
                queue.addLast(a);
            }
            return fresh;
        });

        long deadline = System.currentTimeMillis() + RENDER_WAIT_MS;
        synchronized (tile.lock) {
            while (!tile.done && !tile.failed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    tile.lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        byte[] rendered = tile.results.get(scale);
        if (rendered != null) return new TileResult.Ready(rendered, false, tile.complete);
        if (tile.failed) return new TileResult.Unavailable("the tile could not be rendered");
        return new TileResult.Pending(tile.captured, tile.expected);
    }

    /** Drops a tile from every cache so the next request redraws it. */
    public void invalidate(String world, int tileX, int tileZ) {
        TileStore target = store;
        for (int scale : SCALES) {
            TileKey key = new TileKey(world, tileX, tileZ, scale);
            cache.remove(key);
            if (target != null) target.remove(key);
        }
    }

    public void invalidateWorld(String world) {
        cache.removeWorld(world);
        TileStore target = store;
        if (target != null) target.removeWorld(world);
    }

    // ------------------------------------------------------------------ status

    public void writeStatusJson(JsonWriter w) {
        Map<String, Object> worlds = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.put(world.getName(), config.map.worldEnabled(world.getName()));
        }

        TileStore target = store;

        w.obj()
                .field("enabled", config.map.enabled())
                .field("running", running)
                .field("chunksPerTile", config.map.chunksPerTile())
                .field("tilePixels", config.map.tilePixels())
                .field("scales", SCALES)
                .field("chunksPerTick", config.map.chunksPerTick())
                .field("loadUnloaded", config.map.loadUnloaded())
                .field("tilesRendered", tilesRendered.get())
                .field("tilesIncomplete", tilesIncomplete.get())
                .field("chunksCaptured", chunksCaptured.get())
                .field("cacheHits", cacheHits.get())
                .field("cacheMisses", cacheMisses.get())
                .field("chunksSkippedUnloaded", unloadedSkipped.get())
                .field("areasRefused", areasRefused.get())
                .field("cacheBytes", cache.bytes())
                .field("cacheTiles", cache.size())
                .field("persist", target != null)
                .field("diskTiles", target == null ? 0L : target.files())
                .field("diskBytes", target == null ? 0L : target.bytes())
                .field("diskHits", target == null ? 0L : target.hits())
                .field("diskSurveyed", target == null || target.surveyed())
                .field("queued", queuedTiles())
                .field("worlds", worlds)
                .end();
    }

    public int queuedTiles() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public TileCache cache() {
        return cache;
    }

    /** Null when {@code map.persist} is off. */
    public TileStore store() {
        return store;
    }
}
