package dev.godview.compat;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Keyed;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * Every place where 1.20.x and 1.21.x disagree, in one file.
 *
 * <p>GodView compiles against paper-api 1.20.1 and ships a single jar for both branches.
 * Most of the API we touch is stable across them and is called directly; what actually
 * moved gets a cached {@link Method} and a fallback, so a server on either branch —
 * or a fork that dropped something — degrades to a sane value instead of throwing
 * {@code NoSuchMethodError} out of a scheduled task.
 *
 * <p>The rule for anything added here: resolve reflectively <em>once</em> in the static
 * initializer, never per call. A metrics sampler runs twenty times a second; it cannot
 * afford a {@code getMethod} lookup, and it definitely cannot afford an exception.
 *
 * <p>Class initialization is failure-proof by construction: every lookup is wrapped, and
 * a null {@code Method} simply selects the fallback path.
 */
public final class Compat {

    /** Minor version, e.g. {@code 21} for 1.21.4. Defaults to 20 if detection fails. */
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
    private static final Method M_GET_ATTRIBUTE;      // LivingEntity#getAttribute(Attribute)
    private static final Method M_ATTR_GET_VALUE;     // AttributeInstance#getValue()
    private static final Object ATTR_MAX_HEALTH;      // Attribute constant, renamed in 1.21.3
    private static final BanList.Type BAN_TYPE_NAME;  // PROFILE on 1.20.4+, NAME before

    static {
        int minor = 20;
        int patch = 1;
        String version = "1.20.1";
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

        // BanList.Type.NAME is deprecated from 1.20.4 in favour of PROFILE. Both accept
        // the String-keyed methods we use, so we just prefer whichever exists.
        BanList.Type banType = null;
        try {
            banType = (BanList.Type) enumConst(BanList.Type.class, "PROFILE");
            if (banType == null) banType = (BanList.Type) enumConst(BanList.Type.class, "NAME");
        } catch (Throwable ignored) {
        }
        BAN_TYPE_NAME = banType;
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

    /** Disconnects a player. Must run on the main thread. */
    public static void kick(Player player, String reason) {
        player.kickPlayer(reason == null ? "" : reason);
    }

    // -------------------------------------------------------------------- bans

    /**
     * Bans a player by name. {@code expires} may be null for a permanent ban.
     *
     * @return false when the server exposes no usable ban list
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean ban(String name, String reason, String source, Date expires) {
        if (BAN_TYPE_NAME == null) return false;
        try {
            BanList list = Bukkit.getBanList(BAN_TYPE_NAME);
            list.addBan(name, reason, expires, source);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    public static boolean unban(String name) {
        if (BAN_TYPE_NAME == null) return false;
        try {
            BanList list = Bukkit.getBanList(BAN_TYPE_NAME);
            list.pardon(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    public static boolean isBanned(String name) {
        if (BAN_TYPE_NAME == null) return false;
        try {
            BanList list = Bukkit.getBanList(BAN_TYPE_NAME);
            return list.isBanned(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean banIp(String ip, String reason, String source, Date expires) {
        try {
            BanList list = Bukkit.getBanList(BanList.Type.IP);
            list.addBan(ip, reason, expires, source);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    public static boolean unbanIp(String ip) {
        try {
            BanList list = Bukkit.getBanList(BanList.Type.IP);
            list.pardon(ip);
            return true;
        } catch (Throwable ignored) {
            return false;
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
