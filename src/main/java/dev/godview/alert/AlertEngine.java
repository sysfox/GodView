package dev.godview.alert;

import dev.godview.config.GodViewConfig;
import dev.godview.metrics.GcMonitor;
import dev.godview.metrics.MetricsService;
import dev.godview.metrics.Spike;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.util.LongRing;
import dev.godview.util.Text;
import dev.godview.web.api.EventStream;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Decides what is worth telling somebody about, and tells them.
 *
 * <p>Evaluation is cheap and happens inline — a threshold rule is a comparison and a
 * timestamp. Delivery is not: a Discord webhook can take seconds, so it is handed to a
 * dedicated thread. Nothing in this class blocks the caller, which matters because the
 * caller is usually the main thread and the alert is usually about the server being slow.
 *
 * <p><b>Quiet hours silence {@code info} and {@code warn}, never {@code critical}.</b> An
 * operator who sets quiet hours wants to sleep through a chat filter match, not through
 * the server going down — suppressing everything would make the setting one nobody dares
 * turn on.
 *
 * <p>Every alert is recorded to storage whether or not any channel accepted it, with the
 * channels that succeeded. "Fired but nobody was told" is a materially different situation
 * from "never fired", and only one of them is a configuration problem.
 */
public final class AlertEngine {

    /** Deliveries that may queue before we start dropping. Alerts are small; this is generous. */
    private static final int QUEUE_CAPACITY = 256;
    /** Alerts kept in memory for the panel. Older ones live in storage. */
    private static final int RECENT_ALERTS = 100;
    /** Window for the count-based rules — console errors and login failures. */
    private static final long COUNT_WINDOW_MS = 60_000L;

    private final Supplier<Storage> storage;
    private final Supplier<EventStream> stream;
    private final Logger log;

    private final Deque<Alert> recent = new ArrayDeque<>();
    private final AtomicLong fired = new AtomicLong();
    private final AtomicLong suppressedQuiet = new AtomicLong();
    private final AtomicLong deliveryFailures = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private volatile GodViewConfig config;
    private volatile List<AlertRule> rules = List.of();
    private volatile List<Notifier> notifiers = List.of();
    private volatile QuietHours quietHours = QuietHours.NONE;
    private volatile boolean running;

    /** Timestamps for the count-based rules, keyed by rule id. */
    private final Map<String, LongRing> counters = new java.util.concurrent.ConcurrentHashMap<>();

    private ThreadPoolExecutor dispatcher;

