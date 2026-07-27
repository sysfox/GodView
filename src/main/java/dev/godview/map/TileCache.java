package dev.godview.map;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rendered tiles, held to a byte budget rather than a tile count.
 *
 * <p>Counting tiles would be the wrong unit. A PNG of open ocean compresses to a couple of
 * kilobytes and one of a jungle village to fifty, so "keep 200 tiles" means anywhere
 * between half a megabyte and ten — and {@code map.tile-cache-mb} would be a number that
 * did not mean anything.
 *
 * <p>Eviction is least-recently-used, which suits how the map is actually browsed: an
 * operator pans around one area, and the tiles they are looking at should stay resident
 * while the ones they scrolled past three minutes ago go.
 *
 * <p>Entries also expire, because the world changes. A tile older than
 * {@code map.tile-ttl-seconds} is dropped on read even if there is room for it — a map
 * showing a base that was demolished an hour ago is worse than a blank one.
 */
public final class TileCache {

    private final Map<MapService.TileKey, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long budgetBytes = 24L * 1024 * 1024;
    private long bytes;
    private long evictions;
    private long expirations;

    /**
     * @param complete false when the tile was drawn with some of its chunks unloaded, so
     *                 it has holes in it and the panel should ask again later
     */
    private record Entry(byte[] png, long expiresAt, boolean complete) {

        boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    /** Sets the byte ceiling, evicting immediately if the cache is now over it. */
    public synchronized void setBudget(long newBudgetBytes) {
        this.budgetBytes = Math.max(1024L * 1024, newBudgetBytes);
        evictToBudget();
    }

    /** @return the PNG, or null when absent or expired */
    public synchronized byte[] get(MapService.TileKey key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expired(System.currentTimeMillis())) {
            entries.remove(key);
            bytes -= entry.png().length;
            expirations++;
            return null;
        }
        return entry.png();
    }

    /**
     * Whether the resident tile was drawn from every one of its chunks.
     *
     * <p>Separate from {@link #get} so the common path stays a byte array. An absent tile
     * reports true: the caller has nothing to redraw, so "incomplete" would be a lie about
     * a picture that does not exist.
     */
    public synchronized boolean isComplete(MapService.TileKey key) {
        Entry entry = entries.get(key);
        return entry == null || entry.complete();
    }

    public synchronized void put(MapService.TileKey key, byte[] png, long ttlMillis) {
        put(key, png, ttlMillis, true);
    }

    public synchronized void put(MapService.TileKey key, byte[] png, long ttlMillis,
                                 boolean complete) {
        // A single tile larger than the whole budget would evict everything and then
        // itself; refusing it keeps the cache useful for the tiles that do fit.
        if (png.length > budgetBytes) return;

        Entry previous = entries.remove(key);
        if (previous != null) bytes -= previous.png().length;

        // A non-positive TTL means "no lifetime configured", not "expire immediately"; any
        // positive value is honoured exactly, so the parameter means what it says.
        long lifetime = ttlMillis > 0 ? ttlMillis : 60_000L;
        entries.put(key, new Entry(png, System.currentTimeMillis() + lifetime, complete));
        bytes += png.length;
        evictToBudget();
    }

    public synchronized void remove(MapService.TileKey key) {
        Entry removed = entries.remove(key);
        if (removed != null) bytes -= removed.png().length;
    }

    /** Drops every tile for one world, after a world save or a bulk change. */
    public synchronized void removeWorld(String world) {
        Iterator<Map.Entry<MapService.TileKey, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MapService.TileKey, Entry> entry = it.next();
            if (entry.getKey().world().equals(world)) {
                bytes -= entry.getValue().png().length;
                it.remove();
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        bytes = 0;
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long evictions() {
        return evictions;
    }

    public synchronized long expirations() {
        return expirations;
    }

    /**
     * Evicts from the least recently used end until the budget is met.
     *
     * <p>The backing map is in access order, so its iteration starts at the coldest entry.
     */
    private void evictToBudget() {
        Iterator<Map.Entry<MapService.TileKey, Entry>> it = entries.entrySet().iterator();
        while (bytes > budgetBytes && it.hasNext()) {
            Map.Entry<MapService.TileKey, Entry> oldest = it.next();
            bytes -= oldest.getValue().png().length;
            it.remove();
            evictions++;
        }
    }
}
