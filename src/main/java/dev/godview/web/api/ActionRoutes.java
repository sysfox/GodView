package dev.godview.web.api;

import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.util.MainThread;
import dev.godview.web.Router;
import dev.godview.web.WebExchange;
import dev.godview.web.auth.Role;
import dev.godview.web.auth.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the panel changes.
 *
 * <p>These are the endpoints that reach into the running server, so each one goes through
 * the same four gates: the route table checks the caller's role, dangerous actions demand
 * an explicit confirmation flag, the work hops to the main thread with a timeout, and the
 * outcome is written to the audit log whether it succeeded or not.
 *
 * <p>The confirmation gate is enforced here rather than left to the panel's UI. A
 * confirmation dialog that only exists in JavaScript protects nobody who has a terminal
 * and curl, and {@code security.require-confirm} reads like a promise about the server.
 *
 * <p>Console output is not returned. Capturing it would mean handing plugins a proxied
 * {@code CommandSender}, and plugins that cast the sender to a concrete type would break
 * in ways an operator could not diagnose. The console log stream carries the output
 * instead, timestamped alongside the audit entry.
 */
public final class ActionRoutes {

    /** How long to wait for the main thread before declaring the server unresponsive. */
    private static final long ACTION_TIMEOUT_MS = 3_000L;

    private final ApiContext ctx;

