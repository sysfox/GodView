package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.map.BlockColors;
import dev.godview.map.MapService;
import dev.godview.map.TileCache;
import dev.godview.map.TileRenderer;
import dev.godview.map.TileStore;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Block colours, the tile cache, and real PNGs from synthetic terrain.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class MapSuiteTest {

    @Test
    void verifies() throws Exception {
        colors();
        cache();
        store();
        renderer();
    }

    // ------------------------------------------------------------------ colors

    static void colors() {
        System.out.println("========== block colours ==========");
        check("grass is green", hex(BlockColors.of(Material.GRASS_BLOCK)), "7cb342");
        check("water is blue", hex(BlockColors.of(Material.WATER)), "3b62c4");
        check("lava is loud", hex(BlockColors.of(Material.LAVA)), "e8622b");
        check("stone is grey", hex(BlockColors.of(Material.STONE)), "7e7e7e");
        check("null is not a crash", BlockColors.of(null), BlockColors.UNKNOWN);

        // The point of the name fallback: blocks GodView has never heard of still render.
        check("unknown leaves fall back to foliage",
                hex(BlockColors.of(Material.MANGROVE_LEAVES)), "4f7f2e");
        check("unknown planks fall back to wood",
                BlockColors.of(Material.CHERRY_PLANKS) != BlockColors.UNKNOWN, true);
        check("cherry wood is distinct from oak",
                BlockColors.of(Material.CHERRY_PLANKS) != BlockColors.of(Material.SPRUCE_PLANKS),
                true);
        check("deepslate is not generic stone",
                BlockColors.of(Material.DEEPSLATE) != BlockColors.of(Material.STONE), true);
        check("deepslate variants follow deepslate",
                hex(BlockColors.of(Material.DEEPSLATE_BRICKS)), "4c4c50");
        check("ore is tinted stone",
                BlockColors.of(Material.DIAMOND_ORE) != BlockColors.of(Material.GOLD_ORE), true);
        check("stone stairs are stone, not wood",
                hex(BlockColors.of(Material.COBBLESTONE_STAIRS)), "7e7e7e");
        check("oak stairs are wood, not stone",
                BlockColors.of(Material.OAK_STAIRS) != BlockColors.of(Material.COBBLESTONE_STAIRS),
                true);
        check("crops read as crops",
                BlockColors.of(Material.WHEAT) != BlockColors.UNKNOWN, true);
        check("nether blocks are red",
                hex(BlockColors.of(Material.NETHER_WART_BLOCK)), "6e3634");

        // Nothing in the enum may throw. Material.isBlock() would be the natural filter
        // but it consults the server registry, so it is unusable without one -- the sweep
        // covers items too, which legitimately have no map colour.
        int unknowns = 0;
        boolean threw = false;
        for (Material material : Material.values()) {
            try {
                if (BlockColors.of(material) == BlockColors.UNKNOWN) unknowns++;
            } catch (Throwable t) {
                threw = true;
                System.out.println("     (threw for " + material + ": " + t + ")");
                break;
            }
        }
        check("no lookup throws, over the entire enum", threw, false);
        System.out.println("     (" + unknowns + " of " + Material.values().length
                + " enum entries fall through; most are items, not blocks)");

        // Coverage is asserted over blocks that actually appear on a surface, one per
        // family, which is the set the renderer meets in practice.
        String[] surfaces = {
                "GRASS_BLOCK", "DIRT", "PODZOL", "SAND", "RED_SAND", "GRAVEL", "CLAY",
                "STONE", "ANDESITE", "DIORITE", "GRANITE", "DEEPSLATE", "TUFF", "CALCITE",
                "WATER", "LAVA", "ICE", "SNOW_BLOCK", "OBSIDIAN", "BEDROCK",
                "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG",
                "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "BAMBOO_BLOCK",
                "OAK_PLANKS", "OAK_LEAVES", "AZALEA_LEAVES", "MOSS_BLOCK",
                "COBBLESTONE", "BRICKS", "SANDSTONE", "PRISMARINE", "PURPUR_BLOCK",
                "WHITE_WOOL", "WHITE_CONCRETE", "WHITE_TERRACOTTA", "GLASS",
                "NETHERRACK", "SOUL_SAND", "END_STONE", "WARPED_NYLIUM", "CRIMSON_NYLIUM",
                "IRON_ORE", "DIAMOND_ORE", "COPPER_BLOCK", "IRON_BLOCK", "GOLD_BLOCK",
                "FARMLAND", "WHEAT", "PUMPKIN", "SHORT_GRASS", "FERN", "DANDELION",
        };
        int missing = 0;
        StringBuilder missingNames = new StringBuilder();
        for (String name : surfaces) {
            Material material = Material.getMaterial(name);
            if (material == null) continue;    // not in this version, which is fine
            if (BlockColors.of(material) == BlockColors.UNKNOWN) {
                missing++;
                missingNames.append(' ').append(name);
            }
        }
        if (missing > 0) System.out.println("     (unresolved:" + missingNames + ")");
        check("every common surface block has a colour", missing, 0);

        System.out.println("\n========== shading ==========");
        int base = 0x808080;
        check("higher than the north is lighter",
                BlockColors.shade(base, 70, 64) > base, true);
        check("lower than the north is darker",
                BlockColors.shade(base, 60, 64) < base, true);
        check("level ground is unshaded", BlockColors.shade(base, 64, 64), base);
        check("shading cannot overflow a channel",
                hex(BlockColors.shade(0xFFFFFF, 100, 0)), "ffffff");

        int shallow = BlockColors.water(0xE6DBAC, 1);
        int deep = BlockColors.water(0xE6DBAC, 10);
        check("deep water is darker than shallow", blue(deep) < blue(shallow) || deep < shallow, true);
        check("water tints toward blue", blue(shallow) > red(shallow), true);
    }

    // ------------------------------------------------------------------ cache

    static void cache() {
        System.out.println("\n========== tile cache ==========");
        TileCache cache = new TileCache();
        cache.setBudget(1024 * 1024);   // clamped to a 1 MB floor

        MapService.TileKey a = new MapService.TileKey("world", 0, 0, 1);
        MapService.TileKey b = new MapService.TileKey("world", 1, 0, 1);
        MapService.TileKey c = new MapService.TileKey("nether", 0, 0, 1);

        check("empty cache misses", cache.get(a), null);
        cache.put(a, new byte[100], 60_000);
        check("stored tile is returned", cache.get(a).length, 100);
        check("byte total tracked", cache.bytes(), 100L);
        check("tile count tracked", cache.size(), 1);

        cache.put(a, new byte[250], 60_000);
        check("replacing does not double-count", cache.bytes(), 250L);
        check("still one tile", cache.size(), 1);

        cache.put(b, new byte[250], 60_000);
        cache.put(c, new byte[250], 60_000);
        check("three tiles", cache.size(), 3);

        cache.removeWorld("world");
        check("removing a world drops its tiles", cache.size(), 1);
        check("bytes follow the removal", cache.bytes(), 250L);
        check("the other world survives", cache.get(c).length, 250);

        // Byte budget, not tile count: eviction happens by size.
        TileCache small = new TileCache();
        small.setBudget(1);          // clamped up to the 1 MB floor
        for (int i = 0; i < 20; i++) {
            small.put(new MapService.TileKey("w", i, 0, 1), new byte[100_000], 60_000);
        }
        check("cache stays inside its budget", small.bytes() <= 1024 * 1024, true);
        check("eviction happened", small.evictions() > 0, true);
        check("newest tile survived eviction",
                small.get(new MapService.TileKey("w", 19, 0, 1)) != null, true);
        check("oldest tile was evicted",
                small.get(new MapService.TileKey("w", 0, 0, 1)), null);

        // A tile bigger than the whole cache must be refused, not evict everything.
        TileCache tiny = new TileCache();
        tiny.setBudget(1024 * 1024);
        tiny.put(new MapService.TileKey("w", 0, 0, 1), new byte[500], 60_000);
        tiny.put(new MapService.TileKey("w", 1, 0, 1), new byte[2 * 1024 * 1024], 60_000);
        check("oversized tile refused", tiny.get(new MapService.TileKey("w", 1, 0, 1)), null);
        check("it did not evict the good tile",
                tiny.get(new MapService.TileKey("w", 0, 0, 1)).length, 500);

        // Expiry
        TileCache expiring = new TileCache();
        expiring.put(a, new byte[10], 1);
        try { Thread.sleep(30); } catch (InterruptedException ignored) { }
        check("expired tile is not served", expiring.get(a), null);
        check("expiry is counted", expiring.expirations(), 1L);
        check("expiry reclaims bytes", expiring.bytes(), 0L);

        // Completeness: a tile drawn while chunks were unloaded is worth serving and worth
        // asking for again, and the panel can only tell those apart if the cache remembers.
        TileCache partial = new TileCache();
        partial.put(a, new byte[10], 60_000, false);
        partial.put(b, new byte[10], 60_000);
        check("incomplete tile is still served", partial.get(a).length, 10);
        check("incomplete tile is flagged", partial.isComplete(a), false);
        check("complete tile is flagged", partial.isComplete(b), true);
        check("an absent tile is not called incomplete", partial.isComplete(c), true);
    }

    // ------------------------------------------------------------------- store

    /**
     * The disk store is what makes coverage accumulate, so what matters here is that a
     * tile written once comes back, that expiry is honoured, and that a world name cannot
     * escape the store directory.
     */
    static void store() throws Exception {
        System.out.println("\n========== tile store ==========");
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("godview-tiles");
        try {
            TileStore store = new TileStore(root, 7, java.util.logging.Logger.getAnonymousLogger());
            MapService.TileKey key = new MapService.TileKey("world", -3, 12, 2);

            check("nothing stored yet", store.read(key), null);
            store.write(key, new byte[]{1, 2, 3, 4});
            byte[] back = store.read(key);
            check("a stored tile comes back", back == null ? -1 : back.length, 4);
            check("writes are counted", store.writes(), 1L);

            // Different scales of the same ground are different files.
            store.write(new MapService.TileKey("world", -3, 12, 1), new byte[]{9});
            check("scales do not collide", store.read(key).length, 4);

            store.remove(key);
            check("a removed tile is gone", store.read(key), null);

            // A world name arrives from a URL path. It must not be able to walk out of the
            // store directory, and two names that sanitise alike must not share a folder.
            MapService.TileKey hostile = new MapService.TileKey("../../etc", 0, 0, 1);
            store.write(hostile, new byte[]{7});
            java.nio.file.Path base = root.toAbsolutePath().normalize();
            try (var walk = java.nio.file.Files.walk(root)) {
                check("a traversing world name stays inside the store",
                        walk.allMatch(p -> p.toAbsolutePath().normalize().startsWith(base)), true);
            }
            MapService.TileKey lookalike = new MapService.TileKey("......etc", 0, 0, 1);
            check("names that sanitise alike do not collide", store.read(lookalike), null);

            store.removeWorld("world");
            check("removing a world drops its stored tiles",
                    store.read(new MapService.TileKey("world", -3, 12, 1)), null);

            // Expiry: a zero-day store keeps nothing, because everything is already older.
            TileStore stale = new TileStore(root, 1, java.util.logging.Logger.getAnonymousLogger());
            MapService.TileKey old = new MapService.TileKey("aged", 0, 0, 1);
            stale.write(old, new byte[]{1});
            java.nio.file.Path file;
            try (var walk = java.nio.file.Files.walk(root)) {
                file = walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.toString().contains("aged"))
                        .findFirst().orElseThrow();
            }
            java.nio.file.Files.setLastModifiedTime(file,
                    java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis() - 3L * 86_400_000L));
            check("a tile past its lifetime is not served", stale.read(old), null);
            check("and it is deleted", java.nio.file.Files.exists(file), false);
        } finally {
            try (var walk = java.nio.file.Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // A temp directory that outlives the test is not a failure.
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------- renderer

    /** A ChunkSnapshot standing in for real terrain. */
    static ChunkSnapshot chunk(int surfaceY, Material surface, Material below) {
        return (ChunkSnapshot) Proxy.newProxyInstance(
                MapSuiteTest.class.getClassLoader(),
                new Class<?>[]{ChunkSnapshot.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHighestBlockYAt" -> surfaceY;
                    case "getBlockType" -> {
                        int y = (int) args[1];
                        if (y > surfaceY) yield Material.AIR;
                        yield y == surfaceY ? surface : below;
                    }
                    case "getX", "getZ" -> 0;
                    case "getWorldName" -> "world";
                    case "toString" -> "FakeChunk";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method.getReturnType());
                });
    }

    static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    static void renderer() throws Exception {
        System.out.println("\n========== tile rendering ==========");
        int perTile = 4;                       // 4x4 chunks = 64x64 blocks

        Map<Integer, ChunkSnapshot> full = new HashMap<>();
        for (int x = 0; x < perTile; x++) {
            for (int z = 0; z < perTile; z++) {
                full.put(x * perTile + z, chunk(64, Material.GRASS_BLOCK, Material.STONE));
            }
        }

        byte[] png = TileRenderer.render(full, perTile, 1, -64);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        check("renders a decodable png", image != null, true);
        check("one pixel per block", image.getWidth(), 64);
        check("square tile", image.getHeight(), 64);
        check("png is not empty", png.length > 50, true);
        check("grass renders as grass",
                hex(image.getRGB(10, 10) & 0xFFFFFF), hex(BlockColors.of(Material.GRASS_BLOCK)));

        // Downsampling
        byte[] half = TileRenderer.render(full, perTile, 2, -64);
        BufferedImage halfImage = ImageIO.read(new ByteArrayInputStream(half));
        check("scale 2 halves the edge", halfImage.getWidth(), 32);
        check("scale 2 is smaller on the wire", half.length < png.length, true);
        byte[] quarter = TileRenderer.render(full, perTile, 4, -64);
        check("scale 4 quarters the edge",
                ImageIO.read(new ByteArrayInputStream(quarter)).getWidth(), 16);

        // Missing chunks render as void, not as a failure.
        Map<Integer, ChunkSnapshot> partial = new HashMap<>(full);
        partial.remove(0);
        BufferedImage partialImage = ImageIO.read(
                new ByteArrayInputStream(TileRenderer.render(partial, perTile, 1, -64)));
        check("missing chunk renders as void",
                hex(partialImage.getRGB(5, 5) & 0xFFFFFF), hex(BlockColors.VOID));
        check("present chunks still render",
                hex(partialImage.getRGB(40, 40) & 0xFFFFFF), hex(BlockColors.of(Material.GRASS_BLOCK)));

        BufferedImage emptyImage = ImageIO.read(
                new ByteArrayInputStream(TileRenderer.render(new HashMap<>(), perTile, 1, -64)));
        check("an entirely missing tile still renders", emptyImage.getWidth(), 64);
        check("and is all void", hex(emptyImage.getRGB(32, 32) & 0xFFFFFF), hex(BlockColors.VOID));

        // Relief shading between chunks of different heights.
        Map<Integer, ChunkSnapshot> stepped = new HashMap<>();
        for (int x = 0; x < perTile; x++) {
            for (int z = 0; z < perTile; z++) {
                int height = z < 2 ? 64 : 90;   // a cliff running east-west
                stepped.put(x * perTile + z, chunk(height, Material.STONE, Material.STONE));
            }
        }
        BufferedImage relief = ImageIO.read(
                new ByteArrayInputStream(TileRenderer.render(stepped, perTile, 1, -64)));
        int flat = relief.getRGB(10, 10) & 0xFFFFFF;
        int cliff = relief.getRGB(10, 32) & 0xFFFFFF;
        check("the cliff edge is shaded differently from flat ground", flat != cliff, true);
        check("the raised side is lighter", cliff > flat, true);

        // Water is drawn from the floor beneath it.
        Map<Integer, ChunkSnapshot> ocean = new HashMap<>();
        for (int x = 0; x < perTile; x++) {
            for (int z = 0; z < perTile; z++) {
                ocean.put(x * perTile + z, chunk(62, Material.WATER, Material.SAND));
            }
        }
        BufferedImage sea = ImageIO.read(
                new ByteArrayInputStream(TileRenderer.render(ocean, perTile, 1, -64)));
        int seaPixel = sea.getRGB(20, 20) & 0xFFFFFF;
        check("water is not drawn as the sand under it",
                seaPixel != BlockColors.of(Material.SAND), true);
        check("water reads blue", blue(seaPixel) > red(seaPixel), true);
        check("water is not void", seaPixel != BlockColors.VOID, true);
    }

    static String hex(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }

    static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
