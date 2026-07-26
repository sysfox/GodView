package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.storage.MemoryStorage;
import dev.godview.storage.Storage;
import dev.godview.web.WebServer;
import dev.godview.web.auth.AuthRoutes;
import dev.godview.web.auth.Role;
import dev.godview.web.auth.SessionManager;
import dev.godview.metrics.MetricsService;
import dev.godview.web.api.ApiContext;
import dev.godview.web.api.ApiRoutes;
import dev.godview.web.api.EventStream;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * The built Vue panel served through the real static handler.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class PanelSuiteTest {

    @Test
    void verifies() throws Exception {
        Logger log = Logger.getLogger("panelsmoke");
        log.setLevel(Level.SEVERE);

        Path dir = Files.createTempDirectory("godview-panel-test");
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        String base = "http://127.0.0.1:" + port;

        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                web:
                  enabled: true
                  bind: "127.0.0.1"
                  port: %d
                  threads: 4
                  gzip: true
                storage:
                  type: memory
                """.formatted(port))));

        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();

        WebServer server = new WebServer(cfg, dir, PanelSuiteTest.class.getClassLoader(), log);
        new AuthRoutes(server, () -> storage, () -> cfg).register(server.router());

        // The read API too, so the dashboard payload the panel actually loads is
        // covered. MetricsService is constructed but never started: with no server
        // to sample it publishes its empty sample, which is exactly the shape a
        // panel sees in the first second after a restart.
        MetricsService metrics = new MetricsService(null, cfg);
        EventStream stream = new EventStream(log, count -> { });
        new ApiRoutes(new ApiContext(null, metrics, () -> storage, server, () -> cfg,
                stream, () -> null, () -> null, () -> null)).register(server.router());

        server.users().add("admin", Role.ADMIN, "correct-horse", null);
        server.start();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try {
            System.out.println("========== the shell ==========");
            HttpResponse<String> index = get(client, base + "/");
            check("index is served", index.statusCode(), 200);

            // Built with -Dgodview.panel.skip=true: the server correctly serves its
            // API-only placeholder, and there is no bundle to make assertions about.
            // Skipping beats failing -- the Java-only build is a supported way to work.
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    index.body().contains("<div id=\"app\">"),
                    "panel assets are not in this build; skipping the bundle assertions");

            check("index is html",
                    index.headers().firstValue("Content-Type").orElse(""), "text/html; charset=utf-8");
            check("index is not cached hard",
                    index.headers().firstValue("Cache-Control").orElse(""), "no-cache");
            check("it is the built panel, not the placeholder",
                    index.body().contains("<div id=\"app\">"), true);
            check("panel titles itself", index.body().contains("<title>GodView</title>"), true);

            // The single thing that would break the panel in production while
            // working perfectly in `npm run dev`.
            System.out.println("\n========== content security policy ==========");
            String csp = index.headers().firstValue("Content-Security-Policy").orElse("");
            check("a CSP is sent", csp.isEmpty(), false);
            check("scripts are same-origin only",
                    csp.contains("default-src 'self'"), true);
            check("no unsafe-inline for scripts",
                    csp.contains("script-src") && csp.contains("script-src 'unsafe-inline'"), false);
            check("map tiles may use blob urls", csp.contains("blob:"), true);
            check("the live stream may connect", csp.contains("connect-src 'self'"), true);

            String body = index.body();
            check("no inline script survived the build",
                    body.matches("(?s).*<script(?![^>]*\\bsrc=)[^>]*>\\s*\\S.*"), false);

            String scriptSrc = between(body, "<script type=\"module\" crossorigin src=\"", "\"");
            String styleHref = between(body, "<link rel=\"stylesheet\" crossorigin href=\"", "\"");
            check("index references a bundled script", scriptSrc.startsWith("/assets/"), true);
            check("index references a bundled stylesheet", styleHref.startsWith("/assets/"), true);

            System.out.println("\n========== bundled assets ==========");
            HttpResponse<String> script = get(client, base + scriptSrc);
            check("script is served", script.statusCode(), 200);
            check("script has a javascript type",
                    script.headers().firstValue("Content-Type").orElse("").contains("javascript"), true);
            check("hashed asset is cached hard",
                    script.headers().firstValue("Cache-Control").orElse("").contains("max-age=604800"), true);
            check("script contains the app", script.body().contains("GodView"), true);

            HttpResponse<String> style = get(client, base + styleHref);
            check("stylesheet is served", style.statusCode(), 200);
            check("stylesheet has a css type",
                    style.headers().firstValue("Content-Type").orElse("").contains("text/css"), true);
            check("the design tokens survived the build",
                    style.body().contains("--deepslate"), true);

            System.out.println("\n========== caching and compression ==========");
            String etag = script.headers().firstValue("ETag").orElse("");
            check("assets carry an ETag", etag.isEmpty(), false);
            HttpResponse<String> revalidated = client.send(
                    HttpRequest.newBuilder(URI.create(base + scriptSrc))
                            .header("If-None-Match", etag).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check("an unchanged asset revalidates to 304", revalidated.statusCode(), 304);

            HttpResponse<byte[]> gz = client.send(
                    HttpRequest.newBuilder(URI.create(base + scriptSrc))
                            .header("Accept-Encoding", "gzip").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check("the bundle is gzipped",
                    gz.headers().firstValue("Content-Encoding").orElse(""), "gzip");
            check("gzip roughly thirds it",
                    gz.body().length < script.body().length() / 2, true);

            System.out.println("\n========== routing ==========");
            // Hash routes never reach the server, but a refresh on a bare path must
            // still land on the shell rather than a 404.
            check("an unknown path falls back to the shell",
                    get(client, base + "/settings").statusCode(), 200);
            check("a missing asset is a real 404",
                    get(client, base + "/assets/not-real.js").statusCode(), 404);
            // An unknown API path must 404 as JSON. Falling through to the HTML
            // shell would hand every mistyped fetch a page of markup to parse.
            HttpResponse<String> missing = get(client, base + "/api/nope");
            check("an unknown api path 404s", missing.statusCode(), 404);
            check("and answers json, not the shell",
                    missing.headers().firstValue("Content-Type").orElse(""),
                    "application/json; charset=utf-8");
            check("health stays json",
                    get(client, base + "/api/health").headers()
                            .firstValue("Content-Type").orElse(""), "application/json; charset=utf-8");

            System.out.println("\n========== the panel can actually sign in ==========");
            HttpResponse<String> gate = get(client, base + "/api/auth/state");
            check("state is reachable without a session", gate.statusCode(), 200);
            check("state says an account exists", gate.body().contains("\"configured\":true"), true);

            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"user\":\"admin\",\"password\":\"correct-horse\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check("sign-in works", login.statusCode(), 200);
            check("a csrf token is issued for the panel", login.body().contains("\"csrf\""), true);
            check("permissions are listed for the ui", login.body().contains("player_action"), true);

            String cookie = login.headers().allValues("Set-Cookie").stream()
                    .filter(s -> s.startsWith(SessionManager.COOKIE + "="))
                    .map(s -> s.substring(0, s.indexOf(';')))
                    .findFirst().orElse("");
            HttpResponse<String> overview = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/overview"))
                            .header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check("the dashboard payload loads", overview.statusCode(), 200);
            check("it carries the tick ribbon data", overview.body().contains("\"ticks\""), true);
        } finally {
            server.stop();
            storage.close();
            deleteTree(dir);
        }
    }

    static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) return "";
        start += open.length();
        int end = text.indexOf(close, start);
        return end < 0 ? "" : text.substring(start, end);
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
