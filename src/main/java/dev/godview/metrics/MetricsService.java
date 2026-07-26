package dev.godview.metrics;

import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.util.JsonWriter;
import dev.godview.util.TimeSeries;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns everything GodView knows about how the server is running.
 *
 * <p>Three scheduled tasks, deliberately on different clocks:
 *
 * <ul>
 *   <li><b>Every tick</b> — {@link TickSampler}, under 0.01 ms, never suspended. This is
 *       what makes spike detection possible at all.
 *   <li><b>Every second</b> — TPS, MSPT, heap, CPU, player count. Cheap enough to keep
 *       running with nobody watching, because the alert rules depend on it.
 *   <li><b>Every few seconds</b> — per-world entity and chunk stats. O(entities), so this
 *       is the one that respects {@code idle-suspend} and the performance profile.
 * </ul>
 *
 * <p>Readers are HTTP threads, writers are the main thread. Published state is either
 * volatile-swapped immutable values ({@link #latest()}, {@link #worlds()}) or the
 * internally synchronized {@link TimeSeries}, so no caller ever needs a lock.
 */
public final class MetricsService {

    /** Tick periods retained by the sampler — 200 ticks is ten seconds of history. */
    private static final int TICK_HISTORY = 200;
    /** In-memory spike and GC history. Anything older lives in storage. */
    private static final int RECENT_EVENTS = 200;

    private final Plugin plugin;
    private final SpikeAttributor attributor = new SpikeAttributor();

    private final TimeSeries tpsSeries = TimeSeries.standard();
    private final TimeSeries msptSeries = TimeSeries.standard();
    private final TimeSeries heapSeries = TimeSeries.standard();
    private final TimeSeries cpuSeries = TimeSeries.standard();
    private final TimeSeries playerSeries = TimeSeries.standard();
    private final TimeSeries chunkSeries = TimeSeries.coarse();
    private final TimeSeries entitySeries = TimeSeries.coarse();

    private final Deque<Spike> recentSpikes = new ArrayDeque<>();
    private final Deque<GcMonitor.GcEvent> recentGc = new ArrayDeque<>();
    private final List<Consumer<Spike>> spikeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GcMonitor.GcEvent>> gcListeners = new CopyOnWriteArrayList<>();

    private final AtomicInteger viewers = new AtomicInteger();

    private volatile GodViewConfig config;
    private volatile PerformanceGovernor governor;
    private volatile List<WorldStats> worlds = List.of();
    private volatile Sample latest;
    private volatile boolean running;

    private TickSampler tickSampler;
    private SpikeWatchdog watchdog;
    private GcMonitor gcMonitor;
    private BukkitTask tickTask;
    private BukkitTask sampleTask;
    private BukkitTask worldTask;
    private int scheduledWorldInterval = -1;

    public MetricsService(Plugin plugin, GodViewConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.governor = new PerformanceGovernor(config.performance);
        this.latest = Sample.empty();
    }

    // ------------------------------------------------------------- lifecycle

    public void start() {
        if (running) return;
        if (Compat.FOLIA) {
            // Folia has no global main thread, so per-tick timing and main-thread stack
            // capture are both meaningless there. Refusing loudly beats reporting fiction.
            plugin.getLogger().warning(
                    "Folia detected — GodView's metrics sampling requires a single main thread "
                            + "and has been disabled. The panel will still start.");
            return;
        }
        running = true;

        attributor.refresh(Bukkit.getPluginManager().getPlugins());

        GodViewConfig.Performance perf = config.performance;

        if (perf.tickSampler()) {
            tickSampler = new TickSampler(TICK_HISTORY, perf.spikeThresholdMs(), this::onSpike);
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, tickSampler, 1L, 1L);

            if (perf.spikeStackCapture()) {
                watchdog = new SpikeWatchdog(tickSampler, attributor, perf.spikeThresholdMs(),
                        () -> governor.current().scaleCaptureRate(perf.spikeMaxPerMinute()));
                watchdog.start();
            }
        }

        if (perf.gcMonitor()) {
            gcMonitor = new GcMonitor(this::onGc);
            gcMonitor.start();
        }

        sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleCheap, 20L, 20L);
        rescheduleWorldStats();
        governor.onChange(profile -> rescheduleWorldStats());
    }

    public void stop() {
        running = false;
        cancel(tickTask);
        cancel(sampleTask);
        cancel(worldTask);
        tickTask = sampleTask = worldTask = null;
        scheduledWorldInterval = -1;

        if (watchdog != null) {
            watchdog.stop();
            watchdog = null;
        }
        if (gcMonitor != null) {
            gcMonitor.stop();
            gcMonitor = null;
        }
        tickSampler = null;
    }

    /**
     * Applies a new config. Series history is deliberately kept — a reload should not
     * blank the charts an operator is in the middle of reading.
     */
    public void reload(GodViewConfig newConfig) {
        stop();
        this.config = newConfig;
        this.governor = new PerformanceGovernor(newConfig.performance);
        start();
    }

    /** Rebuilds plugin attribution. Call when a plugin is enabled or disabled at runtime. */
    public void refreshAttribution() {
        attributor.refresh(Bukkit.getPluginManager().getPlugins());
    }

    // -------------------------------------------------------------- sampling

    private void sampleCheap() {
        long now = System.currentTimeMillis();
        double[] tps = Compat.tps();
        double mspt = Compat.mspt();
        int players = Bukkit.getOnlinePlayers().size();

        tpsSeries.add(now, Math.min(tps[0], 20d));
        msptSeries.add(now, mspt);
        heapSeries.add(now, SystemProbe.heapPercent());
        playerSeries.add(now, players);

        double cpu = SystemProbe.processCpu();
        if (cpu != SystemProbe.UNAVAILABLE) cpuSeries.add(now, cpu * 100d);

        // Harmless when notifications are working; the only GC signal when they are not.
        if (gcMonitor != null) gcMonitor.pollDelta();

        governor.sample(tps[0]);
        latest = buildSample(now, tps, mspt, players);
    }

    private void sampleWorlds() {
        if (suspended()) return;

        boolean withTileEntities = governor.current().tileEntityStats;
        List<WorldStats> collected = new ArrayList<>();
        int chunks = 0;
        int entities = 0;
        for (World world : Bukkit.getWorlds()) {
            WorldStats stats = WorldStats.collect(world, withTileEntities);
            collected.add(stats);
            chunks += stats.chunks();
            entities += stats.entities();
        }

        worlds = List.copyOf(collected);
        long now = System.currentTimeMillis();
        chunkSeries.add(now, chunks);
        entitySeries.add(now, entities);
    }

    private void rescheduleWorldStats() {
        if (!running) return;
        int interval = governor.current().scaleInterval(config.performance.worldStatsIntervalTicks());
        if (interval == scheduledWorldInterval) return;

        cancel(worldTask);
        scheduledWorldInterval = interval;
        worldTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleWorlds, interval, interval);
    }

    private Sample buildSample(long now, double[] tps, double mspt, int players) {
        int chunks = 0;
        int entities = 0;
        for (WorldStats stats : worlds) {
            chunks += stats.chunks();
            entities += stats.entities();
        }
        return new Sample(
                now,
                Math.min(tps[0], 20d), Math.min(tps[1], 20d), Math.min(tps[2], 20d),
                mspt,
                tickSampler != null ? tickSampler.p95PeriodMs() : 0d,
                tickSampler != null ? tickSampler.overrunRatio() : 0d,
                SystemProbe.heapUsed(), SystemProbe.heapCommitted(), SystemProbe.heapMax(),
                SystemProbe.nonHeapUsed(),
                SystemProbe.processCpu(), SystemProbe.systemCpu(), SystemProbe.loadAverage(),
                players, Bukkit.getMaxPlayers(), SystemProbe.uptimeMs(),
                chunks, entities,
                gcMonitor != null ? gcMonitor.totalCount() : 0L,
                gcMonitor != null ? gcMonitor.totalPauseMs() : 0L,
                governor.current().lower());
    }

    // ----------------------------------------------------------------- events

    private void onSpike(Spike spike) {
        synchronized (recentSpikes) {
            recentSpikes.addLast(spike);
            while (recentSpikes.size() > RECENT_EVENTS) recentSpikes.removeFirst();
        }
        for (Consumer<Spike> listener : spikeListeners) {
            try {
                listener.accept(spike);
            } catch (Throwable t) {
                plugin.getLogger().warning("A spike listener failed: " + t);
            }
        }
    }

    private void onGc(GcMonitor.GcEvent event) {
        synchronized (recentGc) {
            recentGc.addLast(event);
            while (recentGc.size() > RECENT_EVENTS) recentGc.removeFirst();
        }
        for (Consumer<GcMonitor.GcEvent> listener : gcListeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                plugin.getLogger().warning("A GC listener failed: " + t);
            }
        }
    }

    /** Notified on the main thread for every spike. Used by storage and the alert engine. */
    public void addSpikeListener(Consumer<Spike> listener) {
        spikeListeners.add(listener);
    }

    /** Notified on a JMX notification thread for every collection. */
    public void addGcListener(Consumer<GcMonitor.GcEvent> listener) {
        gcListeners.add(listener);
    }

    // ------------------------------------------------------------- read side

    /** The most recent one-second sample. Never null. */
    public Sample latest() {
        return latest;
    }

    /** Per-world stats as of the last world sampling pass. Empty until the first one runs. */
    public List<WorldStats> worlds() {
        return worlds;
    }

    /** Newest spikes last. */
    public List<Spike> recentSpikes() {
        synchronized (recentSpikes) {
            return List.copyOf(recentSpikes);
        }
    }

    /** Newest collections last. */
    public List<GcMonitor.GcEvent> recentGc() {
        synchronized (recentGc) {
            return List.copyOf(recentGc);
        }
    }

    /** Chartable history, keyed by the name the panel asks for. */
    public Map<String, TimeSeries> series() {
        Map<String, TimeSeries> map = new LinkedHashMap<>();
        map.put("tps", tpsSeries);
        map.put("mspt", msptSeries);
        map.put("heap", heapSeries);
        map.put("cpu", cpuSeries);
        map.put("players", playerSeries);
        map.put("chunks", chunkSeries);
        map.put("entities", entitySeries);
        return map;
    }

    public PerformanceProfile profile() {
        return governor.current();
    }

    public PerformanceGovernor governor() {
        return governor;
    }

    public SpikeAttributor attributor() {
        return attributor;
    }

    public TickSampler tickSampler() {
        return tickSampler;
    }

    public GcMonitor gcMonitor() {
        return gcMonitor;
    }

    public boolean isRunning() {
        return running;
    }

    // --------------------------------------------------------- idle suspend

    /** Call when a panel session starts watching. */
    public int viewerConnected() {
        return viewers.incrementAndGet();
    }

    /** Call when a panel session stops watching. */
    public int viewerDisconnected() {
        return Math.max(0, viewers.decrementAndGet());
    }

    public int viewers() {
        return viewers.get();
    }

    /** True when nobody is watching and {@code idle-suspend} allows us to stop collecting. */
    public boolean suspended() {
        return config.performance.idleSuspend() && viewers.get() == 0;
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------------ types

    /**
     * One second of server state, flattened for the panel.
     *
     * <p>{@code chunks} and {@code entities} come from the slower world pass, so they can
     * lag the rest by a few seconds — cheap totals are not worth an O(entities) walk every
     * second.
     */
    public record Sample(long at, double tps1, double tps5, double tps15, double mspt,
                         double tickPeriodP95, double overrunRatio,
                         long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                         double cpuProcess, double cpuSystem, double loadAverage,
                         int players, int maxPlayers, long uptimeMs,
                         int chunks, int entities, long gcCount, long gcPauseMs, String profile) {

        static Sample empty() {
            return new Sample(0, 20, 20, 20, 0, 0, 0, 0, 0, 0, 0,
                    SystemProbe.UNAVAILABLE, SystemProbe.UNAVAILABLE, SystemProbe.UNAVAILABLE,
                    0, 0, 0, 0, 0, 0, 0, PerformanceProfile.BALANCED.lower());
        }

        public double heapPercent() {
            return heapMax <= 0 ? 0d : heapUsed * 100d / heapMax;
        }

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("tps1", tps1)
                    .field("tps5", tps5)
                    .field("tps15", tps15)
                    .field("mspt", mspt)
                    .field("tickPeriodP95", tickPeriodP95)
                    .field("overrunRatio", overrunRatio)
                    .field("heapUsed", heapUsed)
                    .field("heapCommitted", heapCommitted)
                    .field("heapMax", heapMax)
                    .field("heapPercent", heapPercent())
                    .field("nonHeapUsed", nonHeapUsed)
                    .field("cpuProcess", cpuProcess)
                    .field("cpuSystem", cpuSystem)
                    .field("loadAverage", loadAverage)
                    .field("players", players)
                    .field("maxPlayers", maxPlayers)
                    .field("uptimeMs", uptimeMs)
                    .field("chunks", chunks)
                    .field("entities", entities)
                    .field("gcCount", gcCount)
                    .field("gcPauseMs", gcPauseMs)
                    .field("profile", profile)
                    .end();
        }
    }
}
