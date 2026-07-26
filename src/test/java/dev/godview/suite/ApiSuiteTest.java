package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.storage.MemoryStorage;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.util.Text;
import dev.godview.web.Router;
import dev.godview.web.WebServer;
import dev.godview.web.api.EventStream;
import dev.godview.web.auth.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * The route table and the live event stream.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class ApiSuiteTest {

    @Test
    void verifies() throws Exception {
        router();
        text();
        stream();
    }

    // ------------------------------------------------------------------ router

    static void router() {
        System.out.println("========== router ==========");
        Router r = new Router();
        Router.Handler noop = ex -> { };

        r.get("/api/overview", Role.Permission.VIEW, noop);
        r.get("/api/players", Role.Permission.VIEW, noop);
        r.get("/api/players/{name}", Role.Permission.VIEW, noop);
        r.post("/api/players/{name}/kick", Role.Permission.PLAYER_ACTION, noop);
        r.get("/api/worlds/{name}/entities", Role.Permission.VIEW, noop);
        r.get("/api/worlds/{name}", Role.Permission.VIEW, noop);
        r.get("/api/audit", Role.Permission.MANAGE_USERS, noop);
        r.publicRoute("POST", "/api/auth/login", noop);

        check("exact match", r.match("GET", "/api/overview") != null, true);
        check("no match", r.match("GET", "/api/nothing"), null);
        check("method matters", r.match("POST", "/api/overview"), null);
        check("path exists under another method", r.pathExists("/api/overview"), true);

        Router.Match player = r.match("GET", "/api/players/Steve");
        check("variable binds", player.params().get("name"), "Steve");
        check("variable route keeps its permission", player.required(), "VIEW");

        // A literal segment must beat a variable one regardless of registration order.
        Router.Match literal = r.match("GET", "/api/players");
        check("literal route wins over variable", literal.params().isEmpty(), true);

        Router.Match nested = r.match("GET", "/api/worlds/overworld/entities");
        check("longer pattern wins", nested.params().get("name"), "overworld");
        check("shorter pattern still reachable",
                r.match("GET", "/api/worlds/overworld").params().get("name"), "overworld");

        check("action route carries its permission",
                r.match("POST", "/api/players/Steve/kick").required(), "PLAYER_ACTION");
        check("audit route is admin-only",
                r.match("GET", "/api/audit").required(), "MANAGE_USERS");
        check("login is public", r.match("POST", "/api/auth/login").isPublic(), true);
        check("nothing else is public",
                r.match("GET", "/api/overview").isPublic(), false);

        check("HEAD is served by GET", r.match("HEAD", "/api/overview") != null, true);
        check("lowercase method", r.match("get", "/api/overview") != null, true);
        check("trailing slash", r.match("GET", "/api/overview/") != null, true);
        check("empty variable does not match", r.match("GET", "/api/players//kick"), null);
        check("extra segment does not match", r.match("GET", "/api/overview/extra"), null);
        check("allow header lists methods",
                r.allowedMethods("/api/players/Steve/kick"), "POST");

        // A variable must not swallow a slash, or /api/players/a/b would look like a name.
        check("variable is one segment only", r.match("GET", "/api/players/a/b"), null);
        check("route count", r.size(), 8);
    }

    static void text() {
        System.out.println("\n========== text helpers ==========");
        String colored = Text.COLOR_CHAR + "aSteve" + Text.COLOR_CHAR + "r";
        check("colors stripped", Text.stripColors(colored), "Steve");
        check("plain text untouched", Text.stripColors("Steve"), "Steve");
        check("null becomes empty", Text.stripColors(null), "");
        check("dangling code char kept",
                Text.stripColors("Steve" + Text.COLOR_CHAR), "Steve" + Text.COLOR_CHAR);
        check("truncate short", Text.truncate("abc", 10), "abc");
        check("truncate long", Text.truncate("abcdefghij", 5), "abcd…");
        check("duration days", Text.duration(90_061_000L), "1d 1h");
        check("duration minutes", Text.duration(3_720_000L), "1h 2m");
        check("duration sub-second", Text.duration(500L), "500ms");
    }

    // ------------------------------------------------------------------ stream

    static void stream() throws Exception {
        System.out.println("\n========== event stream ==========");
        Logger log = Logger.getLogger("apismoke");
        log.setLevel(Level.SEVERE);

        Path dir = Files.createTempDirectory("godview-api-test");
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        String base = "http://127.0.0.1:" + port;

        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                web:
                  enabled: true
                  bind: "127.0.0.1"
                  port: %d
                  threads: 4
                security:
                  session-timeout-minutes: 60
                storage:
                  type: memory
                """.formatted(port))));

        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();

        AtomicInteger reportedViewers = new AtomicInteger(-1);
        EventStream stream = new EventStream(log, reportedViewers::set);

        WebServer server = new WebServer(cfg, dir, ApiSuiteTest.class.getClassLoader(), log);
        new AuthRoutes(server, () -> storage, () -> cfg).register(server.router());
        server.router().stream("/api/stream", Role.Permission.VIEW, stream::subscribe);
        server.users().add("admin", Role.ADMIN, "correct-horse", null);
        server.start();
        stream.start();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            // sign in
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"user\":\"admin\",\"password\":\"correct-horse\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().allValues("Set-Cookie").stream()
                    .filter(s -> s.startsWith(SessionManager.COOKIE + "="))
                    .map(s -> s.substring(0, s.indexOf(';')))
                    .findFirst().orElseThrow();

            check("stream needs a session",
                    client.send(HttpRequest.newBuilder(URI.create(base + "/api/stream")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode(), 401);
            check("no subscribers yet", stream.subscriberCount(), 0);

            // Open the stream and read it on a background thread.
            HttpResponse<InputStream> sse = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/stream"))
                            .header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            check("stream accepted", sse.statusCode(), 200);
            check("content type is SSE",
                    sse.headers().firstValue("Content-Type").orElse(""), "text/event-stream; charset=utf-8");
            check("stream is not cached",
                    sse.headers().firstValue("Cache-Control").orElse(""), "no-cache, no-store");
            check("proxy buffering disabled",
                    sse.headers().firstValue("X-Accel-Buffering").orElse(""), "no");
            check("stream is not gzipped",
                    sse.headers().firstValue("Content-Encoding").isPresent(), false);

            List<String> lines = Collections.synchronizedList(new ArrayList<>());
            // Keep the raw stream: BufferedReader.close() takes the same lock readLine()
            // holds, so closing it while the reader thread is parked on an open SSE stream
            // would block forever. Closing the socket stream is what unblocks the read.
            InputStream raw = sse.body();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(raw, StandardCharsets.UTF_8));
            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                } catch (IOException ignored) { }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            Thread.sleep(300);
            check("subscriber registered", stream.subscriberCount(), 1);
            check("viewer count reported", reportedViewers.get(), 1);
            check("retry hint sent", lines.stream().anyMatch(l -> l.startsWith("retry:")), true);

            stream.publish("pulse", "{\"tps\":19.8}");
            stream.publish("snapshot", "{\"players\":[]}");
            Thread.sleep(400);

            check("event names delivered",
                    lines.stream().filter(l -> l.equals("event: pulse")).count(), 1L);
            check("data delivered",
                    lines.stream().anyMatch(l -> l.equals("data: {\"tps\":19.8}")), true);
            check("second event delivered",
                    lines.stream().anyMatch(l -> l.equals("event: snapshot")), true);
            check("frames counted", stream.framesPublished(), 2L);
            check("nothing dropped", stream.framesDropped(), 0L);

            // A payload with newlines would split the SSE frame; Json escapes them, so a
            // single data line must survive intact.
            String tricky = Json.write(Map.of("msg", "line1\nline2\ttab"));
            stream.publish("chat", tricky);
            Thread.sleep(300);
            check("newlines cannot split a frame",
                    lines.stream().anyMatch(l -> l.equals("data: " + tricky)), true);
            check("payload has no raw newline", tricky.contains("\n"), false);

            // Second subscriber
            HttpResponse<InputStream> sse2 = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/stream"))
                            .header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            Thread.sleep(300);
            check("two subscribers", stream.subscriberCount(), 2);
            check("viewer count updated", reportedViewers.get(), 2);

            // Drop one and confirm it is reaped on the next flush.
            sse2.body().close();
            stream.publish("pulse", "{\"tps\":19.0}");
            Thread.sleep(500);
            stream.publish("pulse", "{\"tps\":18.0}");
            Thread.sleep(500);
            check("dead subscriber removed", stream.subscriberCount(), 1);
            check("viewer count went back down", reportedViewers.get(), 1);
            check("surviving subscriber still gets frames",
                    lines.stream().anyMatch(l -> l.equals("data: {\"tps\":18.0}")), true);

            // A subscriber that never reads must not block the publisher, and must lose
            // the oldest frames rather than the newest.
            long before = System.currentTimeMillis();
            for (int i = 0; i < 200; i++) stream.publish("pulse", "{\"n\":" + i + "}");
            long elapsed = System.currentTimeMillis() - before;
            check("publishing 200 frames never blocks", elapsed < 200, true);
            Thread.sleep(500);
            check("newest frame still arrives",
                    lines.stream().anyMatch(l -> l.equals("data: {\"n\":199}")), true);

            // A closed client is only discoverable on the next write -- that is all TCP
            // will tell us -- so the reap happens on the following publish, or on the
            // heartbeat if the stream is completely idle.
            raw.close();
            Thread.sleep(300);
            check("a closed client is not noticed without a write", stream.subscriberCount(), 1);

            // A write into a socket whose peer has closed normally succeeds into the local
            // buffer and only fails on the following one, so reaping takes up to two.
            for (int i = 0; i < 3 && stream.subscriberCount() > 0; i++) {
                stream.publish("pulse", "{\"tps\":20.0}");
                Thread.sleep(400);
            }
            check("writing reaps the closed client", stream.subscriberCount(), 0);
            check("viewer count is zero again", reportedViewers.get(), 0);

            long before2 = stream.framesPublished();
            stream.publish("pulse", "{\"tps\":20.0}");
            check("publishing with no subscribers is a no-op",
                    stream.framesPublished(), before2);
        } finally {
            stream.stop();
            server.stop();
            storage.close();
            deleteTree(dir);
        }
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
