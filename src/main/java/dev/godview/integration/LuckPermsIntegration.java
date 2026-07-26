package dev.godview.integration;

import dev.godview.util.JsonWriter;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads groups, prefixes and permissions from LuckPerms.
 *
 * <p>Only <em>reads</em>. Writing permissions from a web panel is a genuinely dangerous
 * operation — one mistyped node can hand out operator — and LuckPerms already has a well
 * designed interface for it. GodView shows what the permissions are and points at
 * LuckPerms for changing them; the panel is a monitoring tool, not a second, worse
 * permissions editor.
 *
 * <p>The provider is fetched per call rather than cached. LuckPerms can be reloaded
 * underneath us, and a stale reference would keep answering with data from before.
 *
 * <p>Only users LuckPerms already has in memory are consulted. Loading an offline user is
 * a database round trip, and a panel refresh must not turn into one query per player.
 */
public final class LuckPermsIntegration implements Integration {

    /** Permission nodes returned for one player. Beyond this it is a wall of text. */
    private static final int MAX_NODES = 200;

    @Override
    public String id() {
        return "luckperms";
    }

    @Override
    public String displayName() {
        return "LuckPerms";
    }

    @Override
    public boolean available() {
        return provider() != null;
    }

    @Override
    public String version() {
        Plugin plugin = plugin();
        return plugin == null ? "" : dev.godview.compat.Compat.pluginVersion(plugin);
    }

    private Plugin plugin() {
        try {
            return Bukkit.getPluginManager().getPlugin("LuckPerms");
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
        if (!plugin.isEnabled()) return "LuckPerms is installed but disabled";
        return provider() == null ? "LuckPerms has not registered its API provider yet" : "";
    }

    private LuckPerms provider() {
        try {
            return LuckPermsProvider.get();
        } catch (Throwable ignored) {
            // Not installed, or the API was requested before LuckPerms finished loading.
            return null;
        }
    }

    /**
     * What GodView knows about one player's permissions.
     *
     * @param loaded false when LuckPerms has no in-memory record, so the panel can say
     *               "not loaded" instead of showing an empty group list as if it were true
     */
    public record PlayerPermissions(boolean loaded, String primaryGroup, String prefix,
                                    String suffix, List<String> groups, List<String> nodes,
                                    int totalNodes) {

        static PlayerPermissions absent() {
            return new PlayerPermissions(false, "", "", "", List.of(), List.of(), 0);
        }

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("loaded", loaded)
                    .field("primaryGroup", primaryGroup)
                    .field("prefix", prefix)
                    .field("suffix", suffix)
                    .field("groups", groups)
                    .field("nodes", nodes)
                    .field("totalNodes", totalNodes)
                    .end();
        }
    }

    /** Reads one player. Safe from any thread; never touches the database. */
    public PlayerPermissions describe(UUID uuid) {
        LuckPerms api = provider();
        if (api == null || uuid == null) return PlayerPermissions.absent();

        try {
            User user = api.getUserManager().getUser(uuid);
            if (user == null) return PlayerPermissions.absent();

            CachedMetaData meta = user.getCachedData().getMetaData();

            List<String> groups = new ArrayList<>();
            List<String> nodes = new ArrayList<>();
            int total = 0;
            for (Node node : user.getNodes()) {
                total++;
                String key = node.getKey();
                if (key.startsWith("group.")) {
                    groups.add(key.substring("group.".length()));
                } else if (nodes.size() < MAX_NODES) {
                    nodes.add((node.getValue() ? "" : "-") + key);
                }
            }

            return new PlayerPermissions(true,
                    user.getPrimaryGroup(),
                    meta.getPrefix() == null ? "" : dev.godview.util.Text.stripColors(meta.getPrefix()),
                    meta.getSuffix() == null ? "" : dev.godview.util.Text.stripColors(meta.getSuffix()),
                    List.copyOf(groups), List.copyOf(nodes), total);
        } catch (Throwable ignored) {
            return PlayerPermissions.absent();
        }
    }

    /** Every group LuckPerms has loaded, with its weight, most important first. */
    public List<GroupInfo> groups() {
        LuckPerms api = provider();
        if (api == null) return List.of();
        try {
            List<GroupInfo> out = new ArrayList<>();
            for (Group group : api.getGroupManager().getLoadedGroups()) {
                out.add(new GroupInfo(group.getName(),
                        group.getWeight().orElse(0),
                        group.getCachedData().getMetaData().getPrefix() == null ? ""
                                : dev.godview.util.Text.stripColors(
                                        group.getCachedData().getMetaData().getPrefix())));
            }
            out.sort((a, b) -> b.weight() - a.weight());
            return List.copyOf(out);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public record GroupInfo(String name, int weight, String prefix) {

        public void writeJson(JsonWriter w) {
            w.obj().field("name", name).field("weight", weight).field("prefix", prefix).end();
        }
    }
}
