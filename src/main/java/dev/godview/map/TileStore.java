package dev.godview.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rendered tiles, kept on disk under {@code plugins/GodView/map/}.
 *
 * <p>This is what makes the map accumulate. {@link TileCache} holds a few megabytes for a
 * few minutes, which is right for "the operator is panning around right now" and wrong for
 * everything else: without a store, every tile older than {@code map.tile-ttl-seconds} has
 * to be re-captured from chunk snapshots at {@code map.chunks-per-tick}, and the map can
 * never show more world than that budget produces inside one TTL. Which is a small patch
 * around wherever players happen to be standing.
 *
 * <p>With a store, a tile is captured once and stays. Panning over ground somebody already
 * looked at costs a file read, not sixty-four chunk snapshots, and the picture grows toward
 * "everywhere anyone has been" instead of resetting every five minutes.
 *
 * <p>Only complete tiles are written. A tile rendered while half its chunks were unloaded
 * is a picture with holes in it, and persisting that would make the holes permanent — those
 * stay in memory on a short lifetime so they redraw as the chunks arrive.
 *
 * <p>Every method here touches the filesystem, so none of them may be called from the main
 * thread. Writes happen on the render pool; reads happen on HTTP workers.
 */
public final class TileStore {

    /** Written first, then moved into place, so a reader never sees half a PNG. */
    private static final String TEMP_SUFFIX = ".part";

    private final Path root;
    private final Logger log;

    private volatile long maxAgeMillis;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private volatile boolean surveyed;

    public TileStore(Path root, int maxAgeDays, Logger log) {
        this.root = root;
        this.log = log;
        this.maxAgeMillis = Math.max(1L, maxAgeDays) * 86_400_000L;
    }

    public void setMaxAgeDays(int days) {
        this.maxAgeMillis = Math.max(1L, days) * 86_400_000L;
    }

    public Path root() {
        return root;
    }

    // -------------------------------------------------------------------- read

    /** @return the stored PNG, or null when absent, unreadable or past its lifetime */
    public byte[] read(MapService.TileKey key) {
        Path file = pathFor(key);
        if (file == null) return null;
        try {
            if (!Files.isRegularFile(file)) {
                misses.incrementAndGet();
                return null;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (age > maxAgeMillis) {
                // The world moved on. Drop it now rather than serving a picture of a base
                // that was demolished last week.
                deleteQuietly(file);
                expired.incrementAndGet();
                return null;
            }
            byte[] png = Files.readAllBytes(file);
            hits.incrementAndGet();
            return png;
        } catch (IOException e) {
            log.log(Level.FINE, "map: could not read a stored tile", e);
            misses.incrementAndGet();
            return null;
        }
    }

    // ------------------------------------------------------------------- write

    public void write(MapService.TileKey key, byte[] png) {
        Path file = pathFor(key);
        if (file == null) return;
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            boolean replacing = Files.exists(file);
            Files.write(temp, png);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some Windows filesystems refuse an atomic replace. A plain move is still
                // better than writing the destination in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            writes.incrementAndGet();
            // Overwriting a tile is not a new file. The byte figure drifts a little until
            // the next sweep corrects it, which is the right trade against stat-ing the
            // old size on every write.
            if (!replacing) files.incrementAndGet();
            bytes.addAndGet(png.length);
        } catch (IOException e) {
            // A full or read-only disk must not stop the map working from memory.
            log.log(Level.FINE, "map: could not store a tile", e);
            deleteQuietly(temp);
        }
    }

    // ------------------------------------------------------------------ delete

    /** Drops one stored tile, so the next request redraws it from the world. */
    public void remove(MapService.TileKey key) {
        Path file = pathFor(key);
        if (file == null) return;
        try {
            if (Files.deleteIfExists(file)) files.decrementAndGet();
        } catch (IOException ignored) {
            // The sweep will get it, or the next read will find it stale.
        }
    }

    /** Drops every stored tile for one world. */
    public void removeWorld(String world) {
        Path dir = worldDir(world);
        if (dir == null) return;
        deleteTree(dir);
        surveyed = false;
    }

    public void removeAll() {
        deleteTree(root);
        bytes.set(0);
        files.set(0);
        surveyed = false;
    }

    /**
     * Deletes tiles past their lifetime and measures what is left.
     *
     * <p>Runs once at startup on a background thread. Expiry also happens on read, so this
     * is about reclaiming space nobody is going to ask for again, and about being able to
     * report a disk figure in the diagnostics view.
     */
    public void sweep() {
        if (!Files.isDirectory(root)) {
            surveyed = true;
            return;
        }
        long now = System.currentTimeMillis();
        long keptBytes = 0;
        long keptFiles = 0;
        long removed = 0;

        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    String name = path.getFileName().toString();
                    long age = now - Files.getLastModifiedTime(path).toMillis();
                    if (name.endsWith(TEMP_SUFFIX) || age > maxAgeMillis) {
                        Files.deleteIfExists(path);
                        removed++;
                        continue;
                    }
                    keptBytes += Files.size(path);
                    keptFiles++;
                } catch (IOException ignored) {
                    // One unreadable file is not a reason to abandon the sweep.
                }
            }
        } catch (IOException e) {
            log.log(Level.FINE, "map: could not sweep the tile store", e);
        }

        bytes.set(keptBytes);
        files.set(keptFiles);
        expired.addAndGet(removed);
        surveyed = true;
    }

    // ------------------------------------------------------------------ counts

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long writes() {
        return writes.get();
    }

    public long bytes() {
        return bytes.get();
    }

    public long files() {
        return files.get();
    }

    /** False until {@link #sweep()} has finished, so the panel can say "counting". */
    public boolean surveyed() {
        return surveyed;
    }

    // ----------------------------------------------------------------- helpers

    private Path pathFor(MapService.TileKey key) {
        Path dir = worldDir(key.world());
        if (dir == null) return null;
        return dir.resolve("s" + key.scale())
                .resolve(key.tileX() + "_" + key.tileZ() + ".png");
    }

    /**
     * One directory per world, named safely.
     *
     * <p>A world name is operator-supplied and reaches us through a URL path, so it is not
     * allowed anywhere near a filesystem path unsanitised. Unsafe characters become
     * underscores and the original name's hash is appended, which keeps {@code my world}
     * and {@code my/world} in different directories rather than colliding into one.
     */
    private Path worldDir(String world) {
        if (world == null || world.isEmpty()) return null;
        StringBuilder safe = new StringBuilder(world.length() + 10);
        for (int i = 0; i < world.length() && i < 48; i++) {
            char c = world.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            safe.append(ok ? c : '_');
        }
        safe.append('-').append(Integer.toHexString(world.hashCode()).toLowerCase(Locale.ROOT));
        return root.resolve(safe.toString());
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing actionable; the sweep will get it.
        }
    }

    private void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.log(Level.FINE, "map: could not clear stored tiles", e);
        }
    }
}
