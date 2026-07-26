package dev.godview.storage;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.Spike;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * History with no file behind it.
 *
 * <p>Chosen deliberately by {@code storage.type: memory}, or fallen back to when SQLite
 * cannot be opened — a server that starts with no history is far better than one that
 * refuses to start because a JDBC driver failed to download.
 *
 * <p>Bounded twice over: by the configured retention days and by a hard row cap per table,
 * so a chatty server cannot turn the panel into a memory leak. The caps below work out to
 * roughly 10 MB at worst, which is the budget a low-end server can spare.
 *
 * <p>Writes are cheap enough to do inline — no queue, no writer thread. Everything is
 * guarded by the deque it touches, so the main thread and HTTP threads never contend for
 * the same lock unless they are using the same table.
 */
public final class MemoryStorage implements Storage {

    private static final int MAX_EVENTS = 20_000;
    private static final int MAX_METRICS = 20_000;
    private static final int MAX_SPIKES = 2_000;
    private static final int MAX_AUDIT = 5_000;
    private static final int MAX_ALERTS = 2_000;
    /** Minimum gap between the age-based sweeps triggered from the write path. */
    private static final long PURGE_INTERVAL_MS = 60_000L;

    private final GodViewConfig.Storage config;

    private final Deque<Event> events = new ArrayDeque<>();
    private final Deque<MetricPoint> metrics = new ArrayDeque<>();
    private final Deque<Spike> spikes = new ArrayDeque<>();
    private final Deque<AuditEntry> audit = new ArrayDeque<>();
    private final Deque<AlertRecord> alerts = new ArrayDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastPurge = new AtomicLong(System.currentTimeMillis());

    public MemoryStorage(GodViewConfig.Storage config) {
        this.config = config;
    }

    @Override
    public void start() {
        // Nothing to open.
    }

    @Override
    public void close() {
        clear(events);
        clear(metrics);
        clear(spikes);
        clear(audit);
        clear(alerts);
    }

