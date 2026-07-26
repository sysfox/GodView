package dev.godview.metrics;

import dev.godview.util.LongRing;

import java.util.List;
import java.util.function.Supplier;

/**
 * Catches the main thread in the act.
 *
 * <p>Knowing a tick took 800 ms is nearly useless on its own; knowing <em>what the server
 * was doing</em> during those 800 ms is the whole point. By the time a slow tick ends the
 * evidence is gone, so this runs on its own daemon thread, watches
 * {@link TickSampler#tickStartNanos()}, and the moment a tick has been running longer than
 * the threshold it takes a stack trace of the main thread while it is still stuck.
 *
 * <p>The capture is attributed here too, on this thread, so the main thread only ever
 * assembles an already-finished record.
 *
 * <p>Two costs are worth being honest about. {@link Thread#getStackTrace()} on another
 * thread needs that thread to reach a safepoint, so a tick stuck in a tight counted loop
 * can delay the capture until the loop ends — the watchdog blocks, never the server.
 * And the capture itself is not free, which is why it is rate-limited by
 * {@code performance.spike-max-per-minute}, scaled down further in the
 * {@link PerformanceProfile#LOW} profile.
 */
public final class SpikeWatchdog {

    /**
     * How often to check whether the current tick has overrun. Fine enough to catch a
     * stall near its start, coarse enough that the thread costs nothing measurable.
     */
    private static final long POLL_MS = 20L;

    private final TickSampler sampler;
    private final SpikeAttributor attributor;
    private final double thresholdMs;
    private final Supplier<Integer> maxPerMinute;
    private final LongRing captureTimes = new LongRing(600);

    private volatile Thread thread;
    private volatile boolean running;
    private long lastCapturedTickStart;
    private volatile long capturesTaken;
    private volatile long capturesSkipped;

    /**
     * @param maxPerMinute read on every capture so a profile change takes effect at once
     */
    public SpikeWatchdog(TickSampler sampler, SpikeAttributor attributor, double thresholdMs,
                         Supplier<Integer> maxPerMinute) {
        this.sampler = sampler;
        this.attributor = attributor;
        this.thresholdMs = thresholdMs;
        this.maxPerMinute = maxPerMinute;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "GodView-SpikeWatchdog");
        t.setDaemon(true);
        // Below normal: this thread must never compete with the main thread for a core on
        // a two-core VPS, which is exactly the machine most likely to be spiking.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    public boolean isRunning() {
        return running;
    }

    /** Stacks actually captured since start. */
    public long capturesTaken() {
        return capturesTaken;
    }

    /** Spikes seen while over the per-minute ceiling, so the panel can say so. */
    public long capturesSkipped() {
        return capturesSkipped;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(POLL_MS);
                poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
                // Diagnostics must never take the server down. Keep watching.
            }
        }
    }

    private void poll() {
        Thread main = sampler.mainThread();
        long tickStart = sampler.tickStartNanos();
        if (main == null || tickStart == 0L) return;

        double elapsedMs = (System.nanoTime() - tickStart) / 1_000_000d;
        if (elapsedMs < thresholdMs) return;

        // One capture per tick, however long the tick runs.
        if (tickStart == lastCapturedTickStart) return;
        lastCapturedTickStart = tickStart;

        long now = System.currentTimeMillis();
        if (captureTimes.countSince(now - 60_000L) >= maxPerMinute.get()) {
            capturesSkipped++;
            return;
        }

        StackTraceElement[] stack = main.getStackTrace();
        if (stack.length == 0) return;   // thread was not at a safepoint we could read

        // Re-check that the tick we captured is still the one in flight. If it ended while
        // we were waiting for a safepoint, the stack shows the next tick's work and would
        // blame the wrong plugin entirely.
        if (sampler.tickStartNanos() != tickStart) return;

        captureTimes.add(now);
        capturesTaken++;

        SpikeAttributor.Blame blame = attributor.blame(stack);
        List<String> frames = attributor.trim(stack);
        sampler.offerCapture(tickStart, blame.plugin(), blame.frame(), frames);
    }
}
