package dev.godview.compat;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import dev.godview.util.Text;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * Every place the 1.21 line disagrees with itself, in one file.
 *
 * <p>GodView compiles against paper-api 1.21.1 — the oldest 1.21 — and ships a single jar
 * that runs unchanged through 1.21.11. Most of the API we touch is stable across that
 * range and is called directly; what actually moved gets a cached {@link Method} and a
 * fallback, so a server anywhere in the line — or a fork that dropped something —
 * degrades to a sane value instead of throwing {@code NoSuchMethodError} out of a
 * scheduled task.
 *
 * <p>The rule for anything added here: resolve reflectively <em>once</em> in the static
 * initializer, never per call. A metrics sampler runs twenty times a second; it cannot
 * afford a {@code getMethod} lookup, and it definitely cannot afford an exception.
 *
 * <p>Class initialization is failure-proof by construction: every lookup is wrapped, and
 * a null {@code Method} simply selects the fallback path.
 */
public final class Compat {

    /** Minor version, e.g. {@code 21} for 1.21.4. Defaults to the compile floor if detection fails. */
    public static final int MINOR;
    /** Patch version, e.g. {@code 4} for 1.21.4. */
    public static final int PATCH;
    /** Detected Minecraft version, e.g. {@code "1.21.4"}. */
    public static final String VERSION;
    /** Server brand — "Paper", "Purpur", "Folia"… */
    public static final String BRAND;
    /** Folia splits the main thread into region threads; several assumptions here break. */
    public static final boolean FOLIA;

    // --- reflectively resolved, null when the running server lacks them ---
    private static final Method M_CHUNK_COUNT;        // Paper: World#getChunkCount()
    private static final Method M_TILE_ENTITIES_FAST; // Paper: Chunk#getTileEntities(boolean)
    private static final Method M_VIEW_DISTANCE;      // World#getViewDistance()
    private static final Method M_SIM_DISTANCE;       // Paper: World#getSimulationDistance()
    private static final Method M_ENTITY_X;           // Paper: Entity#getX()
    private static final Method M_ENTITY_Z;           // Paper: Entity#getZ()
    private static final Method M_GET_ATTRIBUTE;      // LivingEntity#getAttribute(Attribute)
    private static final Method M_ATTR_GET_VALUE;     // AttributeInstance#getValue()
    private static final Object ATTR_MAX_HEALTH;      // Attribute constant, renamed in 1.21.3
    private static final Method M_GET_BAN_LIST;       // Bukkit#getBanList(<whichever type enum>)
    private static final Object BAN_TYPE_PLAYER;      // PROFILE where present, else NAME
    private static final Object BAN_TYPE_IP;

    static {
        // The floor we compile against, so a detection failure reports the least
        // capable supported server rather than claiming features that may not be there.
        int minor = 21;
        int patch = 1;
        String version = "1.21.1";
        String brand = "Unknown";
        try {
            // "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
            String raw = Bukkit.getBukkitVersion();
            if (raw != null) {
                int dash = raw.indexOf('-');
                version = dash > 0 ? raw.substring(0, dash) : raw;
                String[] parts = version.split("\\.");
                if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
                patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            }
        } catch (Throwable ignored) {
            // Bukkit not initialized (tests) or an unexpected version string — keep the floor.
        }
        try {
            brand = Bukkit.getName();
        } catch (Throwable ignored) {
        }
        MINOR = minor;
        PATCH = patch;
        VERSION = version;
        BRAND = brand;
        FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");

        M_CHUNK_COUNT = method(World.class, "getChunkCount");
        M_TILE_ENTITIES_FAST = method(Chunk.class, "getTileEntities", boolean.class);
        M_VIEW_DISTANCE = method(World.class, "getViewDistance");
        M_SIM_DISTANCE = method(World.class, "getSimulationDistance");
        M_ENTITY_X = method(Entity.class, "getX");
        M_ENTITY_Z = method(Entity.class, "getZ");

        // Attribute was an enum through 1.21.2 and became a registry interface in 1.21.3,
        // where GENERIC_MAX_HEALTH was also renamed to MAX_HEALTH. Both spellings are
        // static fields on the same class, so one field lookup covers every version.
        Object maxHealth = null;
        Method getAttribute = null;
        Method attrValue = null;
        try {
            Class<?> attrCls = Class.forName("org.bukkit.attribute.Attribute");
            maxHealth = staticField(attrCls, "MAX_HEALTH");
            if (maxHealth == null) maxHealth = staticField(attrCls, "GENERIC_MAX_HEALTH");
            getAttribute = method(LivingEntity.class, "getAttribute", attrCls);
            attrValue = method(Class.forName("org.bukkit.attribute.AttributeInstance"), "getValue");
        } catch (Throwable ignored) {
        }
        ATTR_MAX_HEALTH = maxHealth;
        M_GET_ATTRIBUTE = getAttribute;
        M_ATTR_GET_VALUE = attrValue;

        // Ban lists have been reorganised twice. BanList.Type.NAME gave way to PROFILE in
        // 1.20.4, and the whole nested BanList.Type enum gave way to a top-level
        // BanListType during 1.21. Resolving the type class and its constants at runtime
        // means one jar works either way, and works on forks that dropped the old names.
        Class<?> banTypeClass = banTypeClass();
        M_GET_BAN_LIST = banTypeClass == null ? null
                : method(Bukkit.class, "getBanList", banTypeClass);
        Object playerBanType = null;
        Object ipBanType = null;
        if (banTypeClass != null) {
            playerBanType = enumConst(banTypeClass, "PROFILE");
            if (playerBanType == null) playerBanType = enumConst(banTypeClass, "NAME");
            ipBanType = enumConst(banTypeClass, "IP");
        }
        BAN_TYPE_PLAYER = playerBanType;
        BAN_TYPE_IP = ipBanType;
    }

