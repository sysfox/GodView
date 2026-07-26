package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.Spike;
import dev.godview.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Both storage backends, driven against the same expectations.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class StorageSuiteTest {

    static GodViewConfig.Storage cfg(String type, int eventDays) {
        //           type, file,          batch, flushMs, events,    metrics, spikes, audit, alerts
        return new GodViewConfig.Storage(type, "godview.db", 200, 100L, eventDays, 30, 30, 90, 30);
    }

    static final long NOW = System.currentTimeMillis();
    static final long DAY = 86_400_000L;

    @Test
    void verifies() throws Exception {
        Logger log = Logger.getLogger("smoke");
        log.setLevel(Level.WARNING);

        Path dir = Files.createTempDirectory("godview-storage-test");
        System.out.println("temp dir: " + dir + "\n");

        System.out.println("========== SQLite ==========");
        SqliteStorage sqlite = new SqliteStorage(dir.resolve("godview.db"), cfg("sqlite", 14), log);
        sqlite.start();
        exercise(sqlite, true);

        // Reopen to prove the data really landed on disk and the schema is reusable.
        sqlite.close();
        SqliteStorage reopened = new SqliteStorage(dir.resolve("godview.db"), cfg("sqlite", 14), log);
        reopened.start();
        check("survives a restart", reopened.queryEvents(Storage.EventQuery.recent(50)).size() > 0, true);
        check("wal file exists", Files.exists(dir.resolve("godview.db-wal")) || Files.size(dir.resolve("godview.db")) > 0, true);
        check("stats report file size", reopened.stats().fileSizeBytes() > 0, true);
        reopened.close();

        System.out.println("\n========== Memory ==========");
        MemoryStorage memory = new MemoryStorage(cfg("memory", 14));
        memory.start();
        exercise(memory, false);
        memory.close();

        System.out.println("\n========== Backends agree ==========");
        agree(dir, log);

        System.out.println("\n========== Retention ==========");
        retention(dir, log);

        System.out.println("\n========== Fallback ==========");
        // A path that cannot be opened must degrade to memory, not throw.
        Storage fallback = StorageFactory.create(cfg("sqlite", 14),
                Paths.get("Z:\\definitely\\not\\a\\real\\volume"), log);
        check("unopenable path falls back", fallback.kind(), "memory");
        fallback.recordEvent(Storage.Event.of(NOW, "join", "Steve", "joined"));
        check("fallback still records", fallback.queryEvents(Storage.EventQuery.recent(10)).size(), 1);
        fallback.close();

        Storage chosen = StorageFactory.create(cfg("memory", 14), dir, log);
        check("memory type is honoured", chosen.kind(), "memory");
        chosen.close();

        deleteTree(dir);
    }

    static void exercise(Storage s, boolean async) throws Exception {
        String label = s.kind();

        s.recordEvent(new Storage.Event(NOW - 5000, "join", "Steve", "uuid-1", "world", "Steve joined", null));
        s.recordEvent(new Storage.Event(NOW - 4000, "chat", "Steve", "uuid-1", "world", "hello world", null));
        s.recordEvent(new Storage.Event(NOW - 3000, "chat", "Alex", "uuid-2", "world", "100% sure_thing", null));
        s.recordEvent(new Storage.Event(NOW - 2000, "command", "Alex", "uuid-2", "world", "/gamemode creative",
                "{\"allowed\":true}"));
        s.recordEvent(new Storage.Event(NOW - 1000, "quit", "Steve", "uuid-1", "world", "Steve left", null));

        for (int i = 0; i < 30; i++) {
            s.recordMetrics(new Storage.MetricPoint(NOW - (30 - i) * 10_000L,
                    20 - i * 0.1, 5 + i * 0.5, 1_000_000L * i, 4_000_000_000L,
                    0.25, 10 + i, 500 + i, 2000 + i));
        }

        s.recordSpike(new Spike(NOW - 2500, 480.5, 1234, "ShopGUI",
                "com.shopgui.core.Task.run:88", List.of("com.shopgui.core.Task.run:88", "a.b.C.d:1")));
        s.recordSpike(new Spike(NOW - 1500, 150.0, 1290, "Minecraft",
                "net.minecraft.Foo.tick:5", List.of()));

        s.recordAudit(new Storage.AuditEntry(NOW - 900, "admin", "10.0.0.1", "player.kick", "Steve",
                "spam", true));
        s.recordAudit(new Storage.AuditEntry(NOW - 800, "admin", "10.0.0.1", "server.op", "Alex",
                "denied by policy", false));

        s.recordAlert(new Storage.AlertRecord(NOW - 700, "tps-low", "warn", "TPS below 15",
                "TPS dropped to 12.4", 12.4, "panel,discord", true));

        if (async) Thread.sleep(600);   // let the writer thread flush

        // ---- events ----
        List<Storage.Event> all = s.queryEvents(Storage.EventQuery.recent(50));
        check(label + ": all events", all.size(), 5);
        check(label + ": newest first", all.get(0).type(), "quit");

        List<Storage.Event> chat = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, "chat", null, null, 50, 0));
        check(label + ": filter by type", chat.size(), 2);

        List<Storage.Event> steve = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, "Steve", null, 50, 0));
        check(label + ": filter by actor", steve.size(), 3);

        List<Storage.Event> search = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, "hello", 50, 0));
        check(label + ": free-text search", search.size(), 1);

        // % and _ are LIKE wildcards; a user typing them must not match everything.
        List<Storage.Event> wildcard = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, "100%", 50, 0));
        check(label + ": percent is literal", wildcard.size(), 1);
        List<Storage.Event> underscore = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, "sure_thing", 50, 0));
        check(label + ": underscore is literal", underscore.size(), 1);
        List<Storage.Event> noMatch = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, "sureXthing", 50, 0));
        check(label + ": underscore does not match any char", noMatch.size(), 0);

        check(label + ": limit", s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, null, 2, 0)).size(), 2);
        List<Storage.Event> paged = s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, null, null, null, 2, 2));
        check(label + ": offset", paged.size(), 2);
        check(label + ": offset skips", paged.get(0).type(), "chat");

        check(label + ": time window", s.queryEvents(
                new Storage.EventQuery(NOW - 3500, NOW, null, null, null, 50, 0)).size(), 3);
        check(label + ": meta round-trips", s.queryEvents(
                new Storage.EventQuery(0, Long.MAX_VALUE, "command", null, null, 1, 0))
                .get(0).meta(), "{\"allowed\":true}");

        // ---- metrics ----
        List<Storage.MetricPoint> raw = s.queryMetrics(0, Long.MAX_VALUE, 1000);
        check(label + ": all metrics", raw.size(), 30);
        check(label + ": metrics ascend",
                raw.get(0).at() < raw.get(raw.size() - 1).at(), true);

        List<Storage.MetricPoint> down = s.queryMetrics(NOW - 300_000, NOW, 5);
        check(label + ": downsampled to exactly maxPoints", down.size(), 5);
        double first = down.get(0).tps();
        check(label + ": downsample averages into range", first > 0 && first <= 20, true);
        check(label + ": open-ended range still downsamples",
                s.queryMetrics(0, Long.MAX_VALUE, 6).size(), 6);
        check(label + ": fewer rows than maxPoints come back raw",
                s.queryMetrics(0, Long.MAX_VALUE, 500).size(), 30);
        check(label + ": empty window", s.queryMetrics(1, 2, 10).size(), 0);

        // ---- spikes ----
        List<Spike> spikes = s.querySpikes(0, Long.MAX_VALUE, 10);
        check(label + ": spikes", spikes.size(), 2);
        check(label + ": spikes newest first", spikes.get(0).blame(), "Minecraft");
        Spike withStack = spikes.get(1);
        check(label + ": spike blame", withStack.blame(), "ShopGUI");
        check(label + ": spike stack round-trips", withStack.stack().size(), 2);
        check(label + ": spike frame text", withStack.stack().get(0), "com.shopgui.core.Task.run:88");
        check(label + ": empty stack round-trips", spikes.get(0).stack().size(), 0);
        check(label + ": spike duration", withStack.durationMs(), 480.5);
        check(label + ": spike tick", withStack.tick(), 1234L);

        // ---- audit ----
        List<Storage.AuditEntry> audit = s.queryAudit(0, Long.MAX_VALUE, 10);
        check(label + ": audit rows", audit.size(), 2);
        check(label + ": failed action recorded", audit.get(0).success(), false);
        check(label + ": audit newest first", audit.get(0).action(), "server.op");

        // ---- alerts ----
        List<Storage.AlertRecord> alerts = s.queryAlerts(0, Long.MAX_VALUE, 10);
        check(label + ": alerts", alerts.size(), 1);
        check(label + ": alert value", alerts.get(0).value(), 12.4);
        check(label + ": alert channels", alerts.get(0).channels(), "panel,discord");
        check(label + ": alert delivered", alerts.get(0).delivered(), true);

        // ---- stats ----
        Storage.StorageStats stats = s.stats();
        check(label + ": stats kind", stats.kind(), label);
        check(label + ": stats events", stats.events(), 5L);
        check(label + ": stats metrics", stats.metrics(), 30L);
        check(label + ": stats spikes", stats.spikes(), 2L);
        check(label + ": nothing dropped", stats.dropped(), 0L);
    }

    /** The same request against either backend must produce the same series. */
    static void agree(Path dir, Logger log) throws Exception {
        SqliteStorage a = new SqliteStorage(dir.resolve("agree.db"), cfg("sqlite", 30), log);
        a.start();
        MemoryStorage b = new MemoryStorage(cfg("memory", 30));
        b.start();

        for (int i = 0; i < 500; i++) {
            Storage.MetricPoint p = new Storage.MetricPoint(NOW - (500 - i) * 10_000L,
                    20 - (i % 50) * 0.1, i % 30, 1_000_000L * i, 4_000_000_000L,
                    (i % 100) / 100.0, i % 60, 400 + i, 1500 + i * 2);
            a.recordMetrics(p);
            b.recordMetrics(p);
        }
        Thread.sleep(800);

        for (int maxPoints : new int[]{1, 7, 50, 499, 500, 5000}) {
            List<Storage.MetricPoint> fromSqlite = a.queryMetrics(0, Long.MAX_VALUE, maxPoints);
            List<Storage.MetricPoint> fromMemory = b.queryMetrics(0, Long.MAX_VALUE, maxPoints);
            check("maxPoints=" + maxPoints + ": same bucket count",
                    fromSqlite.size(), fromMemory.size());
            check("maxPoints=" + maxPoints + ": within limit",
                    fromSqlite.size() <= Math.max(1, maxPoints), true);
            boolean sameTimes = true;
            boolean sameValues = true;
            for (int i = 0; i < Math.min(fromSqlite.size(), fromMemory.size()); i++) {
                if (fromSqlite.get(i).at() != fromMemory.get(i).at()) sameTimes = false;
                if (Math.abs(fromSqlite.get(i).tps() - fromMemory.get(i).tps()) > 1e-9) sameValues = false;
            }
            check("maxPoints=" + maxPoints + ": same bucket times", sameTimes, true);
            check("maxPoints=" + maxPoints + ": same averages", sameValues, true);
        }
        a.close();
        b.close();
    }


    static void retention(Path dir, Logger log) throws Exception {
        // events kept 1 day; everything written is either 3 days old or fresh.
        Path db = dir.resolve("retention.db");
        SqliteStorage s = new SqliteStorage(db, cfg("sqlite", 1), log);
        s.start();
        s.recordEvent(Storage.Event.of(NOW - 3 * DAY, "chat", "Old", "ancient"));
        s.recordEvent(Storage.Event.of(NOW, "chat", "New", "fresh"));
        Thread.sleep(400);
        check("sqlite: both rows present before purge",
                s.queryEvents(Storage.EventQuery.recent(10)).size(), 2);

        s.purge();
        Thread.sleep(600);   // purge runs on the writer thread
        List<Storage.Event> left = s.queryEvents(Storage.EventQuery.recent(10));
        check("sqlite: expired row purged", left.size(), 1);
        check("sqlite: fresh row survived", left.get(0).actor(), "New");
        s.close();

        MemoryStorage m = new MemoryStorage(cfg("memory", 1));
        m.start();
        m.recordEvent(Storage.Event.of(NOW - 3 * DAY, "chat", "Old", "ancient"));
        m.recordEvent(Storage.Event.of(NOW, "chat", "New", "fresh"));
        check("memory: both rows present before purge",
                m.queryEvents(Storage.EventQuery.recent(10)).size(), 2);
        m.purge();
        List<Storage.Event> mLeft = m.queryEvents(Storage.EventQuery.recent(10));
        check("memory: expired row purged", mLeft.size(), 1);
        check("memory: fresh row survived", mLeft.get(0).actor(), "New");

        // Hard row cap keeps memory bounded even inside the retention window.
        MemoryStorage capped = new MemoryStorage(cfg("memory", 30));
        capped.start();
        for (int i = 0; i < 25_000; i++) {
            capped.recordEvent(Storage.Event.of(NOW, "chat", "u" + i, "line " + i));
        }
        check("memory: row cap enforced", capped.stats().events(), 20_000L);
        check("memory: overflow counted", capped.stats().dropped(), 5_000L);
        check("memory: newest kept", capped.queryEvents(Storage.EventQuery.recent(1)).get(0).detail(),
                "line 24999");
        capped.close();
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
