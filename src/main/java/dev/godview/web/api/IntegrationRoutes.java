package dev.godview.web.api;

import dev.godview.integration.CoreProtectIntegration;
import dev.godview.integration.EssentialsIntegration;
import dev.godview.integration.IntegrationManager;
import dev.godview.integration.LuckPermsIntegration;
import dev.godview.integration.PlaceholderIntegration;
import dev.godview.integration.VaultIntegration;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.web.Router;
import dev.godview.web.WebExchange;
import dev.godview.web.auth.Role;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the other plugins on the server know.
 *
 * <p>Every endpoint here degrades rather than fails. A server without LuckPerms gets
 * {@code available: false} and an empty payload, not a 404 or a 500 — the panel renders
 * the same view either way and simply has less to show, which is the behaviour that keeps
 * the front-end from needing to know which plugins exist.
 *
 * <p>The block-history endpoint is the one that does real work: a CoreProtect lookup is a
 * database query, and CoreProtect's API requires it be off the main thread. Running on an
 * HTTP worker satisfies that naturally, which is why this lives in the web layer rather
 * than being scheduled.
 */
public final class IntegrationRoutes {

    /** Maximum blocks either side of the point a lookup will search. */
    private static final int MAX_RADIUS = 50;
    /** Furthest back a lookup will look: 30 days. */
    private static final int MAX_SECONDS = 2_592_000;

    private final ApiContext ctx;

