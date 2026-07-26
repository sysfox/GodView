package dev.godview.metrics;

import dev.godview.config.GodViewConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Moves GodView between {@link PerformanceProfile}s as the server's TPS changes.
 *
 * <p>Fed one TPS reading per second. Degradation is deliberately quick and restoration
 * deliberately slow: ten bad seconds are enough to back off, but a full minute of health
 * is required before stepping back up. Combined with the configured gap between
 * {@code degrade.tps-below} and {@code degrade.restore-tps-above}, that gives hysteresis
 * on both value and time — a server hovering at the threshold settles on one profile
 * instead of thrashing between two every few seconds.
 *
 * <p>Steps move one profile at a time, so recovering from {@link PerformanceProfile#LOW}
 * passes through {@code BALANCED} and gets a chance to fall back before going to
 * {@code FULL}.
 *
 * <p>When {@code performance.profile} names a fixed profile the governor is inert:
 * {@link #sample} does nothing and {@link #current()} always returns that profile.
 */
public final class PerformanceGovernor {

    /** Consecutive seconds under the degrade threshold before stepping down. */
    private static final int DEGRADE_SECONDS = 10;
    /** Consecutive seconds over the restore threshold before stepping up. */
    private static final int RESTORE_SECONDS = 60;

    private final boolean auto;
    private final double degradeBelow;
    private final double restoreAbove;
    private final List<Consumer<PerformanceProfile>> listeners = new CopyOnWriteArrayList<>();

    private volatile PerformanceProfile current;
    private volatile long lastChangeMillis;
    private volatile String lastReason = "initial";
    private int secondsBelow;
    private int secondsAbove;

    public PerformanceGovernor(GodViewConfig.Performance config) {
        this.auto = config.autoProfile();
        this.degradeBelow = config.degradeTpsBelow();
        this.restoreAbove = config.restoreTpsAbove();
        // Auto mode starts optimistic: assume the server is healthy and let the first bad
        // sample prove otherwise, rather than making a cold-start server look degraded.
        this.current = auto ? PerformanceProfile.FULL : PerformanceProfile.parse(config.profile());
    }

    public PerformanceProfile current() {
        return current;
    }

    public boolean isAuto() {
        return auto;
    }

    /** Epoch millis of the last profile change, or 0 if it has never changed. */
    public long lastChangeMillis() {
        return lastChangeMillis;
    }

    /** Human-readable explanation of the current profile, for the panel and {@code /godview status}. */
    public String lastReason() {
        return lastReason;
    }

    /** Notified on the sampling thread whenever the profile actually changes. */
    public void onChange(Consumer<PerformanceProfile> listener) {
        listeners.add(listener);
    }

    /**
     * Feeds one TPS reading. Call once per second from the metrics sampler.
     *
     * @param tps1m the 1-minute rolling TPS — smoothed enough that a single slow tick
     *              cannot drag the whole plugin into {@link PerformanceProfile#LOW}
     */
    public void sample(double tps1m) {
        if (!auto) return;

        if (tps1m < degradeBelow) {
            secondsBelow++;
            secondsAbove = 0;
        } else if (tps1m > restoreAbove) {
            secondsAbove++;
            secondsBelow = 0;
        } else {
            // Between the thresholds: neither counter advances, so the profile holds.
            secondsBelow = 0;
            secondsAbove = 0;
            return;
        }

        if (secondsBelow >= DEGRADE_SECONDS) {
            secondsBelow = 0;
            apply(current.degraded(), String.format(
                    "TPS below %.1f for %ds", degradeBelow, DEGRADE_SECONDS));
        } else if (secondsAbove >= RESTORE_SECONDS) {
            secondsAbove = 0;
            apply(current.restored(), String.format(
                    "TPS above %.1f for %ds", restoreAbove, RESTORE_SECONDS));
        }
    }

    /** Forces a profile, leaving auto mode's counters reset. Used by {@code /godview} and tests. */
    public void force(PerformanceProfile profile, String reason) {
        secondsBelow = 0;
        secondsAbove = 0;
        apply(profile, reason);
    }

    private void apply(PerformanceProfile next, String reason) {
        if (next == current) return;
        current = next;
        lastReason = reason;
        lastChangeMillis = System.currentTimeMillis();
        for (Consumer<PerformanceProfile> listener : listeners) {
            try {
                listener.accept(next);
            } catch (Throwable ignored) {
                // A listener that cannot reschedule itself must not stop the others.
            }
        }
    }
}
