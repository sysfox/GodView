package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.storage.MemoryStorage;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.web.Router;
import dev.godview.web.WebServer;
import dev.godview.web.auth.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * The web server and its authentication pipeline, over real HTTP.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class WebSuiteTest {
    static String base;
    static HttpClient client;

    // ---- tiny HTTP helpers -------------------------------------------------

    record Resp(int status, String body, HttpHeaders headers) {
        Map<String, Object> json() { return Json.asObj(Json.parse(body)); }
        String header(String n) { return headers.firstValue(n).orElse(null); }
        List<String> all(String n) { return headers.allValues(n); }
    }

    static Resp get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static Resp post(String path, String body, String cookie, String csrf, String contentType)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (contentType != null) b.header("Content-Type", contentType);
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-GodView-CSRF", csrf);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static String cookieFrom(Resp r) {
        for (String set : r.all("Set-Cookie")) {
            if (set.startsWith(SessionManager.COOKIE + "=")) {
                int semi = set.indexOf(';');
                return semi < 0 ? set : set.substring(0, semi);
            }
        }
        return null;
    }

    // ---- config ------------------------------------------------------------

    static GodViewConfig config(int port, String extra) {
        String yaml = """
                locale: en_US
                web:
                  enabled: true
                  bind: "127.0.0.1"
                  port: %d
                  threads: 4
                  max-body-kb: 8
                  gzip: true
                  behind-proxy: false
                  trusted-proxies: []
                  tls:
                    enabled: false
                security:
                  session-timeout-minutes: 120
                  session-sliding-renew: true
                  max-sessions-per-user: 3
                  login:
                    max-attempts: 3
                    window-seconds: 300
                    lockout-seconds: 2
                  audit: true
                %s
                storage:
                  type: memory
                """.formatted(port, extra);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return GodViewConfig.from(y);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @Test
    void verifies() throws Exception {
        Logger log = Logger.getLogger("websmoke");
        log.setLevel(Level.SEVERE);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        Path dir = Files.createTempDirectory("godview-web-test");
        int port = freePort();
        base = "http://127.0.0.1:" + port;

        GodViewConfig cfg = config(port, "  ip-allowlist: []");
        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();

        WebServer server = new WebServer(cfg, dir, WebSuiteTest.class.getClassLoader(), log);
        new AuthRoutes(server, () -> storage, () -> cfg).register(server.router());

        // A route that only an admin may reach, to prove the table drives authorization.
        server.router().get("/api/test/admin-only", Role.Permission.MANAGE_USERS,
                ex -> ex.json(200, "{\"secret\":true}"));
        server.router().get("/api/test/big", Role.Permission.VIEW,
                ex -> ex.json(200, "{\"pad\":\"" + "x".repeat(4000) + "\"}"));
        server.router().post("/api/test/echo", Role.Permission.VIEW,
                ex -> ex.json(200, "{\"len\":" + ex.body(cfg.web.maxBodyBytes()).length + "}"));

        check("no accounts yet", server.users().isEmpty(), true);
        server.users().add("admin", Role.ADMIN, "correct-horse", null);
        server.users().add("watcher", Role.VIEWER, "correct-horse", null);

        server.start();
        check("server running", server.isRunning(), true);

        try {
            run(server, storage, dir, port);
        } finally {
            server.stop();
            storage.close();
            deleteTree(dir);
        }
    }

    static void run(WebServer server, Storage storage, Path dir, int port) throws Exception {
        System.out.println("\n---------- public endpoints ----------");
        Resp health = get("/api/health", null);
        check("health is public", health.status(), 200);
        check("health body", health.json().get("ok"), true);

        Resp state = get("/api/auth/state", null);
        check("state is public", state.status(), 200);
        check("state reports configured", state.json().get("configured"), true);
        check("state reports anonymous", state.json().get("authenticated"), false);

        System.out.println("\n---------- security headers ----------");
        check("nosniff", health.header("X-Content-Type-Options"), "nosniff");
        check("frame options", health.header("X-Frame-Options"), "DENY");
        check("referrer policy", health.header("Referrer-Policy"), "no-referrer");
        check("csp present", health.header("Content-Security-Policy").contains("default-src 'self'"), true);
        check("no HSTS on plain http", health.header("Strict-Transport-Security"), null);

        System.out.println("\n---------- unauthenticated access ----------");
        check("me needs a session", get("/api/auth/me", null).status(), 401);
        check("admin route needs a session", get("/api/test/admin-only", null).status(), 401);
        Resp unauth = get("/api/auth/me", null);
        check("401 names the reason", unauth.json().get("error"), "unauthenticated");
        check("bogus cookie is rejected",
                get("/api/auth/me", SessionManager.COOKIE + "=not-a-real-token").status(), 401);

        System.out.println("\n---------- login validation ----------");
        check("login rejects form encoding",
                post("/api/auth/login", "user=admin", null, null,
                        "application/x-www-form-urlencoded").status(), 415);
        check("login rejects no content type",
                post("/api/auth/login", "{}", null, null, null).status(), 415);
        check("login needs both fields",
                post("/api/auth/login", "{\"user\":\"admin\"}", null, null, "application/json").status(),
                400);
        check("oversized login body",
                post("/api/auth/login", "{\"user\":\"" + "a".repeat(9000) + "\"}", null, null,
                        "application/json").status(), 413);

        System.out.println("\n---------- wrong credentials ----------");
        Resp bad1 = post("/api/auth/login", "{\"user\":\"admin\",\"password\":\"wrong\"}",
                null, null, "application/json");
        check("wrong password rejected", bad1.status(), 401);
        check("no session cookie issued", cookieFrom(bad1), null);
        check("attempts counted down", bad1.json().get("remainingAttempts"), 2L);

        Resp bad2 = post("/api/auth/login", "{\"user\":\"ghost\",\"password\":\"wrong\"}",
                null, null, "application/json");
        check("unknown account gives the same error", bad2.json().get("error"), "invalid_credentials");
        check("unknown account gives the same message",
                bad2.json().get("message"), bad1.json().get("message"));

        System.out.println("\n---------- lockout ----------");
        Resp bad3 = post("/api/auth/login", "{\"user\":\"admin\",\"password\":\"wrong\"}",
                null, null, "application/json");
        check("third failure still 401", bad3.status(), 401);
        Resp locked = post("/api/auth/login", "{\"user\":\"admin\",\"password\":\"correct-horse\"}",
                null, null, "application/json");
        check("locked out even with the right password", locked.status(), 429);
        check("lockout names itself", locked.json().get("error"), "locked_out");
        check("retry-after header", locked.header("Retry-After") != null, true);

        System.out.println("waiting out the 2s lockout...");
        Thread.sleep(2200);

        System.out.println("\n---------- successful login ----------");
        Resp ok = post("/api/auth/login", "{\"user\":\"admin\",\"password\":\"correct-horse\"}",
                null, null, "application/json");
        check("login succeeds after lockout expires", ok.status(), 200);
        String adminCookie = cookieFrom(ok);
        String adminCsrf = (String) ok.json().get("csrf");
        check("cookie issued", adminCookie != null, true);
        check("csrf issued", adminCsrf != null && adminCsrf.length() > 10, true);
        check("role reported", ok.json().get("role"), "admin");

        String rawSetCookie = ok.all("Set-Cookie").get(0);
        check("cookie is HttpOnly", rawSetCookie.contains("HttpOnly"), true);
        check("cookie is SameSite=Strict", rawSetCookie.contains("SameSite=Strict"), true);
        check("cookie is not Secure over http", rawSetCookie.contains("Secure"), false);

        Resp me = get("/api/auth/me", adminCookie);
        check("me works with a session", me.status(), 200);
        check("me reports the user", me.json().get("user"), "admin");
        check("me lists permissions",
                Json.asArr(me.json().get("permissions")).contains("manage_users"), true);

        System.out.println("\n---------- CSRF ----------");
        check("POST without csrf header is refused",
                post("/api/auth/logout", "", adminCookie, null, "application/json").status(), 403);
        check("POST with a wrong csrf token is refused",
                post("/api/auth/logout", "", adminCookie, "wrong-token-wrong-token",
                        "application/json").status(), 403);
        Resp csrfFail = post("/api/auth/logout", "", adminCookie, null, "application/json");
        check("csrf failure names itself", csrfFail.json().get("error"), "csrf_failed");
        check("GET needs no csrf header", get("/api/auth/me", adminCookie).status(), 200);

        System.out.println("\n---------- permissions ----------");
        check("admin reaches the admin route", get("/api/test/admin-only", adminCookie).status(), 200);

        Resp viewerLogin = post("/api/auth/login", "{\"user\":\"watcher\",\"password\":\"correct-horse\"}",
                null, null, "application/json");
        String viewerCookie = cookieFrom(viewerLogin);
        String viewerCsrf = (String) viewerLogin.json().get("csrf");
        check("viewer can log in", viewerLogin.status(), 200);
        check("viewer role", viewerLogin.json().get("role"), "viewer");
        Resp denied = get("/api/test/admin-only", viewerCookie);
        check("viewer is refused the admin route", denied.status(), 403);
        check("403 names the role", denied.json().get("message").toString().contains("viewer"), true);
        check("viewer still reaches a VIEW route", get("/api/auth/me", viewerCookie).status(), 200);

        System.out.println("\n---------- routing ----------");
        check("unknown api path 404s", get("/api/nope", adminCookie).status(), 404);
        Resp wrongMethod = post("/api/auth/me", "{}", adminCookie, adminCsrf, "application/json");
        check("wrong method 405s", wrongMethod.status(), 405);
        check("405 advertises Allow", wrongMethod.header("Allow"), "GET");
        check("path variables bind",
                get("/api/auth/link/not-a-real-token", null).status(), 401);
        check("trailing slash is the same route", get("/api/health/", null).status(), 200);

        System.out.println("\n---------- body limits ----------");
        check("body under the cap is accepted",
                post("/api/test/echo", "{\"x\":1}", adminCookie, adminCsrf, "application/json").status(),
                200);
        check("body over web.max-body-kb is refused",
                post("/api/test/echo", "x".repeat(9000), adminCookie, adminCsrf,
                        "application/json").status(), 413);

        System.out.println("\n---------- gzip ----------");
        HttpResponse<byte[]> gz = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/test/big"))
                        .header("Cookie", adminCookie)
                        .header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        check("large response is gzipped",
                gz.headers().firstValue("Content-Encoding").orElse(""), "gzip");
        check("gzip sets Vary", gz.headers().allValues("Vary").contains("Accept-Encoding"), true);
        check("gzip actually shrinks it", gz.body().length < 4000, true);

        HttpResponse<String> plain = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/test/big"))
                        .header("Cookie", adminCookie)
                        .header("Accept-Encoding", "identity").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check("identity response is not encoded",
                plain.headers().firstValue("Content-Encoding").isPresent(), false);
        check("identity response is whole", plain.body().length() > 4000, true);

        Resp small = get("/api/health", null);
        check("small response is not gzipped", small.header("Content-Encoding"), null);

        System.out.println("\n---------- static assets ----------");
        Resp root = get("/", null);
        check("root serves something without auth", root.status(), 200);
        check("root is html", root.header("Content-Type").contains("text/html"), true);
        check("missing asset 404s", get("/app.js", null).status(), 404);

        System.out.println("\n---------- sessions ----------");
        Resp sessions = get("/api/auth/sessions", adminCookie);
        check("admin sees sessions", sessions.status(), 200);
        check("admin sees both accounts",
                Json.asArr(sessions.json().get("sessions")).size() >= 2, true);
        Resp viewerSessions = get("/api/auth/sessions", viewerCookie);
        check("viewer sees only their own",
                Json.asArr(viewerSessions.json().get("sessions")).size(), 1);

        // max-sessions-per-user is 3; a fourth login must evict the oldest.
        String c1 = cookieFrom(post("/api/auth/login", "{\"user\":\"watcher\",\"password\":\"correct-horse\"}",
                null, null, "application/json"));
        Thread.sleep(5);
        String c2 = cookieFrom(post("/api/auth/login", "{\"user\":\"watcher\",\"password\":\"correct-horse\"}",
                null, null, "application/json"));
        Thread.sleep(5);
        String c3 = cookieFrom(post("/api/auth/login", "{\"user\":\"watcher\",\"password\":\"correct-horse\"}",
                null, null, "application/json"));
        check("newest session works", get("/api/auth/me", c3).status(), 200);
        check("oldest session was evicted", get("/api/auth/me", viewerCookie).status(), 401);
        check("only max-sessions remain for that user",
                Json.asArr(get("/api/auth/sessions", c3).json().get("sessions")).size(), 3);

        System.out.println("\n---------- logout ----------");
        Resp loggedOut = post("/api/auth/logout", "", adminCookie, adminCsrf, "application/json");
        check("logout succeeds", loggedOut.status(), 200);
        check("logout clears the cookie",
                loggedOut.all("Set-Cookie").stream().anyMatch(s -> s.contains("Max-Age=0")), true);
        check("session is dead immediately", get("/api/auth/me", adminCookie).status(), 401);

        System.out.println("\n---------- one-time login links ----------");
        UserStore.User admin = server.users().get("admin");
        String linkToken = server.sessions().createLoginLink(admin);
        HttpClient noRedirect = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> redeemed = noRedirect.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/link/" + linkToken)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check("link redirects into the panel", redeemed.statusCode(), 303);
        check("link sets a session cookie",
                redeemed.headers().allValues("Set-Cookie").stream()
                        .anyMatch(s -> s.startsWith(SessionManager.COOKIE + "=")), true);
        check("link lands on the dashboard",
                redeemed.headers().firstValue("Location").orElse(""), "/");
        check("a link cannot be used twice",
                get("/api/auth/link/" + linkToken, null).status(), 401);

        System.out.println("\n---------- audit trail ----------");
        List<Storage.AuditEntry> audit = storage.queryAudit(0, Long.MAX_VALUE, 100);
        check("logins were audited",
                audit.stream().anyMatch(a -> a.action().equals("auth.login") && a.success()), true);
        check("failures were audited",
                audit.stream().anyMatch(a -> a.action().equals("auth.login") && !a.success()), true);
        check("lockout was audited",
                audit.stream().anyMatch(a -> "locked out".equals(a.detail())), true);
        check("logout was audited",
                audit.stream().anyMatch(a -> a.action().equals("auth.logout")), true);
        check("audit records the address",
                audit.stream().allMatch(a -> a.ip() != null && !a.ip().isEmpty()), true);

        System.out.println("\n---------- ip allowlist ----------");
        int port2 = freePort();
        GodViewConfig blocked = config(port2, "  ip-allowlist:\n    - \"10.99.0.0/16\"");
        WebServer server2 = new WebServer(blocked, dir, WebSuiteTest.class.getClassLoader(),
                Logger.getLogger("blocked"));
        new AuthRoutes(server2, () -> storage, () -> blocked).register(server2.router());
        server2.start();
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port2 + "/api/health"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            check("allowlist blocks a non-listed address", r.statusCode(), 403);
            check("403 explains itself",
                    Json.str(Json.asObj(Json.parse(r.body())), "error", ""), "ip_not_allowed");
        } finally {
            server2.stop();
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
