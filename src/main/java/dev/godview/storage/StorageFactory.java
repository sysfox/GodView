package dev.godview.storage;

import dev.godview.config.GodViewConfig;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Picks a {@link Storage} and guarantees one comes back.
 *
 * <p>{@code storage.type: sqlite} is a preference, not a requirement. The driver arrives
 * through Paper's library loader, so a server behind a firewall or with a cold, offline
 * first start may simply not have it. In that case GodView keeps history in memory and
 * says so once in the log — the alternative, refusing to enable because a chart cannot be
 * persisted, would be a poor trade for a monitoring plugin.
 */
public final class StorageFactory {

    private StorageFactory() {
    }

    /**
     * @param dataFolder the plugin's own folder; the database file is resolved inside it
     */
    public static Storage create(GodViewConfig.Storage config, Path dataFolder, Logger log) {
        if (!config.persistent()) {
            log.info("GodView history is in memory only (storage.type: memory).");
            return started(new MemoryStorage(config), log);
        }

        Path file = dataFolder.resolve(config.file());
        SqliteStorage sqlite = new SqliteStorage(file, config, log);
        try {
            sqlite.start();
            log.info("GodView history is persisted to " + file.getFileName());
            return sqlite;
        } catch (Throwable t) {
            sqlite.close();
            log.log(Level.WARNING, "GodView could not open " + file
                    + " — falling back to in-memory history, which is lost on restart. "
                    + "Check that sqlite-jdbc downloaded and that the folder is writable.", t);
            return started(new MemoryStorage(config), log);
        }
    }

    private static Storage started(Storage storage, Logger log) {
        try {
            storage.start();
        } catch (Exception e) {
            // MemoryStorage.start() does nothing, so reaching this means something is very
            // wrong; there is no third fallback to offer.
            log.log(Level.SEVERE, "GodView storage failed to start", e);
        }
        return storage;
    }
}
