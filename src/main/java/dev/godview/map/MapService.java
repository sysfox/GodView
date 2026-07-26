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
 * <p>Chunks that are not loaded are skipped unless {@code map.load-unloaded} is on, and
 * even then they are loaded asynchronously and rate-limited per second. Reading the world
 * off disk to draw a picture of somewhere nobody is standing is a real cost, and it should
 * be a deliberate choice rather than a default.
 *
 * <p>Rendered tiles are cached by bytes, not by count, because a tile's size varies by an
 * order of magnitude with terrain: an ocean tile compresses to a few kilobytes and a
 * jungle one does not.
 */
public final class MapService {

    /** How long a request will wait for a tile to render before answering "not yet". */
    public static final long RENDER_WAIT_MS = 2_500L;
    /** Tiles that may be queued for capture at once. */
    private static final int QUEUE_CAPACITY = 64;
    /** Downsampling factors the API accepts. */
    private static final List<Integer> SCALES = List.of(1, 2, 4);

    private final Plugin plugin;
    private final Logger log;
    private final MetricsService metrics;

    private volatile GodViewConfig config;
    private volatile boolean running;

    private final TileCache cache = new TileCache();
    private final Map<TileKey, PendingTile> pending = new ConcurrentHashMap<>();
    private final Deque<TileKey> queue = new ArrayDeque<>();

    private BukkitTask captureTask;
    private ThreadPoolExecutor renderer;

    private final AtomicLong tilesRendered = new AtomicLong();
    private final AtomicLong chunksCaptured = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong unloadedSkipped = new AtomicLong();
    private long unloadedLoadedThisSecond;
    private long unloadedWindowStart;

    public MapService(Plugin plugin, GodViewConfig config, MetricsService metrics, Logger log) {
        this.plugin = plugin;
        this.config = config;
        this.metrics = metrics;
        this.log = log;
    }

    /** A tile's identity: which world, which tile coordinates, at which downsampling. */
    public record TileKey(String world, int tileX, int tileZ, int scale) {

        public String describe() {
            return world + "/" + tileX + "," + tileZ + "@" + scale;
        }
    }

    /** A tile being captured, and whoever is waiting for it. */
    private static final class PendingTile {
        final Map<Integer, ChunkSnapshot> snapshots = new HashMap<>();
        final Object lock = new Object();
        int captured;
        int expected;
        volatile byte[] result;
        volatile boolean failed;
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        if (running || !config.map.enabled()) {
            if (!config.map.enabled()) log.info("GodView's map is disabled in config.yml.");
            return;
        }
        running = true;

        renderer = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "GodView-MapRender");
                    t.setDaemon(true);
                    // Below normal: a map redraw must never compete with the server for CPU.
                    t.setPriority(Thread.NORM_PRIORITY - 2);
                    return t;
                });

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
        pending.clear();
        cache.clear();
    }

    public void reload(GodViewConfig newConfig) {
        boolean wasEnabled = config.map.enabled();
        this.config = newConfig;
        // Tile size and scale are baked into cached images, so a config change invalidates
        // them outright rather than mixing two geometries in one cache.
        cache.clear();
        cache.setBudget(newConfig.map.tileCacheMb() * 1024L * 1024L);

        if (wasEnabled && !newConfig.map.enabled()) stop();
        else if (!wasEnabled && newConfig.map.enabled()) start();
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
            TileKey key;
            synchronized (queue) {
                key = queue.peekFirst();
            }
            if (key == null) return;

            PendingTile tile = pending.get(key);
            if (tile == null) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                continue;
            }

            taken += captureSome(key, tile, budget - taken);

            if (tile.captured >= tile.expected) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                submitRender(key, tile);
            }
        }
    }

    /** @return how many chunks were snapshotted, which may be fewer than allowed */
    private int captureSome(TileKey key, PendingTile tile, int allowance) {
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            tile.failed = true;
            wake(tile);
            return 0;
        }

        int perTile = config.map.chunksPerTile();
        int baseX = key.tileX() * perTile;
        int baseZ = key.tileZ() * perTile;
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

    private void submitRender(TileKey key, PendingTile tile) {
        ThreadPoolExecutor executor = renderer;
        if (executor == null) {
            tile.failed = true;
            wake(tile);
            return;
        }

        World world = Bukkit.getWorld(key.world());
        int minY = world == null ? -64 : world.getMinHeight();

        try {
            executor.execute(() -> {
                try {
                    byte[] png = TileRenderer.render(tile.snapshots,
                            config.map.chunksPerTile(), key.scale(), minY);
                    cache.put(key, png, config.map.tileTtlSeconds() * 1000L);
                    tile.result = png;
                    tilesRendered.incrementAndGet();
                } catch (Throwable t) {
                    tile.failed = true;
                    log.log(Level.WARNING, "GodView could not render map tile "
                            + key.describe(), t);
                } finally {
                    // Snapshots are large; drop them the moment the PNG exists.
                    tile.snapshots.clear();
                    pending.remove(key);
                    wake(tile);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            tile.failed = true;
            pending.remove(key);
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

        /** The PNG is ready. */
        record Ready(byte[] png, boolean fromCache) implements TileResult {
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
            return new TileResult.Ready(cached, true);
        }
        cacheMisses.incrementAndGet();

        int perTile = config.map.chunksPerTile();
        PendingTile tile = pending.computeIfAbsent(key, k -> {
            PendingTile fresh = new PendingTile();
            fresh.expected = perTile * perTile;
            synchronized (queue) {
                queue.addLast(k);
            }
            return fresh;
        });

        long deadline = System.currentTimeMillis() + RENDER_WAIT_MS;
        synchronized (tile.lock) {
            while (tile.result == null && !tile.failed) {
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

        if (tile.result != null) return new TileResult.Ready(tile.result, false);
        if (tile.failed) return new TileResult.Unavailable("the tile could not be rendered");
        return new TileResult.Pending(tile.captured, tile.expected);
    }

    /** Drops a tile from the cache so the next request redraws it. */
    public void invalidate(String world, int tileX, int tileZ) {
        for (int scale : SCALES) cache.remove(new TileKey(world, tileX, tileZ, scale));
    }

    public void invalidateWorld(String world) {
        cache.removeWorld(world);
    }

    // ------------------------------------------------------------------ status

    public void writeStatusJson(JsonWriter w) {
        Map<String, Object> worlds = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.put(world.getName(), config.map.worldEnabled(world.getName()));
        }

        w.obj()
                .field("enabled", config.map.enabled())
                .field("running", running)
                .field("chunksPerTile", config.map.chunksPerTile())
                .field("tilePixels", config.map.tilePixels())
                .field("scales", SCALES)
                .field("chunksPerTick", config.map.chunksPerTick())
                .field("loadUnloaded", config.map.loadUnloaded())
                .field("tilesRendered", tilesRendered.get())
                .field("chunksCaptured", chunksCaptured.get())
                .field("cacheHits", cacheHits.get())
                .field("cacheMisses", cacheMisses.get())
                .field("chunksSkippedUnloaded", unloadedSkipped.get())
                .field("cacheBytes", cache.bytes())
                .field("cacheTiles", cache.size())
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
}
