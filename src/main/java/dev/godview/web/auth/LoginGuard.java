package dev.godview.web.auth;

import dev.godview.config.GodViewConfig;
import dev.godview.util.LongRing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limits and locks out login attempts, per client address.
 *
 * <p>A panel on a public port will be found and probed. PBKDF2 makes each guess expensive
 * for the attacker, but it makes each guess expensive for <em>us</em> too — roughly a
 * tenth of a second of CPU — so an unthrottled login endpoint is a denial-of-service
 * vector aimed at the server it is supposed to be monitoring. This is what stops that.
 *
 * <p>Attempts are held as timestamps in a fixed ring per address, so the "how many failures
 * in the last N seconds" question is answered without allocating or sweeping a list. The
 * address table itself is capped and evicted oldest-first; otherwise a spray across forged
 * source addresses would turn the defence into a memory leak.
 *
 * <p>Successful logins clear the address's history, so one person fat-fingering their
 * password four times and then getting it right does not leave a trap for their next
 * session.
 */
public final class LoginGuard {

    /**
     * Distinct addresses tracked at once. Past this the least recently active are dropped,
     * which briefly forgives an attacker mid-spray but keeps memory bounded.
     */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Attempts> byAddress = new ConcurrentHashMap<>();
    private final AtomicLong lockouts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile GodViewConfig.Security config;

    public LoginGuard(GodViewConfig.Security config) {
        this.config = config;
    }

    public void reconfigure(GodViewConfig.Security config) {
        this.config = config;
    }

    private static final class Attempts {
        final LongRing times;
        volatile long lockedUntil;
        volatile long lastTouched;

        Attempts(int capacity) {
            this.times = new LongRing(Math.max(4, capacity));
            this.lastTouched = System.currentTimeMillis();
        }
    }

    /** True when this address is currently locked out and must not be allowed to try. */
    public boolean isLocked(String ip) {
        return lockedUntil(ip) > System.currentTimeMillis();
    }

    /** Epoch millis the lockout ends, or 0 when the address is not locked. */
    public long lockedUntil(String ip) {
        Attempts attempts = byAddress.get(key(ip));
        if (attempts == null) return 0L;
        long until = attempts.lockedUntil;
        return until > System.currentTimeMillis() ? until : 0L;
    }

    /** Seconds remaining on a lockout, for the {@code Retry-After} header. */
    public long retryAfterSeconds(String ip) {
        long until = lockedUntil(ip);
        if (until == 0L) return 0L;
        return Math.max(1, (until - System.currentTimeMillis() + 999) / 1000);
    }

    /**
     * Records a failed attempt and locks the address out once it has failed too often
     * inside the configured window.
     *
     * @return true when this failure triggered a lockout
     */
    public boolean recordFailure(String ip) {
        failures.incrementAndGet();
        GodViewConfig.Security cfg = config;
        long now = System.currentTimeMillis();

        Attempts attempts = byAddress.computeIfAbsent(key(ip),
                k -> new Attempts(cfg.loginMaxAttempts() * 2));
        attempts.lastTouched = now;
        attempts.times.add(now);

        int recent = attempts.times.countSince(now - cfg.loginWindowSeconds() * 1000L);
        if (recent >= cfg.loginMaxAttempts()) {
            attempts.lockedUntil = now + cfg.lockoutSeconds() * 1000L;
            lockouts.incrementAndGet();
            evictIfCrowded();
            return true;
        }
        evictIfCrowded();
        return false;
    }

    /** Clears an address's history after a successful login. */
    public void recordSuccess(String ip) {
        byAddress.remove(key(ip));
    }

    /** Failed attempts inside the current window for this address. */
    public int recentFailures(String ip) {
        Attempts attempts = byAddress.get(key(ip));
        if (attempts == null) return 0;
        return attempts.times.countSince(
                System.currentTimeMillis() - config.loginWindowSeconds() * 1000L);
    }

    /** How many attempts remain before this address is locked out. */
    public int remainingAttempts(String ip) {
        return Math.max(0, config.loginMaxAttempts() - recentFailures(ip));
    }

    public long totalFailures() {
        return failures.get();
    }

    public long totalLockouts() {
        return lockouts.get();
    }

    public int trackedAddresses() {
        return byAddress.size();
    }

    /** Drops entries that are past their window and no longer locked. Call on a timer. */
    public void purge() {
        long now = System.currentTimeMillis();
        long windowMs = config.loginWindowSeconds() * 1000L;
        byAddress.entrySet().removeIf(entry -> {
            Attempts attempts = entry.getValue();
            if (attempts.lockedUntil > now) return false;
            return now - attempts.lastTouched > windowMs;
        });
    }

    /** Lifts a lockout by hand, for {@code /godview} when an admin locks themselves out. */
    public boolean unlock(String ip) {
        return byAddress.remove(key(ip)) != null;
    }

    public int unlockAll() {
        int count = byAddress.size();
        byAddress.clear();
        return count;
    }

    private void evictIfCrowded() {
        if (byAddress.size() <= MAX_TRACKED) return;
        // Not exact LRU — good enough, and it must not become expensive under the very
        // flood it exists to survive.
        long oldest = Long.MAX_VALUE;
        String victim = null;
        for (Map.Entry<String, Attempts> entry : byAddress.entrySet()) {
            Attempts attempts = entry.getValue();
            if (attempts.lockedUntil > System.currentTimeMillis()) continue;   // keep active lockouts
            if (attempts.lastTouched < oldest) {
                oldest = attempts.lastTouched;
                victim = entry.getKey();
            }
        }
        if (victim != null) byAddress.remove(victim);
    }

    private static String key(String ip) {
        return ip == null ? "" : ip;
    }
}
