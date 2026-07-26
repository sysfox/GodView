package dev.godview.metrics;

import dev.godview.compat.Compat;
import dev.godview.util.JsonWriter;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A point-in-time picture of one world.
 *
 * <p>Totals alone rarely answer the question an operator is actually asking. "14 000
 * entities" prompts "which ones, and where?", so a single pass over the entity list also
 * produces the type breakdown and the busiest chunks — the two things that turn a number
 * into an action.
 *
 * <p>Collection must run on the main thread and is not cheap: it is O(entities), plus
 * O(loaded chunks) when block entities are included. That is why the sampler runs it on
 * its own slow interval, skips it entirely while nobody is watching the panel, and drops
 * the block-entity walk in the {@link PerformanceProfile#LOW} profile.
 *
 * @param tileEntities {@code -1} when the block-entity walk was skipped, so the panel can
 *                     say "not collected" rather than showing a misleading zero
 */
public record WorldStats(String name, String environment, int players, int chunks, int entities,
                         int tileEntities, long time, boolean storm, boolean thundering,
                         int viewDistance, int simulationDistance,
                         List<TypeCount> topTypes, List<Hotspot> hotspots) {

    /** How many entity types and hotspot chunks to keep. Beyond this it is noise. */
    public static final int TOP_N = 8;

    /** An entity type and how many of it are loaded. */
    public record TypeCount(String type, int count) {
    }

    /** A chunk carrying an unusual share of the world's entities. */
    public record Hotspot(int x, int z, int entities) {
    }

    /**
     * Walks a world and measures it.
     *
     * @param withTileEntities whether to also walk loaded chunks counting block entities
     */
    public static WorldStats collect(World world, boolean withTileEntities) {
        Map<String, int[]> byType = new HashMap<>();
        Map<Long, int[]> byChunk = new HashMap<>();

        int total = 0;
        for (Entity entity : world.getEntities()) {
            total++;
            byType.computeIfAbsent(entity.getType().name(), k -> new int[1])[0]++;
            byChunk.computeIfAbsent(Compat.entityChunkKey(entity), k -> new int[1])[0]++;
        }

        int tileEntities = -1;
        if (withTileEntities) {
            tileEntities = 0;
            for (Chunk chunk : world.getLoadedChunks()) {
                tileEntities += Compat.tileEntityCount(chunk);
            }
        }

        return new WorldStats(
                world.getName(),
                world.getEnvironment().name(),
                world.getPlayers().size(),
                Compat.chunkCount(world),
                total,
                tileEntities,
                world.getTime(),
                world.hasStorm(),
                world.isThundering(),
                Compat.viewDistance(world),
                Compat.simulationDistance(world),
                topTypes(byType),
                hotspots(byChunk));
    }

    private static List<TypeCount> topTypes(Map<String, int[]> byType) {
        List<TypeCount> out = new ArrayList<>(byType.size());
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            out.add(new TypeCount(e.getKey(), e.getValue()[0]));
        }
        out.sort(Comparator.comparingInt(TypeCount::count).reversed());
        return List.copyOf(out.subList(0, Math.min(TOP_N, out.size())));
    }

    private static List<Hotspot> hotspots(Map<Long, int[]> byChunk) {
        List<Hotspot> out = new ArrayList<>(byChunk.size());
        for (Map.Entry<Long, int[]> e : byChunk.entrySet()) {
            long key = e.getKey();
            out.add(new Hotspot(Compat.chunkKeyX(key), Compat.chunkKeyZ(key), e.getValue()[0]));
        }
        out.sort(Comparator.comparingInt(Hotspot::entities).reversed());
        return List.copyOf(out.subList(0, Math.min(TOP_N, out.size())));
    }

    public void writeJson(JsonWriter w) {
        w.obj()
                .field("name", name)
                .field("environment", environment)
                .field("players", players)
                .field("chunks", chunks)
                .field("entities", entities)
                .field("tileEntities", tileEntities)
                .field("time", time)
                .field("storm", storm)
                .field("thundering", thundering)
                .field("viewDistance", viewDistance)
                .field("simulationDistance", simulationDistance)
                .name("topTypes").arr();
        for (TypeCount t : topTypes) {
            w.obj().field("type", t.type()).field("count", t.count()).end();
        }
        w.end().name("hotspots").arr();
        for (Hotspot h : hotspots) {
            w.obj().field("x", h.x()).field("z", h.z()).field("entities", h.entities()).end();
        }
        w.end().end();
    }
}
