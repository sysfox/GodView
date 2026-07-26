package dev.godview;

import dev.godview.alert.AlertEngine;
import dev.godview.command.GodViewCommand;
import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.integration.IntegrationManager;
import dev.godview.listener.ConsoleCapture;
import dev.godview.listener.EventCapture;
import dev.godview.map.MapService;
import dev.godview.metrics.MetricsService;
import dev.godview.storage.Storage;
import dev.godview.storage.StorageFactory;
import dev.godview.web.WebServer;
import dev.godview.web.api.ActionRoutes;
import dev.godview.web.api.ApiContext;
import dev.godview.web.api.ApiRoutes;
import dev.godview.web.api.IntegrationRoutes;
import dev.godview.web.api.EventStream;
import dev.godview.web.api.MapRoutes;
import dev.godview.web.api.SnapshotPusher;
import dev.godview.web.auth.AuthRoutes;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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
    private EventStream stream;
    private SnapshotPusher pusher;
    private EventCapture capture;
    private ConsoleCapture console;
    private IntegrationManager integrations;
    private AlertEngine alerts;
    private MapService map;
    private AuthRoutes authRoutes;
    private BukkitTask metricWriteTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = loadConfig();

        getLogger().info("Running on " + Compat.describe());

        storage = StorageFactory.create(config.storage, getDataFolder().toPath(), getLogger());

        // Detected before the API comes up so the first request already sees them, and
        // after the config so the integrations: flags are honoured.
        integrations = new IntegrationManager(this, config, getLogger());

        metrics = new MetricsService(this, config);
        metrics.start();
        wireStorage();

        // The web layer comes up before capture so the event stream exists to publish into,
        // and before alerts so the panel channel has somewhere to publish.
        startWeb();
        startCapture();
        startAlerts();
        startMap();
        registerCommands();

        if (web != null && web.users().isEmpty()) {
            getLogger().warning("No panel account exists yet. Run this in the console or in game:");
            getLogger().warning("  /godview user add <name> admin");
        }
    }

    @Override
    public void onDisable() {
        // Reverse of startup: stop accepting requests, then stop producing, then close
        // what consumes. Doing this the other way round leaves a handler reading from a
        // storage layer that has already shut its connections.
        // Stop producing events before the things they are written into go away.
        if (map != null) {
            map.stop();
            map = null;
        }
        if (alerts != null) {
            alerts.stop();
            alerts = null;
        }
        if (console != null) {
            console.detach();
            console = null;
        }
        if (capture != null) {
            capture.unregister();
            capture = null;
        }
        if (pusher != null) {
            pusher.stop();
            pusher = null;
        }
        if (stream != null) {
            stream.stop();
            stream = null;
        }
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

        // The stream reports its subscriber count straight into the metrics layer, which
        // is what makes performance.idle-suspend work: no panels open, no collection.
        stream = new EventStream(getLogger(), count -> metrics.setViewers(count));
        ApiContext ctx = new ApiContext(this, metrics, this::storage, server, this::config,
                stream, this::console, this::integrations, this::alerts);

        authRoutes = new AuthRoutes(server, this::storage, this::config);
        authRoutes.register(server.router());
        new ApiRoutes(ctx).register(server.router());
        new ActionRoutes(ctx).register(server.router());
        new IntegrationRoutes(ctx).register(server.router());
        new MapRoutes(ctx, this::map).register(server.router());

        try {
            server.start();
            web = server;
            stream.start();
            pusher = new SnapshotPusher(ctx);
            pusher.start();
        } catch (IOException e) {
            // A port clash or an unreadable keystore must not take the plugin down with
            // it — metrics, alerts and the in-game commands all keep working without a
            // panel, and that is worth more than failing loudly here.
            getLogger().log(Level.SEVERE, "GodView could not start the web panel on "
                    + config.web.bind() + ":" + config.web.port()
                    + " — the panel is unavailable, everything else keeps running.", e);
        }
    }

    private void startCapture() {
        capture = new EventCapture(this, config, this::storage, this::stream,
                () -> {
                    // A plugin was enabled or disabled, so spike attribution's package map
                    // is now stale. Rebuilding it is cheap and this is rare.
                    if (metrics != null) metrics.refreshAttribution();
                });
        capture.register();

        if (config.capture.consoleErrors() || config.capture.consoleBufferLines() > 0) {
            console = new ConsoleCapture(config.capture.consoleBufferLines(),
                    config.capture.consoleErrors(), this::stream, capture);
            console.attach();
        }
    }

    /**
     * Starts the alert engine and connects it to everything that can trigger a rule.
     *
     * <p>Wired last because it consumes from every other layer: samples from metrics,
     * events from capture, failed sign-ins from auth. All of these are callbacks, so a
     * reload swaps the engine's rules without having to re-register any of them.
     */
    private void startAlerts() {
        alerts = new AlertEngine(config, this::storage, this::stream, getLogger());
        alerts.start();

        metrics.addSampleListener(sample -> alerts.onSample(sample));
        metrics.addSpikeListener(spike -> alerts.onSpike(spike));
        metrics.addGcListener(event -> alerts.onGc(event));
        if (capture != null) capture.onEvent(event -> alerts.onEvent(event));
        if (authRoutes != null) authRoutes.onLoginFailure(ip -> alerts.onLoginFailure(ip));
    }

    /**
     * Starts the map renderer.
     *
     * <p>Given the metrics service so it can honour both the performance profile and
     * idle-suspend: with nobody watching, there is no reason to spend tick budget drawing
     * a picture of the world.
     */
    private void startMap() {
        map = new MapService(this, config, metrics, getLogger());
        map.cache().setBudget(config.map.tileCacheMb() * 1024L * 1024L);
        map.start();
    }

    private void registerCommands() {
        PluginCommand command = getCommand("godview");
        if (command == null) {
            // Only reachable if plugin.yml and this class disagree, which is a build bug.
            getLogger().severe("The 'godview' command is missing from plugin.yml — "
                    + "in-game administration is unavailable.");
            return;
        }
        GodViewCommand executor = new GodViewCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
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
        if (capture != null) capture.reload(config);

        // Rebuilt rather than reconfigured: a flag turning on has to construct an
        // integration that was never created, and turning one off has to forget it.
        integrations = new IntegrationManager(this, config, getLogger());

        // Console capture is rebuilt rather than reconfigured: the buffer size and the
        // error toggle are both fixed at construction, and reattaching is cheap.
        if (console != null) {
            console.detach();
            console = null;
        }
        if (capture != null
                && (config.capture.consoleErrors() || config.capture.consoleBufferLines() > 0)) {
            console = new ConsoleCapture(config.capture.consoleBufferLines(),
                    config.capture.consoleErrors(), this::stream, capture);
            console.attach();
        }
        if (alerts != null) alerts.reload(config);
        if (map != null) map.reload(config);
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

    /** Null until the panel has started. */
    public EventStream stream() {
        return stream;
    }

    /** Null when console capture is disabled in config.yml. */
    public ConsoleCapture console() {
        return console;
    }

    public IntegrationManager integrations() {
        return integrations;
    }

    /** Null until the alert engine has started. */
    public AlertEngine alerts() {
        return alerts;
    }

    /** Null when the map is disabled in config.yml. */
    public MapService map() {
        return map;
    }
}
