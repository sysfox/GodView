package dev.godview.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.godview.config.GodViewConfig;
import dev.godview.web.auth.IpRules;
import dev.godview.web.auth.LoginGuard;
import dev.godview.web.auth.Role;
import dev.godview.web.auth.SessionManager;
import dev.godview.web.auth.UserStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The panel's HTTP front door.
 *
 * <p>Built on the JDK's own {@code com.sun.net.httpserver} — no shaded framework, no extra
 * megabytes, no classloader argument with the Netty a Minecraft server already runs.
 *
 * <p>Every request passes the same pipeline before a handler sees it:
 *
 * <ol>
 *   <li>resolve the real client address (see {@link IpRules} on why the header is not
 *       trusted by default),
 *   <li>allowlist check,
 *   <li>route lookup — the required permission comes from the route table, never from the
 *       handler, so it cannot be forgotten,
 *   <li>session lookup for non-public routes,
 *   <li>permission check,
 *   <li>CSRF check on anything that is not a GET.
 * </ol>
 *
 * <p>The worker pool is deliberately tiny, and stays viable because streaming responses do
 * not hold a worker: the SSE handler writes its headers, hands the stream to the
 * broadcaster and returns, so a hundred open panels still cost two threads.
 */
public final class WebServer {

    /** Requests that may wait for a worker before we start refusing connections. */
    private static final int QUEUE_CAPACITY = 256;
    /** Sessions and login records are swept on this interval. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private final Logger log;
    private final Path dataFolder;
    private final Router router;
    private final StaticAssets assets;
    private final UserStore users;
    private final SessionManager sessions;
    private final LoginGuard loginGuard;

    private volatile GodViewConfig config;
    private volatile IpRules ipRules;
    private volatile boolean secure;

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private Thread sweeper;
    private volatile boolean running;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public WebServer(GodViewConfig config, Path dataFolder, ClassLoader assetLoader, Logger log) {
        this.config = config;
        this.dataFolder = dataFolder;
        this.log = log;
        this.router = new Router();
        this.assets = new StaticAssets(assetLoader, false);
        this.users = new UserStore(dataFolder.resolve("users.yml"), log);
        this.sessions = new SessionManager(config.security);
        this.loginGuard = new LoginGuard(config.security);
        this.ipRules = buildIpRules(config);
    }

    // --------------------------------------------------------------- lifecycle

    /** Registers routes before {@link #start()}; the table is fixed once serving begins. */
    public Router router() {
        return router;
    }

