package dev.godview.map;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps blocks to the colour they are drawn as on the map.
 *
 * <p>Two layers, and the second is the important one. Named blocks get an exact colour;
 * everything else falls back to matching on the material's <em>name</em> — anything ending
 * in {@code _LEAVES} is foliage, anything containing {@code _ORE} is stone with a fleck of
 * colour, and so on.
 *
 * <p>That fallback is what keeps the map working across versions. Minecraft adds dozens of
 * blocks every release, and a lookup table built purely from an enum would render each new
 * wood set, each new stone variant and each new copper state as blank grey until somebody
 * updated it. Matching on the name means a block called {@code PALE_OAK_PLANKS} draws as
 * planks the day it ships, without GodView knowing it exists.
 *
 * <p>Colours are approximations of Minecraft's own map palette rather than exact averages
 * of the textures. A map is read at a glance: water must be obviously water and grass
 * obviously grass, which matters more than either being exactly right.
 */
public final class BlockColors {

    /** Anything unrecognised, including air seen through a hole in the world. */
    public static final int UNKNOWN = 0x2A2A2E;
    /** Drawn where a chunk has no blocks at all. */
    public static final int VOID = 0x101014;

    private static final Map<Material, Integer> EXACT = new EnumMap<>(Material.class);

    private BlockColors() {
    }

    static {
        // Terrain
        put("GRASS_BLOCK", 0x7CB342);
        put("DIRT", 0x8B6547);
        put("COARSE_DIRT", 0x7F5C3F);
        put("ROOTED_DIRT", 0x8A6248);
        put("PODZOL", 0x6B4423);
        put("MYCELIUM", 0x6F6265);
        put("FARMLAND", 0x6B4A2F);
        put("DIRT_PATH", 0x9A8348);
        put("STONE", 0x7E7E7E);
        put("DEEPSLATE", 0x4C4C50);
        put("TUFF", 0x6C6E67);
        put("CALCITE", 0xDFDFD5);
        put("GRAVEL", 0x8B8687);
        put("SAND", 0xE6DBAC);
        put("RED_SAND", 0xBF6A2E);
        put("SANDSTONE", 0xDCD2A0);
        put("CLAY", 0xA0A6B0);
        put("BEDROCK", 0x333333);
        put("OBSIDIAN", 0x15121F);
        put("NETHERRACK", 0x6E3634);
        put("SOUL_SAND", 0x54413A);
        put("SOUL_SOIL", 0x4C3A33);
        put("END_STONE", 0xDCDCA0);
        put("MOSS_BLOCK", 0x5A7A2E);

        // Water and ice
        put("WATER", 0x3B62C4);
        put("ICE", 0x9CBFF0);
        put("PACKED_ICE", 0x8FB4E8);
        put("BLUE_ICE", 0x74A2E8);
        put("SNOW", 0xF2FAFA);
        put("SNOW_BLOCK", 0xF2FAFA);
        put("POWDER_SNOW", 0xF0F6F6);

        // Hazards, drawn loud on purpose: a lava lake next to a build is worth seeing.
        put("LAVA", 0xE8622B);
        put("MAGMA_BLOCK", 0x9C4321);
        put("FIRE", 0xE87A20);

        // Vegetation
        put("OAK_LEAVES", 0x4F7F2E);
        put("SPRUCE_LEAVES", 0x3E6B37);
        put("BIRCH_LEAVES", 0x6E9B3C);
        put("JUNGLE_LEAVES", 0x4C8A28);
        put("ACACIA_LEAVES", 0x6E9433);
        put("DARK_OAK_LEAVES", 0x3F6B24);
        put("AZALEA_LEAVES", 0x5C8235);

        // Common built blocks
        put("COBBLESTONE", 0x767676);
        put("MOSSY_COBBLESTONE", 0x66755C);
        put("BRICKS", 0x976157);
        put("NETHER_BRICKS", 0x36191E);
        put("GLASS", 0xC8E7EC);
        put("GLOWSTONE", 0xE4C46A);
        put("SEA_LANTERN", 0xB6CFC7);
        put("TORCH", 0xE9C36B);
        put("CRAFTING_TABLE", 0x8B5F35);
        put("CHEST", 0x9C6B2E);
        put("FURNACE", 0x757575);
        put("HOPPER", 0x3B3B3F);
        put("REDSTONE_BLOCK", 0xB5271A);
        put("IRON_BLOCK", 0xD8D8D8);
        put("GOLD_BLOCK", 0xF2CA3D);
        put("DIAMOND_BLOCK", 0x5DDBD3);
        put("EMERALD_BLOCK", 0x2FBE58);
        put("NETHERITE_BLOCK", 0x453F42);
        put("COPPER_BLOCK", 0xC06B4D);
        put("QUARTZ_BLOCK", 0xE6E1D8);
        put("BOOKSHELF", 0x9A7B4B);
        put("SPAWNER", 0x22323C);
        put("BEACON", 0x84E0DC);
        put("ENCHANTING_TABLE", 0x3B2B3F);
        put("SLIME_BLOCK", 0x7BC26B);
        put("HONEY_BLOCK", 0xE5A02F);
        put("SCAFFOLDING", 0xC8A465);
    }