    public IntegrationRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router router) {
        router.get("/api/integrations", Role.Permission.VIEW, this::list);
        router.get("/api/permissions/groups", Role.Permission.VIEW, this::groups);
        router.get("/api/players/{name}/plugins", Role.Permission.VIEW, this::playerDetail);
        router.get("/api/blocks", Role.Permission.VIEW, this::blocks);
        // A POST because the request carries a list of expressions, not because it changes
        // anything — it is read-only and needs only VIEW.
        router.post("/api/placeholders", Role.Permission.VIEW, this::placeholders);
    }

    private void list(WebExchange exchange) throws IOException {
        IntegrationManager manager = ctx.plugins();
        if (manager == null) {
            exchange.json(200, new JsonWriter(64).obj().name("integrations").arr().end().end());
            return;
        }
        JsonWriter w = new JsonWriter(1024);
        manager.writeJson(w);
        exchange.json(200, w);
    }

    // ------------------------------------------------------------- permissions

    private void groups(WebExchange exchange) throws IOException {
        IntegrationManager manager = ctx.plugins();
        LuckPermsIntegration luckPerms = manager == null ? null : manager.luckPerms();

        JsonWriter w = new JsonWriter(2048);
        w.obj().field("available", luckPerms != null).name("groups").arr();
        if (luckPerms != null) {
            for (LuckPermsIntegration.GroupInfo group : luckPerms.groups()) group.writeJson(w);
        }
        exchange.json(200, w.end().end());
    }

    /**
     * Everything the other plugins know about one player, in a single request.
     *
     * <p>Grouped rather than split across five endpoints because the panel shows them on
     * one page: five round trips to render one card would be the wrong shape, and each
     * source here is cheap enough to gather together.
     */
    private void playerDetail(WebExchange exchange) throws IOException {
        String name = exchange.param("name");
        IntegrationManager manager = ctx.plugins();

        Player online = Bukkit.getPlayerExact(name);
        UUID uuid = online != null ? online.getUniqueId() : resolveUuid(name);

        JsonWriter w = new JsonWriter(2048);
        w.obj().field("player", name).field("uuid", uuid == null ? null : uuid.toString());

        LuckPermsIntegration luckPerms = manager == null ? null : manager.luckPerms();
        w.name("permissions");
        if (luckPerms != null && uuid != null) {
            luckPerms.describe(uuid).writeJson(w);
        } else {
            w.obj().field("loaded", false).end();
        }

        VaultIntegration vault = manager == null ? null : manager.vault();
        w.name("economy");
        if (vault != null && uuid != null) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            double balance = vault.balance(target);
            w.obj()
                    .field("available", !Double.isNaN(balance))
                    .field("balance", Double.isNaN(balance) ? 0 : balance)
                    .field("formatted", vault.formatted(target))
                    .field("currency", vault.currency())
                    .field("provider", vault.economyName())
                    .end();
        } else {
            w.obj().field("available", false).end();
        }

        EssentialsIntegration essentials = manager == null ? null : manager.essentials();
        w.name("essentials");
        if (essentials != null && online != null) {
            essentials.describe(online).writeJson(w);
        } else {
            w.obj().field("known", false).end();
        }

        exchange.json(200, w.end());
    }

    /**
     * Resolves a name to a UUID without ever contacting Mojang.
     *
     * <p>{@code Bukkit.getOfflinePlayer(String)} would happily make a blocking web request
     * for an unknown name, on an HTTP worker, for a panel that is probably just showing a
     * stale row. Only players the server already knows are resolved.
     */
    private static UUID resolveUuid(String name) {
        try {
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (name.equalsIgnoreCase(player.getName())) return player.getUniqueId();
            }
        } catch (Throwable ignored) {
            // Some forks make this expensive or unavailable; an unresolved name is fine.
        }
        return null;
    }

    // ---------------------------------------------------------------- blocks

    private void blocks(WebExchange exchange) throws IOException {
        IntegrationManager manager = ctx.plugins();
        CoreProtectIntegration coreProtect = manager == null ? null : manager.coreProtect();

        if (coreProtect == null) {
            exchange.json(200, new JsonWriter(256).obj()
                    .field("available", false)
                    .field("message", "CoreProtect is not available; "
                            + "GodView does not keep block history of its own.")
                    .name("changes").arr().end()
                    .end());
            return;
        }

        String world = exchange.query("world", null);
        if (world == null) {
            exchange.error(400, "missing_world", "A world is required.");
            return;
        }

        int radius = clamp(exchange.queryInt("radius", 5), 1, MAX_RADIUS);
        int seconds = clamp(exchange.queryInt("seconds", 3600), 1, MAX_SECONDS);

        try {
            List<CoreProtectIntegration.Change> changes = coreProtect.lookup(
                    world,
                    exchange.queryInt("x", 0), exchange.queryInt("y", 64), exchange.queryInt("z", 0),
                    radius, seconds);

            JsonWriter w = new JsonWriter(4096);
            w.obj()
                    .field("available", true)
                    .field("radius", radius)
                    .field("seconds", seconds)
                    .field("count", changes.size())
                    .name("changes").arr();
            for (CoreProtectIntegration.Change change : changes) change.writeJson(w);
            exchange.json(200, w.end().end());
        } catch (CoreProtectIntegration.LookupFailure e) {
            exchange.error(503, "lookup_failed", e.getMessage());
        }
    }

    // ----------------------------------------------------------- placeholders

    private void placeholders(WebExchange exchange) throws IOException {
        IntegrationManager manager = ctx.plugins();
        PlaceholderIntegration papi = manager == null ? null : manager.placeholders();

        if (papi == null) {
            exchange.json(200, new JsonWriter(128).obj()
                    .field("available", false).name("values").obj().end().end());
            return;
        }

        Map<String, Object> body = exchange.json(ctx.maxBody());
        List<String> expressions = new ArrayList<>();
        for (Object value : Json.asArr(body.get("placeholders"))) {
            String expression = String.valueOf(value).trim();
            if (!expression.isEmpty()) expressions.add(expression);
        }
        if (expressions.isEmpty()) {
            exchange.error(400, "missing_placeholders",
                    "Send a \"placeholders\" array of expressions.");
            return;
        }

        String playerName = Json.str(body, "player", "");
        Player target = playerName.isEmpty() ? null : Bukkit.getPlayerExact(playerName);

        Map<String, String> resolved = papi.resolve(target, expressions);

        JsonWriter w = new JsonWriter(1024);
        w.obj()
                .field("available", true)
                .field("player", playerName)
                .field("truncated", expressions.size() > PlaceholderIntegration.MAX_PER_REQUEST)
                .name("values").obj();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            w.field(entry.getKey(), entry.getValue());
        }
        exchange.json(200, w.end().end());
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
