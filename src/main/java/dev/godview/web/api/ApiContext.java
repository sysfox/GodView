package dev.godview.web.api;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.MetricsService;
import dev.godview.storage.Storage;
import dev.godview.web.WebServer;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the API handlers need, in one place.
 *
 * <p>The mutable pieces arrive as suppliers rather than values. A reload replaces the
 * config object and can replace the storage backend, and a route registered at startup
 * must see the new one — capturing either directly would leave handlers reading a config
 * that was thrown away hours ago.
 */
public record ApiContext(Plugin plugin, MetricsService metrics, Supplier<Storage> storage,
                         WebServer web, Supplier<GodViewConfig> config, EventStream stream) {

    public GodViewConfig cfg() {
        return config.get();
    }

    public Storage store() {
        return storage.get();
    }

    /** Request body cap, from {@code web.max-body-kb}. */
    public int maxBody() {
        return cfg().web.maxBodyBytes();
    }
}