    public AlertEngine(GodViewConfig config, Supplier<Storage> storage,
                       Supplier<EventStream> stream, Logger log) {
        this.config = config;
        this.storage = storage;
        this.stream = stream;
        this.log = log;
        rebuild(config);
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        if (running) return;
        running = true;
        dispatcher = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "GodView-Alerts");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });

        int usable = 0;
        for (AlertRule rule : rules) {
            if (rule.recognised()) usable++;
            else log.warning("alerts.rules: '" + rule.id() + "' has unknown type '"
                    + rule.def().type() + "' — it will never fire");
        }
        for (Notifier notifier : notifiers) {
            String problem = notifier.problem();
            if (!problem.isEmpty()) log.warning("alerts: " + problem);
        }
        if (config.alerts.enabled() && usable > 0) {
            log.info("GodView is watching " + usable + " alert rule(s).");
        }
    }

    public void stop() {
        running = false;
        if (dispatcher != null) {
            dispatcher.shutdown();
            try {
                // Long enough to flush a queued alert, short enough not to hold up shutdown.
                if (!dispatcher.awaitTermination(3, TimeUnit.SECONDS)) dispatcher.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dispatcher.shutdownNow();
            }
            dispatcher = null;
        }
    }

    public void reload(GodViewConfig newConfig) {
        this.config = newConfig;
        rebuild(newConfig);
    }

    private void rebuild(GodViewConfig newConfig) {
        List<AlertRule> built = new ArrayList<>();
        for (GodViewConfig.RuleDef def : newConfig.alerts.rules()) {
            built.add(new AlertRule(def, newConfig.alerts.defaultCooldownSeconds()));
        }
        this.rules = List.copyOf(built);
        this.notifiers = Notifiers.build(newConfig.alerts, stream);
        this.quietHours = QuietHours.parse(newConfig.alerts.quietHours(), log);
        this.counters.clear();
    }

    // -------------------------------------------------------------- evaluation

    /**
     * Feeds the threshold rules. Call once a second from the metrics sampler.
     *
     * <p>Runs on the main thread, so it does comparisons and nothing else — everything
     * expensive happens on the dispatcher.
     */
    public void onSample(MetricsService.Sample sample) {
        if (!active()) return;
        // The sample's own timestamp, not the wall clock: a rule's sustain window should
        // measure the data it is judging. In production the two are the same, but tying
        // them together keeps the timing honest and makes the behaviour reproducible.
        long now = sample.at() > 0 ? sample.at() : System.currentTimeMillis();

        for (AlertRule rule : rules) {
            if (!rule.kind().isThreshold()) continue;

            double value = switch (rule.kind()) {
                case TPS_BELOW -> sample.tps1();
                case MSPT_ABOVE -> sample.mspt();
                case MEMORY_ABOVE -> sample.heapPercent();
                case PLAYER_COUNT_ABOVE, PLAYER_COUNT_BELOW -> sample.players();
                default -> Double.NaN;
            };
            if (Double.isNaN(value)) continue;

            if (rule.sample(now, value)) {
                fire(rule, now, value, describeThreshold(rule, value, now));
            }
        }
    }

    /** A tick that overran. Fires {@code tick_spike} rules above their threshold. */
    public void onSpike(Spike spike) {
        if (!active()) return;
        long now = System.currentTimeMillis();
        for (AlertRule rule : rules) {
            if (rule.kind() != AlertRule.Kind.TICK_SPIKE) continue;
            if (spike.durationMs() < rule.def().threshold()) continue;
            if (!rule.trigger(now)) continue;

            String blame = spike.blame() == null || spike.blame().isEmpty() ? "unknown" : spike.blame();
            fire(rule, now, spike.durationMs(),
                    "Title: A tick took " + Math.round(spike.durationMs()) + " ms\n"
                            + "Blamed on: " + blame
                            + (spike.blameFrame().isEmpty() ? "" : "\nAt: " + spike.blameFrame()));
        }
    }

    /** A garbage collection pause. Fires {@code gc_pause} rules above their threshold. */
    public void onGc(GcMonitor.GcEvent event) {
        if (!active()) return;
        long now = System.currentTimeMillis();
        for (AlertRule rule : rules) {
            if (rule.kind() != AlertRule.Kind.GC_PAUSE) continue;
            if (event.durationMs() < rule.def().threshold()) continue;
            if (!rule.trigger(now)) continue;

            fire(rule, now, event.durationMs(),
                    "Title: Garbage collection paused the server for "
                            + event.durationMs() + " ms\n"
                            + "Collector: " + event.name() + "\nCause: " + event.cause());
        }
    }

    /**
     * A captured server event. Drives the pattern and occurrence rules.
     *
     * <p>Called from wherever the event happened, which for chat is a netty thread. Only
     * the regex runs here; delivery is queued as usual.
     */
    public void onEvent(Storage.Event event) {
        if (!active()) return;
        long now = System.currentTimeMillis();

        for (AlertRule rule : rules) {
            switch (rule.kind()) {
                case PLAYER_JOIN -> {
                    if ("join".equals(event.type()) && matches(rule, event.actor())) {
                        maybeFire(rule, now, "Title: " + event.actor() + " joined",
                                event.detail());
                    }
                }
                case FIRST_JOIN -> {
                    if ("join".equals(event.type()) && isFirstJoin(event) && matches(rule, event.actor())) {
                        maybeFire(rule, now, "Title: " + event.actor() + " joined for the first time",
                                event.detail());
                    }
                }
                case PLAYER_QUIT -> {
                    if ("quit".equals(event.type()) && matches(rule, event.actor())) {
                        maybeFire(rule, now, "Title: " + event.actor() + " left", event.detail());
                    }
                }
                case PLAYER_DEATH -> {
                    if ("death".equals(event.type()) && matches(rule, event.detail())) {
                        maybeFire(rule, now, "Title: " + event.actor() + " died", event.detail());
                    }
                }
                case CHAT_MATCH -> {
                    if ("chat".equals(event.type()) && matches(rule, event.detail())) {
                        maybeFire(rule, now, "Title: Chat matched " + rule.id(),
                                event.actor() + ": " + event.detail());
                    }
                }
                case COMMAND_MATCH -> {
                    if ("command".equals(event.type()) && matches(rule, event.detail())) {
                        maybeFire(rule, now, "Title: Command matched " + rule.id(),
                                event.actor() + " ran " + event.detail());
                    }
                }
                case CONSOLE_ERROR -> {
                    if ("console_error".equals(event.type())) {
                        countAndMaybeFire(rule, now, "console errors",
                                "Latest: " + Text.truncate(event.detail(), 300));
                    }
                }
                default -> {
                    // Threshold rules and unknown types are handled elsewhere.
                }
            }
        }
    }

    /** A failed panel sign-in. Drives {@code login_failed} rules. */
    public void onLoginFailure(String ip) {
        if (!active()) return;
        long now = System.currentTimeMillis();
        for (AlertRule rule : rules) {
            if (rule.kind() != AlertRule.Kind.LOGIN_FAILED) continue;
            countAndMaybeFire(rule, now, "failed panel sign-ins", "Most recent from " + ip);
        }
    }

    /**
     * Counts an occurrence and fires once the rate crosses the threshold.
     *
     * <p>Rate rather than a single occurrence: one console error is normal on any server,
     * and twenty in a minute is a plugin in a loop. The threshold is the count inside a
     * one-minute window.
     */
    private void countAndMaybeFire(AlertRule rule, long now, String noun, String detail) {
        LongRing times = counters.computeIfAbsent(rule.id(), k -> new LongRing(512));
        times.add(now);
        int recentCount = times.countSince(now - COUNT_WINDOW_MS);
        int threshold = Math.max(1, (int) rule.def().threshold());
        if (recentCount < threshold) return;
        if (!rule.trigger(now)) return;

        fire(rule, now, recentCount,
                "Title: " + recentCount + " " + noun + " in the last minute\n" + detail);
    }

    private void maybeFire(AlertRule rule, long now, String titleLine, String detail) {
        if (!rule.trigger(now)) return;
        fire(rule, now, 0, titleLine + "\n" + detail);
    }

    // ------------------------------------------------------------------ firing

    /**
     * @param body first line may be {@code Title: ...}, which becomes the alert title and
     *             the rest the message — it keeps the call sites above readable
     */
    private void fire(AlertRule rule, long now, double value, String body) {
        String title;
        String message;
        if (body.startsWith("Title: ")) {
            int newline = body.indexOf('\n');
            title = newline < 0 ? body.substring(7) : body.substring(7, newline);
            message = newline < 0 ? "" : body.substring(newline + 1);
        } else {
            title = rule.id();
            message = body;
        }

        Alert alert = new Alert(now, rule.id(), rule.def().severity(), title,
                Text.truncate(message, Notifiers.MAX_BODY), value, rule.def().channels());
        rule.fired(now);
        fired.incrementAndGet();

        if (quietHours.silences(alert)) {
            suppressedQuiet.incrementAndGet();
            // Still recorded: the panel should show what happened overnight.
            record(alert, List.of(), false);
            return;
        }

        ThreadPoolExecutor executor = dispatcher;
        if (executor == null) {
            record(alert, List.of(), false);
            return;
        }
        try {
            executor.execute(() -> deliver(alert));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            dropped.incrementAndGet();
            record(alert, List.of(), false);
        }
    }

    /** Runs on the dispatcher thread. Blocking here is expected and harmless. */
    private void deliver(Alert alert) {
        List<String> delivered = new ArrayList<>(alert.channels().size());
        for (Notifier notifier : notifiers) {
            if (!alert.channels().contains(notifier.id())) continue;
            if (!notifier.enabled()) continue;
            try {
                notifier.send(alert);
                delivered.add(notifier.id());
            } catch (Throwable t) {
                deliveryFailures.incrementAndGet();
                log.log(Level.WARNING, "GodView could not deliver alert '" + alert.ruleId()
                        + "' to " + notifier.id() + ": " + t.getMessage());
            }
        }
        record(alert, delivered, !delivered.isEmpty());
    }

    private void record(Alert alert, List<String> delivered, boolean success) {
        synchronized (recent) {
            recent.addLast(alert);
            while (recent.size() > RECENT_ALERTS) recent.removeFirst();
        }
        Storage store = storage.get();
        if (store == null) return;
        store.recordAlert(new Storage.AlertRecord(alert.at(), alert.ruleId(), alert.severity(),
                alert.title(), alert.message(), alert.value(),
                String.join(",", delivered), success));
    }

    // -------------------------------------------------------------- accessors

    public boolean active() {
        return running && config.alerts.enabled();
    }

    public List<Alert> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public List<AlertRule> rules() {
        return rules;
    }

    /**
     * Sends a synthetic alert through a rule's channels, so an admin can prove their
     * webhook works without waiting for the server to break.
     *
     * @return false when no such rule exists
     */
    public boolean test(String ruleId) {
        for (AlertRule rule : rules) {
            if (!rule.id().equalsIgnoreCase(ruleId)) continue;
            Alert alert = new Alert(System.currentTimeMillis(), rule.id(), rule.def().severity(),
                    "Test alert from GodView",
                    "This confirms the channels configured for rule '" + rule.id()
                            + "' are reachable. Nothing is wrong with your server.",
                    0, rule.def().channels());
            ThreadPoolExecutor executor = dispatcher;
            // A test bypasses quiet hours on purpose: it is sent by hand, right now, by
            // somebody who wants to see whether it arrives.
            if (executor != null) executor.execute(() -> deliver(alert));
            return true;
        }
        return false;
    }

    public void writeStatusJson(JsonWriter w) {
        long now = System.currentTimeMillis();
        w.obj()
                .field("enabled", config.alerts.enabled())
                .field("running", running)
                .field("quietHours", config.alerts.quietHours())
                .field("quietNow", quietHours.active(now))
                .field("fired", fired.get())
                .field("suppressedByQuietHours", suppressedQuiet.get())
                .field("deliveryFailures", deliveryFailures.get())
                .field("droppedWhenBusy", dropped.get())
                .name("channels").arr();
        for (Notifier notifier : notifiers) {
            w.obj()
                    .field("id", notifier.id())
                    .field("enabled", notifier.enabled())
                    .field("problem", notifier.problem())
                    .end();
        }
        w.end().name("rules").arr();
        for (AlertRule rule : rules) {
            w.obj()
                    .field("id", rule.id())
                    .field("type", rule.def().type())
                    .field("recognised", rule.recognised())
                    .field("severity", rule.def().severity())
                    .field("threshold", rule.def().threshold())
                    .field("sustainSeconds", rule.def().sustainSeconds())
                    .field("channels", rule.def().channels())
                    .field("timesFired", rule.timesFired())
                    .field("timesSuppressed", rule.timesSuppressed())
                    .field("lastFiredAt", rule.lastFiredAt())
                    .field("cooldownRemaining", rule.cooldownRemaining(now))
                    .field("heldSeconds", rule.heldSeconds(now))
                    .end();
        }
        w.end().end();
    }

    // --------------------------------------------------------------- helpers

    private String describeThreshold(AlertRule rule, double value, long now) {
        String rounded = String.valueOf(Math.round(value * 100) / 100d);
        return switch (rule.kind()) {
            case TPS_BELOW -> "Title: TPS is " + rounded + "\nBelow the "
                    + rule.def().threshold() + " threshold for " + rule.def().sustainSeconds() + "s.";
            case MSPT_ABOVE -> "Title: MSPT is " + rounded + " ms\nAbove the "
                    + rule.def().threshold() + " ms threshold.";
            case MEMORY_ABOVE -> "Title: Heap is " + rounded + "% full\nAbove the "
                    + rule.def().threshold() + "% threshold.";
            case PLAYER_COUNT_ABOVE -> "Title: " + (int) value + " players online\nAbove "
                    + (int) rule.def().threshold() + ".";
            case PLAYER_COUNT_BELOW -> "Title: only " + (int) value + " players online\nBelow "
                    + (int) rule.def().threshold() + ".";
            default -> "Title: " + rule.id() + "\nValue " + rounded;
        };
    }

    /** True when the rule has no pattern, or its pattern matches. */
    private static boolean matches(AlertRule rule, String text) {
        Pattern pattern = rule.def().pattern();
        if (pattern == null) return true;
        return text != null && pattern.matcher(text).find();
    }

    private static boolean isFirstJoin(Storage.Event event) {
        if (event.meta() == null || event.meta().isEmpty()) return false;
        try {
            return Json.flag(Json.asObj(Json.parse(event.meta())), "first", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The configured silent window.
     *
     * <p>Wrapping windows like {@code 23:00-07:00} are the normal case, so the comparison
     * handles the midnight crossing rather than assuming start is before end.
     */
    record QuietHours(boolean configured, LocalTime start, LocalTime end) {

        static final QuietHours NONE = new QuietHours(false, LocalTime.MIN, LocalTime.MIN);

        static QuietHours parse(String raw, Logger log) {
            if (raw == null || raw.isBlank()) return NONE;
            String[] parts = raw.trim().split("-");
            if (parts.length != 2) {
                log.warning("alerts.quiet-hours '" + raw + "' is not in HH:mm-HH:mm form — ignored");
                return NONE;
            }
            try {
                return new QuietHours(true,
                        LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
            } catch (RuntimeException e) {
                log.warning("alerts.quiet-hours '" + raw + "' is not a valid time range — ignored");
                return NONE;
            }
        }

        boolean active(long nowMillis) {
            if (!configured) return false;
            LocalTime now = java.time.Instant.ofEpochMilli(nowMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
            if (start.equals(end)) return false;
            return start.isBefore(end)
                    ? !now.isBefore(start) && now.isBefore(end)
                    : !now.isBefore(start) || now.isBefore(end);
        }

        /** Critical alerts are never silenced — see the class note on {@link AlertEngine}. */
        boolean silences(Alert alert) {
            return active(alert.at()) && !alert.isCritical();
        }
    }

    /** Lower-cased channel ids the config knows about, for validation messages. */
    static List<String> knownChannels() {
        return List.of("panel", "discord", "telegram", "webhook", "serverchan", "bark")
                .stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