    private static void put(String name, int rgb) {
        Material material = Material.getMaterial(name);
        // Absent on older versions: skipped rather than crashing the class initializer.
        if (material != null) EXACT.put(material, rgb);
    }

    /** The colour for a block, never failing. */
    public static int of(Material material) {
        if (material == null) return UNKNOWN;
        Integer exact = EXACT.get(material);
        if (exact != null) return exact;
        return byName(material.name());
    }

    /**
     * The name-shaped fallback.
     *
     * <p>Ordered from most to least specific, because the tests overlap: {@code DEEPSLATE}
     * contains {@code SLATE} but must not be caught by the generic stone rule, and
     * {@code STRIPPED_OAK_LOG} has to reach the log rule rather than the plank one.
     */
    private static int byName(String name) {
        // Wood, the single biggest family and the one that grows every release.
        if (name.endsWith("_LEAVES")) return 0x4F7F2E;
        if (name.endsWith("_SAPLING")) return 0x4F7F2E;
        if (name.contains("_LOG") || name.contains("_WOOD") || name.contains("_STEM")
                || name.contains("_HYPHAE")) {
            return woodTone(name, 0x6B4E2E);
        }
        if (name.contains("PLANKS") || name.contains("_STAIRS") || name.contains("_SLAB")
                || name.contains("_FENCE") || name.contains("_DOOR")
                || name.contains("_TRAPDOOR") || name.contains("_SIGN")) {
            return woodOrStone(name);
        }

        // Ore and stone families.
        if (name.contains("_ORE")) return oreTone(name);
        if (name.startsWith("DEEPSLATE")) return 0x4C4C50;
        if (name.contains("BLACKSTONE")) return 0x2C262C;
        if (name.contains("ANDESITE")) return 0x8A8A8A;
        if (name.contains("DIORITE")) return 0xC5C5C5;
        if (name.contains("GRANITE")) return 0x9A6B58;
        if (name.contains("SANDSTONE")) return name.startsWith("RED") ? 0xBF6A2E : 0xDCD2A0;
        if (name.contains("PRISMARINE")) return 0x5A9C8E;
        if (name.contains("PURPUR")) return 0xA779A7;
        if (name.contains("TERRACOTTA")) return 0x9A5B41;
        if (name.contains("CONCRETE")) return 0x8A8A96;
        if (name.contains("WOOL") || name.contains("CARPET")) return 0xD0D0D0;
        if (name.contains("GLASS")) return 0xC8E7EC;
        if (name.contains("STONE") || name.contains("BRICK") || name.contains("COBBLED")) {
            return 0x7E7E7E;
        }

        // Plants and crops read as green whatever they are called. Flowers are the one
        // family Minecraft names individually rather than with a shared suffix, so each
        // has to be listed -- DANDELION and POPPY share no substring with anything.
        if (name.contains("FLOWER") || name.contains("TULIP") || name.contains("ORCHID")
                || name.contains("ROSE") || name.contains("DAISY") || name.contains("ALLIUM")
                || name.contains("DANDELION") || name.contains("POPPY")
                || name.contains("BLUET") || name.contains("PETALS")
                || name.contains("BLOSSOM") || name.contains("PITCHER")
                || name.contains("LILAC") || name.contains("PEONY")) {
            return 0x8FBF5A;
        }
        if (name.contains("GRASS") || name.contains("FERN") || name.contains("VINE")
                || name.contains("MOSS") || name.contains("BAMBOO") || name.contains("KELP")
                || name.contains("SEAGRASS") || name.contains("LILY")) {
            return 0x6FA83C;
        }
        if (name.contains("WHEAT") || name.contains("CARROT") || name.contains("POTATO")
                || name.contains("BEETROOT") || name.contains("CROP") || name.contains("MELON")
                || name.contains("PUMPKIN")) {
            return 0xB5A03A;
        }
        if (name.contains("MUSHROOM") || name.contains("FUNGUS")) return 0x9A5B41;

        // Nether and end.
        if (name.contains("NETHER")) return 0x6E3634;
        if (name.contains("WARPED")) return 0x2A7A72;
        if (name.contains("CRIMSON")) return 0x8A3A4A;
        if (name.contains("END_")) return 0xDCDCA0;
        if (name.contains("COPPER")) return 0xC06B4D;

        if (name.contains("AIR") || name.contains("VOID")) return VOID;
        return UNKNOWN;
    }

