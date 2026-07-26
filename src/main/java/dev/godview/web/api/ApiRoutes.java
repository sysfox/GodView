package dev.godview.web.api;

import dev.godview.compat.Compat;
import dev.godview.metrics.GcMonitor;
import dev.godview.metrics.MetricsService;
import dev.godview.metrics.PlayerSnapshot;
import dev.godview.metrics.Spike;
import dev.godview.metrics.SystemProbe;
import dev.godview.metrics.WorldStats;
import dev.godview.storage.Storage;
import dev.godview.util.JsonWriter;
import dev.godview.util.TimeSeries;
import dev.godview.web.Router;
import dev.godview.web.WebExchange;
import dev.godview.web.auth.Role;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Everything the panel reads.
 *
 * <p>Nothing here touches the Bukkit API. Every value is served from the caches
 * {@link MetricsService} fills on the main thread, or from storage, so a burst of panel
 * traffic cannot turn into load on the tick loop. State-changing endpoints, which do have
 * to hop threads, live in {@link ActionRoutes}.
 *
 * <p>Every list endpoint takes a bounded {@code limit} and a time window, and clamps both.
 * An authenticated but careless client asking for a million rows should get a sensible
 * page, not an out-of-memory error on the server it is monitoring.
 */
public final class ApiRoutes {

    /** Rows any single list request may return. */
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 100;
    /** Points a chart request may ask for. Beyond this the browser is the bottleneck anyway. */
    private static final int MAX_CHART_POINTS = 2000;
    /** Default history window when the caller gives none: one hour. */
    private static final long DEFAULT_WINDOW_MS = 3_600_000L;

    private final ApiContext ctx;

