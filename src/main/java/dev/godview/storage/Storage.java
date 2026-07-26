package dev.godview.storage;

import dev.godview.metrics.Spike;
import dev.godview.util.JsonWriter;

import java.util.List;

/**
 * Everything GodView keeps beyond what fits in memory.
 *
 * <p>Two implementations, one contract: {@link SqliteStorage} persists to disk, and
 * {@link MemoryStorage} holds a bounded window in RAM for servers that want no file at
 * all — or where the JDBC driver failed to download and running without history beats
 * failing to start.
 *
 * <p><b>Writes never block the caller.</b> Every {@code record*} method is called from the
 * main thread, often from inside an event handler, and must return immediately. The SQLite
 * implementation queues and drains on its own thread; when that queue is full it drops and
 * counts rather than applying back-pressure to the server. Losing an audit line is bad;
 * stalling the tick to write one is worse.
 *
 * <p>Reads happen on HTTP threads and may block — they are already off the main thread.
 */
public interface Storage extends AutoCloseable {

    /** Opens connections, creates the schema and starts the writer. */
    void start() throws Exception;

    /** Flushes anything queued and releases resources. Safe to call twice. */
    @Override
    void close();

    /** {@code "sqlite"} or {@code "memory"} — shown in the panel so nobody is surprised. */
    String kind();

    // ------------------------------------------------------------------ writes

    void recordEvent(Event event);

    void recordMetrics(MetricPoint point);

    void recordSpike(Spike spike);

    void recordAudit(AuditEntry entry);

    void recordAlert(AlertRecord alert);

    // ------------------------------------------------------------------- reads

    List<Event> queryEvents(EventQuery query);

    /**
     * Metric history, downsampled server-side.
     *
     * <p>A month of ten-second samples is a quarter of a million rows and no chart needs
     * that; the implementation buckets to at most {@code maxPoints} and averages within
     * each bucket, so the wire payload stays flat however wide the window.
     *
     * <p>Bucket width is derived from the span the stored samples actually cover, not from
     * the requested window — an open-ended range would otherwise collapse everything into
     * a single point. Fewer rows than {@code maxPoints} are returned unbucketed. Both
     * implementations follow the same rule and return the same series.
     */
    List<MetricPoint> queryMetrics(long fromMillis, long toMillis, int maxPoints);

    List<Spike> querySpikes(long fromMillis, long toMillis, int limit);

    List<AuditEntry> queryAudit(long fromMillis, long toMillis, int limit);

    List<AlertRecord> queryAlerts(long fromMillis, long toMillis, int limit);

    /** Deletes anything past its retention window. Called on a timer by the implementation. */
    void purge();

    StorageStats stats();

    // ------------------------------------------------------------------- types

    /**
     * Something that happened on the server.
     *
     * @param type      lower-case discriminator: {@code join}, {@code quit}, {@code chat},
     *                  {@code command}, {@code death}, {@code kick}, {@code ban},
     *                  {@code console_error}, {@code world_load}, {@code teleport}
     * @param actor     player or console name, may be null
     * @param actorUuid player UUID as a string, may be null
     * @param detail    the human-readable payload — chat text, command line, death message
     * @param meta      a JSON object with anything type-specific, or null
     */
    record Event(long at, String type, String actor, String actorUuid, String world,
                 String detail, String meta) {

        public static Event of(long at, String type, String actor, String detail) {
            return new Event(at, type, actor, null, null, detail, null);
        }

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("type", type)
                    .field("actor", actor)
                    .field("actorUuid", actorUuid)
                    .field("world", world)
                    .field("detail", detail);
            if (meta != null && !meta.isEmpty()) w.rawField("meta", meta);
            w.end();
        }
    }

    /** One periodic snapshot of the headline numbers, for charts wider than memory holds. */
    record MetricPoint(long at, double tps, double mspt, long heapUsed, long heapMax,
                       double cpu, int players, int chunks, int entities) {

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("tps", tps)
                    .field("mspt", mspt)
                    .field("heapUsed", heapUsed)
                    .field("heapMax", heapMax)
                    .field("cpu", cpu)
                    .field("players", players)
                    .field("chunks", chunks)
                    .field("entities", entities)
                    .end();
        }
    }

    /**
     * A change made through the panel.
     *
     * <p>Written for every state-changing request, successful or not — a rejected attempt
     * to grant operator is exactly the line an investigation needs.
     */
    record AuditEntry(long at, String user, String ip, String action, String target,
                      String detail, boolean success) {

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("user", user)
                    .field("ip", ip)
                    .field("action", action)
                    .field("target", target)
                    .field("detail", detail)
                    .field("success", success)
                    .end();
        }
    }

    /**
     * An alert that fired.
     *
     * @param channels comma-separated channel names the alert was sent to
     * @param delivered false when every channel failed, so the panel can show the gap
     */
    record AlertRecord(long at, String rule, String severity, String title, String message,
                       double value, String channels, boolean delivered) {

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("rule", rule)
                    .field("severity", severity)
                    .field("title", title)
                    .field("message", message)
                    .field("value", value)
                    .field("channels", channels)
                    .field("delivered", delivered)
                    .end();
        }
    }

    /**
     * Filters for {@link #queryEvents}. Null fields mean "no filter".
     *
     * @param search matched against the detail text, case-insensitively
     */
    record EventQuery(long fromMillis, long toMillis, String type, String actor, String search,
                      int limit, int offset) {

        public static EventQuery recent(int limit) {
            return new EventQuery(0, Long.MAX_VALUE, null, null, null, limit, 0);
        }
    }

    /**
     * What the storage layer is doing, for the panel's diagnostics view.
     *
     * @param queued  rows waiting to be written
     * @param dropped rows discarded because the queue was full — should stay 0
     */
    record StorageStats(String kind, long events, long metrics, long spikes, long audit,
                        long alerts, long fileSizeBytes, int queued, long dropped) {

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("kind", kind)
                    .field("events", events)
                    .field("metrics", metrics)
                    .field("spikes", spikes)
                    .field("audit", audit)
                    .field("alerts", alerts)
                    .field("fileSizeBytes", fileSizeBytes)
                    .field("queued", queued)
                    .field("dropped", dropped)
                    .end();
        }
    }
}
