package dev.godview.web.auth;

import dev.godview.config.GodViewConfig;
import dev.godview.util.JsonWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side sessions for the panel.
 *
 * <p>Server-side rather than a signed stateless token, because the operations that matter
 * here need immediate revocation. {@code /godview sessions revoke} and "this account is
 * compromised, cut it off now" must take effect on the next request, not whenever a JWT
 * happens to expire.
 *
 * <p>Each session carries a CSRF token in addition to its cookie. The cookie is
 * {@code HttpOnly} and {@code SameSite=Strict}, and every state-changing request must also
 * echo the CSRF value in a header — which a cross-site request cannot read, and cannot set
 * without a preflight the browser will refuse.
 *
 * <p>One-time login links are held in the same structure with a short expiry, so
 * {@code /godview link} can hand an operator a URL in chat without them typing a password
 * into a game client.
 */
public final class SessionManager {

    /** Cookie the browser sends back on every request. */
    public static final String COOKIE = "gv_session";
    /** Header a state-changing request must carry, matching {@link Session#csrf()}. */
    public static final String CSRF_HEADER = "X-GodView-CSRF";

    /** Session tokens are 32 bytes; guessing one is not a realistic attack. */
    private static final int TOKEN_BYTES = 32;
    /** One-time login links are short-lived by design. */
    private static final long LINK_TTL_MS = 300_000L;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingLink> links = new ConcurrentHashMap<>();
    private final AtomicLong created = new AtomicLong();

    private volatile GodViewConfig.Security config;

    public SessionManager(GodViewConfig.Security config) {
        this.config = config;
    }

    /** Applies new timeouts on reload. Existing sessions keep working under the new rules. */
    public void reconfigure(GodViewConfig.Security config) {
        this.config = config;
        enforcePerUserLimit(null);
    }

    /**
     * An authenticated browser.
     *
     * <p>Mutable where it has to be — {@code lastSeenAt} and {@code expiresAt} move on
     * every request under sliding renewal, and copying the map entry each time would churn
     * for no benefit. Both are volatile; nothing else changes after construction.
     */
    public static final class Session {
        private final String token;
        private final String csrf;
        private final String user;
        private final Role role;
        private final long createdAt;
        private final String ip;
        private final String userAgent;
        private volatile long lastSeenAt;
        private volatile long expiresAt;

        Session(String token, String csrf, String user, Role role, String ip, String userAgent,
                long now, long expiresAt) {
            this.token = token;
            this.csrf = csrf;
            this.user = user;
            this.role = role;
            this.ip = ip;
            this.userAgent = userAgent;
            this.createdAt = now;
            this.lastSeenAt = now;
            this.expiresAt = expiresAt;
        }

        public String token() {
            return token;
        }

        public String csrf() {
            return csrf;
        }

        public String user() {
            return user;
        }

        public Role role() {
            return role;
        }

        public long createdAt() {
            return createdAt;
        }

        public long lastSeenAt() {
            return lastSeenAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public String ip() {
            return ip;
        }

        public String userAgent() {
            return userAgent;
        }

        public boolean expired(long now) {
            return now >= expiresAt;
        }

        public boolean can(Role.Permission permission) {
            return role.can(permission);
        }

        /** Never includes the token — that value only ever travels in a Set-Cookie header. */
        public void writeJson(JsonWriter w, boolean isCurrent) {
            w.obj()
                    .field("user", user)
                    .field("role", role.lower())
                    .field("createdAt", createdAt)
                    .field("lastSeenAt", lastSeenAt)
                    .field("expiresAt", expiresAt)
                    .field("ip", ip)
                    .field("userAgent", userAgent)
                    .field("current", isCurrent)
                    .end();
        }
    }

    private record PendingLink(String user, Role role, long expiresAt) {
    }

    // ------------------------------------------------------------------ create

    public Session create(UserStore.User user, String ip, String userAgent) {
        long now = System.currentTimeMillis();
        Session session = new Session(
                PasswordHash.randomToken(TOKEN_BYTES),
                PasswordHash.randomToken(16),
                user.name(), user.role(), ip, trim(userAgent),
                now, now + config.sessionTimeoutMillis());
        sessions.put(session.token(), session);
        created.incrementAndGet();
        enforcePerUserLimit(user.name());
        return session;
    }

    /**
     * Validates a cookie value.
     *
     * <p>Under sliding renewal an active session's expiry moves forward on every request,
     * so somebody watching the panel all afternoon is not logged out mid-incident.
     *
     * @return the session, or null when the token is unknown or expired
     */
    public Session validate(String token) {
        if (token == null || token.isEmpty()) return null;
        Session session = sessions.get(token);
        if (session == null) return null;

        long now = System.currentTimeMillis();
        if (session.expired(now)) {
            sessions.remove(token);
            return null;
        }
        session.lastSeenAt = now;
        if (config.sessionSlidingRenew()) {
            session.expiresAt = now + config.sessionTimeoutMillis();
        }
        return session;
    }