    @Override
    public String kind() {
        return "memory";
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void recordEvent(Event event) {
        push(events, event, MAX_EVENTS);
    }

    @Override
    public void recordMetrics(MetricPoint point) {
        push(metrics, point, MAX_METRICS);
    }

    @Override
    public void recordSpike(Spike spike) {
        push(spikes, spike, MAX_SPIKES);
    }

    @Override
    public void recordAudit(AuditEntry entry) {
        push(audit, entry, MAX_AUDIT);
    }

    @Override
    public void recordAlert(AlertRecord alert) {
        push(alerts, alert, MAX_ALERTS);
    }

    private <T> void push(Deque<T> deque, T value, int cap) {
        synchronized (deque) {
            deque.addLast(value);
            while (deque.size() > cap) {
                deque.removeFirst();
                dropped.incrementAndGet();
            }
        }
        maybePurge();
    }

    /**
     * Age-based retention, amortized over writes.
     *
     * <p>There is no writer thread here to hang a timer off, and asking the plugin to
     * schedule one would mean the storage layer only works when someone remembers to wire
     * it up. Throttling to once a minute keeps the cost off the write path: expired
     * entries sit at the front of each deque, so the usual outcome is five cheap peeks.
     */
    private void maybePurge() {
        long now = System.currentTimeMillis();
        long last = lastPurge.get();
        if (now - last < PURGE_INTERVAL_MS) return;
        // Only the thread that wins the swap does the work, so concurrent writers do not
        // all sweep at the same moment.
        if (!lastPurge.compareAndSet(last, now)) return;
        purge();
    }

    // ------------------------------------------------------------------- reads

    @Override
    public List<Event> queryEvents(EventQuery query) {
        String type = lower(query.type());
        String actor = lower(query.actor());
        String search = lower(query.search());

        List<Event> matched = new ArrayList<>();
        synchronized (events) {
            // Newest first, so the limit keeps the most recent rather than the oldest.
            for (var it = events.descendingIterator(); it.hasNext(); ) {
                Event e = it.next();
                if (e.at() < query.fromMillis() || e.at() > query.toMillis()) continue;
                if (type != null && !type.equals(lower(e.type()))) continue;
                if (actor != null && !actor.equals(lower(e.actor()))) continue;
                if (search != null) {
                    String detail = lower(e.detail());
                    if (detail == null || !detail.contains(search)) continue;
                }
                matched.add(e);
                if (matched.size() >= query.offset() + query.limit()) break;
            }
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return List.copyOf(matched.subList(from, to));
    }

    @Override
    public List<MetricPoint> queryMetrics(long fromMillis, long toMillis, int maxPoints) {
        List<MetricPoint> window = new ArrayList<>();
        synchronized (metrics) {
            for (MetricPoint p : metrics) {
                if (p.at() >= fromMillis && p.at() <= toMillis) window.add(p);
            }
        }
        return Downsample.metrics(window, maxPoints);
    }

    @Override
    public List<Spike> querySpikes(long fromMillis, long toMillis, int limit) {
        return newestFirst(spikes, Spike::at, fromMillis, toMillis, limit);
    }

    @Override
    public List<AuditEntry> queryAudit(long fromMillis, long toMillis, int limit) {
        return newestFirst(audit, AuditEntry::at, fromMillis, toMillis, limit);
    }

    @Override
    public List<AlertRecord> queryAlerts(long fromMillis, long toMillis, int limit) {
        return newestFirst(alerts, AlertRecord::at, fromMillis, toMillis, limit);
    }

    private static <T> List<T> newestFirst(Deque<T> deque, java.util.function.ToLongFunction<T> at,
                                           long from, long to, int limit) {
        List<T> out = new ArrayList<>();
        synchronized (deque) {
            for (var it = deque.descendingIterator(); it.hasNext() && out.size() < limit; ) {
                T value = it.next();
                long when = at.applyAsLong(value);
                if (when >= from && when <= to) out.add(value);
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ upkeep

    @Override
    public void purge() {
        long now = System.currentTimeMillis();
        trimOlderThan(events, Event::at, now - days(config.eventsDays()));
        trimOlderThan(metrics, MetricPoint::at, now - days(config.metricsDays()));
        trimOlderThan(spikes, Spike::at, now - days(config.spikesDays()));
        trimOlderThan(audit, AuditEntry::at, now - days(config.auditDays()));
        trimOlderThan(alerts, AlertRecord::at, now - days(config.alertsDays()));
    }

    private static <T> void trimOlderThan(Deque<T> deque, java.util.function.ToLongFunction<T> at,
                                          long cutoff) {
        synchronized (deque) {
            // Entries are appended in time order, so the expired ones are all at the front.
            while (!deque.isEmpty() && at.applyAsLong(deque.peekFirst()) < cutoff) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public StorageStats stats() {
        return new StorageStats(kind(), size(events), size(metrics), size(spikes), size(audit),
                size(alerts), 0L, 0, dropped.get());
    }

    private static long days(int count) {
        return count * 86_400_000L;
    }

    private static int size(Deque<?> deque) {
        synchronized (deque) {
            return deque.size();
        }
    }

    private static void clear(Deque<?> deque) {
        synchronized (deque) {
            deque.clear();
        }
    }

    private static String lower(String value) {
        return value == null || value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Shared bucketing so memory and SQLite return identically shaped series. */
    static final class Downsample {

        private Downsample() {
        }

        static List<MetricPoint> metrics(List<MetricPoint> points, int maxPoints) {
            if (points.isEmpty()) return List.of();

            List<MetricPoint> sorted = new ArrayList<>(points);
            sorted.sort(Comparator.comparingLong(MetricPoint::at));

            int limit = Math.max(1, maxPoints);
            if (sorted.size() <= limit) return List.copyOf(sorted);

            // Width comes from the span the samples actually cover, and buckets align to
            // the first one — identical to the SQL path, so both backends return the same
            // series for the same request.
            long min = sorted.get(0).at();
            long span = sorted.get(sorted.size() - 1).at() - min + 1;
            long bucket = Math.max(1, (span + limit - 1) / limit);

            List<MetricPoint> out = new ArrayList<>(limit);
            long currentBucket = Long.MIN_VALUE;
            double tps = 0, mspt = 0, cpu = 0;
            long heapUsed = 0, heapMax = 0;
            long players = 0, chunks = 0, entities = 0;
            int count = 0;

            for (MetricPoint p : sorted) {
                long b = ((p.at() - min) / bucket) * bucket + min;
                if (b != currentBucket && count > 0) {
                    out.add(mean(currentBucket, tps, mspt, heapUsed, heapMax, cpu, players,
                            chunks, entities, count));
                    tps = mspt = cpu = 0;
                    heapUsed = heapMax = players = chunks = entities = 0;
                    count = 0;
                }
                currentBucket = b;
                tps += p.tps();
                mspt += p.mspt();
                cpu += p.cpu();
                heapUsed += p.heapUsed();
                heapMax = Math.max(heapMax, p.heapMax());
                players += p.players();
                chunks += p.chunks();
                entities += p.entities();
                count++;
            }
            if (count > 0) {
                out.add(mean(currentBucket, tps, mspt, heapUsed, heapMax, cpu, players, chunks,
                        entities, count));
            }
            return List.copyOf(out);
        }

        private static MetricPoint mean(long at, double tps, double mspt, long heapUsed,
                                        long heapMax, double cpu, long players, long chunks,
                                        long entities, int count) {
            return new MetricPoint(at, tps / count, mspt / count, heapUsed / count, heapMax,
                    cpu / count, (int) (players / count), (int) (chunks / count),
                    (int) (entities / count));
        }
    }
}
