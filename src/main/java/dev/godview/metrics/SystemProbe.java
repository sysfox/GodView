package dev.godview.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;

/**
 * JVM and host readings that Bukkit does not expose: heap, CPU, uptime.
 *
 * <p>CPU load is only available through {@code com.sun.management.OperatingSystemMXBean},
 * which not every JVM ships. The methods are resolved once against that <em>interface</em>
 * rather than the bean's implementation class — the implementation is a non-exported
 * internal type, and reflecting on it directly fails with an access error on a modern JDK
 * even though the method is public.
 *
 * <p>Every reading is safe from any thread and returns a sentinel instead of throwing when
 * the platform will not answer, so a JVM without CPU accounting simply shows no CPU chart.
 */
public final class SystemProbe {

    /** Returned by the CPU readings when the platform does not provide them. */
    public static final double UNAVAILABLE = -1d;

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean RUNTIME = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();

    private static final Method M_PROCESS_CPU;
    private static final Method M_SYSTEM_CPU;

    static {
        Method processCpu = null;
        Method systemCpu = null;
        try {
            Class<?> sunOs = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunOs.isInstance(OS)) {
                processCpu = method(sunOs, "getProcessCpuLoad");
                // Renamed in Java 14; the old name is still there but deprecated.
                systemCpu = method(sunOs, "getCpuLoad");
                if (systemCpu == null) systemCpu = method(sunOs, "getSystemCpuLoad");
            }
        } catch (Throwable ignored) {
            // No com.sun management extensions — CPU charts stay empty.
        }
        M_PROCESS_CPU = processCpu;
        M_SYSTEM_CPU = systemCpu;
    }

    private SystemProbe() {
    }

    /** Bytes of heap in use. */
    public static long heapUsed() {
        return MEMORY.getHeapMemoryUsage().getUsed();
    }

    /** Bytes of heap the JVM has actually reserved from the OS. */
    public static long heapCommitted() {
        return MEMORY.getHeapMemoryUsage().getCommitted();
    }

    /**
     * Bytes of heap the JVM may grow to, i.e. {@code -Xmx}.
     *
     * <p>Falls back to the committed size when the max is undefined, so callers dividing
     * by it never produce a negative percentage.
     */
    public static long heapMax() {
        MemoryUsage usage = MEMORY.getHeapMemoryUsage();
        return usage.getMax() > 0 ? usage.getMax() : usage.getCommitted();
    }

    /** Heap in use as a percentage of {@link #heapMax()}, 0..100. */
    public static double heapPercent() {
        long max = heapMax();
        return max <= 0 ? 0d : heapUsed() * 100d / max;
    }

    /** Bytes of non-heap memory — metaspace, code cache, and friends. */
    public static long nonHeapUsed() {
        return MEMORY.getNonHeapMemoryUsage().getUsed();
    }

    /** Milliseconds since the JVM started. */
    public static long uptimeMs() {
        return RUNTIME.getUptime();
    }

    public static int availableProcessors() {
        return OS.getAvailableProcessors();
    }

    /**
     * Load average over the last minute as reported by the OS, or {@link #UNAVAILABLE}.
     * Always unavailable on Windows.
     */
    public static double loadAverage() {
        double load = OS.getSystemLoadAverage();
        return load < 0 ? UNAVAILABLE : load;
    }

    /** This JVM's share of total CPU, 0..1, or {@link #UNAVAILABLE}. */
    public static double processCpu() {
        return cpu(M_PROCESS_CPU);
    }

    /** Whole-host CPU usage, 0..1, or {@link #UNAVAILABLE}. */
    public static double systemCpu() {
        return cpu(M_SYSTEM_CPU);
    }

    private static double cpu(Method m) {
        if (m == null) return UNAVAILABLE;
        try {
            double value = ((Number) m.invoke(OS)).doubleValue();
            // The first call after startup legitimately returns a negative "not yet known".
            return value < 0 ? UNAVAILABLE : value;
        } catch (Throwable ignored) {
            return UNAVAILABLE;
        }
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
