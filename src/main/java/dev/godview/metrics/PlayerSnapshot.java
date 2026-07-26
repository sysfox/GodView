package dev.godview.metrics;

import dev.godview.compat.Compat;
import dev.godview.util.JsonWriter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One online player, frozen at a moment in time.
 *
 * <p>Collected on the main thread once a second and published as an immutable list, so the
 * panel's player view is served straight from memory. The alternative — hopping to the
 * main thread on every request — would add up to a tick of latency per poll and put the
 * HTTP layer's load directly onto the tick loop.
 *
 * <p>{@code ip} is separated out deliberately. It is written into the snapshot because
 * operators genuinely need it for ban decisions, and stripped by the API for anyone
 * without {@link dev.godview.web.auth.Role.Permission#PLAYER_ACTION} — a read-only account
 * has no business collecting the addresses of everyone on the server.
 */
public record PlayerSnapshot(String name, String uuid, String displayName, int ping,
                             double health, double maxHealth, int food, int level, float exp,
                             String gamemode, String world, double x, double y, double z,
                             boolean op, boolean flying, boolean sneaking, boolean sleeping,
                             long firstPlayed, long playTimeMs, String ip, String locale) {

    /** Reads every online player. Main thread only. */
    public static List<PlayerSnapshot> collectAll() {
        List<PlayerSnapshot> out = new ArrayList<>(Bukkit.getOnlinePlayers().size());
        for (Player player : Bukkit.getOnlinePlayers()) {
            out.add(of(player));
        }
        return List.copyOf(out);
    }

    /** Reads one player. Main thread only. */
    public static PlayerSnapshot of(Player player) {
        Location loc = player.getLocation();
        String address = "";
        try {
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                address = player.getAddress().getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
            // Some proxies detach the address; an empty string is the honest answer.
        }

        return new PlayerSnapshot(
                player.getName(),
                player.getUniqueId().toString(),
                Compat.displayName(player),
                Compat.ping(player),
                player.getHealth(),
                Compat.maxHealth(player),
                player.getFoodLevel(),
                player.getLevel(),
                player.getExp(),
                player.getGameMode().name(),
                loc.getWorld() == null ? "" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                player.isOp(),
                player.isFlying(),
                player.isSneaking(),
                player.isSleeping(),
                player.getFirstPlayed(),
                playTimeMs(player),
                address,
                Compat.locale(player));
    }

    /**
     * Total time played, in milliseconds.
     *
     * <p>{@code PLAY_ONE_MINUTE} is a long-standing misnomer — it counts ticks, not
     * minutes — which is exactly the kind of thing that produces a panel showing someone
     * has played for four hundred years.
     */
    private static long playTimeMs(Player player) {
        try {
            return player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) * 50L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public double healthPercent() {
        return maxHealth <= 0 ? 0 : health * 100d / maxHealth;
    }

    /**
     * @param includeIp false for accounts that may only look, not act — see the class note
     */
    public void writeJson(JsonWriter w, boolean includeIp) {
        w.obj()
                .field("name", name)
                .field("uuid", uuid)
                .field("displayName", displayName)
                .field("ping", ping)
                .field("health", health)
                .field("maxHealth", maxHealth)
                .field("healthPercent", healthPercent())
                .field("food", food)
                .field("level", level)
                .field("exp", exp)
                .field("gamemode", gamemode)
                .field("world", world)
                .field("x", x)
                .field("y", y)
                .field("z", z)
                .field("op", op)
                .field("flying", flying)
                .field("sneaking", sneaking)
                .field("sleeping", sleeping)
                .field("firstPlayed", firstPlayed)
                .field("playTimeMs", playTimeMs)
                .field("locale", locale);
        if (includeIp) w.field("ip", ip);
        w.end();
    }
}