    public ApiRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router router) {
        router.get("/api/overview", Role.Permission.VIEW, this::overview);
        router.get("/api/pulse", Role.Permission.VIEW, this::pulse);
        router.get("/api/series", Role.Permission.VIEW, this::series);
        router.get("/api/history", Role.Permission.VIEW, this::history);
        router.get("/api/spikes", Role.Permission.VIEW, this::spikes);
        router.get("/api/gc", Role.Permission.VIEW, this::gc);
        router.get("/api/worlds", Role.Permission.VIEW, this::worlds);
        router.get("/api/players", Role.Permission.VIEW, this::players);
        router.get("/api/players/{name}", Role.Permission.VIEW, this::player);
        router.get("/api/events", Role.Permission.VIEW, this::events);
        router.get("/api/system", Role.Permission.VIEW, this::system);
        // The audit log records who did what, including failed attempts. That is exactly
        // the sort of thing an ordinary operator account should not be able to browse.
        router.get("/api/audit", Role.Permission.MANAGE_USERS, this::audit);
        router.stream("/api/stream", Role.Permission.VIEW, this::stream);
    }

    // --------------------------------------------------------------- dashboard

    private void overview(WebExchange exchange) throws IOException {
        exchange.json(200, Snapshots.overview(ctx));
    }

    private void pulse(WebExchange exchange) throws IOException {
        exchange.json(200, Snapshots.pulse(ctx));
    }

    /**
     * Live chart data from the in-memory series.
     *
     * <p>{@code window} picks the resolution: the finest level whose full span covers it.
     * A five-minute window gets per-second points, a day gets five-minute buckets, and the
     * payload stays about the same size either way.
     */
    private void series(WebExchange exchange) throws IOException {
        Map<String, TimeSeries> all = ctx.metrics().series();
        String requested = exchange.query("names", String.join(",", all.keySet()));
        long window = clamp(exchange.queryLong("window", DEFAULT_WINDOW_MS), 1_000L, 86_400_000L);
        long from = System.currentTimeMillis() - window;

        JsonWriter w = new JsonWriter(8192);
        w.obj().field("window", window).name("series").obj();

        for (String name : requested.split(",")) {
            String key = name.trim();
            TimeSeries series = all.get(key);
            if (series == null) continue;

            int level = series.levelFor(window);
            TimeSeries.Snapshot snapshot = series.snapshot(level, from);
            w.name(key).obj()
                    .field("bucketMillis", snapshot.bucketMillis())
                    .field("t", snapshot.t())
                    .field("avg", snapshot.avg())
                    .field("min", snapshot.min())
                    .field("max", snapshot.max())
                    .field("count", snapshot.count())
                    .end();
        }

        exchange.json(200, w.end().end());
    }

    /** Long-range chart data, from storage rather than memory. */
    private void history(WebExchange exchange) throws IOException {
        long to = exchange.queryLong("to", System.currentTimeMillis());
        long from = exchange.queryLong("from", to - DEFAULT_WINDOW_MS);
        int points = (int) clamp(exchange.queryInt("points", 300), 2, MAX_CHART_POINTS);

        Storage storage = ctx.store();
        List<Storage.MetricPoint> history =
                storage == null ? List.of() : storage.queryMetrics(from, to, points);

        JsonWriter w = new JsonWriter(4096);
        w.obj().field("from", from).field("to", to).name("points").arr();
        for (Storage.MetricPoint point : history) point.writeJson(w);
        exchange.json(200, w.end().end());
    }

    // ------------------------------------------------------------ diagnostics

    private void spikes(WebExchange exchange) throws IOException {
        int limit = limit(exchange);
        boolean live = exchange.queryFlag("live", false);

        List<Spike> spikes;
        if (live) {
            // The in-memory ring, for "what just happened" — no storage round trip.
            List<Spike> recent = ctx.metrics().recentSpikes();
            int from = Math.max(0, recent.size() - limit);
            spikes = recent.subList(from, recent.size());
        } else {
            long to = exchange.queryLong("to", System.currentTimeMillis());
            long from = exchange.queryLong("from", to - DEFAULT_WINDOW_MS);
            Storage storage = ctx.store();
            spikes = storage == null ? List.of() : storage.querySpikes(from, to, limit);
        }

        JsonWriter w = new JsonWriter(4096);
        w.obj().name("spikes").arr();
        for (Spike spike : spikes) spike.writeJson(w);
        w.end();

        // A per-plugin tally over the returned window: this is the number an operator
        // actually acts on, and computing it here saves the panel doing it in JavaScript.
        java.util.Map<String, Integer> blame = new java.util.LinkedHashMap<>();
        for (Spike spike : spikes) blame.merge(spike.blame(), 1, Integer::sum);
        w.name("byPlugin").obj();
        blame.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> w.field(e.getKey(), e.getValue()));
        w.end();

        exchange.json(200, w.end());
    }

    private void gc(WebExchange exchange) throws IOException {
        MetricsService metrics = ctx.metrics();
        GcMonitor monitor = metrics.gcMonitor();

        JsonWriter w = new JsonWriter(2048);
        w.obj()
                .field("enabled", monitor != null)
                .field("usingNotifications", monitor != null && monitor.usingNotifications())
                .field("totalCount", monitor == null ? 0 : monitor.totalCount())
                .field("totalPauseMs", monitor == null ? 0 : monitor.totalPauseMs())
                .field("worstRecentPauseMs", monitor == null ? 0 : monitor.worstRecentPauseMs())
                .name("recent").arr();
        for (GcMonitor.GcEvent event : metrics.recentGc()) {
            w.obj()
                    .field("at", event.at())
                    .field("name", event.name())
                    .field("action", event.action())
                    .field("cause", event.cause())
                    .field("durationMs", event.durationMs())
                    .field("major", event.major())
                    .end();
        }
        exchange.json(200, w.end().end());
    }

    private void worlds(WebExchange exchange) throws IOException {
        JsonWriter w = new JsonWriter(4096);
        w.obj().name("worlds").arr();
        for (WorldStats world : ctx.metrics().worlds()) world.writeJson(w);
        exchange.json(200, w.end().end());
    }

    // ---------------------------------------------------------------- players

    private void players(WebExchange exchange) throws IOException {
        // Addresses go only to accounts that can act on players. A read-only account has
        // no reason to be collecting the IP of everyone who logs in.
        boolean includeIp = exchange.session().can(Role.Permission.PLAYER_ACTION);

        JsonWriter w = new JsonWriter(4096);
        w.obj().name("players").arr();
        for (PlayerSnapshot player : ctx.metrics().players()) player.writeJson(w, includeIp);
        exchange.json(200, w.end().end());
    }

    private void player(WebExchange exchange) throws IOException {
        PlayerSnapshot player = ctx.metrics().player(exchange.param("name"));
        if (player == null) {
            exchange.error(404, "player_not_found", "That player is not online.");
            return;
        }
        boolean includeIp = exchange.session().can(Role.Permission.PLAYER_ACTION);

        JsonWriter w = new JsonWriter(1024);
        w.obj().name("player");
        player.writeJson(w, includeIp);

        // Their recent activity, which is the reason anyone opens a player's page.
        Storage storage = ctx.store();
        w.name("recentEvents").arr();
        if (storage != null) {
            List<Storage.Event> events = storage.queryEvents(new Storage.EventQuery(
                    0, Long.MAX_VALUE, null, player.name(), null, 50, 0));
            for (Storage.Event event : events) event.writeJson(w);
        }
        w.end();

        exchange.json(200, w.end());
    }

    // ----------------------------------------------------------------- history

    private void events(WebExchange exchange) throws IOException {
        Storage storage = ctx.store();
        long to = exchange.queryLong("to", System.currentTimeMillis());
        long from = exchange.queryLong("from", to - DEFAULT_WINDOW_MS);

        Storage.EventQuery query = new Storage.EventQuery(
                from, to,
                exchange.query("type", null),
                exchange.query("actor", null),
                exchange.query("search", null),
                limit(exchange),
                Math.max(0, exchange.queryInt("offset", 0)));

        List<Storage.Event> events = storage == null ? List.of() : storage.queryEvents(query);

        JsonWriter w = new JsonWriter(8192);
        w.obj().field("from", from).field("to", to).field("count", events.size()).name("events").arr();
        for (Storage.Event event : events) event.writeJson(w);
        exchange.json(200, w.end().end());
    }

    private void audit(WebExchange exchange) throws IOException {
        Storage storage = ctx.store();
        long to = exchange.queryLong("to", System.currentTimeMillis());
        long from = exchange.queryLong("from", to - DEFAULT_WINDOW_MS * 24);

        List<Storage.AuditEntry> entries =
                storage == null ? List.of() : storage.queryAudit(from, to, limit(exchange));

        JsonWriter w = new JsonWriter(4096);
        w.obj().field("from", from).field("to", to).name("entries").arr();
        for (Storage.AuditEntry entry : entries) entry.writeJson(w);
        exchange.json(200, w.end().end());
    }

    // ------------------------------------------------------------------ system

    /** Everything about the process and the plugin itself, for the diagnostics page. */
    private void system(WebExchange exchange) throws IOException {
        MetricsService metrics = ctx.metrics();
        Plugin plugin = ctx.plugin();

        JsonWriter w = new JsonWriter(4096);
        w.obj();

        w.name("jvm").obj()
                .field("java", System.getProperty("java.version", "?"))
                .field("vendor", System.getProperty("java.vendor", "?"))
                .field("vm", System.getProperty("java.vm.name", "?"))
                .field("os", System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", ""))
                .field("processors", SystemProbe.availableProcessors())
                .field("uptimeMs", SystemProbe.uptimeMs())
                .field("heapUsed", SystemProbe.heapUsed())
                .field("heapCommitted", SystemProbe.heapCommitted())
                .field("heapMax", SystemProbe.heapMax())
                .field("nonHeapUsed", SystemProbe.nonHeapUsed())
                .field("loadAverage", SystemProbe.loadAverage())
                .end();

        w.name("server").obj()
                .field("brand", Compat.BRAND)
                .field("version", Compat.VERSION)
                .field("folia", Compat.FOLIA)
                .end();

        w.name("plugins").arr();
        for (Plugin other : plugin.getServer().getPluginManager().getPlugins()) {
            w.obj()
                    .field("name", other.getName())
                    .field("version", Compat.pluginVersion(other))
                    .field("enabled", other.isEnabled())
                    .end();
        }
        w.end();

        w.name("godview").obj()
                .field("version", Compat.pluginVersion(plugin))
                .field("profile", metrics.profile().lower())
                .field("metricsRunning", metrics.isRunning())
                .field("tickSampler", metrics.tickSampler() != null)
                .field("viewers", metrics.viewers())
                .field("streamSubscribers", ctx.stream().subscriberCount())
                .field("streamFramesPublished", ctx.stream().framesPublished())
                .field("streamFramesDropped", ctx.stream().framesDropped())
                .field("httpRequests", ctx.web().totalRequests())
                .field("httpRejected", ctx.web().rejectedRequests())
                .field("httpErrors", ctx.web().failedRequests())
                .field("httpActiveWorkers", ctx.web().activeWorkers())
                .field("sessions", ctx.web().sessions().activeCount())
                .field("loginFailures", ctx.web().loginGuard().totalFailures())
                .field("loginLockouts", ctx.web().loginGuard().totalLockouts())
                .end();

        w.name("storage");
        Storage storage = ctx.store();
        if (storage != null) storage.stats().writeJson(w);
        else w.nul();

        exchange.json(200, w.end());
    }

    // ------------------------------------------------------------------ stream

    /**
     * Attaches the caller to the live event stream.
     *
     * <p>Returns as soon as the headers are written — the worker thread is freed and the
     * shared stream thread takes over the connection.
     */
    private void stream(WebExchange exchange) throws IOException {
        ctx.stream().subscribe(exchange);
    }

    // ----------------------------------------------------------------- helpers

    private static int limit(WebExchange exchange) {
        return (int) clamp(exchange.queryInt("limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }
}
