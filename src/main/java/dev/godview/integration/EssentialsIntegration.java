package dev.godview.integration;

import dev.godview.compat.Compat;
import dev.godview.util.JsonWriter;
import dev.godview.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Moderation state from EssentialsX: mutes, jails, AFK, nicknames.
 *
 * <p>These are the facts that explain a player's behaviour on the panel. Someone who has
 * said nothing for an hour looks idle until you can see they are muted, and a player
 * apparently stuck in one place is obviously jailed once the panel says so.
 *
 * <p>Reflective, because EssentialsX publishes to its own repository and this needs five
 * getters. Every one is individually guarded: EssentialsX renames things between major
 * versions, and one missing method should cost that one field, not the whole integration.
 */
public final class EssentialsIntegration implements Integration {

    @Override
    public String id() {
        return "essentials";
    }

    @Override
    public String displayName() {
        return "EssentialsX";
    }

    @Override
    public boolean available() {
        return essentials() != null;
    }

    @Override
    public String version() {
        Plugin plugin = plugin();
        return plugin == null ? "" : Compat.pluginVersion(plugin);
    }

    @Override
    public String problem() {
        Plugin plugin = plugin();
        if (plugin == null) return "";
        return plugin.isEnabled() ? "" : "EssentialsX is installed but disabled";
    }

    private Plugin plugin() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
            return plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("EssentialsX");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object essentials() {
        Plugin plugin = plugin();
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /**
     * EssentialsX's view of one player.
     *
     * @param known false when EssentialsX has no record, so the panel does not report
     *              "not muted" about a player it never actually asked about
     */
    public record PlayerState(boolean known, String nickname, boolean muted, long muteUntil,
                              String muteReason, boolean afk, boolean jailed, boolean godMode,
                              boolean vanished, int homes) {

        static PlayerState absent() {
            return new PlayerState(false, "", false, 0, "", false, false, false, false, 0);
        }

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("known", known)
                    .field("nickname", nickname)
                    .field("muted", muted)
                    .field("muteUntil", muteUntil)
                    .field("muteReason", muteReason)
                    .field("afk", afk)
                    .field("jailed", jailed)
                    .field("godMode", godMode)
                    .field("vanished", vanished)
                    .field("homes", homes)
                    .end();
        }
    }

    /** Reads one online player. Main thread not required. */
    public PlayerState describe(Player player) {
        Object essentials = essentials();
        if (essentials == null || player == null) return PlayerState.absent();

        try {
            Object user = Reflect.call(essentials, "getUser", new Class<?>[]{Player.class}, player);
            if (user == null) return PlayerState.absent();

            String nickname = Text.stripColors(
                    Reflect.string(Reflect.call(user, "getNickname", new Class<?>[0]), ""));

            Object homes = Reflect.call(user, "getHomes", new Class<?>[0]);
            int homeCount = homes instanceof java.util.Collection<?> c ? c.size() : 0;

            return new PlayerState(true,
                    nickname,
                    Reflect.flag(Reflect.call(user, "isMuted", new Class<?>[0]), false),
                    (long) Reflect.number(Reflect.call(user, "getMuteTimeout", new Class<?>[0]), 0),
                    Reflect.string(Reflect.call(user, "getMuteReason", new Class<?>[0]), ""),
                    Reflect.flag(Reflect.call(user, "isAfk", new Class<?>[0]), false),
                    Reflect.flag(Reflect.call(user, "isJailed", new Class<?>[0]), false),
                    Reflect.flag(Reflect.call(user, "isGodModeEnabled", new Class<?>[0]), false),
                    Reflect.flag(Reflect.call(user, "isVanished", new Class<?>[0]), false),
                    homeCount);
        } catch (Throwable ignored) {
            return PlayerState.absent();
        }
    }
}