    /**
     * The enum a {@code getBanList} call expects: the modern top-level {@code BanListType}
     * where the server has one, otherwise the deprecated nested {@code BanList.Type}.
     */
    @SuppressWarnings("deprecation")
    private static Class<?> banTypeClass() {
        try {
            return Class.forName("org.bukkit.BanListType");
        } catch (Throwable ignored) {
            // Older 1.21 builds only have the nested enum.
        }
        try {
            return BanList.Type.class;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Compat() {
    }

    // ------------------------------------------------------------------ version

    /** {@code atLeast(21, 3)} — true on 1.21.3 and everything after. */
    public static boolean atLeast(int minor, int patch) {
        return MINOR > minor || (MINOR == minor && PATCH >= patch);
    }

    public static boolean hasClass(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** One line for {@code /godview status} and the panel footer. */
    public static String describe() {
        return BRAND + " " + VERSION + (FOLIA ? " (Folia)" : "");
    }

    // ------------------------------------------------------------ server health

    /** 1 m / 5 m / 15 m rolling TPS. Always length 3. */
    public static double[] tps() {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length >= 3) return tps;
        } catch (Throwable ignored) {
        }
        return new double[]{20d, 20d, 20d};
    }

    /** Mean milliseconds per tick as the server measures it. */
    public static double mspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
            return 0d;
        }
    }

    // ------------------------------------------------------------------- world

    /**
     * Loaded chunks in a world. Paper's counter is O(1); the Bukkit fallback allocates an
     * array of every loaded chunk, which is why callers must keep this off per-tick paths.
     */
    public static int chunkCount(World world) {
        if (M_CHUNK_COUNT != null) {
            try {
                return ((Number) M_CHUNK_COUNT.invoke(world)).intValue();
            } catch (Throwable ignored) {
            }
        }
        try {
            return world.getLoadedChunks().length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Block entities in a chunk (hoppers, chests, spawners — the usual lag suspects).
     *
     * <p>Paper's {@code getTileEntities(false)} skips the defensive snapshot copy of every
     * state; the Bukkit fallback does not, so it is materially more expensive.
     */
    public static int tileEntityCount(Chunk chunk) {
        if (M_TILE_ENTITIES_FAST != null) {
            try {
                Object result = M_TILE_ENTITIES_FAST.invoke(chunk, false);
                if (result instanceof BlockState[] states) return states.length;
                if (result instanceof java.util.Collection<?> c) return c.size();
            } catch (Throwable ignored) {
            }
        }
        try {
            return chunk.getTileEntities().length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Per-world view distance, or {@code -1} when the server does not expose it. */
    public static int viewDistance(World world) {
        return intCall(M_VIEW_DISTANCE, world, -1);
    }

    /** Per-world simulation distance (Paper), or {@code -1}. */
    public static int simulationDistance(World world) {
        return intCall(M_SIM_DISTANCE, world, -1);
    }

    /**
     * Namespaced biome id at a position, e.g. {@code "minecraft:plains"}.
     *
     * <p>Biome went from enum to registry interface in 1.21.3, but stayed {@link Keyed}
     * throughout — so the key works on both, while {@code name()} would not.
     */
    public static String biomeKey(World world, int x, int y, int z) {
        try {
            Object biome = world.getBiome(x, y, z);
            if (biome instanceof Keyed keyed) return keyed.getKey().toString();
            return String.valueOf(biome);
        } catch (Throwable ignored) {
            return "minecraft:plains";
        }
    }

    /**
     * Packs an entity's chunk coordinates into one long ({@code cx} high, {@code cz} low).
     *
     * <p>Used to build entity hotspot counts, which means calling it once per entity on a
     * server that may have tens of thousands. Paper's {@code getX()}/{@code getZ()} read
     * the position directly; {@link org.bukkit.entity.Entity#getLocation()} allocates a
     * {@link org.bukkit.Location} per call and is only the fallback.
     */
    public static long entityChunkKey(Entity entity) {
        if (M_ENTITY_X != null && M_ENTITY_Z != null) {
            try {
                int cx = (int) Math.floor(((Number) M_ENTITY_X.invoke(entity)).doubleValue()) >> 4;
                int cz = (int) Math.floor(((Number) M_ENTITY_Z.invoke(entity)).doubleValue()) >> 4;
                return chunkKey(cx, cz);
            } catch (Throwable ignored) {
            }
        }
        Location loc = entity.getLocation();
        return chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    // ------------------------------------------------------------------ player

    /** Round-trip time in ms, or {@code -1} if unavailable. */
    public static int ping(Player player) {
        try {
            return player.getPing();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Effective max health including modifiers; falls back to the vanilla 20. */
    public static double maxHealth(Player player) {
        if (M_GET_ATTRIBUTE != null && M_ATTR_GET_VALUE != null && ATTR_MAX_HEALTH != null) {
            try {
                Object instance = M_GET_ATTRIBUTE.invoke(player, ATTR_MAX_HEALTH);
                if (instance != null) {
                    return ((Number) M_ATTR_GET_VALUE.invoke(instance)).doubleValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return 20d;
    }

    /** Epoch millis of the player's last logout, or {@code 0}. */
    public static long lastSeen(OfflinePlayer player) {
        try {
            long seen = player.getLastSeen();
            if (seen > 0) return seen;
        } catch (Throwable ignored) {
        }
        try {
            return player.getLastLogin();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * Disconnects a player. Must run on the main thread.
     *
     * <p>Deprecated in favour of the Adventure {@code kick(Component)} overload, which does
     * not exist on every fork we support — the String form still works everywhere.
     */
    @SuppressWarnings("deprecation")
    public static void kick(Player player, String reason) {
        player.kickPlayer(reason == null ? "" : reason);
    }

    // ------------------------------------------------------- legacy text APIs

    // Paper is steadily replacing String-based text APIs with Adventure Components. The
    // replacements do not exist across every version and fork GodView supports, and the
    // panel wants a plain String at the end of it anyway, so the legacy calls stay — in
    // one place, suppressed deliberately rather than left to accumulate as noise.

    /** The player's display name with formatting codes removed. */
    @SuppressWarnings("deprecation")
    public static String displayName(Player player) {
        try {
            return Text.stripColors(player.getDisplayName());
        } catch (Throwable ignored) {
            return player.getName();
        }
    }

    /** The player's client locale, e.g. {@code "en_us"}, or an empty string. */
    @SuppressWarnings("deprecation")
    public static String locale(Player player) {
        try {
            return player.getLocale();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The server MOTD with formatting codes removed. */
    @SuppressWarnings("deprecation")
    public static String motd() {
        try {
            return Text.stripColors(Bukkit.getMotd());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Sends a message to every online player. Main thread only. */
    @SuppressWarnings("deprecation")
    public static void broadcast(String message) {
        Bukkit.broadcastMessage(message);
    }

    /** A plugin's declared version. */
    @SuppressWarnings("deprecation")
    public static String pluginVersion(Plugin plugin) {
        try {
            return plugin.getDescription().getVersion();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // -------------------------------------------------------------------- bans

    // The String-keyed BanList methods below are deprecated in favour of profile-keyed
    // ones, but those need a resolved PlayerProfile — a blocking Mojang lookup for anyone
    // who has never joined. The panel bans by typed name, often pre-emptively, so the
    // deprecated overloads are the correct tool here and the warnings are suppressed
    // deliberately rather than left to accumulate.

    /**
     * Bans a player by name. {@code expires} may be null for a permanent ban.
     *
     * @return false when the server exposes no usable ban list
     */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    public static boolean ban(String name, String reason, String source, Date expires) {
        BanList list = banList(BAN_TYPE_PLAYER);
        if (list == null) return false;
        try {
            list.addBan(name, reason, expires, source);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "deprecation"})
    public static boolean unban(String name) {
        BanList list = banList(BAN_TYPE_PLAYER);
        if (list == null) return false;
        try {
            list.pardon(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "deprecation"})
    public static boolean isBanned(String name) {
        BanList list = banList(BAN_TYPE_PLAYER);
        if (list == null) return false;
        try {
            return list.isBanned(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    public static boolean banIp(String ip, String reason, String source, Date expires) {
        BanList list = banList(BAN_TYPE_IP);
        if (list == null) return false;
        try {
            list.addBan(ip, reason, expires, source);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "deprecation"})
    public static boolean unbanIp(String ip) {
        BanList list = banList(BAN_TYPE_IP);
        if (list == null) return false;
        try {
            list.pardon(ip);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return the requested ban list, or null when this server exposes none we understand */
    @SuppressWarnings("rawtypes")
    private static BanList banList(Object type) {
        if (M_GET_BAN_LIST == null || type == null) return null;
        try {
            return (BanList) M_GET_BAN_LIST.invoke(null, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // --------------------------------------------------------------- internals

    private static int intCall(Method m, Object target, int def) {
        if (m == null) return def;
        try {
            return ((Number) m.invoke(target)).intValue();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object enumConst(Class<?> enumClass, String name) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) return staticField(enumClass, name);
        for (Object c : constants) {
            if (((Enum<?>) c).name().equals(name)) return c;
        }
        return null;
    }
}
