package dev.godview.web.auth;

import dev.godview.config.GodViewConfig;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.web.Router;
import dev.godview.web.WebExchange;
import dev.godview.web.WebServer;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sign in, sign out, and everything about the current session.
 *
 * <p>These are the only endpoints reachable without a session, which is why the checks
 * here are spelled out rather than left to the pipeline:
 *
 * <ul>
 *   <li>Login is rate-limited per address before the password is even looked at, so the
 *       cost of PBKDF2 cannot be turned back on the server.
 *   <li>Failures are deliberately vague. "No such account" and "wrong password" are the
 *       same response, because the difference is a free account-enumeration oracle.
 *   <li>A JSON content type is required. Browsers preflight cross-origin JSON posts, and
 *       we answer no preflight, so a hostile page cannot log a visitor into an
 *       attacker-controlled account.
 * </ul>
 *
 * <p>Every outcome, good or bad, is written to the audit log — a burst of failures from
 * one address is exactly what an operator wants to find later.
 */
public final class AuthRoutes {

    /** Login bodies are tiny; anything larger is not a login attempt. */
    private static final int MAX_LOGIN_BODY = 4096;

    private final WebServer web;
    private final Supplier<Storage> storage;
    private final Supplier<GodViewConfig> config;
    private volatile java.util.function.Consumer<String> loginFailureListener;

    public AuthRoutes(WebServer web, Supplier<Storage> storage, Supplier<GodViewConfig> config) {
        this.web = web;
        this.storage = storage;
        this.config = config;
    }

    /**
     * Notified with the client address on every failed sign-in.
     *
     * <p>The alert engine uses this for {@code login_failed}. Kept as a callback rather
     * than a direct dependency because auth comes up before the engine does.
     */
    public void onLoginFailure(java.util.function.Consumer<String> listener) {
        this.loginFailureListener = listener;
    }

    public void register(Router router) {
        router.publicRoute("POST", "/api/auth/login", this::login);
        router.publicRoute("GET", "/api/auth/state", this::state);
        router.publicRoute("GET", "/api/health", this::health);
        router.publicRoute("GET", "/api/auth/link/{token}", this::redeemLink);

        router.post("/api/auth/logout", Role.Permission.VIEW, this::logout);
        router.get("/api/auth/me", Role.Permission.VIEW, this::me);
        router.get("/api/auth/sessions", Role.Permission.VIEW, this::listSessions);
        router.post("/api/auth/sessions/revoke", Role.Permission.MANAGE_USERS, this::revokeSessions);
        router.post("/api/auth/password", Role.Permission.VIEW, this::changeOwnPassword);
    }

    // ------------------------------------------------------------------- login

    private void login(WebExchange exchange) throws IOException {
        String ip = exchange.clientIp();

        // Checked before anything expensive happens, which is the entire point.
        if (web.loginGuard().isLocked(ip)) {
            long retryAfter = web.loginGuard().retryAfterSeconds(ip);
            exchange.setHeader("Retry-After", String.valueOf(retryAfter));
            audit(ip, null, "auth.login", "locked out", false);
            exchange.error(429, "locked_out",
                    "Too many failed attempts. Try again in " + retryAfter + "s.");
            return;
        }

        String contentType = exchange.header("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            exchange.error(415, "unsupported_media_type",
                    "Login requires Content-Type: application/json.");
            return;
        }

        Map<String, Object> body = exchange.json(MAX_LOGIN_BODY);
        String name = Json.str(body, "user", "").trim();
        String password = Json.str(body, "password", "");

        if (name.isEmpty() || password.isEmpty()) {
            exchange.error(400, "missing_credentials", "Both user and password are required.");
            return;
        }

        UserStore.User user = web.users().authenticate(name, password);
        if (user == null) {
            boolean nowLocked = web.loginGuard().recordFailure(ip);
            audit(ip, name, "auth.login", nowLocked ? "failed, address locked out" : "failed", false);

            java.util.function.Consumer<String> listener = loginFailureListener;
            if (listener != null) {
                try {
                    listener.accept(ip);
                } catch (Throwable ignored) {
                    // An alert rule must not turn a failed login into a 500.
                }
            }

            JsonWriter w = new JsonWriter(160);
            w.obj()
                    .field("error", "invalid_credentials")
                    // One message for both causes: naming which half was wrong would let
                    // an attacker confirm account names for free.
                    .field("message", "Incorrect account or password.")
                    .field("remainingAttempts", web.loginGuard().remainingAttempts(ip))
                    .end();
            exchange.json(401, w);
            return;
        }

        web.loginGuard().recordSuccess(ip);
        web.users().recordLogin(user.name(), ip, password);

        SessionManager.Session session =
                web.sessions().create(user, ip, exchange.header("User-Agent"));
        exchange.addHeader("Set-Cookie", web.sessions().cookieFor(session, exchange.isSecure()));
        audit(ip, user.name(), "auth.login", "signed in", true);

