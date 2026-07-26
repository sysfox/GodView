package dev.godview.alert;

import dev.godview.config.GodViewConfig;

import java.util.Locale;

/**
 * One configured rule, plus the state it needs to decide whether to fire.
 *
 * <p>Two behaviours make the difference between an alerting system an operator keeps and
 * one they mute inside a week.
 *
 * <p><b>Sustain.</b> A threshold rule only fires once the condition has held for
 * {@code sustain-seconds}. TPS dips below 15 for a single sample every time a world saves;
 * alerting on that teaches people to ignore alerts. The counter resets the moment the
 * condition clears, so only a genuinely sustained problem gets through.
 *
 * <p><b>Cooldown.</b> After firing, a rule stays quiet for its cooldown even if the
 * condition persists — which it usually does, because the server is still broken. The
 * point of the alert is to tell somebody once, not to keep telling them every second while
 * they are already looking at it.
 *
 * <p>Not thread-safe by itself; {@link AlertEngine} owns the evaluation and serializes it.
 */
public final class AlertRule {

    /** Rule kinds that watch a number over time and honour {@code sustain-seconds}. */
    public enum Kind {
        TPS_BELOW, MSPT_ABOVE, MEMORY_ABOVE, PLAYER_COUNT_ABOVE, PLAYER_COUNT_BELOW,
        TICK_SPIKE, GC_PAUSE,
        PLAYER_JOIN, PLAYER_QUIT, FIRST_JOIN, PLAYER_DEATH,
        CHAT_MATCH, COMMAND_MATCH, CONSOLE_ERROR, LOGIN_FAILED,
        UNKNOWN;

        /** True for rules driven by the metrics sampler rather than by a discrete event. */
        public boolean isThreshold() {
            return switch (this) {
                case TPS_BELOW, MSPT_ABOVE, MEMORY_ABOVE, PLAYER_COUNT_ABOVE, PLAYER_COUNT_BELOW ->
                        true;
                default -> false;
            };
        }

        static Kind parse(String type) {
            if (type == null) return UNKNOWN;
            try {
                return valueOf(type.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    private final GodViewConfig.RuleDef def;
    private final Kind kind;
    private final long cooldownMillis;

    /** When the condition first became true, or 0 while it is false. */
    private long conditionSince;
    private long lastFiredAt;
    private long timesFired;
    private long timesSuppressed;

    public AlertRule(GodViewConfig.RuleDef def, int defaultCooldownSeconds) {
        this.def = def;
        this.kind = Kind.parse(def.type());
        this.cooldownMillis = def.effectiveCooldown(defaultCooldownSeconds) * 1000L;
    }

    public String id() {
        return def.id();
    }

    public Kind kind() {
        return kind;
    }

    public GodViewConfig.RuleDef def() {
        return def;
    }

    public boolean recognised() {
        return kind != Kind.UNKNOWN;
    }

    public long timesFired() {
        return timesFired;
    }

    public long timesSuppressed() {
        return timesSuppressed;
    }

    public long lastFiredAt() {
        return lastFiredAt;
    }

    /** True while the rule is inside its cooldown window. */
    public boolean cooling(long now) {
        return lastFiredAt != 0 && now - lastFiredAt < cooldownMillis;
    }

    /** Seconds until this rule may fire again, or 0. */
    public long cooldownRemaining(long now) {
        if (!cooling(now)) return 0;
        return Math.max(1, (cooldownMillis - (now - lastFiredAt) + 999) / 1000);
    }

    /**
     * Feeds a threshold rule one sample.
     *
     * @return true when the rule should fire now
     */
    public boolean sample(long now, double value) {
        boolean breached = breached(value);

        if (!breached) {
            conditionSince = 0;
            return false;
        }
        if (conditionSince == 0) conditionSince = now;

        long heldMillis = now - conditionSince;
        if (heldMillis < def.sustainSeconds() * 1000L) return false;

        if (cooling(now)) {
            timesSuppressed++;
            return false;
        }
        return true;
    }

    /**
     * Offers a discrete event to an event rule.
     *
     * @return true when the rule should fire now
     */
    public boolean trigger(long now) {
        if (cooling(now)) {
            timesSuppressed++;
            return false;
        }
        return true;
    }

    /**
     * Records that an alert was actually sent, starting the cooldown.
     *
     * <p>The sustain window is reset, but it starts accruing again on the very next
     * breaching sample -- including during the cooldown. That is deliberate: if TPS has
     * sat at 10 for the whole five minutes of a cooldown, the operator should be reminded
     * the moment it lapses, not made to wait another sustain period for a problem that
     * never went away. A condition that recovered and came back late in the cooldown still
     * has to sustain properly, which is the case the reset exists for.
     */
    public void fired(long now) {
        lastFiredAt = now;
        timesFired++;
        conditionSince = 0;
    }

    private boolean breached(double value) {
        return switch (kind) {
            case TPS_BELOW, PLAYER_COUNT_BELOW -> value < def.threshold();
            case MSPT_ABOVE, MEMORY_ABOVE, PLAYER_COUNT_ABOVE -> value > def.threshold();
            default -> false;
        };
    }

    /** How long the condition has held, in seconds. Zero when it is not currently true. */
    public long heldSeconds(long now) {
        return conditionSince == 0 ? 0 : (now - conditionSince) / 1000;
    }
}
