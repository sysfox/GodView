package dev.godview.integration;

import dev.godview.config.GodViewConfig;
import dev.godview.util.JsonWriter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Detects the optional plugins GodView can read from, and hands them out.
 *
 * <p>Two independent gates decide whether an integration exists: the {@code integrations:}
 * flag, and whether the plugin is actually installed. Turning a flag off means the class is
 * never constructed at all — which matters because constructing it is what triggers loading
 * the other plugin's API types.
 *
 * <p>Every accessor returns null when the integration is unavailable. Callers are expected
 * to check; that is deliberately more honest than a no-op stub, which would let the panel
 * silently display "balance: 0" for a server with no economy.
 *
 * <p>Construction is wrapped per integration. One plugin shipping an API that fails to load
 * must cost that one integration, not the other five.
 */
public final class IntegrationManager {

    private final Logger log;
    private final Map<String, Integration> byId = new LinkedHashMap<>();

    private LuckPermsIntegration luckPerms;
    private CoreProtectIntegration coreProtect;
    private PlaceholderIntegration placeholders;
    private VaultIntegration vault;
    private SparkIntegration spark;
    private EssentialsIntegration essentials;

    public IntegrationManager(Plugin plugin, GodViewConfig config, Logger log) {
        this.log = log;
        detect(plugin, config);
    }

    private void detect(Plugin plugin, GodViewConfig config) {
        GodViewConfig.Integrations enabled = config.integrations;

        if (enabled.luckperms()) {
            luckPerms = build("LuckPerms", LuckPermsIntegration::new);
        }
        if (enabled.coreprotect()) {
            coreProtect = build("CoreProtect", CoreProtectIntegration::new);
        }
        if (enabled.placeholderapi()) {
            placeholders = build("PlaceholderAPI", () -> new PlaceholderIntegration(plugin));
        }
        if (enabled.vault()) {
            vault = build("Vault", VaultIntegration::new);
        }
        if (enabled.spark()) {
            spark = build("spark", SparkIntegration::new);
        }
        if (enabled.essentials()) {
            essentials = build("EssentialsX", EssentialsIntegration::new);
        }

        register(luckPerms);
        register(coreProtect);
        register(placeholders);
        register(vault);
        register(spark);
        register(essentials);

        List<String> active = new ArrayList<>();
        for (Integration integration : byId.values()) {
            // Guarded: an integration that throws while being asked whether it is usable
            // has answered the question, and must not abort detection of the others.
            if (available(integration)) active.add(integration.displayName());
        }
        if (!active.isEmpty()) {
            log.info("GodView is reading from " + String.join(", ", active) + ".");
        }
        for (Integration integration : byId.values()) {
            String problem = problem(integration);
            if (!problem.isEmpty()) {
                log.warning("GodView integration " + integration.id() + ": " + problem);
            }
        }
    }

    /**
     * Constructs one integration, swallowing whatever its API throws on the way in.
     *
     * <p>This is where a class-loading failure would surface: the constructor is the first
     * thing that touches the other plugin's types.
     */
    private <T extends Integration> T build(String name, java.util.function.Supplier<T> factory) {
        try {
            return factory.get();
        } catch (Throwable t) {
            log.warning("GodView could not set up the " + name + " integration: " + t);
            return null;
        }
    }

    private void register(Integration integration) {
        if (integration != null) byId.put(integration.id(), integration);
    }

    // ------------------------------------------------------------------ access

    /** Null when disabled in config or LuckPerms is absent. */
    public LuckPermsIntegration luckPerms() {
        return available(luckPerms) ? luckPerms : null;
    }

    public CoreProtectIntegration coreProtect() {
        return available(coreProtect) ? coreProtect : null;
    }

    public PlaceholderIntegration placeholders() {
        return available(placeholders) ? placeholders : null;
    }

    public VaultIntegration vault() {
        return available(vault) ? vault : null;
    }

    public SparkIntegration spark() {
        return available(spark) ? spark : null;
    }

    public EssentialsIntegration essentials() {
        return available(essentials) ? essentials : null;
    }

    private static boolean available(Integration integration) {
        try {
            return integration != null && integration.available();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String problem(Integration integration) {
        try {
            return integration == null ? "" : integration.problem();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Every integration GodView was configured to look for, available or not. */
    public List<Integration> all() {
        return List.copyOf(byId.values());
    }

    public void writeJson(JsonWriter w) {
        w.obj().name("integrations").arr();
        for (Integration integration : byId.values()) {
            try {
                integration.writeJson(w);
            } catch (Throwable ignored) {
                // A plugin that throws from its own version getter is not worth a 500.
                w.obj().field("id", integration.id()).field("available", false)
                        .field("problem", "the plugin threw while being queried").end();
            }
        }
        w.end().end();
    }
}
