package dev.godview.web.api;

import dev.godview.metrics.PerformanceProfile;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the live updates the panel is built on.
 *
 * <p>Two frames rather than one, because they have very different costs. The
 * {@code pulse} is a few hundred bytes — the numbers on the header bar — and goes out on
 * every interval. The full {@code snapshot} carries every world and every player, which on
 * a busy server is tens of kilobytes, so it goes out every few seconds instead. Sending
 * the full document at pulse rate would push megabytes a minute at each open panel for
 * data that mostly has not changed.
 *
 * <p>Nothing is built while nobody is subscribed. That, and the interval scaling with the
 * performance profile, is what keeps an open panel from being a cost the server notices.
 */
public final class SnapshotPusher {

    /** Roughly how often the full document is sent, in milliseconds. */
    private static final long FULL_SNAPSHOT_INTERVAL_MS = 5_000L;

    private final ApiContext ctx;
    private BukkitTask task;
    private int scheduledInterval = -1;
    private long lastFullSnapshot;

    public SnapshotPusher(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        reschedule();
        // The profile changes the interval, so the task has to be rebuilt when it moves.
        ctx.metrics().governor().onChange(profile -> reschedule());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        scheduledInterval = -1;
    }

    private void reschedule() {
        PerformanceProfile profile = ctx.metrics().profile();
        int interval = profile.scaleInterval(ctx.cfg().performance.snapshotIntervalTicks());
        if (interval == scheduledInterval && task != null) return;

        if (task != null) task.cancel();
        scheduledInterval = interval;
        task = Bukkit.getScheduler().runTaskTimer(ctx.plugin(), this::push, interval, interval);
    }

    private void push() {
        EventStream stream = ctx.stream();
        if (stream.subscriberCount() == 0) return;

        stream.publish("pulse", Snapshots.pulse(ctx));

        long now = System.currentTimeMillis();
        if (now - lastFullSnapshot >= FULL_SNAPSHOT_INTERVAL_MS) {
            lastFullSnapshot = now;
            stream.publish("snapshot", Snapshots.overview(ctx));
        }
    }

    /** Sends a full snapshot at once — used when something structural changes. */
    public void pushNow() {
        if (ctx.stream().subscriberCount() == 0) return;
        lastFullSnapshot = System.currentTimeMillis();
        ctx.stream().publish("snapshot", Snapshots.overview(ctx));
    }
}
