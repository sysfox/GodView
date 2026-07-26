package dev.godview.util;

/**
 * Multi-resolution rolling time series for one metric (TPS, MSPT, heap, player count…).
 *
 * <p>A chart of the last 24 hours does not need 86 400 points, and a chart of the last
 * five minutes must not be smoothed into uselessness. So each series keeps several
 * independent resolutions — by default 1 s for 5 minutes, 15 s for 1 hour, 5 min for 24 h —
 * and the panel asks for whichever level matches the window on screen.
 *
 * <p>Every sample is folded into all levels at once rather than cascading between them.
 * With three levels that is three array writes per sample: cheaper than a rollup pass,
 * and each level stays exact instead of averaging already-averaged data.
 *
 * <p>Empty buckets are not materialized. A server that was idle leaves a gap in the
 * returned timestamps rather than a run of fake zeroes — the frontend draws the break.
 *
 * <p>Memory is fixed at construction: the default layout is ~40 KB per metric.
 * All methods are {@code synchronized}; the main thread writes and HTTP threads read.
 */
public final class TimeSeries {

    private final Level[] levels;

    public TimeSeries(long[] bucketMillis, int[] capacity) {
        if (bucketMillis.length != capacity.length || bucketMillis.length == 0) {
            throw new IllegalArgumentException("bucketMillis and capacity must pair up");
        }
        this.levels = new Level[bucketMillis.length];
        for (int i = 0; i < bucketMillis.length; i++) {
            levels[i] = new Level(bucketMillis[i], capacity[i]);
        }
    }

    /** 1 s × 5 min, 15 s × 1 h, 5 min × 24 h. */
    public static TimeSeries standard() {
        return new TimeSeries(
                new long[]{1_000L, 15_000L, 300_000L},
                new int[]{300, 240, 288});
    }

    /** 15 s × 1 h, 5 min × 24 h — for metrics too expensive to sample every second. */
    public static TimeSeries coarse() {
        return new TimeSeries(
                new long[]{15_000L, 300_000L},
                new int[]{240, 288});
    }

    public synchronized void add(long nowMillis, double value) {
        for (Level level : levels) level.add(nowMillis, value);
    }

    public int levelCount() {
        return levels.length;
    }

    /** Bucket width of a level, in milliseconds. */
    public long bucketMillis(int level) {
        return levels[level].bucketMillis;
    }

    /**
     * The level whose full span covers {@code windowMillis} at the finest resolution
     * available — what an endpoint should pick when the panel asks for "last 30 minutes".
     */
    public int levelFor(long windowMillis) {
        for (int i = 0; i < levels.length; i++) {
            if (levels[i].bucketMillis * (long) levels[i].capacity >= windowMillis) return i;
        }
        return levels.length - 1;
    }

    public synchronized Snapshot snapshot(int level) {
        return levels[level].snapshot(Long.MIN_VALUE);
    }

    /** Only buckets starting at or after {@code fromMillis}. */
    public synchronized Snapshot snapshot(int level, long fromMillis) {
        return levels[level].snapshot(fromMillis);
    }

    /** Most recent value folded in at the finest level, or {@code def} if never written. */
    public synchronized double latest(double def) {
        Level fine = levels[0];
        return fine.size == 0 ? def : fine.last[fine.head];
    }

    public synchronized void clear() {
        for (Level level : levels) level.clear();
    }

    /**
     * One resolution's worth of buckets, oldest first. Parallel arrays rather than a list
     * of objects — these go straight out to JSON via {@link JsonWriter}'s primitive-array
     * fields, with nothing boxed on the way.
     *
     * @param t     bucket start times (epoch millis)
     * @param avg   mean of the samples in each bucket
     * @param min   lowest sample in each bucket
     * @param max   highest sample in each bucket
     * @param last  final sample in each bucket
     * @param count how many samples each bucket holds
     */
    public record Snapshot(long[] t, double[] avg, double[] min, double[] max,
                           double[] last, int[] count, long bucketMillis) {

        public int size() {
            return t.length;
        }

        public boolean isEmpty() {
            return t.length == 0;
        }
    }

    /** One resolution. Not synchronized itself — the enclosing {@link TimeSeries} owns the lock. */
    private static final class Level {
        final long bucketMillis;
        final int capacity;
        final long[] start;
        final double[] sum;
        final double[] min;
        final double[] max;
        final double[] last;
        final int[] count;
        int head = -1;
        int size;

        Level(long bucketMillis, int capacity) {
            if (bucketMillis < 1) throw new IllegalArgumentException("bucketMillis must be >= 1");
            if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
            this.bucketMillis = bucketMillis;
            this.capacity = capacity;
            this.start = new long[capacity];
            this.sum = new double[capacity];
            this.min = new double[capacity];
            this.max = new double[capacity];
            this.last = new double[capacity];
            this.count = new int[capacity];
        }

        void add(long now, double v) {
            long bucket = now - Math.floorMod(now, bucketMillis);
            if (size > 0 && bucket <= start[head]) {
                // Same bucket, or a sample that arrived out of order (clock adjustment,
                // a batch flushed late). Fold it into the newest bucket either way —
                // dropping it would silently lose data, and rewriting history is worse.
                merge(head, v);
                return;
            }
            head = (head + 1) % capacity;
            start[head] = bucket;
            sum[head] = v;
            min[head] = v;
            max[head] = v;
            last[head] = v;
            count[head] = 1;
            if (size < capacity) size++;
        }

        private void merge(int i, double v) {
            sum[i] += v;
            if (v < min[i]) min[i] = v;
            if (v > max[i]) max[i] = v;
            last[i] = v;
            count[i]++;
        }

        Snapshot snapshot(long fromMillis) {
            int skip = 0;
            while (skip < size && start[idx(skip)] < fromMillis) skip++;
            int n = size - skip;

            long[] t = new long[n];
            double[] avg = new double[n];
            double[] lo = new double[n];
            double[] hi = new double[n];
            double[] lastOut = new double[n];
            int[] cnt = new int[n];
            for (int i = 0; i < n; i++) {
                int j = idx(skip + i);
                t[i] = start[j];
                avg[i] = sum[j] / count[j];
                lo[i] = min[j];
                hi[i] = max[j];
                lastOut[i] = last[j];
                cnt[i] = count[j];
            }
            return new Snapshot(t, avg, lo, hi, lastOut, cnt, bucketMillis);
        }

        /** Backing index of the {@code i}-th oldest retained bucket. */
        private int idx(int i) {
            return (head - size + 1 + i + capacity * 2) % capacity;
        }

        void clear() {
            head = -1;
            size = 0;
        }
    }
}
