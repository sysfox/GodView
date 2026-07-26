package dev.godview.metrics;

import dev.godview.util.DoubleRing;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reports garbage collection pauses without polling for them.
 *
 * <p>A 900 ms full GC and a 900 ms plugin stall look identical in the tick timings but
 * need completely different fixes, so GC gets its own signal. HotSpot emits a JMX
 * notification per collection carrying the exact pause, which costs nothing until a
 * collection actually happens — far better than sampling {@code getCollectionTime()} on a
 * timer and inferring pauses from deltas.
 *
 * <p>Everything here is read through {@link CompositeData} rather than
 * {@code com.sun.management.GarbageCollectionNotificationInfo}. The data is identical, but
 * the typed class lives outside {@code java.base} and is absent on some JVMs; going
 * through the open type keeps this compiling and running everywhere.
 *
 * <p>JVMs that emit no notifications at all (some OpenJ9 configurations) fall back to
 * {@link #pollDelta()}, which the metrics sampler calls once a second. That yields totals
 * rather than individual pauses — worse, but not nothing.
 */
public final class GcMonitor {

    /** The notification type HotSpot publishes for every collection. */
    private static final String GC_NOTIFICATION = "com.sun.management.gc.notification";

    private final Consumer<GcEvent> sink;
    private final DoubleRing recentPauses = new DoubleRing(120);
    private final List<Registration> registrations = new ArrayList<>();

    private volatile boolean notificationsWorking;
    private volatile long totalCount;
    private volatile long totalPauseMs;
    private long lastPolledCount;
    private long lastPolledTimeMs;

    public GcMonitor(Consumer<GcEvent> sink) {
        this.sink = sink;
    }

    /**
     * One collection.
     *
     * @param at         epoch millis the collection finished
     * @param name       collector name, e.g. {@code "G1 Young Generation"}
     * @param action     {@code "end of minor GC"} / {@code "end of major GC"}
     * @param cause      why it ran, e.g. {@code "G1 Evacuation Pause"} or {@code "System.gc()"}
     * @param durationMs pause length
     */
    public record GcEvent(long at, String name, String action, String cause, long durationMs) {

        /** Major collections are the ones that stop the world for long enough to notice. */
        public boolean major() {
            return action != null && action.toLowerCase().contains("major");
        }
    }

    public void start() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(bean instanceof NotificationEmitter emitter)) continue;
            NotificationListener listener = (notification, handback) -> {
                if (!GC_NOTIFICATION.equals(notification.getType())) return;
                handle(notification.getUserData());
            };
            try {
                emitter.addNotificationListener(listener, null, null);
                registrations.add(new Registration(emitter, listener));
                notificationsWorking = true;
            } catch (Throwable ignored) {
                // This collector will be covered by pollDelta() instead.
            }
        }
        // Seed the polling baseline either way, so a fallback mid-run does not report a
        // single enormous pause made of everything that happened before it started.
        pollDelta();
    }

    public void stop() {
        for (Registration r : registrations) {
            try {
                r.emitter.removeNotificationListener(r.listener);
            } catch (Throwable ignored) {
                // Already gone, or the bean rejected it — nothing useful to do on shutdown.
            }
        }
        registrations.clear();
        notificationsWorking = false;
    }

    private void handle(Object userData) {
        if (!(userData instanceof CompositeData cd)) return;
        try {
            Object rawInfo = cd.get("gcInfo");
            if (!(rawInfo instanceof CompositeData info)) return;
            long durationMs = ((Number) info.get("duration")).longValue();

            totalCount++;
            totalPauseMs += durationMs;
            recentPauses.add(durationMs);

            sink.accept(new GcEvent(
                    System.currentTimeMillis(),
                    String.valueOf(cd.get("gcName")),
                    String.valueOf(cd.get("gcAction")),
                    String.valueOf(cd.get("gcCause")),
                    durationMs));
        } catch (Throwable ignored) {
            // An unexpected notification shape is not worth a stack trace in the console.
        }
    }

    /**
     * Fallback accounting for JVMs that do not emit notifications. Call once a second;
     * it is a no-op once notifications are known to work.
     *
     * @return milliseconds of GC since the previous call, or 0
     */
    public long pollDelta() {
        long count = 0;
        long timeMs = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) timeMs += t;
        }

        long deltaCount = count - lastPolledCount;
        long deltaTime = timeMs - lastPolledTimeMs;
        lastPolledCount = count;
        lastPolledTimeMs = timeMs;

        if (notificationsWorking || deltaCount <= 0 || deltaTime <= 0) return Math.max(0, deltaTime);

        totalCount += deltaCount;
        totalPauseMs += deltaTime;
        // Without per-collection data the best we can say is the mean over this second.
        double mean = deltaTime / (double) deltaCount;
        recentPauses.add(mean);
        sink.accept(new GcEvent(System.currentTimeMillis(), "unknown",
                deltaCount > 1 ? "end of " + deltaCount + " collections" : "end of GC",
                "polled", deltaTime));
        return deltaTime;
    }

    /** True when at least one collector accepted a notification listener. */
    public boolean usingNotifications() {
        return notificationsWorking;
    }

    /** Collections observed since start. */
    public long totalCount() {
        return totalCount;
    }

    /** Total pause milliseconds observed since start. */
    public long totalPauseMs() {
        return totalPauseMs;
    }

    /** Longest pause in the recent window, or 0. */
    public double worstRecentPauseMs() {
        return recentPauses.max(0d);
    }

    /** Recent pauses, newest last. */
    public DoubleRing recentPauses() {
        return recentPauses;
    }

    private record Registration(NotificationEmitter emitter, NotificationListener listener) {
    }
}
