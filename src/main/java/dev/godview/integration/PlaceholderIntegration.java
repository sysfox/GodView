package dev.godview.integration;

import dev.godview.compat.Compat;
import dev.godview.util.MainThread;
import dev.godview.util.Text;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves PlaceholderAPI expressions so the panel can show values GodView knows nothing
 * about — a faction name, a rank from some other plugin, a custom economy balance.
 *
 * <p>Expansions are third-party code that may touch the world, so resolution hops to the
 * main thread with a timeout. That makes it more expensive than anything else in the API
 * layer, which is why the number of placeholders per request is capped: a panel widget
 * asking for forty of them would otherwise cost forty pieces of unknown work inside one
 * tick.
 *
 * <p>Output is stripped of formatting codes. Expansions return text meant for chat, and a
 * raw {@code §a} renders as mojibake in a browser.
 */
public final class PlaceholderIntegration implements Integration {

    /** Placeholders one request may resolve. Each is arbitrary third-party code. */
    public static final int MAX_PER_REQUEST = 16;

    private final Plugin plugin;

    public PlaceholderIntegration(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "placeholderapi";
    }

    @Override
    public String displayName() {
        return "PlaceholderAPI";
    }

    @Override
    public boolean available() {
        Plugin papi = plugin();
        return papi != null && papi.isEnabled();
    }

    @Override
    public String version() {
        Plugin papi = plugin();
        return papi == null ? "" : Compat.pluginVersion(papi);
    }

    private Plugin plugin() {
        try {
            return Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        } catch (Throwable ignored) {
            // Only reachable outside a running server, but availability checks must never
            // be the thing that throws -- see the Integration contract.
            return null;
        }
    }

    @Override
    public String problem() {
        Plugin papi = plugin();
        if (papi != null && !papi.isEnabled()) return "PlaceholderAPI is installed but disabled";
        return "";
    }

    /**
     * Resolves placeholders for a player, or with no player context when {@code target} is
     * null.
     *
     * <p>Unresolvable placeholders come back unchanged, which is PlaceholderAPI's own
     * behaviour and more useful than an empty string: {@code %faction_name%} in the panel
     * tells an operator the expansion is missing.
     *
     * @return the input expressions mapped to their resolved values, in the order given
     */
    public Map<String, String> resolve(OfflinePlayer target, List<String> expressions) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!available() || expressions == null || expressions.isEmpty()) return out;

        List<String> capped = expressions.size() <= MAX_PER_REQUEST
                ? expressions
                : expressions.subList(0, MAX_PER_REQUEST);

        try {
            // An expansion may read blocks, entities or another plugin's state, none of
            // which is safe off the main thread.
            Map<String, String> resolved = MainThread.call(plugin, () -> {
                Map<String, String> values = new LinkedHashMap<>();
                for (String expression : capped) {
                    try {
                        String value = target instanceof Player online
                                ? PlaceholderAPI.setPlaceholders(online, expression)
                                : PlaceholderAPI.setPlaceholders(target, expression);
                        values.put(expression, Text.stripColors(value));
                    } catch (Throwable t) {
                        // One broken expansion must not lose the other fifteen values.
                        values.put(expression, "");
                    }
                }
                return values;
            }, 2_000L);
            out.putAll(resolved);
        } catch (Throwable ignored) {
            // The server is not ticking, or PlaceholderAPI went away mid-request.
        }
        return out;
    }
}
