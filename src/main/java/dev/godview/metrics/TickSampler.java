package dev.godview.metrics;

import dev.godview.util.DoubleRing;

import java.util.List;
import java.util.function.Consumer;

/**
 * Times the main thread, one tick at a time.
 *
 * <p>Runs as a 1-tick repeating task, so each invocation is separated from the last by
 * exactly one full tick of server work. The gap between two invocations is therefore the
 * <em>tick period</em>: 50 ms on a healthy server, and however long the tick actually took
 * once the server falls behind. That is the number that matters for lag detection — a
 * period of 300 ms means 250 ms of overrun no matter what was responsible.
 *
 * <p>Deliberately <em>not</em> the same thing as MSPT. Paper measures MSPT as time spent
 * working, excluding the sleep at the end of a spare tick, so on an idle server it reads
 * ~2 ms while the period reads 50 ms. GodView reports both, from their proper sources:
 * MSPT from {@code Bukkit.getAverageTickTime()}, period from here.
 *
 * <p>Cost per tick is one {@code nanoTime}, one array store and a comparison — under
 * 0.01 ms, which is why {@code performance.tick-sampler} stays on even in the
 * {@link PerformanceProfile#LOW} profile.
 *
 * <p>Stacks come from {@link SpikeWatchdog}, which captures them while the tick is still
 * running. This class only joins that evidence to the exact duration it measures once the
 * tick finally ends.
 */
public final class TickSampler implements Runnable {

    /** Nominal tick period. Anything above this is the server running behind. */
    public static final double NOMINAL_PERIOD_MS = 50.0;

    private final DoubleRing periods;
    private final double spikeThresholdMs;
    private final Consumer<Spike> sink;

    /** Read by the watchdog thread to spot a tick that has not finished yet. */
    private volatile long tickStartNanos;
    private volatile Thread mainThread;
    private volatile Pending pending;
    private volatile long ticks;

    private long lastTickNanos;

    /**
     * @param historyTicks how many recent periods to retain — 200 is ten seconds
     * @param sink         receives every spike, on the main thread
     */
    public TickSampler(int historyTicks, double spikeThresholdMs, Consumer<Spike> sink) {
        this.periods = new DoubleRing(historyTicks);
        this.spikeThresholdMs = spikeThresholdMs;
        this.sink = sink;
    }

    @Override
    public void run() {
        long now = System.nanoTime();

        if (mainThread == null) {
            // First invocation: a scheduled task runs on the main thread by definition,
            // so this is how the watchdog learns which thread to sample.
            mainThread = Thread.currentThread();
        }

        if (lastTickNanos != 0L) {
            double periodMs = (now - lastTickNanos) / 1_000_000d;
            periods.add(periodMs);
            if (periodMs >= spikeThresholdMs) emit(periodMs);
        }

        lastTickNanos = now;
        tickStartNanos = now;
        ticks++;
    }

    private void emit(double periodMs) {
        // The watchdog may have grabbed a stack partway through the tick that just ended.
        // At this point lastTickNanos still holds that tick's start, which is exactly what
        // the watchdog captured against. A capture carrying any other start belongs to an
        // earlier spike and is dropped rather than misattributed to this one.
        Pending captured = pending;
        pending = null;
        boolean matches = captured != null && captured.tickStart == lastTickNanos;

        sink.accept(new Spike(
                System.currentTimeMillis(),
                periodMs,
                ticks,
                matches ? captured.blame : SpikeAttributor.UNKNOWN,
                matches ? captured.blameFrame : "",
                matches ? captured.frames : List.of()));
    }

    /**
     * Hands over a stack captured mid-tick. Called from the watchdog thread.
     *
     * @param tickStart the value of {@link #tickStartNanos()} the capture was taken against
     */
    void offerCapture(long tickStart, String blame, String blameFrame, List<String> frames) {
        pending = new Pending(tickStart, blame, blameFrame, frames);
    }

    /** {@code System.nanoTime()} at the start of the tick in progress, or 0 before the first. */
    public long tickStartNanos() {
        return tickStartNanos;
    }

    /** The server's main thread, or null until the first tick has run. */
    public Thread mainThread() {
        return mainThread;
    }

    /** Ticks observed since the sampler started. */
    public long ticks() {
        return ticks;
    }

    /** Recent tick periods in milliseconds — see the class note on why this is not MSPT. */
    public DoubleRing periods() {
        return periods;
    }

    /** Worst tick period in the retained window, or {@link #NOMINAL_PERIOD_MS} when empty. */
    public double worstPeriodMs() {
        return periods.max(NOMINAL_PERIOD_MS);
    }

    /** 95th-percentile tick period over the retained window. */
    public double p95PeriodMs() {
        return periods.percentile(0.95, NOMINAL_PERIOD_MS);
    }

    /**
     * Fraction of the retained window spent behind schedule, 0..1. A useful headline
     * number: 0.05 means one tick in twenty overran, which users feel as stutter even
     * though the TPS average still looks fine.
     */
    public double overrunRatio() {
        int size = periods.size();
        if (size == 0) return 0d;
        return periods.countAtLeast(NOMINAL_PERIOD_MS * 1.5) / (double) size;
    }

    private record Pending(long tickStart, String blame, String blameFrame, List<String> frames) {
    }
}
