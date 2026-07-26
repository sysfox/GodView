package dev.godview.web.api;

import dev.godview.compat.Compat;
import dev.godview.metrics.MetricsService;
import dev.godview.metrics.TickSampler;
import dev.godview.metrics.PlayerSnapshot;
import dev.godview.metrics.WorldStats;
import dev.godview.storage.Storage;
import dev.godview.util.JsonWriter;
import org.bukkit.Bukkit;

/**
 * Builds the one payload the dashboard is drawn from.
 *
 * <p>The same document is returned by {@code GET /api/overview} and pushed over SSE as a
 * {@code snapshot} event, so a page load and a live update never disagree about shape.
 *
 * <p><b>No IP addresses here, ever.</b> One SSE frame is written to every subscriber
 * regardless of their role, so anything role-dependent cannot go in it — there would be no
 * way to redact per recipient. Player addresses are served only by
 * {@code GET /api/players}, which can check the caller's permission.
 */
public final class Snapshots {

    /** Ticks sent with a full snapshot — ten seconds, matching the sampler's own history. */
    private static final int RIBBON_TICKS = 200;

    private Snapshots() {
    }

    /** The full dashboard payload. Safe to call from any thread. */
    public static String overview(ApiContext ctx) {
        MetricsService metrics = ctx.metrics();
        JsonWriter w = new JsonWriter(4096);

        w.obj();
        w.field("at", System.currentTimeMillis());

        w.name("server").obj()
                .field("brand", Compat.BRAND)
                .field("version", Compat.VERSION)
                .field("folia", Compat.FOLIA)
                .field("java", System.getProperty("java.version", "?"))
                .field("motd", Compat.motd())
                .field("maxPlayers", safeMaxPlayers())
                .field("pluginVersion", Compat.pluginVersion(ctx.plugin()))
                .end();

        w.name("sample");
        metrics.latest().writeJson(w);

        w.name("worlds").arr();
        for (WorldStats world : metrics.worlds()) world.writeJson(w);
        w.end();

        // Deliberately without addresses — see the class note.
        w.name("players").arr();
        for (PlayerSnapshot player : metrics.players()) player.writeJson(w, false);
        w.end();

        // A wider window than a pulse carries, so a freshly opened panel draws a full
        // ribbon instead of filling in one second at a time.
        writeTicks(w, metrics, RIBBON_TICKS);

        w.name("performance").obj()
                .field("profile", metrics.profile().lower())
                .field("auto", metrics.governor().isAuto())
                .field("reason", metrics.governor().lastReason())
                .field("changedAt", metrics.governor().lastChangeMillis())
                .field("suspended", metrics.suspended())
                .field("viewers", metrics.viewers())
                .end();

        w.name("storage");
        Storage storage = ctx.store();
        if (storage != null) {
            storage.stats().writeJson(w);
        } else {
            w.nul();
        }

        w.end();
        return w.done();
    }

    /** A compact frame for the ticker — everything that changes every second, nothing else. */
    public static String pulse(ApiContext ctx) {
        MetricsService metrics = ctx.metrics();
        MetricsService.Sample sample = metrics.latest();

        JsonWriter w = new JsonWriter(768);
        w.obj()
                .field("at", sample.at())
                .field("tps1", sample.tps1())
                .field("mspt", sample.mspt())
                .field("heapUsed", sample.heapUsed())
                .field("heapMax", sample.heapMax())
                .field("heapPercent", sample.heapPercent())
                .field("cpuProcess", sample.cpuProcess())
                .field("players", sample.players())
                .field("chunks", sample.chunks())
                .field("entities", sample.entities())
                .field("tickPeriodP95", sample.tickPeriodP95())
                .field("overrunRatio", sample.overrunRatio())
                .field("profile", sample.profile());

        // Exactly the ticks that elapsed since the previous pulse, so the panel can append
        // them to its ribbon without gaps or repeats. Rounded to whole milliseconds: the
        // ribbon is a bar chart a few pixels tall and sub-millisecond precision would be
        // three times the bytes for something nobody can see.
        writeTicks(w, metrics, pulseIntervalTicks(ctx));

        w.end();
        return w.done();
    }

    /**
     * How many ticks one pulse covers.
     *
     * <p>Derived from the same interval the pusher runs at, including the performance
     * profile's scaling, so the count always matches the ticks that actually elapsed.
     */
    private static int pulseIntervalTicks(ApiContext ctx) {
        return ctx.metrics().profile()
                .scaleInterval(ctx.cfg().performance.snapshotIntervalTicks());
    }

    private static void writeTicks(JsonWriter w, MetricsService metrics, int count) {
        TickSampler sampler = metrics.tickSampler();
        if (sampler == null) {
            w.name("ticks").arr().end();
            return;
        }
        double[] periods = sampler.periods().tail(Math.max(1, count));
        int[] rounded = new int[periods.length];
        for (int i = 0; i < periods.length; i++) rounded[i] = (int) Math.round(periods[i]);
        w.field("ticks", rounded);
    }

    private static int safeMaxPlayers() {
        try {
            return Bukkit.getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
