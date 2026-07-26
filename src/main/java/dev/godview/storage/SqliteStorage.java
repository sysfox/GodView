package dev.godview.storage;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.Spike;
import dev.godview.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable history in a single SQLite file.
 *
 * <p>The whole design follows from one rule: the main thread must never wait on disk.
 * Every write is handed to a bounded queue and drained by one dedicated thread that
 * batches into a single transaction. When the queue fills — a disk stall, a burst of
 * block events — writes are dropped and counted rather than blocking the tick. That
 * trade is deliberate and visible in {@link StorageStats#dropped()}.
 *
 * <p>One writer thread also removes the need for any write locking: SQLite serializes
 * writers anyway, so contending for it from several threads would only add waiting.
 * Readers run on HTTP threads through a separate connection, which WAL journalling lets
 * proceed without blocking the writer.
 *
 * <p>The driver is instantiated directly instead of going through {@code DriverManager}.
 * Paper's library loader puts sqlite-jdbc on the plugin's own classloader, which
 * {@code DriverManager} does not scan.
 */
public final class SqliteStorage implements Storage {

    /** Rows that may wait to be written. Beyond this we drop rather than stall the server. */
    private static final int QUEUE_CAPACITY = 20_000;
    /** How often the writer thread deletes expired rows. */
    private static final long PURGE_INTERVAL_MS = 3_600_000L;
    /** Row counts are a full scan in SQLite, so the diagnostics view gets a cached answer. */
    private static final long STATS_CACHE_MS = 30_000L;

    private static final int SCHEMA_VERSION = 1;

    /**
     * Queued by {@link #purge()} so retention deletes run on the writer thread like
     * every other statement. A JDBC connection is not thread-safe, and a purge issued
     * from the main thread would be using {@code writeConn} underneath the writer.
     */
    private static final Object PURGE_REQUEST = new Object();

    private final Path file;
    private final GodViewConfig.Storage config;
    private final Logger log;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Object readLock = new Object();

    private Connection writeConn;
    private Connection readConn;
    private PreparedStatement psEvent;
    private PreparedStatement psMetric;
    private PreparedStatement psSpike;
    private PreparedStatement psAudit;
    private PreparedStatement psAlert;

    private Thread writer;
    private volatile boolean running;
    private long lastPurge;

    private volatile StorageStats cachedStats;
    private volatile long cachedStatsAt;

    public SqliteStorage(Path file, GodViewConfig.Storage config, Logger log) {
        this.file = file;
        this.config = config;
        this.log = log;
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    public void start() throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        boolean fresh = !Files.exists(file);

        writeConn = open();
        readConn = open();

        try (Statement st = writeConn.createStatement()) {
            // auto_vacuum only takes effect on a database with no tables yet, so it has to
            // come first — on an existing file this is a no-op and the pragma is ignored.
            if (fresh) st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            createSchema(st);
        }

        prepareStatements();
        lastPurge = System.currentTimeMillis();

        running = true;
        writer = new Thread(this::writeLoop, "GodView-StorageWriter");
        writer.setDaemon(true);
        writer.setPriority(Thread.NORM_PRIORITY - 1);
        writer.start();
    }

    private Connection open() throws Exception {
        // Instantiated directly: see the class note on DriverManager and classloaders.
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC")
                .getDeclaredConstructor().newInstance();
        Properties props = new Properties();
        Connection conn = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), props);
        if (conn == null) throw new SQLException("sqlite-jdbc rejected the connection URL");
        conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            // Wait rather than fail if the other connection holds the write lock briefly.
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private void createSchema(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                  version INTEGER NOT NULL
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS events (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  at         INTEGER NOT NULL,
                  type       TEXT    NOT NULL,
                  actor      TEXT,
                  actor_uuid TEXT,
                  world      TEXT,
                  detail     TEXT,
                  meta       TEXT
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS idx_events_at ON events(at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_events_type_at ON events(type, at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_events_actor_at ON events(actor, at)");

        // at is the primary key: the sampler writes on a fixed cadence, so a duplicate
        // timestamp means a replay and should replace rather than accumulate.
        st.execute("""
                CREATE TABLE IF NOT EXISTS metrics (
                  at        INTEGER PRIMARY KEY,
                  tps       REAL,
                  mspt      REAL,
                  heap_used INTEGER,
                  heap_max  INTEGER,
                  cpu       REAL,
                  players   INTEGER,
                  chunks    INTEGER,
                  entities  INTEGER
                )""");

        st.execute("""
                CREATE TABLE IF NOT EXISTS spikes (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  at          INTEGER NOT NULL,
                  duration_ms REAL    NOT NULL,
                  tick        INTEGER,
                  blame       TEXT,
                  blame_frame TEXT,
                  stack       TEXT
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS idx_spikes_at ON spikes(at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_spikes_blame_at ON spikes(blame, at)");

        st.execute("""
                CREATE TABLE IF NOT EXISTS audit (
                  id      INTEGER PRIMARY KEY AUTOINCREMENT,
                  at      INTEGER NOT NULL,
                  user    TEXT,
                  ip      TEXT,
                  action  TEXT,
                  target  TEXT,
                  detail  TEXT,
                  success INTEGER NOT NULL
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS idx_audit_at ON audit(at)");

        st.execute("""
                CREATE TABLE IF NOT EXISTS alerts (
                  id        INTEGER PRIMARY KEY AUTOINCREMENT,
                  at        INTEGER NOT NULL,
                  rule      TEXT,
                  severity  TEXT,
                  title     TEXT,
                  message   TEXT,
                  value     REAL,
                  channels  TEXT,
                  delivered INTEGER NOT NULL
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS idx_alerts_at ON alerts(at)");

        try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            if (!rs.next()) {
                st.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
            }
        }
    }

    private void prepareStatements() throws SQLException {
        psEvent = writeConn.prepareStatement(
                "INSERT INTO events(at,type,actor,actor_uuid,world,detail,meta) VALUES (?,?,?,?,?,?,?)");
        psMetric = writeConn.prepareStatement(
                "INSERT OR REPLACE INTO metrics(at,tps,mspt,heap_used,heap_max,cpu,players,chunks,entities) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)");
        psSpike = writeConn.prepareStatement(
                "INSERT INTO spikes(at,duration_ms,tick,blame,blame_frame,stack) VALUES (?,?,?,?,?,?)");
        psAudit = writeConn.prepareStatement(
                "INSERT INTO audit(at,user,ip,action,target,detail,success) VALUES (?,?,?,?,?,?,?)");
        psAlert = writeConn.prepareStatement(
                "INSERT INTO alerts(at,rule,severity,title,message,value,channels,delivered) "
                        + "VALUES (?,?,?,?,?,?,?,?)");
    }

    @Override
    public void close() {
        if (!running && writeConn == null) return;
        running = false;

        Thread t = writer;
        if (t != null) {
            t.interrupt();
            try {
                // Long enough to finish a batch already in flight, short enough that a
                // wedged disk cannot hold up server shutdown.
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writer = null;

        closeQuietly(psEvent, psMetric, psSpike, psAudit, psAlert);
        closeQuietly(readConn, writeConn);
        readConn = null;
        writeConn = null;
    }

    @Override
    public String kind() {
        return "sqlite";
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void recordEvent(Event event) {
        enqueue(event);
    }

    @Override
    public void recordMetrics(MetricPoint point) {
        enqueue(point);
    }

    @Override
    public void recordSpike(Spike spike) {
        enqueue(spike);
    }

    @Override
    public void recordAudit(AuditEntry entry) {
        enqueue(entry);
    }

    @Override
    public void recordAlert(AlertRecord alert) {
        enqueue(alert);
    }

    /** Never blocks. A full queue means the disk cannot keep up, and the tick wins. */
    private void enqueue(Object row) {
        if (!running || !queue.offer(row)) dropped.incrementAndGet();
    }

    // ------------------------------------------------------------ writer thread

    private void writeLoop() {
        List<Object> batch = new ArrayList<>(config.batchSize());
        while (running) {
            try {
                Object first = queue.poll(config.flushIntervalMs(), TimeUnit.MILLISECONDS);
                boolean purgeRequested = false;
                if (first != null) {
                    batch.clear();
                    batch.add(first);
                    queue.drainTo(batch, config.batchSize() - 1);
                    purgeRequested = batch.removeIf(row -> row == PURGE_REQUEST);
                    if (!batch.isEmpty()) flush(batch);
                }
                if (purgeRequested) doPurge();
                else maybePurge();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.log(Level.WARNING, "GodView storage write failed; the batch was dropped", t);
            }
        }
        drainRemaining();
    }

    /** Last chance on shutdown: write whatever is still queued, without blocking forever. */
    private void drainRemaining() {
        List<Object> batch = new ArrayList<>(config.batchSize());
        while (!queue.isEmpty()) {
            batch.clear();
            queue.drainTo(batch, config.batchSize());
            batch.removeIf(row -> row == PURGE_REQUEST);
            if (batch.isEmpty()) break;
            try {
                flush(batch);
            } catch (SQLException e) {
                log.log(Level.WARNING, "GodView could not flush storage on shutdown", e);
                return;
            }
        }
    }

    private void flush(List<Object> batch) throws SQLException {
        boolean events = false, metrics = false, spikes = false, audits = false, alerts = false;

        writeConn.setAutoCommit(false);
        try {
            for (Object row : batch) {
                if (row instanceof Event e) {
                    bind(e);
                    events = true;
                } else if (row instanceof MetricPoint p) {
                    bind(p);
                    metrics = true;
                } else if (row instanceof Spike s) {
                    bind(s);
                    spikes = true;
                } else if (row instanceof AuditEntry a) {
                    bind(a);
                    audits = true;
                } else if (row instanceof AlertRecord a) {
                    bind(a);
                    alerts = true;
                }
            }
            if (events) psEvent.executeBatch();
            if (metrics) psMetric.executeBatch();
            if (spikes) psSpike.executeBatch();
            if (audits) psAudit.executeBatch();
            if (alerts) psAlert.executeBatch();
            writeConn.commit();
        } catch (SQLException e) {
            writeConn.rollback();
            // A rolled-back statement keeps its batch, which would replay on the next
            // flush and fail again. Clearing costs one batch and keeps the writer alive.
            clearBatches();
            throw e;
        } finally {
            writeConn.setAutoCommit(true);
        }
    }

    private void clearBatches() {
        for (PreparedStatement ps : new PreparedStatement[]{psEvent, psMetric, psSpike, psAudit, psAlert}) {
            try {
                ps.clearBatch();
            } catch (SQLException ignored) {
                // Nothing useful to do; the next flush recreates the batch anyway.
            }
        }
    }

    private void bind(Event e) throws SQLException {
        psEvent.setLong(1, e.at());
        psEvent.setString(2, e.type());
        psEvent.setString(3, e.actor());
        psEvent.setString(4, e.actorUuid());
        psEvent.setString(5, e.world());
        psEvent.setString(6, e.detail());
        psEvent.setString(7, e.meta());
        psEvent.addBatch();
    }

    private void bind(MetricPoint p) throws SQLException {
        psMetric.setLong(1, p.at());
        psMetric.setDouble(2, p.tps());
        psMetric.setDouble(3, p.mspt());
        psMetric.setLong(4, p.heapUsed());
        psMetric.setLong(5, p.heapMax());
        psMetric.setDouble(6, p.cpu());
        psMetric.setInt(7, p.players());
        psMetric.setInt(8, p.chunks());
        psMetric.setInt(9, p.entities());
        psMetric.addBatch();
    }

    private void bind(Spike s) throws SQLException {
        psSpike.setLong(1, s.at());
        psSpike.setDouble(2, s.durationMs());
        psSpike.setLong(3, s.tick());
        psSpike.setString(4, s.blame());
        psSpike.setString(5, s.blameFrame());
        psSpike.setString(6, Json.write(s.stack()));
        psSpike.addBatch();
    }

    private void bind(AuditEntry a) throws SQLException {
        psAudit.setLong(1, a.at());
        psAudit.setString(2, a.user());
        psAudit.setString(3, a.ip());
        psAudit.setString(4, a.action());
        psAudit.setString(5, a.target());
        psAudit.setString(6, a.detail());
        psAudit.setInt(7, a.success() ? 1 : 0);
        psAudit.addBatch();
    }

    private void bind(AlertRecord a) throws SQLException {
        psAlert.setLong(1, a.at());
        psAlert.setString(2, a.rule());
        psAlert.setString(3, a.severity());
        psAlert.setString(4, a.title());
        psAlert.setString(5, a.message());
        psAlert.setDouble(6, a.value());
        psAlert.setString(7, a.channels());
        psAlert.setInt(8, a.delivered() ? 1 : 0);
        psAlert.addBatch();
    }

    // ------------------------------------------------------------------- reads

    @Override
    public List<Event> queryEvents(EventQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT at,type,actor,actor_uuid,world,detail,meta FROM events WHERE at BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(query.fromMillis());
        args.add(query.toMillis());

        if (notBlank(query.type())) {
            sql.append(" AND type = ?");
            args.add(query.type());
        }
        if (notBlank(query.actor())) {
            sql.append(" AND actor = ?");
            args.add(query.actor());
        }
        if (notBlank(query.search())) {
            sql.append(" AND detail LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(query.search()) + "%");
        }
        sql.append(" ORDER BY at DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, query.limit()));
        args.add(Math.max(0, query.offset()));

        List<Event> out = new ArrayList<>();
        read(sql.toString(), args, rs -> out.add(new Event(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7))));
        return List.copyOf(out);
    }

    @Override
    public List<MetricPoint> queryMetrics(long fromMillis, long toMillis, int maxPoints) {
        int limit = Math.max(1, maxPoints);
        String columns = "AVG(tps), AVG(mspt), AVG(heap_used), MAX(heap_max), AVG(cpu), "
                + "AVG(players), AVG(chunks), AVG(entities)";

        // Bucket width comes from the span the data actually covers, not the span that was
        // asked for. A panel requesting "everything" passes an open-ended range, and
        // dividing that by maxPoints would collapse a month of samples into one point.
        long[] range = metricRange(fromMillis, toMillis);
        long count = range[0];
        if (count == 0) return List.of();

        List<MetricPoint> out = new ArrayList<>();
        if (count <= limit) {
            String sql = "SELECT at, tps, mspt, heap_used, heap_max, cpu, players, chunks, entities "
                    + "FROM metrics WHERE at BETWEEN ? AND ? ORDER BY at";
            read(sql, List.of(fromMillis, toMillis), rs -> out.add(readMetric(rs)));
            return List.copyOf(out);
        }

        long min = range[1];
        long span = range[2] - min + 1;
        long bucket = Math.max(1, ceilDiv(span, limit));

        // Buckets are aligned to the first sample rather than to the epoch, so the result
        // is exactly `limit` buckets instead of one extra wherever the window happens to
        // straddle an epoch multiple.
        String sql = "SELECT ((at - ?) / ?) * ? + ? AS bucket, " + columns
                + " FROM metrics WHERE at BETWEEN ? AND ? GROUP BY bucket ORDER BY bucket";

        // Aggregation happens in SQL: a month of samples is a quarter of a million rows,
        // and none of them need to cross into the heap to be averaged.
        read(sql, List.of(min, bucket, bucket, min, fromMillis, toMillis),
                rs -> out.add(readMetric(rs)));
        return List.copyOf(out);
    }

    /** @return {@code {count, minAt, maxAt}} for the window */
    private long[] metricRange(long fromMillis, long toMillis) {
        long[] result = {0, 0, 0};
        read("SELECT COUNT(*), COALESCE(MIN(at), 0), COALESCE(MAX(at), 0) FROM metrics "
                        + "WHERE at BETWEEN ? AND ?",
                List.of(fromMillis, toMillis), rs -> {
                    result[0] = rs.getLong(1);
                    result[1] = rs.getLong(2);
                    result[2] = rs.getLong(3);
                });
        return result;
    }

    private static MetricPoint readMetric(ResultSet rs) throws SQLException {
        return new MetricPoint(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getLong(4),
                rs.getLong(5), rs.getDouble(6), rs.getInt(7), rs.getInt(8), rs.getInt(9));
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    @Override
    public List<Spike> querySpikes(long fromMillis, long toMillis, int limit) {
        String sql = "SELECT at,duration_ms,tick,blame,blame_frame,stack FROM spikes "
                + "WHERE at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?";
        List<Spike> out = new ArrayList<>();
        read(sql, List.of(fromMillis, toMillis, Math.max(1, limit)), rs -> {
            List<String> stack = new ArrayList<>();
            for (Object frame : Json.asArr(Json.parse(orEmptyArray(rs.getString(6))))) {
                stack.add(String.valueOf(frame));
            }
            out.add(new Spike(rs.getLong(1), rs.getDouble(2), rs.getLong(3),
                    rs.getString(4), rs.getString(5), List.copyOf(stack)));
        });
        return List.copyOf(out);
    }

    @Override
    public List<AuditEntry> queryAudit(long fromMillis, long toMillis, int limit) {
        String sql = "SELECT at,user,ip,action,target,detail,success FROM audit "
                + "WHERE at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?";
        List<AuditEntry> out = new ArrayList<>();
        read(sql, List.of(fromMillis, toMillis, Math.max(1, limit)), rs -> out.add(new AuditEntry(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7) != 0)));
        return List.copyOf(out);
    }

    @Override
    public List<AlertRecord> queryAlerts(long fromMillis, long toMillis, int limit) {
        String sql = "SELECT at,rule,severity,title,message,value,channels,delivered FROM alerts "
                + "WHERE at BETWEEN ? AND ? ORDER BY at DESC LIMIT ?";
        List<AlertRecord> out = new ArrayList<>();
        read(sql, List.of(fromMillis, toMillis, Math.max(1, limit)), rs -> out.add(new AlertRecord(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getDouble(6), rs.getString(7), rs.getInt(8) != 0)));
        return List.copyOf(out);
    }

    private void read(String sql, List<Object> args, RowReader reader) {
        synchronized (readLock) {
            if (readConn == null) return;
            try (PreparedStatement ps = readConn.prepareStatement(sql)) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) reader.read(rs);
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "GodView storage query failed: " + sql, e);
            }
        }
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rs) throws SQLException;
    }

    // ------------------------------------------------------------------ upkeep

    private void maybePurge() {
        if (System.currentTimeMillis() - lastPurge < PURGE_INTERVAL_MS) return;
        doPurge();
    }

    /** Asks the writer thread to purge. Safe to call from anywhere, including the main thread. */
    @Override
    public void purge() {
        if (running) queue.offer(PURGE_REQUEST);
    }

    /** The real work. Only ever runs on the writer thread. */
    private void doPurge() {
        long now = System.currentTimeMillis();
        lastPurge = now;
        try (Statement st = writeConn.createStatement()) {
            int removed = 0;
            removed += st.executeUpdate("DELETE FROM events WHERE at < " + (now - days(config.eventsDays())));
            removed += st.executeUpdate("DELETE FROM metrics WHERE at < " + (now - days(config.metricsDays())));
            removed += st.executeUpdate("DELETE FROM spikes WHERE at < " + (now - days(config.spikesDays())));
            removed += st.executeUpdate("DELETE FROM audit WHERE at < " + (now - days(config.auditDays())));
            removed += st.executeUpdate("DELETE FROM alerts WHERE at < " + (now - days(config.alertsDays())));
            if (removed > 0) {
                // Hand the freed pages back a slice at a time. A full VACUUM would rewrite
                // the whole file and block every reader while it did.
                st.execute("PRAGMA incremental_vacuum(200)");
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "GodView storage purge failed", e);
        }
    }

    @Override
    public StorageStats stats() {
        long now = System.currentTimeMillis();
        StorageStats cached = cachedStats;
        if (cached != null && now - cachedStatsAt < STATS_CACHE_MS) {
            // Only the live counters are worth recomputing between cache refreshes.
            return new StorageStats(cached.kind(), cached.events(), cached.metrics(),
                    cached.spikes(), cached.audit(), cached.alerts(), cached.fileSizeBytes(),
                    queue.size(), dropped.get());
        }

        StorageStats fresh = new StorageStats(kind(),
                count("events"), count("metrics"), count("spikes"), count("audit"), count("alerts"),
                fileSize(), queue.size(), dropped.get());
        cachedStats = fresh;
        cachedStatsAt = now;
        return fresh;
    }

    private long count(String table) {
        long[] result = {0};
        read("SELECT COUNT(*) FROM " + table, List.of(), rs -> result[0] = rs.getLong(1));
        return result[0];
    }

    private long fileSize() {
        try {
            long total = Files.size(file);
            // WAL content is not in the main file yet but is very much on disk.
            Path wal = file.resolveSibling(file.getFileName() + "-wal");
            if (Files.exists(wal)) total += Files.size(wal);
            return total;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long days(int count) {
        return count * 86_400_000L;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Keeps a user's {@code %} or {@code _} from turning a search into a wildcard scan. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String orEmptyArray(String json) {
        return json == null || json.isBlank() ? "[]" : json;
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c == null) continue;
            try {
                c.close();
            } catch (Exception ignored) {
                // Shutdown path — a failure here has nowhere useful to go.
            }
        }
    }
}