    public ActionRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router router) {
        router.post("/api/players/{name}/kick", Role.Permission.PLAYER_ACTION, this::kick);
        router.post("/api/players/{name}/ban", Role.Permission.PLAYER_ACTION, this::ban);
        router.post("/api/players/{name}/unban", Role.Permission.PLAYER_ACTION, this::unban);
        router.post("/api/players/{name}/message", Role.Permission.PLAYER_ACTION, this::message);
        router.post("/api/players/{name}/gamemode", Role.Permission.PLAYER_ACTION, this::gamemode);
        router.post("/api/players/{name}/teleport", Role.Permission.PLAYER_ACTION, this::teleport);

        router.post("/api/worlds/{name}/weather", Role.Permission.WORLD_ACTION, this::weather);
        router.post("/api/worlds/{name}/time", Role.Permission.WORLD_ACTION, this::time);
        router.post("/api/worlds/{name}/save", Role.Permission.WORLD_ACTION, this::saveWorld);

        router.post("/api/console", Role.Permission.COMMAND, this::console);
        router.post("/api/broadcast", Role.Permission.PLAYER_ACTION, this::broadcast);
    }

    // ---------------------------------------------------------------- players

    private void kick(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        String reason = Json.str(body, "reason", "Kicked by an operator");

        Boolean result = onMainThread(exchange, () -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) return null;
            Compat.kick(player, reason);
            return Boolean.TRUE;
        });
        if (result == null) return;   // a response was already sent

        if (!result) {
            finish(exchange, 404, "player_not_found", "That player is not online.",
                    "player.kick", name, "not online", false);
            return;
        }
        finish(exchange, 200, null, "Kicked " + name + ".", "player.kick", name, reason, true);
    }

    private void ban(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        if (!confirmed(exchange, body, "player.ban")) return;

        String name = exchange.param("name");
        String reason = Json.str(body, "reason", "Banned by an operator");
        long durationMs = Json.lng(body, "durationMs", 0L);
        Date expires = durationMs > 0 ? new Date(System.currentTimeMillis() + durationMs) : null;
        String source = "GodView/" + user(exchange);

        Boolean ok = onMainThread(exchange, () -> {
            boolean banned = Compat.ban(name, reason, source, expires);
            // Kick them too — a ban that leaves the player connected is not a ban.
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) Compat.kick(player, reason);
            return banned;
        });
        if (ok == null) return;

        String detail = reason + (expires == null ? " (permanent)" : " (until " + expires + ")");
        if (!ok) {
            finish(exchange, 500, "ban_failed", "The server rejected the ban.",
                    "player.ban", name, detail, false);
            return;
        }
        finish(exchange, 200, null, "Banned " + name + ".", "player.ban", name, detail, true);
    }

    private void unban(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        if (!confirmed(exchange, body, "player.unban")) return;

        String name = exchange.param("name");
        Boolean ok = onMainThread(exchange, () -> Compat.unban(name));
        if (ok == null) return;

        if (!ok) {
            finish(exchange, 500, "unban_failed", "The server rejected the unban.",
                    "player.unban", name, "", false);
            return;
        }
        finish(exchange, 200, null, "Unbanned " + name + ".", "player.unban", name, "", true);
    }

    private void message(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        String text = Json.str(body, "message", "").trim();
        if (text.isEmpty()) {
            exchange.error(400, "missing_message", "A message is required.");
            return;
        }

        Boolean found = onMainThread(exchange, () -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) return Boolean.FALSE;
            player.sendMessage(text);
            return Boolean.TRUE;
        });
        if (found == null) return;

        if (!found) {
            finish(exchange, 404, "player_not_found", "That player is not online.",
                    "player.message", name, "not online", false);
            return;
        }
        finish(exchange, 200, null, "Message sent.", "player.message", name, text, true);
    }

    private void gamemode(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        String requested = Json.str(body, "mode", "").toUpperCase(Locale.ROOT);

        GameMode mode;
        try {
            mode = GameMode.valueOf(requested);
        } catch (IllegalArgumentException e) {
            exchange.error(400, "invalid_gamemode",
                    "Mode must be survival, creative, adventure or spectator.");
            return;
        }

        Boolean found = onMainThread(exchange, () -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) return Boolean.FALSE;
            player.setGameMode(mode);
            return Boolean.TRUE;
        });
        if (found == null) return;

        if (!found) {
            finish(exchange, 404, "player_not_found", "That player is not online.",
                    "player.gamemode", name, "not online", false);
            return;
        }
        finish(exchange, 200, null, name + " is now in " + mode.name().toLowerCase(Locale.ROOT) + ".",
                "player.gamemode", name, mode.name(), true);
    }

    private void teleport(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        String target = Json.str(body, "target", "").trim();
        String worldName = Json.str(body, "world", "");
        double x = Json.num(body, "x", 0);
        double y = Json.num(body, "y", 0);
        double z = Json.num(body, "z", 0);

        String result = onMainThread(exchange, () -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) return "not-online";

            Location destination;
            if (!target.isEmpty()) {
                Player other = Bukkit.getPlayerExact(target);
                if (other == null) return "target-offline";
                destination = other.getLocation();
            } else {
                World world = worldName.isEmpty() ? player.getWorld() : Bukkit.getWorld(worldName);
                if (world == null) return "no-world";
                destination = new Location(world, x, y, z);
            }
            player.teleport(destination);
            return "ok";
        });
        if (result == null) return;

        switch (result) {
            case "not-online" -> finish(exchange, 404, "player_not_found",
                    "That player is not online.", "player.teleport", name, "not online", false);
            case "target-offline" -> finish(exchange, 404, "target_not_found",
                    "The destination player is not online.", "player.teleport", name,
                    "target " + target + " offline", false);
            case "no-world" -> finish(exchange, 400, "world_not_found",
                    "No world named " + worldName + ".", "player.teleport", name,
                    "unknown world " + worldName, false);
            default -> finish(exchange, 200, null, "Teleported " + name + ".",
                    "player.teleport", name,
                    target.isEmpty() ? worldName + " " + x + "," + y + "," + z : "to " + target, true);
        }
    }

    // ----------------------------------------------------------------- worlds

    private void weather(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        String type = Json.str(body, "type", "clear").toLowerCase(Locale.ROOT);
        if (!type.equals("clear") && !type.equals("rain") && !type.equals("thunder")) {
            exchange.error(400, "invalid_weather", "Type must be clear, rain or thunder.");
            return;
        }

        Boolean found = onMainThread(exchange, () -> {
            World world = Bukkit.getWorld(name);
            if (world == null) return Boolean.FALSE;
            world.setStorm(!type.equals("clear"));
            world.setThundering(type.equals("thunder"));
            return Boolean.TRUE;
        });
        if (found == null) return;

        if (!found) {
            finish(exchange, 404, "world_not_found", "No world named " + name + ".",
                    "world.weather", name, "unknown world", false);
            return;
        }
        finish(exchange, 200, null, "Weather set to " + type + ".", "world.weather", name, type, true);
    }

    private void time(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String name = exchange.param("name");
        long value = Json.lng(body, "time", 0L);
        if (value < 0 || value > 24_000) {
            exchange.error(400, "invalid_time", "Time must be between 0 and 24000.");
            return;
        }

        Boolean found = onMainThread(exchange, () -> {
            World world = Bukkit.getWorld(name);
            if (world == null) return Boolean.FALSE;
            world.setTime(value);
            return Boolean.TRUE;
        });
        if (found == null) return;

        if (!found) {
            finish(exchange, 404, "world_not_found", "No world named " + name + ".",
                    "world.time", name, "unknown world", false);
            return;
        }
        finish(exchange, 200, null, "Time set to " + value + ".", "world.time", name,
                String.valueOf(value), true);
    }

    private void saveWorld(WebExchange exchange) throws IOException {
        String name = exchange.param("name");
        // A world save is synchronous disk I/O on the main thread and can take seconds on
        // a large world, so it gets a longer leash than the other actions.
        Boolean found;
        try {
            found = MainThread.call(ctx.plugin(), () -> {
                World world = Bukkit.getWorld(name);
                if (world == null) return Boolean.FALSE;
                world.save();
                return Boolean.TRUE;
            }, 30_000L);
        } catch (MainThread.NotRespondingException e) {
            finish(exchange, 503, "server_busy", e.getMessage(), "world.save", name,
                    "timed out", false);
            return;
        }

        if (!found) {
            finish(exchange, 404, "world_not_found", "No world named " + name + ".",
                    "world.save", name, "unknown world", false);
            return;
        }
        finish(exchange, 200, null, "Saved " + name + ".", "world.save", name, "", true);
    }

    // ---------------------------------------------------------------- console

    /**
     * Runs a console command, subject to {@code security.command-blacklist} and
     * {@code command-whitelist}.
     *
     * <p>The filter runs before the command reaches the server and its verdict is audited
     * either way, so a refused attempt at {@code /stop} is on the record.
     */
    private void console(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String command = Json.str(body, "command", "").trim();
        if (command.isEmpty()) {
            exchange.error(400, "missing_command", "A command is required.");
            return;
        }
        if (command.length() > 512) {
            exchange.error(400, "command_too_long", "Commands are limited to 512 characters.");
            return;
        }

        GodViewConfig.Security security = ctx.cfg().security;
        if (!security.commandAllowed(command)) {
            finish(exchange, 403, "command_blocked",
                    "That command is blocked by security.command-blacklist.",
                    "console.command", command, "blocked by filter", false);
            return;
        }
        if (!confirmed(exchange, body, "console.command")) return;

        String withoutSlash = command.startsWith("/") ? command.substring(1) : command;
        Boolean accepted = onMainThread(exchange,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), withoutSlash));
        if (accepted == null) return;

        JsonWriter w = new JsonWriter(256);
        w.obj()
                .field("ok", accepted)
                .field("command", command)
                // Output is not captured here; see the class note. The console log stream
                // carries it, and the panel lines the two up by timestamp.
                .field("message", accepted
                        ? "Command dispatched. Output appears in the console view."
                        : "The server did not recognise that command.")
                .end();
        audit(exchange, "console.command", command, accepted ? "dispatched" : "unknown command",
                accepted);
        exchange.json(accepted ? 200 : 400, w);
    }

    private void broadcast(WebExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String text = Json.str(body, "message", "").trim();
        if (text.isEmpty()) {
            exchange.error(400, "missing_message", "A message is required.");
            return;
        }

        Integer reached = onMainThread(exchange, () -> {
            Compat.broadcast(text);
            return Bukkit.getOnlinePlayers().size();
        });
        if (reached == null) return;

        finish(exchange, 200, null, "Broadcast to " + reached + " player(s).",
                "server.broadcast", "-", text, true);
    }

    // ----------------------------------------------------------------- helpers

    private Map<String, Object> body(WebExchange exchange) throws IOException {
        return exchange.json(ctx.maxBody());
    }

    private static String user(WebExchange exchange) {
        SessionManager.Session session = exchange.session();
        return session == null ? "?" : session.user();
    }

    /**
     * Enforces {@code security.require-confirm} for a destructive action.
     *
     * @return true when the caller may proceed; otherwise a 409 has been sent
     */
    private boolean confirmed(WebExchange exchange, Map<String, Object> body, String action)
            throws IOException {
        if (!ctx.cfg().security.requireConfirm()) return true;
        if (Json.flag(body, "confirm", false)) return true;

        audit(exchange, action, "-", "refused: confirmation required", false);
        exchange.json(409, new JsonWriter(256)
                .obj()
                .field("error", "confirmation_required")
                .field("message", "This action needs \"confirm\": true "
                        + "(security.require-confirm is on).")
                .end());
        return false;
    }

    /**
     * Runs work on the main thread, answering 503 if the server is not ticking.
     *
     * @return the result, or null when a response has already been sent
     */
    private <T> T onMainThread(WebExchange exchange, java.util.function.Supplier<T> work)
            throws IOException {
        try {
            return MainThread.call(ctx.plugin(), work, ACTION_TIMEOUT_MS);
        } catch (MainThread.NotRespondingException e) {
            // The panel is most useful precisely when the server is struggling, so this
            // has to be a clear answer rather than a hung request.
            exchange.error(503, "server_busy", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            exchange.error(500, "action_failed",
                    "The server rejected that action: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Sends the response and writes the audit entry in one step, so neither is forgotten. */
    private void finish(WebExchange exchange, int status, String errorCode, String message,
                        String action, String target, String detail, boolean success)
            throws IOException {
        audit(exchange, action, target, detail, success);
        if (errorCode != null) {
            exchange.error(status, errorCode, message);
        } else {
            exchange.json(status, new JsonWriter(160)
                    .obj().field("ok", true).field("message", message).end());
        }
    }

    private void audit(WebExchange exchange, String action, String target, String detail,
                       boolean success) {
        if (!ctx.cfg().security.audit()) return;
        Storage storage = ctx.store();
        if (storage == null) return;
        storage.recordAudit(new Storage.AuditEntry(System.currentTimeMillis(),
                user(exchange), exchange.clientIp(), action, target, detail, success));
    }
}
