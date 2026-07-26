package dev.godview.metrics;

import java.util.Locale;

/**
 * How much work GodView is allowed to do per tick.
 *
 * <p>A profile never replaces the numbers in {@code config.yml} — it scales them. The
 * configured values are the {@link #BALANCED} baseline, so an admin who sets
 * {@code snapshot-interval-ticks: 20} gets 10 under {@link #FULL} and 40 under
 * {@link #LOW}, exactly as the config comment promises. Anyone who has tuned their
 * numbers keeps their ratios.
 *
 * <p>Note what {@link #LOW} does <em>not</em> turn off: tick sampling and spike stack
 * capture. A struggling server is precisely when the operator needs to know which plugin
 * is eating the tick, so the capture rate is halved rather than disabled.
 */
public enum PerformanceProfile {

    /** Server has headroom — sample twice as often, render map tiles twice as fast. */
    FULL(0.5, 2.0, true, 1.0),

    /** The configured values, used as written. */
    BALANCED(1.0, 1.0, true, 1.0),

    /** Server is behind — halve the sampling rate and stop walking chunks for block entities. */
    LOW(2.0, 0.5, false, 0.5);

    /** Multiplies configured tick intervals. Larger means less frequent. */
    public final double intervalScale;
    /** Multiplies per-tick work budgets, such as map chunks per tick. */
    public final double budgetScale;
    /** Whether world stats walk loaded chunks to count block entities — the expensive part. */
    public final boolean tileEntityStats;
    /** Multiplies the configured spike capture ceiling. */
    public final double spikeCaptureScale;

    PerformanceProfile(double intervalScale, double budgetScale, boolean tileEntityStats,
                       double spikeCaptureScale) {
        this.intervalScale = intervalScale;
        this.budgetScale = budgetScale;
        this.tileEntityStats = tileEntityStats;
        this.spikeCaptureScale = spikeCaptureScale;
    }

    /** Applies {@link #intervalScale} to a configured tick interval, never returning 0. */
    public int scaleInterval(int baseTicks) {
        return Math.max(1, (int) Math.round(baseTicks * intervalScale));
    }

    /** Applies {@link #budgetScale} to a configured per-tick budget, never returning 0. */
    public int scaleBudget(int baseBudget) {
        return Math.max(1, (int) Math.round(baseBudget * budgetScale));
    }

    /** Applies {@link #spikeCaptureScale} to a configured per-minute ceiling. */
    public int scaleCaptureRate(int basePerMinute) {
        return Math.max(1, (int) Math.round(basePerMinute * spikeCaptureScale));
    }

    /** One step toward {@link #LOW}, or {@code this} when already there. */
    public PerformanceProfile degraded() {
        return switch (this) {
            case FULL -> BALANCED;
            case BALANCED, LOW -> LOW;
        };
    }

    /** One step toward {@link #FULL}, or {@code this} when already there. */
    public PerformanceProfile restored() {
        return switch (this) {
            case LOW -> BALANCED;
            case BALANCED, FULL -> FULL;
        };
    }

    /** Parses a {@code performance.profile} value; anything unrecognised gives {@link #BALANCED}. */
    public static PerformanceProfile parse(String name) {
        if (name == null) return BALANCED;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "low" -> LOW;
            case "full" -> FULL;
            default -> BALANCED;
        };
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }
}
