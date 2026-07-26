package dev.godview;

import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.metrics.MetricsService;
import dev.godview.storage.Storage;
import dev.godview.storage.StorageFactory;
import dev.godview.web.WebServer;
import dev.godview.web.auth.AuthRoutes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.logging.Level;

/**
 * Plugin entry point and the single owner of every long-lived service.
 *
 * <p>Startup order matters and is fixed here: config first, then metrics, then — as later
 * layers land — storage, the web server and the alert engine. Shutdown runs the reverse,
 * so nothing is still publishing into a service that has already closed.
 *
 * <p>A reload rebuilds the config and hands the new instance to each service rather than
 * tearing the plugin down. Services keep whatever history they hold, which is the point:
 * an operator reloading to change one alert threshold should not lose the charts they were
 * reading.
 */
public final class GodViewPlugin extends JavaPlugin {

    /**
     * How often a metric row is persisted, in ticks. One second is the right cadence for
     * the live charts and far too fine for disk: at ten seconds a month of history is a
     * quarter of a million rows instead of two and a half million, and no chart drawn from
     * it can tell the difference.
     */
    private static final long METRIC_WRITE_TICKS = 200L;

    private GodViewConfig config;
    private MetricsService metrics;
    private Storage storage;
    private WebServer web;
    private BukkitTask metricWriteTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = loadConfig();

        getLogger().info("Running on " + Compat.describe());

        storage = StorageFactory.create(config.storage, getDataFolder().toPath(), getLogger());

        metrics = new MetricsService(this, config);
        metrics.start();
        wireStorage();

        startWeb();
    }

    @Override
    public void onDisable() {
        // Reverse of startup: stop accepting requests, then stop producing, then close
        // what consumes. Doing this the other way round leaves a handler reading from a
        // storage layer that has already shut its connections.
        if (web != null) {
            web.stop();
            web = null;
        }
        if (metricWriteTask != null) {
            metricWriteTask.cancel();
            metricWriteTask = null;
        }
        if (metrics != null) {
            metrics.stop();
            metrics = null;
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
    }

    private void startWeb() {
        WebServer server = new WebServer(config, getDataFolder().toPath(),
                getClass().getClassLoader(), getLogger());
        new AuthRoutes(server, this::storage, this::config).register(server.router());

        try {
            server.start();
            web = server;
        } catch (IOException e) {
            // A port clash or an unreadable keystore must not take the plugin down with
            // it — metrics, alerts and the in-game commands all keep working without a
            // panel, and that is worth more than failing loudly here.
            getLogger().log(Level.SEVERE, "GodView could not start the web panel on "
                    + config.web.bind() + ":" + config.web.port()
                    + " — the panel is unavailable, everything else keeps running.", e);
        }
    }

    /**
     * Connects the metrics layer to storage: spikes as they happen, headline numbers on a
     * slower timer.
     *
     * <p>Registered once, on the {@link MetricsService} instance that outlives reloads, so
     * a reload does not stack duplicate listeners.
     */
    private void wireStorage() {
        metrics.addSpikeListener(spike -> storage.recordSpike(spike));

        metricWriteTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            MetricsService.Sample s = metrics.latest();
            if (s.at() == 0L) return;   // nothing sampled yet
            storage.recordMetrics(new Storage.MetricPoint(
                    s.at(), s.tps1(), s.mspt(), s.heapUsed(), s.heapMax(),
                    s.cpuProcess(), s.players(), s.chunks(), s.entities()));
        }, METRIC_WRITE_TICKS, METRIC_WRITE_TICKS);
    }

    /**
     * Re-reads {@code config.yml} and pushes it into every service.
     *
     * @return the warnings produced by parsing, already logged
     */
    public GodViewConfig reload() {
        reloadConfig();
        config = loadConfig();
        if (metrics != null) metrics.reload(config);
        if (web != null) web.reload(config);
        return config;
    }

    private GodViewConfig loadConfig() {
        GodViewConfig parsed = GodViewConfig.from(getConfig());
        for (String warning : parsed.warnings()) {
            getLogger().warning("config.yml: " + warning);
        }
        return parsed;
    }

    public GodViewConfig config() {
        return config;
    }

    public MetricsService metrics() {
        return metrics;
    }

    public Storage storage() {
        return storage;
    }

    /** Null when the panel is disabled or failed to bind. */
    public WebServer web() {
        return web;
    }
}
