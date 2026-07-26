package dev.godview.util;

import java.util.Arrays;

/**
 * Fixed-capacity ring of {@code double}s — no boxing, no allocation after construction.
 *
 * <p>Backs the per-tick MSPT sampler and the short rolling windows behind alert rules,
 * where a {@code Deque<Double>} would churn the allocator twenty times a second.
 *
 * <p>Writes happen on the main thread; the HTTP threads read. Every method is
 * {@code synchronized} on the instance — uncontended locking costs far less than the
 * copying a lock-free design would need, at these sample rates.
 */
public final class DoubleRing {

    private final double[] buf;
    private int head = -1;   // index of the newest sample
    private int size;
    private long written;    // total ever added, including overwritten samples

    public DoubleRing(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.buf = new double[capacity];
    }

    public synchronized void add(double v) {
        head = (head + 1) % buf.length;
        buf[head] = v;
        if (size < buf.length) size++;
        written++;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized long written() {
        return written;
    }

    public synchronized void clear() {
        head = -1;
        size = 0;
        Arrays.fill(buf, 0d);
    }

    /** @param i 0 is the oldest retained sample, {@code size()-1} the newest */
    public synchronized double get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("index " + i + " of " + size);
        return buf[(head - size + 1 + i + buf.length * 2) % buf.length];
    }

    /** Newest sample, or {@code def} when empty. */
    public synchronized double last(double def) {
        return size == 0 ? def : buf[head];
    }

    /** Oldest→newest copy. */
    public synchronized double[] toArray() {
        double[] out = new double[size];
        copyInto(out);
        return out;
    }

    /** Oldest→newest copy of at most the {@code n} newest samples. */
    public synchronized double[] tail(int n) {
        int take = Math.min(n, size);
        double[] out = new double[take];
        for (int i = 0; i < take; i++) {
            out[i] = buf[(head - take + 1 + i + buf.length * 2) % buf.length];
        }
        return out;
    }

    public synchronized double avg(double def) {
        if (size == 0) return def;
        double sum = 0;
        for (int i = 0; i < size; i++) sum += buf[(head - size + 1 + i + buf.length * 2) % buf.length];
        return sum / size;
    }

    public synchronized double min(double def) {
        if (size == 0) return def;
        double m = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = buf[(head - size + 1 + i + buf.length * 2) % buf.length];
            if (v < m) m = v;
        }
        return m;
    }

    public synchronized double max(double def) {
        if (size == 0) return def;
        double m = -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = buf[(head - size + 1 + i + buf.length * 2) % buf.length];
            if (v > m) m = v;
        }
        return m;
    }

    /**
     * Nearest-rank percentile over the retained window.
     *
     * @param p 0..1 — {@code 0.95} gives p95. Sorts a copy, so keep this off per-tick paths.
     */
    public synchronized double percentile(double p, double def) {
        if (size == 0) return def;
        double[] sorted = new double[size];
        copyInto(sorted);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * size) - 1;
        return sorted[Math.min(Math.max(idx, 0), size - 1)];
    }

    /** Number of retained samples at or above {@code threshold} — the spike counter. */
    public synchronized int countAtLeast(double threshold) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (buf[(head - size + 1 + i + buf.length * 2) % buf.length] >= threshold) n++;
        }
        return n;
    }

    private void copyInto(double[] out) {
        int start = (head - size + 1 + buf.length * 2) % buf.length;
        int firstRun = Math.min(size, buf.length - start);
        System.arraycopy(buf, start, out, 0, firstRun);
        if (firstRun < size) System.arraycopy(buf, 0, out, firstRun, size - firstRun);
    }
}