    // ------------------------------------------------------------------ revoke

    public boolean revoke(String token) {
        return token != null && sessions.remove(token) != null;
    }

    /** Cuts off every session for one account. Returns how many were ended. */
    public int revokeUser(String user) {
        if (user == null) return 0;
        String target = user.toLowerCase(Locale.ROOT);
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().user().toLowerCase(Locale.ROOT).equals(target)) {
                sessions.remove(entry.getKey());
                removed++;
            }
        }
        links.values().removeIf(link -> link.user().toLowerCase(Locale.ROOT).equals(target));
        return removed;
    }

    public int revokeAll() {
        int count = sessions.size();
        sessions.clear();
        links.clear();
        return count;
    }

    // ------------------------------------------------------- one-time login links

    /**
     * Mints a single-use login token for {@code /godview link}.
     *
     * <p>Short TTL and single use because the link travels through Minecraft chat, which
     * is logged to disk, echoed to the console and visible to anyone reading over a
     * shoulder. Five minutes and one redemption keeps that exposure survivable.
     */
    public String createLoginLink(UserStore.User user) {
        String token = PasswordHash.randomToken(TOKEN_BYTES);
        links.put(token, new PendingLink(user.name(), user.role(),
                System.currentTimeMillis() + LINK_TTL_MS));
        return token;
    }

    /** Redeems a link token, consuming it. Returns null when unknown, used or expired. */
    public Session redeemLink(String token, String ip, String userAgent) {
        if (token == null) return null;
        PendingLink link = links.remove(token);   // remove first: one use, even on failure
        if (link == null || System.currentTimeMillis() >= link.expiresAt()) return null;

        long now = System.currentTimeMillis();
        Session session = new Session(
                PasswordHash.randomToken(TOKEN_BYTES),
                PasswordHash.randomToken(16),
                link.user(), link.role(), ip, trim(userAgent),
                now, now + config.sessionTimeoutMillis());
        sessions.put(session.token(), session);
        created.incrementAndGet();
        enforcePerUserLimit(link.user());
        return session;
    }

    // ------------------------------------------------------------------ upkeep

    /** Drops expired sessions and links. Cheap; call on a timer. */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(s -> s.expired(now));
        links.values().removeIf(l -> now >= l.expiresAt());
        return before - sessions.size();
    }

    /**
     * Keeps a single account from accumulating sessions without bound.
     *
     * <p>The oldest are dropped first, so logging in from a new device pushes out the
     * laptop somebody forgot to close last week rather than the tab they are using now.
     */
    private void enforcePerUserLimit(String user) {
        if (user == null) {
            // Reconfiguration: apply the new cap to every account that has sessions.
            for (String name : sessions.values().stream().map(Session::user).distinct().toList()) {
                enforcePerUserLimit(name);
            }
            return;
        }

        List<Session> mine = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.user().equalsIgnoreCase(user)) mine.add(session);
        }
        int max = config.maxSessionsPerUser();
        if (mine.size() <= max) return;

        mine.sort(Comparator.comparingLong(Session::lastSeenAt));
        for (int i = 0; i < mine.size() - max; i++) {
            sessions.remove(mine.get(i).token());
        }
    }

    // ------------------------------------------------------------------- read

    public List<Session> active() {
        long now = System.currentTimeMillis();
        List<Session> out = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (!session.expired(now)) out.add(session);
        }
        out.sort(Comparator.comparingLong(Session::lastSeenAt).reversed());
        return List.copyOf(out);
    }

    public int activeCount() {
        return sessions.size();
    }

    public long totalCreated() {
        return created.get();
    }

    /** Builds the Set-Cookie value for a new session. */
    public String cookieFor(Session session, boolean secure) {
        long maxAgeSeconds = Math.max(1, (session.expiresAt() - System.currentTimeMillis()) / 1000);
        StringBuilder sb = new StringBuilder(160);
        sb.append(COOKIE).append('=').append(session.token())
                .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(maxAgeSeconds);
        // Secure would make the cookie unusable over plain HTTP, which is how most people
        // run this behind a LAN or a reverse proxy, so it is tied to the actual scheme.
        if (secure) sb.append("; Secure");
        return sb.toString();
    }

    /** The Set-Cookie value that clears the session cookie on logout. */
    public String clearCookie(boolean secure) {
        return COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (secure ? "; Secure" : "");
    }

    private static String trim(String userAgent) {
        if (userAgent == null) return "";
        return userAgent.length() <= 160 ? userAgent : userAgent.substring(0, 160);
    }
}
