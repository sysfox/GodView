package dev.godview.integration;

import dev.godview.compat.Compat;
import dev.godview.util.JsonWriter;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Block history, borrowed from CoreProtect.
 *
 * <p>GodView deliberately does not store block events itself — {@code capture.block-place}
 * and {@code block-break} exist, and are off by default, precisely because a plugin built
 * for this already does it far better. When CoreProtect is installed the panel queries it
 * instead of duplicating a second, worse copy of the same data.
 *
 * <p><b>Lookups must not run on the main thread.</b> CoreProtect's API says so explicitly:
 * a lookup is a database query and blocking the tick on one is exactly the kind of stall
 * GodView exists to catch. Every call here is expected to arrive on an HTTP worker, and
 * {@link #lookup} refuses outright if it finds itself on the main thread rather than
 * quietly freezing the server.
 *
 * <p>Rollbacks are not exposed. They rewrite the world, they are hard to undo, and getting
 * the radius wrong from a web form is a very expensive mistake — CoreProtect's own command
 * has the confirmation flow for that.
 */
public final class CoreProtectIntegration implements Integration {

    /** Rows one lookup may return. CoreProtect will happily return tens of thousands. */
    private static final int MAX_ROWS = 500;
    /** The oldest API revision exposing the lookup methods GodView uses. */
    private static final int MIN_API_VERSION = 9;

    @Override
    public String id() {
        return "coreprotect";
    }

    @Override
    public String displayName() {
        return "CoreProtect";
    }

    @Override
    public boolean available() {
        return api() != null;
    }

    @Override
    public String version() {
        Plugin plugin = plugin();
        return plugin == null ? "" : Compat.pluginVersion(plugin);
    }

    private Plugin plugin() {
        try {
            return Bukkit.getPluginManager().getPlugin("CoreProtect");
        } catch (Throwable ignored) {
            // Only reachable outside a running server, but availability checks must never
            // be the thing that throws -- see the Integration contract.
            return null;
        }
    }

    @Override
    public String problem() {
        Plugin plugin = plugin();
        if (plugin == null) return "";
        if (!(plugin instanceof CoreProtect)) return "another plugin is using the CoreProtect name";
        CoreProtectAPI api = ((CoreProtect) plugin).getAPI();
        if (api == null) return "CoreProtect has not published its API";
        if (!api.isEnabled()) return "CoreProtect's API is disabled in its own config";
        if (api.APIVersion() < MIN_API_VERSION) {
            return "CoreProtect's API is version " + api.APIVersion()
                    + ", GodView needs " + MIN_API_VERSION + " or newer";
        }
        return "";
    }

    private CoreProtectAPI api() {
        try {
            Plugin plugin = plugin();
            if (!(plugin instanceof CoreProtect coreProtect) || !plugin.isEnabled()) return null;
            CoreProtectAPI api = coreProtect.getAPI();
            if (api == null || !api.isEnabled() || api.APIVersion() < MIN_API_VERSION) return null;
            return api;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One row of block history. */
    public record Change(long at, String player, String type, String action, int x, int y, int z,
                         boolean rolledBack) {

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("player", player)
                    .field("type", type)
                    .field("action", action)
                    .field("x", x)
                    .field("y", y)
                    .field("z", z)
                    .field("rolledBack", rolledBack)
                    .end();
        }
    }

    /** Why a lookup produced nothing, so the panel can tell the difference from "no history". */
    public static final class LookupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LookupFailure(String message) {
            super(message);
        }
    }

    /**
     * Looks up recent changes around a point.
     *
     * <p>{@code ParseResult.getTime()} is deprecated in newer CoreProtect but is what the
     * version GodView compiles against exposes, and it is still present. Suppressed here
     * rather than chased, since the replacement is not available across the range.
     *
     * @param seconds how far back to search
     * @param radius  block radius around the point
     * @throws LookupFailure when CoreProtect is unusable, or when called on the main thread
     */
    @SuppressWarnings("deprecation")
    public List<Change> lookup(String worldName, int x, int y, int z, int radius, int seconds) {
        if (onMainThread()) {
            // Deliberately loud. Silently running the query would stall the tick, which is
            // the single worst thing a monitoring plugin could do.
            throw new LookupFailure("a CoreProtect lookup must not run on the main thread");
        }
        CoreProtectAPI api = api();
        if (api == null) {
            String problem = problem();
            throw new LookupFailure(problem.isEmpty() ? "CoreProtect is not installed" : problem);
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new LookupFailure("no world named " + worldName);

        try {
            // (time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actions,
            //  radius, location) - every null means "no filter on this dimension".
            List<String[]> raw = api.performLookup(
                    Math.max(1, seconds), null, null, null, null, null,
                    Math.max(1, Math.min(radius, 100)),
                    new Location(world, x, y, z));
            if (raw == null) return List.of();

            List<Change> out = new ArrayList<>(Math.min(raw.size(), MAX_ROWS));
            for (String[] row : raw) {
                if (out.size() >= MAX_ROWS) break;
                CoreProtectAPI.ParseResult parsed = api.parseResult(row);
                if (parsed == null) continue;
                out.add(new Change(
                        parsed.getTime() * 1000L,
                        parsed.getPlayer(),
                        parsed.getType() == null ? "?" : parsed.getType().name(),
                        describeAction(parsed.getActionId()),
                        parsed.getX(), parsed.getY(), parsed.getZ(),
                        parsed.isRolledBack()));
            }
            return List.copyOf(out);
        } catch (LookupFailure e) {
            throw e;
        } catch (Throwable t) {
            throw new LookupFailure("CoreProtect rejected the lookup: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Whether we are on the tick loop.
     *
     * <p>Guarded because {@link #lookup} promises to signal every failure as a
     * {@link LookupFailure}, and the API layer only translates that into a clean 503.
     * Anything else escaping from here becomes a generic 500 with no explanation.
     */
    private static boolean onMainThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            // No server to protect, so nothing to refuse.
            return false;
        }
    }

    /** CoreProtect reports actions as small integers. */
    private static String describeAction(int actionId) {
        return switch (actionId) {
            case 0 -> "break";
            case 1 -> "place";
            case 2 -> "click";
            case 3 -> "container";
            case 4 -> "kill";
            default -> "action-" + actionId;
        };
    }
}