    public void start() throws IOException {
        if (running) return;
        GodViewConfig.Web web = config.web;
        if (!web.enabled()) {
            log.info("GodView's web panel is disabled in config.yml.");
            return;
        }

        users.load();
        if (users.isEmpty()) {
            log.warning("GodView has no panel accounts yet — nobody can log in. "
                    + "Create one with: /godview user add <name> admin");
        }

        InetSocketAddress address = new InetSocketAddress(web.bind(), web.port());
        if (web.tlsEnabled()) {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(httpsConfigurator(web));
            server = https;
            secure = true;
        } else {
            server = HttpServer.create(address, 0);
            secure = false;
        }

        AtomicInteger counter = new AtomicInteger();
        // A bounded queue with the default abort policy: under a flood we refuse
        // connections outright rather than growing a queue until the JVM runs out of heap.
        executor = new ThreadPoolExecutor(web.threads(), web.threads(), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "GodView-Web-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(false);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        running = true;

        startSweeper();
        log.info("GodView panel listening on " + (secure ? "https" : "http") + "://"
                + web.bind() + ":" + web.port());
        if (!assets.available()) {
            log.warning("GodView's panel assets are missing from the jar — only the API is served.");
        }
    }

    public void stop() {
        running = false;
        if (sweeper != null) {
            sweeper.interrupt();
            sweeper = null;
        }
        if (server != null) {
            // A short delay lets in-flight requests finish; anything streaming is cut.
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Applies a new config.
     *
     * <p>Bind address, port, thread count and TLS are marked {@code [restart]} in
     * {@code config.yml} and are not re-read here — rebinding a socket under live
     * connections is how a reload turns into an outage. Everything else takes effect at
     * once, and sessions survive.
     */
    public void reload(GodViewConfig newConfig) {
        this.config = newConfig;
        this.ipRules = buildIpRules(newConfig);
        this.sessions.reconfigure(newConfig.security);
        this.loginGuard.reconfigure(newConfig.security);
        this.assets.clearCache();
        this.users.load();
    }

    public boolean isRunning() {
        return running;
    }

    public UserStore users() {
        return users;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public LoginGuard loginGuard() {
        return loginGuard;
    }

    public StaticAssets assets() {
        return assets;
    }

    public long totalRequests() {
        return requests.get();
    }

    public long rejectedRequests() {
        return rejected.get();
    }

    public long failedRequests() {
        return errors.get();
    }

    /** Worker threads currently busy, for the diagnostics view. */
    public int activeWorkers() {
        ThreadPoolExecutor pool = executor;
        return pool == null ? 0 : pool.getActiveCount();
    }

    // ---------------------------------------------------------------- pipeline

    private void dispatch(HttpExchange http) {
        requests.incrementAndGet();
        WebExchange exchange = null;
        try {
            String peer = http.getRemoteAddress() == null ? ""
                    : http.getRemoteAddress().getAddress().getHostAddress();
            String clientIp = ipRules.clientIp(peer, firstHeader(http, "X-Forwarded-For"));
            exchange = new WebExchange(http, clientIp, config.web.gzip(), secure);

            applySecurityHeaders(exchange);

            if (!ipRules.allowed(clientIp)) {
                rejected.incrementAndGet();
                exchange.error(403, "ip_not_allowed",
                        "This address is not in security.ip-allowlist.");
                return;
            }

            handle(exchange);
        } catch (WebExchange.BodyTooLargeException e) {
            safeError(exchange, 413, "body_too_large", e.getMessage());
        } catch (Throwable t) {
            errors.incrementAndGet();
            log.log(Level.WARNING, "GodView request failed: " + http.getRequestURI(), t);
            // Never echo the exception to the client; it would leak paths and versions.
            safeError(exchange, 500, "internal_error", "The panel hit an unexpected error.");
        } finally {
            // A streaming handler keeps its exchange open on purpose and closes it when
            // the client disconnects. Everything else is finished the moment it returns,
            // including the error paths above.
            if (exchange == null || !exchange.isStreaming()) http.close();
        }
    }

    private void handle(WebExchange exchange) throws Exception {
        Router.Match match = router.match(exchange.method(), exchange.path());

        if (match == null) {
            if (serveStatic(exchange)) return;
            if (router.pathExists(exchange.path())) {
                exchange.setHeader("Allow", router.allowedMethods(exchange.path()));
                exchange.error(405, "method_not_allowed",
                        exchange.method() + " is not allowed on this path.");
            } else {
                exchange.error(404, "not_found", "No such endpoint.");
            }
            return;
        }

        if (!match.isPublic() && !authorize(exchange, match)) return;

        exchange.setParams(match.params());
        match.handler().handle(exchange);
    }

    /** @return true when the request may proceed; otherwise a response has been sent */
    private boolean authorize(WebExchange exchange, Router.Match match) throws IOException {
        SessionManager.Session session = sessions.validate(exchange.cookie(SessionManager.COOKIE));
        if (session == null) {
            exchange.error(401, "unauthenticated", "Sign in to continue.");
            return false;
        }
        exchange.setSession(session);

        Role.Permission required = match.required();
        if (required != null && !session.can(required)) {
            exchange.error(403, "forbidden",
                    "Your role (" + session.role().lower() + ") cannot do that.");
            return false;
        }

        // The session cookie is SameSite=Strict, so a cross-site form post cannot carry it.
        // The header is a second, independent barrier: a cross-origin request cannot set a
        // custom header without a preflight the browser will refuse.
        if (!"GET".equals(exchange.method())) {
            String presented = exchange.header(SessionManager.CSRF_HEADER);
            if (presented == null || !constantTimeEquals(presented, session.csrf())) {
                exchange.error(403, "csrf_failed",
                        "Missing or stale " + SessionManager.CSRF_HEADER + " header.");
                return false;
            }
        }
        return true;
    }

    private boolean serveStatic(WebExchange exchange) throws IOException {
        if (!"GET".equals(exchange.method()) && !"HEAD".equals(exchange.method())) return false;
        // /api is the API's namespace; a miss there must 404, never fall through to the page.
        if (exchange.path().startsWith("/api/") || exchange.path().equals("/api")) return false;

        if (assets.serve(exchange, exchange.path())) return true;
        if ("/".equals(exchange.path())) {
            exchange.bytes(200, "text/html; charset=utf-8", StaticAssets.placeholderPage(), true);
            return true;
        }
        return false;
    }

    private void applySecurityHeaders(WebExchange exchange) {
        exchange.setHeader("X-Content-Type-Options", "nosniff");
        exchange.setHeader("X-Frame-Options", "DENY");
        exchange.setHeader("Referrer-Policy", "no-referrer");
        // The panel is entirely self-hosted, so nothing needs to load from anywhere else.
        // 'unsafe-inline' for styles only: the panel inlines its theme variables.
        exchange.setHeader("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                        + "connect-src 'self'; font-src 'self' data:; object-src 'none'; "
                        + "base-uri 'none'; frame-ancestors 'none'");
        if (secure) {
            exchange.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
    }

    // ------------------------------------------------------------------- TLS

    private HttpsConfigurator httpsConfigurator(GodViewConfig.Web web) throws IOException {
        Path keystore = dataFolder.resolve(web.keystore());
        if (!Files.exists(keystore)) {
            throw new IOException("web.tls is enabled but " + keystore + " does not exist");
        }
        try {
            char[] password = web.keystorePassword() == null ? new char[0]
                    : web.keystorePassword().toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, password);
            }
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);

            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);

            return new HttpsConfigurator(ssl) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLEngine engine = getSSLContext().createSSLEngine();
                    SSLParameters defaults = getSSLContext().getDefaultSSLParameters();
                    // TLS 1.0 and 1.1 are long dead; a panel should not offer them.
                    defaults.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    defaults.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setSSLParameters(defaults);
                }
            };
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not load the TLS keystore " + keystore
                    + " — check web.tls.password", e);
        }
    }

    // --------------------------------------------------------------- upkeep

    private void startSweeper() {
        sweeper = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MS);
                    sessions.purgeExpired();
                    loginGuard.purge();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    log.log(Level.WARNING, "GodView session sweep failed", t);
                }
            }
        }, "GodView-WebSweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private IpRules buildIpRules(GodViewConfig cfg) {
        for (String bad : IpRules.invalidEntries(cfg.security.ipAllowlist())) {
            log.warning("security.ip-allowlist entry '" + bad + "' is not an address or CIDR range — ignored");
        }
        for (String bad : IpRules.invalidEntries(cfg.web.trustedProxies())) {
            log.warning("web.trusted-proxies entry '" + bad + "' is not an address or CIDR range — ignored");
        }
        if (cfg.web.behindProxy() && cfg.web.trustedProxies().isEmpty()) {
            log.warning("web.behind-proxy is on but web.trusted-proxies is empty — "
                    + "X-Forwarded-For will be ignored, which is the safe reading. "
                    + "List your proxy's address to enable it.");
        }
        return new IpRules(cfg.security.ipAllowlist(), cfg.web.behindProxy(),
                cfg.web.trustedProxies());
    }

    // --------------------------------------------------------------- helpers

    private void safeError(WebExchange exchange, int status, String code, String message) {
        if (exchange == null || exchange.responded()) return;
        try {
            exchange.error(status, code, message);
        } catch (IOException ignored) {
            // The client is already gone; there is nowhere to report this.
        }
    }

    private static String firstHeader(HttpExchange http, String name) {
        List<String> values = http.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Compares two tokens without leaking their common prefix through timing. */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
