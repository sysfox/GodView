package dev.godview.util;

import java.util.Arrays;

/**
 * Fixed-capacity ring of {@code long}s — the integral twin of {@link DoubleRing}.
 *
 * <p>Used for timestamps (login-attempt windows, spike times, GC pause epochs) and for
 * counters that must not lose precision to {@code double} above 2^53.
 */
public final class LongRing {

    private final long[] buf;
    private int head = -1;
    private int size;
    private long written;

    public LongRing(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.buf = new long[capacity];
    }

    public synchronized void add(long v) {
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
        Arrays.fill(buf, 0L);
    }

    /** @param i 0 is the oldest retained value, {@code size()-1} the newest */
    public synchronized long get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("index " + i + " of " + size);
        return buf[(head - size + 1 + i + buf.length * 2) % buf.length];
    }

    public synchronized long last(long def) {
        return size == 0 ? def : buf[head];
    }

    public synchronized long[] toArray() {
        long[] out = new long[size];
        int start = (head - size + 1 + buf.length * 2) % buf.length;
        int firstRun = Math.min(size, buf.length - start);
        System.arraycopy(buf, start, out, 0, firstRun);
        if (firstRun < size) System.arraycopy(buf, 0, out, firstRun, size - firstRun);
        return out;
    }

    public synchronized long sum() {
        long total = 0;
        for (int i = 0; i < size; i++) total += buf[(head - size + 1 + i + buf.length * 2) % buf.length];
        return total;
    }

    /**
     * How many retained values are {@code >= from}. With a ring of event timestamps this
     * answers "how many in the last N seconds" without scanning a collection —
     * the rate limiter and the login lockout both run on it.
     */
    public synchronized int countSince(long from) {
        int n = 0;
        for (int i = size - 1; i >= 0; i--) {
            if (buf[(head - size + 1 + i + buf.length * 2) % buf.length] >= from) n++;
            else break;   // timestamps are appended in order, so we can stop at the first miss
        }
        return n;
    }
}