        writeSession(exchange, 200, session);
    }

    private void logout(WebExchange exchange) throws IOException {
        SessionManager.Session session = exchange.session();
        web.sessions().revoke(session.token());
        exchange.addHeader("Set-Cookie", web.sessions().clearCookie(exchange.isSecure()));
        audit(exchange.clientIp(), session.user(), "auth.logout", "signed out", true);
        exchange.json(200, new JsonWriter(48).obj().field("ok", true).end());
    }

    private void me(WebExchange exchange) throws IOException {
        writeSession(exchange, 200, exchange.session());
    }

    /**
     * What the login page needs before anyone has signed in.
     *
     * <p>Says whether any account exists at all, so a fresh install can show "run
     * {@code /godview user add}" instead of a login form nobody can pass. That is not a
     * useful disclosure: an attacker learns only that the panel is unconfigured, which
     * they would discover on their first attempt anyway.
     */
    private void state(WebExchange exchange) throws IOException {
        SessionManager.Session session = web.sessions().validate(
                exchange.cookie(SessionManager.COOKIE));
        JsonWriter w = new JsonWriter(200);
        w.obj()
                .field("configured", !web.users().isEmpty())
                .field("authenticated", session != null)
                .field("secure", exchange.isSecure())
                .field("lockedOut", web.loginGuard().isLocked(exchange.clientIp()));
        if (session != null) {
            w.field("user", session.user()).field("role", session.role().lower());
        }
        exchange.json(200, w.end());
    }

    /** Unauthenticated liveness check, for uptime monitors and reverse proxies. */
    private void health(WebExchange exchange) throws IOException {
        exchange.json(200, new JsonWriter(64).obj().field("ok", true).end());
    }

    /**
     * Redeems a one-time link from {@code /godview link} and redirects into the panel.
     *
     * <p>A redirect rather than JSON because this URL is pasted into a browser bar from
     * Minecraft chat; the operator should land on the dashboard, already signed in.
     */
    private void redeemLink(WebExchange exchange) throws IOException {
        String token = exchange.param("token");
        SessionManager.Session session =
                web.sessions().redeemLink(token, exchange.clientIp(), exchange.header("User-Agent"));

        if (session == null) {
            audit(exchange.clientIp(), null, "auth.link", "invalid or expired link", false);
            exchange.error(401, "invalid_link", "That login link is invalid, used or expired.");
            return;
        }

        exchange.addHeader("Set-Cookie", web.sessions().cookieFor(session, exchange.isSecure()));
        audit(exchange.clientIp(), session.user(), "auth.link", "signed in via one-time link", true);
        exchange.setHeader("Location", "/");
        exchange.send(303, new byte[0], false);
    }

    // ---------------------------------------------------------------- sessions

    private void listSessions(WebExchange exchange) throws IOException {
        SessionManager.Session current = exchange.session();
        boolean seeAll = current.can(Role.Permission.MANAGE_USERS);

        JsonWriter w = new JsonWriter(512);
        w.obj().name("sessions").arr();
        for (SessionManager.Session session : web.sessions().active()) {
            // Without user management you only get to see your own sessions.
            if (!seeAll && !session.user().equalsIgnoreCase(current.user())) continue;
            session.writeJson(w, session == current);
        }
        exchange.json(200, w.end().end());
    }

    private void revokeSessions(WebExchange exchange) throws IOException {
        Map<String, Object> body = exchange.json(MAX_LOGIN_BODY);
        String target = Json.str(body, "user", "").trim();
        SessionManager.Session current = exchange.session();

        int revoked;
        if (target.isEmpty()) {
            revoked = web.sessions().revokeAll();
            audit(exchange.clientIp(), current.user(), "auth.sessions.revoke",
                    "revoked every session", true);
        } else {
            revoked = web.sessions().revokeUser(target);
            audit(exchange.clientIp(), current.user(), "auth.sessions.revoke",
                    "revoked sessions for " + target, true);
        }
        exchange.json(200, new JsonWriter(64).obj().field("revoked", revoked).end());
    }

    /**
     * Lets a signed-in account change its own password.
     *
     * <p>The current password is required even though the session already proves identity:
     * it means a walk-up to an unlocked browser cannot silently take the account over.
     * Every other session for the account is ended, since a password change usually means
     * the old one is suspect.
     */
    private void changeOwnPassword(WebExchange exchange) throws IOException {
        Map<String, Object> body = exchange.json(MAX_LOGIN_BODY);
        String current = Json.str(body, "currentPassword", "");
        String replacement = Json.str(body, "newPassword", "");
        SessionManager.Session session = exchange.session();

        if (web.users().authenticate(session.user(), current) == null) {
            audit(exchange.clientIp(), session.user(), "auth.password",
                    "current password did not match", false);
            exchange.error(403, "wrong_password", "That is not your current password.");
            return;
        }

        UserStore.Result result = web.users().setPassword(session.user(), replacement);
        if (!result.ok()) {
            exchange.error(400, "invalid_password", result.message());
            return;
        }

        web.sessions().revokeUser(session.user());
        exchange.addHeader("Set-Cookie", web.sessions().clearCookie(exchange.isSecure()));
        audit(exchange.clientIp(), session.user(), "auth.password",
                "password changed, all sessions ended", true);
        exchange.json(200, new JsonWriter(96)
                .obj()
                .field("ok", true)
                .field("message", "Password changed. Sign in again.")
                .end());
    }

    // ----------------------------------------------------------------- helpers

    private void writeSession(WebExchange exchange, int status, SessionManager.Session session)
            throws IOException {
        List<String> permissions = session.role().permissions().stream()
                .map(p -> p.name().toLowerCase(Locale.ROOT))
                .toList();

        JsonWriter w = new JsonWriter(320);
        w.obj()
                .field("user", session.user())
                .field("role", session.role().lower())
                // The panel echoes this in X-GodView-CSRF on every state-changing request.
                .field("csrf", session.csrf())
                .field("expiresAt", session.expiresAt())
                .field("permissions", permissions)
                .end();
        exchange.json(status, w);
    }

    private void audit(String ip, String user, String action, String detail, boolean success) {
        if (!config.get().security.audit()) return;
        Storage store = storage.get();
        if (store == null) return;
        store.recordAudit(new Storage.AuditEntry(System.currentTimeMillis(),
                user == null ? "-" : user, ip, action, user == null ? "-" : user, detail, success));
    }
}
