package dev.godview;

import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.metrics.MetricsService;
import org.bukkit.plugin.java.JavaPlugin;

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

    private GodViewConfig config;
    private MetricsService metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = loadConfig();

        getLogger().info("Running on " + Compat.describe());

        metrics = new MetricsService(this, config);
        metrics.start();
    }

    @Override
    public void onDisable() {
        if (metrics != null) {
            metrics.stop();
            metrics = null;
        }
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
}
