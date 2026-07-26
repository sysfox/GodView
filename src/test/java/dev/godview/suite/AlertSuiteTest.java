package dev.godview.suite;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import dev.godview.alert.Alert;
import dev.godview.alert.AlertEngine;
import dev.godview.alert.AlertRule;
import dev.godview.config.GodViewConfig;
import dev.godview.metrics.MetricsService;
import dev.godview.metrics.Spike;
import dev.godview.storage.MemoryStorage;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Alert rules and delivery, against an endpoint that records what arrives.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class AlertSuiteTest {
    static final List<String> received = java.util.Collections.synchronizedList(new ArrayList<>());

    static MetricsService.Sample sample(long at, double tps, double heapPercent, int players) {
        long max = 1000L;
        long used = (long) (heapPercent * 10);
        return new MetricsService.Sample(at, tps, tps, tps, 50, 0, 0,
                used, max, max, 0, 0.2, 0.3, 1.0, players, 100, 1000, 10, 20, 0, 0, "balanced");
    }

    @Test
    void verifies() throws Exception {
        Logger log = Logger.getLogger("alertsmoke");
        log.setLevel(Level.SEVERE);

        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        sink.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        sink.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        sink.start();

        try {
            ruleStateMachine();
            engine(port, log);
        } finally {
            sink.stop(0);
        }
    }

    // ------------------------------------------------------------- rule logic

    static void ruleStateMachine() {
        System.out.println("========== rule state machine ==========");

        GodViewConfig.RuleDef def = new GodViewConfig.RuleDef(
                "tps-low", "tps_below", 15.0, 30, "warn", 300, null, List.of("panel"));
        AlertRule rule = new AlertRule(def, 60);

        check("kind parsed", rule.kind(), "TPS_BELOW");
        check("recognised", rule.recognised(), true);
        check("is a threshold rule", rule.kind().isThreshold(), true);

        long t = 1_000_000L;
        check("healthy value does not fire", rule.sample(t, 19.9), false);
        check("first breach does not fire (sustain)", rule.sample(t + 1000, 12.0), false);
        check("still inside sustain", rule.sample(t + 29_000, 12.0), false);
        check("fires once sustained", rule.sample(t + 31_000, 12.0), true);
        rule.fired(t + 31_000);

        // A single sample below threshold every world save must not alert.
        AlertRule flappy = new AlertRule(def, 60);
        for (int i = 0; i < 10; i++) {
            flappy.sample(t + i * 2000L, i % 2 == 0 ? 12.0 : 19.5);
        }
        check("alternating values never sustain", flappy.timesFired(), 0L);

        check("cooling right after firing", rule.cooling(t + 32_000), true);
        check("cooldown remaining is reported", rule.cooldownRemaining(t + 32_000) > 0, true);
        // Still broken, but the operator has already been told.
        for (int i = 0; i < 60; i++) rule.sample(t + 40_000 + i * 1000L, 10.0);
        check("cooldown suppresses repeats", rule.timesFired(), 1L);
        check("suppressions are counted", rule.timesSuppressed() > 0, true);

        // A condition that never went away re-alerts as soon as the cooldown lapses:
        // sustain accrued during the cooldown, and the problem is still there.
        long later = t + 31_000 + 300_000 + 1000;
        check("re-alerts immediately when the problem never went away",
                rule.sample(later, 10.0), true);
        rule.fired(later);

        // But one that recovered and came back late has to sustain properly again.
        AlertRule recovered = new AlertRule(def, 60);
        recovered.sample(t, 12.0);
        recovered.sample(t + 31_000, 12.0);
        recovered.fired(t + 31_000);
        recovered.sample(t + 40_000, 19.9);              // healthy for most of the cooldown
        long lateDip = t + 31_000 + 300_000 + 1000;
        check("a fresh dip after cooldown must sustain",
                recovered.sample(lateDip, 12.0), false);
        check("and fires once it has", recovered.sample(lateDip + 31_000, 12.0), true);

        AlertRule above = new AlertRule(new GodViewConfig.RuleDef(
                "heap", "memory_above", 90.0, 0, "warn", 0, null, List.of("panel")), 60);
        check("above-threshold rule ignores low values", above.sample(t, 50.0), false);
        check("above-threshold rule fires with no sustain", above.sample(t, 95.0), true);

        AlertRule unknown = new AlertRule(new GodViewConfig.RuleDef(
                "x", "not_a_real_type", 1, 0, "warn", 0, null, List.of("panel")), 60);
        check("unknown type is flagged", unknown.recognised(), false);
        check("unknown type never fires", unknown.sample(t, 999), false);

        AlertRule event = new AlertRule(new GodViewConfig.RuleDef(
                "join", "player_join", 0, 0, "info", 5, null, List.of("panel")), 60);
        check("event rule is not a threshold rule", event.kind().isThreshold(), false);
        check("event rule triggers", event.trigger(t), true);
        event.fired(t);
        check("event rule respects cooldown", event.trigger(t + 1000), false);
        check("event rule fires again after cooldown", event.trigger(t + 6000), true);
    }

    // ----------------------------------------------------------------- engine

    static void engine(int port, Logger log) throws Exception {
        System.out.println("\n========== engine and delivery ==========");
        String hook = "http://127.0.0.1:" + port + "/hook";

        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                storage:
                  type: memory
                alerts:
                  enabled: true
                  default-cooldown-seconds: 1
                  channels:
                    panel:
                      enabled: true
                    webhook:
                      enabled: true
                      url: "%s"
                      method: POST
                  rules:
                    - id: tps-low
                      type: tps_below
                      threshold: 15.0
                      sustain-seconds: 2
                      severity: warn
                      channels: [webhook]
                    - id: tps-dead
                      type: tps_below
                      threshold: 5.0
                      sustain-seconds: 0
                      severity: critical
                      channels: [webhook]
                    - id: swearing
                      type: chat_match
                      pattern: "(?i)badword"
                      severity: warn
                      channels: [webhook]
                    - id: op-granted
                      type: command_match
                      pattern: "(?i)^/?op\\\\s"
                      severity: critical
                      channels: [webhook]
                    - id: errors
                      type: console_error
                      threshold: 3
                      severity: warn
                      channels: [webhook]
                    - id: spikes
                      type: tick_spike
                      threshold: 400
                      severity: warn
                      channels: [webhook]
                    - id: bruteforce
                      type: login_failed
                      threshold: 2
                      severity: critical
                      channels: [webhook]
                """.formatted(hook))));

        check("config parsed all rules", cfg.alerts.rules().size(), 7);
        check("no config warnings", cfg.warnings().size(), 0);

        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();
        AlertEngine engine = new AlertEngine(cfg, () -> storage, () -> null, log);
        engine.start();

        try {
            check("engine is active", engine.active(), true);
            received.clear();

            // ---- threshold with sustain ----
            // Sample timestamps drive the sustain window, so the test can advance time
            // without sleeping through it.
            long t = System.currentTimeMillis();
            engine.onSample(sample(t, 12.0, 40, 5));
            engine.onSample(sample(t + 500, 12.0, 40, 5));
            Thread.sleep(200);
            check("does not fire before sustain elapses", received.size(), 0);

            engine.onSample(sample(t + 2500, 12.0, 40, 5));
            Thread.sleep(600);
            check("fires after sustain", received.size(), 1);

            Map<String, Object> payload = Json.asObj(Json.parse(received.get(0)));
            check("payload names the rule", payload.get("ruleId"), "tps-low");
            check("payload has severity", payload.get("severity"), "warn");
            // Json writes an integral double without the trailing .0, so it parses back
            // as a Long. Both are the same number to any JSON consumer.
            check("payload carries the value", payload.get("value"), 12L);
            check("payload title mentions TPS",
                    String.valueOf(payload.get("title")).contains("TPS"), true);
            check("payload message names the threshold",
                    String.valueOf(payload.get("message")).contains("15"), true);

            // ---- zero sustain fires at once ----
            received.clear();
            engine.onSample(sample(t + 3500, 2.0, 40, 5));
            Thread.sleep(400);
            check("critical rule with no sustain fires immediately", received.size() >= 1, true);
            check("critical severity delivered",
                    received.stream().anyMatch(b -> b.contains("\"severity\":\"critical\"")), true);

            // ---- pattern rules ----
            received.clear();
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "chat", "Steve",
                    null, "world", "this contains BadWord here", null));
            Thread.sleep(400);
            check("chat pattern matched", received.size(), 1);
            check("chat alert quotes the line",
                    received.get(0).contains("BadWord"), true);

            received.clear();
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "chat", "Steve",
                    null, "world", "a perfectly ordinary message", null));
            Thread.sleep(300);
            check("non-matching chat is ignored", received.size(), 0);

            received.clear();
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "command", "Alex",
                    null, "world", "/op Steve", null));
            Thread.sleep(400);
            check("command pattern matched", received.size(), 1);

            received.clear();
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "command", "Alex",
                    null, "world", "/help", null));
            Thread.sleep(300);
            check("unrelated command ignored", received.size(), 0);

            // ---- rate-based rules ----
            received.clear();
            for (int i = 0; i < 2; i++) {
                engine.onEvent(new Storage.Event(System.currentTimeMillis(), "console_error",
                        "server", null, null, "NullPointerException " + i, null));
            }
            Thread.sleep(300);
            check("below the error rate threshold stays quiet", received.size(), 0);
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "console_error",
                    "server", null, null, "NullPointerException 3", null));
            Thread.sleep(400);
            check("crossing the error rate fires", received.size(), 1);
            check("error alert reports the count",
                    received.get(0).contains("3 console errors"), true);

            received.clear();
            engine.onLoginFailure("10.0.0.9");
            Thread.sleep(200);
            check("one failed login is not an alert", received.size(), 0);
            engine.onLoginFailure("10.0.0.9");
            Thread.sleep(400);
            check("repeated failures alert", received.size(), 1);
            check("brute-force alert names the address",
                    received.get(0).contains("10.0.0.9"), true);

            // ---- spikes ----
            received.clear();
            engine.onSpike(new Spike(System.currentTimeMillis(), 200, 1, "ShopGUI", "a.b:1", List.of()));
            Thread.sleep(250);
            check("small spike is below threshold", received.size(), 0);
            engine.onSpike(new Spike(System.currentTimeMillis(), 900, 2, "ShopGUI",
                    "com.shopgui.Task.run:5", List.of()));
            Thread.sleep(400);
            check("large spike fires", received.size(), 1);
            check("spike alert names the plugin", received.get(0).contains("ShopGUI"), true);
            check("spike alert names the frame",
                    received.get(0).contains("com.shopgui.Task.run:5"), true);

            // ---- storage ----
            List<Storage.AlertRecord> stored = storage.queryAlerts(0, Long.MAX_VALUE, 100);
            check("alerts were recorded", stored.size() >= 6, true);
            check("records mark delivery",
                    stored.stream().anyMatch(Storage.AlertRecord::delivered), true);
            check("records name the channel",
                    stored.stream().anyMatch(a -> a.channels().contains("webhook")), true);

            // ---- test alert ----
            received.clear();
            check("test on an unknown rule", engine.test("nope"), false);
            check("test on a real rule", engine.test("tps-low"), true);
            Thread.sleep(400);
            check("test alert delivered", received.size(), 1);
            check("test alert says it is a test",
                    received.get(0).contains("Test alert"), true);

            // ---- status ----
            dev.godview.util.JsonWriter w = new dev.godview.util.JsonWriter(4096);
            engine.writeStatusJson(w);
            Map<String, Object> status = Json.asObj(Json.parse(w.done()));
            check("status reports enabled", status.get("enabled"), true);
            check("status lists every rule", Json.asArr(status.get("rules")).size(), 7);
            check("status lists channels", Json.asArr(status.get("channels")).size(), 6);
            check("status counts fired alerts", ((Number) status.get("fired")).intValue() >= 6, true);
        } finally {
            engine.stop();
            storage.close();
        }

        quietHours(port, log);
        deliveryFailure(port, log);
    }

    // ------------------------------------------------------------ quiet hours

    /** {@code HH:mm} offset from now, so a window can be built around whatever time it is. */
    static String clock(int minutesFromNow) {
        java.time.LocalTime t = java.time.LocalTime.now().plusMinutes(minutesFromNow);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    static void quietHours(int port, Logger log) throws Exception {
        System.out.println("\n========== quiet hours ==========");
        // Built around the current time rather than hard-coded. A literal "00:00-23:59"
        // looks like the whole day, but the window is half-open, so it silently excludes
        // the last minute -- and the test would only fail on the one run that lands there.
        String window = clock(-60) + "-" + clock(60);
        String outside = clock(120) + "-" + clock(180);
        System.out.println("     (silent window " + window + ", awake window " + outside + ")");
        String hook = "http://127.0.0.1:" + port + "/hook";

        String template = """
                storage:
                  type: memory
                alerts:
                  enabled: true
                  quiet-hours: "%s"
                  default-cooldown-seconds: 0
                  channels:
                    webhook:
                      enabled: true
                      url: "%s"
                  rules:
                    - id: noisy
                      type: chat_match
                      severity: warn
                      channels: [webhook]
                    - id: urgent
                      type: command_match
                      severity: critical
                      channels: [webhook]
                """;

        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(
                new StringReader(template.formatted(window, hook))));

        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();
        AlertEngine engine = new AlertEngine(cfg, () -> storage, () -> null, log);
        engine.start();
        try {
            received.clear();
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "chat", "Steve",
                    null, "world", "hello", null));
            Thread.sleep(400);
            check("warn is silenced during quiet hours", received.size(), 0);

            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "command", "Steve",
                    null, "world", "/stop", null));
            Thread.sleep(500);
            check("critical is never silenced", received.size(), 1);

            List<Storage.AlertRecord> stored = storage.queryAlerts(0, Long.MAX_VALUE, 10);
            check("silenced alert is still recorded", stored.size(), 2);
            check("silenced alert is marked undelivered",
                    stored.stream().filter(a -> a.rule().equals("noisy"))
                            .allMatch(a -> !a.delivered()), true);

            dev.godview.util.JsonWriter w = new dev.godview.util.JsonWriter(2048);
            engine.writeStatusJson(w);
            Map<String, Object> status = Json.asObj(Json.parse(w.done()));
            check("status reports quiet hours active", status.get("quietNow"), true);
            check("status counts suppressions",
                    ((Number) status.get("suppressedByQuietHours")).intValue(), 1);
        } finally {
            engine.stop();
            storage.close();
        }

        // The complementary case. Without it, a comparison inverted the wrong way would
        // still pass everything above.
        GodViewConfig awake = GodViewConfig.from(YamlConfiguration.loadConfiguration(
                new StringReader(template.formatted(outside, hook))));
        Storage awakeStorage = new MemoryStorage(awake.storage);
        awakeStorage.start();
        AlertEngine awakeEngine = new AlertEngine(awake, () -> awakeStorage, () -> null, log);
        awakeEngine.start();
        try {
            received.clear();
            awakeEngine.onEvent(new Storage.Event(System.currentTimeMillis(), "chat", "Steve",
                    null, "world", "hello", null));
            Thread.sleep(500);
            check("outside the window nothing is silenced", received.size(), 1);

            dev.godview.util.JsonWriter w = new dev.godview.util.JsonWriter(2048);
            awakeEngine.writeStatusJson(w);
            check("status reports quiet hours inactive",
                    Json.asObj(Json.parse(w.done())).get("quietNow"), false);
        } finally {
            awakeEngine.stop();
            awakeStorage.close();
        }
    }

    // -------------------------------------------------------- delivery failure

    static void deliveryFailure(int port, Logger log) throws Exception {
        System.out.println("\n========== delivery failure ==========");
        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                storage:
                  type: memory
                alerts:
                  enabled: true
                  default-cooldown-seconds: 0
                  channels:
                    webhook:
                      enabled: true
                      url: "http://127.0.0.1:%d/broken"
                  rules:
                    - id: chatty
                      type: chat_match
                      severity: warn
                      channels: [webhook]
                """.formatted(port))));

        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();
        AlertEngine engine = new AlertEngine(cfg, () -> storage, () -> null, log);
        engine.start();
        try {
            engine.onEvent(new Storage.Event(System.currentTimeMillis(), "chat", "Steve",
                    null, "world", "hello", null));
            Thread.sleep(700);

            List<Storage.AlertRecord> stored = storage.queryAlerts(0, Long.MAX_VALUE, 10);
            check("alert recorded despite the failure", stored.size(), 1);
            check("recorded as undelivered", stored.get(0).delivered(), false);
            check("no channel claimed success", stored.get(0).channels(), "");

            dev.godview.util.JsonWriter w = new dev.godview.util.JsonWriter(2048);
            engine.writeStatusJson(w);
            Map<String, Object> status = Json.asObj(Json.parse(w.done()));
            check("failure counted",
                    ((Number) status.get("deliveryFailures")).intValue(), 1);
        } finally {
            engine.stop();
            storage.close();
        }
    }
}