    /** Distinguishes the wood types that actually look different from a distance. */
    private static int woodTone(String name, int fallback) {
        if (name.contains("SPRUCE")) return 0x5A4227;
        if (name.contains("BIRCH")) return 0xC8B98A;
        if (name.contains("JUNGLE")) return 0x9A6E4A;
        if (name.contains("ACACIA")) return 0xA5623A;
        if (name.contains("DARK_OAK")) return 0x4A3520;
        if (name.contains("MANGROVE")) return 0x7A3A38;
        if (name.contains("CHERRY")) return 0xD8A0A8;
        if (name.contains("BAMBOO")) return 0xC2B14A;
        if (name.contains("CRIMSON")) return 0x8A3A4A;
        if (name.contains("WARPED")) return 0x2A7A72;
        return fallback;
    }

    /** {@code _STAIRS} and {@code _SLAB} exist in both wood and stone; the prefix decides. */
    private static int woodOrStone(String name) {
        if (name.contains("STONE") || name.contains("BRICK") || name.contains("COBBLE")
                || name.contains("DEEPSLATE") || name.contains("BLACKSTONE")
                || name.contains("QUARTZ") || name.contains("SANDSTONE")
                || name.contains("PURPUR") || name.contains("PRISMARINE")
                || name.contains("COPPER") || name.contains("IRON")) {
            return 0x7E7E7E;
        }
        return woodTone(name, 0x9A7247);
    }

    /** Ore is mostly the stone around it, tinted by what is in it. */
    private static int oreTone(String name) {
        if (name.contains("COAL")) return 0x4A4A4A;
        if (name.contains("IRON")) return 0xA98A6E;
        if (name.contains("COPPER")) return 0xA57350;
        if (name.contains("GOLD")) return 0xB09A4A;
        if (name.contains("REDSTONE")) return 0x8A4A4A;
        if (name.contains("LAPIS")) return 0x4A6A9A;
        if (name.contains("DIAMOND")) return 0x6A9A9A;
        if (name.contains("EMERALD")) return 0x4A9A5A;
        if (name.contains("QUARTZ")) return 0x9A7A6A;
        return 0x7E7E7E;
    }

    /**
     * Applies relief shading from the height difference with the block to the north.
     *
     * <p>Without it the map is a flat colour field where a cliff and a plain look the
     * same. Three steps rather than a smooth gradient, matching how Minecraft's own maps
     * do it — it reads more clearly at small sizes than a continuous ramp.
     */
    public static int shade(int rgb, int height, int northHeight) {
        double factor;
        if (height > northHeight) factor = 1.14;
        else if (height < northHeight) factor = 0.80;
        else factor = 1.0;

        int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Darkens a colour toward deep water blue.
     *
     * @param depth blocks of water above the floor; deeper reads darker, so coastlines and
     *              drop-offs are visible instead of one flat sheet of blue
     */
    public static int water(int floorRgb, int depth) {
        // The floor matters -- a sandy shallow should look different from a stony one --
        // but water has to read as water first. A single block over pale sand mixed at a
        // quarter came out sand-coloured, which is worse than losing some floor detail.
        double blend = Math.min(0.90, 0.45 + depth * 0.05);
        int r = (int) (((floorRgb >> 16) & 0xFF) * (1 - blend) + 0x2C * blend);
        int g = (int) (((floorRgb >> 8) & 0xFF) * (1 - blend) + 0x4E * blend);
        int b = (int) ((floorRgb & 0xFF) * (1 - blend) + 0xA8 * blend);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
