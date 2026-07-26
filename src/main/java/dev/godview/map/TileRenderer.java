package dev.godview.map;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Turns chunk snapshots into a PNG.
 *
 * <p>Runs entirely off the main thread. A {@link ChunkSnapshot} is a detached copy of a
 * chunk's block data, so once the main thread has handed one over, everything here — the
 * column walk, the shading, the PNG encode — is pure computation on data nobody else is
 * touching. That separation is the whole reason the map can exist on a server that cannot
 * spare tick time.
 *
 * <p>Rendering is top-down: for each block column, walk down from the highest block until
 * something opaque is found, colour that, then shade it against its northern neighbour so
 * terrain has relief. Water is handled separately — the floor beneath it is coloured and
 * then tinted by depth, which is what makes coastlines and drop-offs legible instead of
 * one flat blue sheet.
 */
public final class TileRenderer {

    /** Blocks per chunk edge. */
    static final int CHUNK = 16;
    /** Deepest water we bother measuring; past this it is all the same dark blue. */
    private static final int MAX_WATER_DEPTH = 12;

    private TileRenderer() {
    }

    /**
     * Renders one tile.
     *
     * @param snapshots     chunk snapshots keyed by {@code (localX, localZ)} within the
     *                      tile, packed as {@code localX * chunksPerTile + localZ}. Missing
     *                      entries are drawn as void, so a tile renders even when only part
     *                      of it is loaded.
     * @param chunksPerTile tile width in chunks
     * @param scale         1 for one pixel per block; 2 or 4 to downsample for zoomed-out
     *                      views, which cuts the payload by 4x or 16x
     * @param minY          the world's floor. Passed in because a snapshot does not carry
     *                      it, and it moved from 0 to -64 in 1.18 — assuming either would
     *                      make custom-height worlds render wrong
     * @return PNG bytes
     */
    public static byte[] render(Map<Integer, ChunkSnapshot> snapshots, int chunksPerTile,
                                int scale, int minY) throws IOException {
        int blocks = chunksPerTile * CHUNK;
        int step = Math.max(1, scale);
        int size = Math.max(1, blocks / step);

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] raster = new int[size * size];

        // Heights are kept for the whole tile first, so shading can compare a pixel with
        // its northern neighbour even when that neighbour is in a different chunk.
        int[] heights = new int[size * size];
        int[] colors = new int[size * size];

        for (int px = 0; px < size; px++) {
            for (int pz = 0; pz < size; pz++) {
                int blockX = px * step;
                int blockZ = pz * step;

                ChunkSnapshot chunk = snapshots.get(
                        (blockX / CHUNK) * chunksPerTile + (blockZ / CHUNK));
                int index = pz * size + px;

                if (chunk == null) {
                    colors[index] = BlockColors.VOID;
                    heights[index] = Integer.MIN_VALUE;
                    continue;
                }

                Column column = column(chunk, blockX % CHUNK, blockZ % CHUNK, minY);
                colors[index] = column.rgb();
                heights[index] = column.height();
            }
        }

        for (int px = 0; px < size; px++) {
            for (int pz = 0; pz < size; pz++) {
                int index = pz * size + px;
                if (heights[index] == Integer.MIN_VALUE) {
                    raster[index] = BlockColors.VOID;
                    continue;
                }
                // The pixel to the north, or itself at the tile's northern edge. Comparing
                // against itself yields no shading, which is the right answer: we cannot
                // see the neighbouring tile's heights from here.
                int north = pz > 0 ? heights[(pz - 1) * size + px] : heights[index];
                if (north == Integer.MIN_VALUE) north = heights[index];
                raster[index] = BlockColors.shade(colors[index], heights[index], north);
            }
        }

        image.setRGB(0, 0, size, size, raster, 0, size);

        ByteArrayOutputStream out = new ByteArrayOutputStream(size * size / 4);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** The surface of one block column: what colour it is, and how high. */
    private record Column(int rgb, int height) {
    }

    /**
     * Walks a column from the top down.
     *
     * <p>Starts at the snapshot's recorded highest block rather than the world ceiling, so
     * a column over open ground costs a couple of reads instead of three hundred.
     */
    private static Column column(ChunkSnapshot chunk, int x, int z, int min) {
        int top = safeHighest(chunk, x, z);
        if (top < min) return new Column(BlockColors.VOID, Integer.MIN_VALUE);

        int waterDepth = 0;
        for (int y = top; y >= min; y--) {
            Material material = safeType(chunk, x, y, z);
            if (material == null || material == Material.AIR
                    || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                continue;
            }

            if (isWater(material)) {
                if (waterDepth < MAX_WATER_DEPTH) waterDepth++;
                continue;   // keep descending to find the floor under the water
            }

            int rgb = BlockColors.of(material);
            if (waterDepth > 0) rgb = BlockColors.water(rgb, waterDepth);
            return new Column(rgb, y);
        }

        // Nothing but air and water all the way down.
        return waterDepth > 0
                ? new Column(BlockColors.water(BlockColors.UNKNOWN, waterDepth), min)
                : new Column(BlockColors.VOID, Integer.MIN_VALUE);
    }

    private static boolean isWater(Material material) {
        // Kelp, seagrass and the like sit in water and should not stop the descent, or a
        // kelp forest would render as an opaque green slab instead of ocean.
        String name = material.name();
        return material == Material.WATER
                || name.equals("BUBBLE_COLUMN")
                || name.contains("SEAGRASS")
                || name.equals("KELP")
                || name.equals("KELP_PLANT");
    }

    private static int safeHighest(ChunkSnapshot chunk, int x, int z) {
        try {
            return chunk.getHighestBlockYAt(x, z);
        } catch (Throwable ignored) {
            // A snapshot taken without height data; fall back to a sane ceiling.
            return 320;
        }
    }

    private static Material safeType(ChunkSnapshot chunk, int x, int y, int z) {
        try {
            return chunk.getBlockType(x, y, z);
        } catch (Throwable ignored) {
            // Out of the snapshot's vertical range on an unusual world height.
            return null;
        }
    }
}
