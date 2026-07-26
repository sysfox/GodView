package dev.godview.integration;

import dev.godview.compat.Compat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Detects spark and points at it.
 *
 * <p>Deliberately shallow. spark is the best profiler in the ecosystem and GodView is not
 * competing with it — the two answer different questions. GodView tells an operator
 * <em>that</em> a tick took 800 ms and which plugin's frame was on top; spark tells them
 * where the time went across thousands of samples with a proper call tree.
 *
 * <p>So this integration reports that spark is available and surfaces the command to run,
 * rather than wrapping a profiler API that would produce a worse version of spark's own
 * output. The panel's spike view links to it.
 *
 * <p>spark's own CPU and TPS numbers are read where they are cheap, purely as a
 * cross-check: if GodView and spark disagree about TPS, something is wrong with one of
 * them and an operator should know.
 */
public final class SparkIntegration implements Integration {

    private static final String SPARK_CLASS = "me.lucko.spark.api.Spark";

    @Override
    public String id() {
        return "spark";
    }

    @Override
    public String displayName() {
        return "spark";
    }

    @Override
    public boolean available() {
        Plugin plugin = plugin();
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public String version() {
        Plugin plugin = plugin();
        return plugin == null ? "" : Compat.pluginVersion(plugin);
    }

    @Override
    public String problem() {
        Plugin plugin = plugin();
        if (plugin == null) return "";
        if (!plugin.isEnabled()) return "spark is installed but disabled";
        return api() == null ? "spark is running but has not registered its API service" : "";
    }

    private Plugin plugin() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("spark");
            return plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("Spark");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** spark's API service, or null. Only used for the cross-check readings. */
    private Object api() {
        try {
            Class<?> sparkClass = Reflect.clazz(SPARK_CLASS);
            if (sparkClass == null) return null;
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(sparkClass);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The command an operator should run for a real profile. */
    public String profilerCommand() {
        return "/spark profiler start --timeout 60";
    }

    /**
     * spark's own 1-minute TPS, or {@link Double#NaN}.
     *
     * <p>A cross-check, not a data source: GodView samples TPS itself. Two independent
     * numbers that disagree are worth surfacing.
     */
    public double tps() {
        Object api = api();
        if (api == null) return Double.NaN;
        try {
            Object tps = Reflect.call(api, "tps", new Class<?>[0]);
            if (tps == null) return Double.NaN;
            // DoubleStatistic#poll(WindowType) — reached generically so a signature change
            // costs the cross-check and nothing else.
            Class<?> windowType = Reflect.clazz("me.lucko.spark.api.statistic.StatisticWindow$TicksPerSecond");
            if (windowType == null || !windowType.isEnum()) return Double.NaN;
            Object minuteWindow = null;
            for (Object constant : windowType.getEnumConstants()) {
                if ("MINUTES_1".equals(((Enum<?>) constant).name())) minuteWindow = constant;
            }
            if (minuteWindow == null) return Double.NaN;
            Object value = Reflect.call(tps, "poll",
                    new Class<?>[]{Reflect.clazz("me.lucko.spark.api.statistic.StatisticWindow")},
                    minuteWindow);
            return Reflect.number(value, Double.NaN);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }
}
