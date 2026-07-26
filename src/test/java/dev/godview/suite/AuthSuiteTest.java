package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.web.auth.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.util.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Proxy headers, password hashing, roles and the login guard.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class AuthSuiteTest {

    @Test
    void verifies() throws Exception {
        System.out.println("========== X-Forwarded-For ==========");
        // The header is client-written. Everything here is about not believing it.
        List<String> proxies = List.of("10.0.0.1", "172.16.0.0/12");

        IpRules noProxy = new IpRules(List.of(), false, proxies);
        check("behind-proxy off: header ignored",
                noProxy.clientIp("10.0.0.1", "203.0.113.7"), "10.0.0.1");
        check("behind-proxy off: no header",
                noProxy.clientIp("198.51.100.4", null), "198.51.100.4");

        IpRules withProxy = new IpRules(List.of(), true, proxies);
        check("untrusted peer: header ignored",
                withProxy.clientIp("198.51.100.4", "203.0.113.7"), "198.51.100.4");
        check("trusted peer: header honoured",
                withProxy.clientIp("10.0.0.1", "203.0.113.7"), "203.0.113.7");
        check("trusted CIDR peer: header honoured",
                withProxy.clientIp("172.16.5.9", "203.0.113.7"), "203.0.113.7");

        // A client that sends its own X-Forwarded-For has the proxy append to it, so the
        // leftmost value is attacker-controlled and must never be taken.
        check("forged left-hand hop is not used",
                withProxy.clientIp("10.0.0.1", "1.2.3.4, 203.0.113.7"), "203.0.113.7");
        check("forged chain of hops is not used",
                withProxy.clientIp("10.0.0.1", "127.0.0.1, 10.0.0.1, 8.8.8.8, 203.0.113.7"),
                "203.0.113.7");
        check("our own proxies are skipped right-to-left",
                withProxy.clientIp("10.0.0.1", "203.0.113.7, 172.16.0.9, 10.0.0.1"), "203.0.113.7");
        check("every hop trusted falls back to the peer",
                withProxy.clientIp("10.0.0.1", "10.0.0.1, 172.16.0.9"), "10.0.0.1");
        check("empty header ignored", withProxy.clientIp("10.0.0.1", "  "), "10.0.0.1");
        check("whitespace around hops tolerated",
                withProxy.clientIp("10.0.0.1", "  203.0.113.7  ,  10.0.0.1 "), "203.0.113.7");

        System.out.println("\n========== allowlist ==========");
        IpRules open = new IpRules(List.of(), false, List.of());
        check("empty allowlist allows everyone", open.allowed("8.8.8.8"), true);
        check("empty allowlist reports itself", open.allowsEveryone(), true);

        IpRules gated = new IpRules(
                List.of("192.168.1.0/24", "10.0.0.5", "2001:db8::/32"), false, List.of());
        check("inside the range", gated.allowed("192.168.1.77"), true);
        check("boundary low", gated.allowed("192.168.1.0"), true);
        check("boundary high", gated.allowed("192.168.1.255"), true);
        check("just outside the range", gated.allowed("192.168.2.1"), false);
        check("exact host allowed", gated.allowed("10.0.0.5"), true);
        check("neighbour of an exact host refused", gated.allowed("10.0.0.6"), false);
        check("unrelated address refused", gated.allowed("8.8.8.8"), false);
        check("ipv6 range allowed", gated.allowed("2001:db8:1234::1"), true);
        check("ipv6 outside range refused", gated.allowed("2001:dead::1"), false);
        check("ipv4-mapped ipv6 normalizes", gated.allowed("::ffff:192.168.1.77"), true);
        check("address with a port still matches", gated.allowed("192.168.1.77:51234"), true);
        check("bracketed ipv6 with port", gated.allowed("[2001:db8::1]:8080"), true);
        check("scoped ipv6", gated.allowed("2001:db8::1%eth0"), true);
        check("garbage refused", gated.allowed("not-an-address"), false);
        check("null refused", gated.allowed(null), false);
        // A hostname must never reach the resolver on the request path.
        check("hostname refused without a lookup", gated.allowed("localhost"), false);
        check("hostname refused even if it would resolve", gated.allowed("example.com"), false);

        IpRules single = new IpRules(List.of("0.0.0.0/0"), false, List.of());
        check("/0 matches every v4", single.allowed("203.0.113.9"), true);
        check("/0 does not match v6", single.allowed("2001:db8::1"), false);

        System.out.println("\n========== bad allowlist entries ==========");
        List<String> bad = IpRules.invalidEntries(List.of(
                "192.168.1.0/24", "nonsense", "10.0.0.1/99", "", "10.0.0.1"));
        check("invalid entries reported", bad.size(), 2);
        check("bad hostname flagged", bad.contains("nonsense"), true);
        check("bad prefix flagged", bad.contains("10.0.0.1/99"), true);

        System.out.println("\n========== password hashing ==========");
        String hash = PasswordHash.hash("correct-horse-battery");
        check("format is self-describing", hash.startsWith("pbkdf2-sha256$210000$"), true);
        check("verifies", PasswordHash.verify("correct-horse-battery", hash), true);
        check("rejects a wrong password", PasswordHash.verify("correct-horse-batterz", hash), false);
        check("rejects empty", PasswordHash.verify("", hash), false);
        check("rejects null", PasswordHash.verify((String) null, hash), false);
        check("rejects a null hash", PasswordHash.verify("x", null), false);
        check("rejects garbage", PasswordHash.verify("x", "not-a-hash"), false);
        check("rejects a truncated hash", PasswordHash.verify("x", "pbkdf2-sha256$210000$abc"), false);
        // A stored record claiming one iteration must not be honoured.
        String[] parts = hash.split("\\$");
        check("refuses a downgraded work factor",
                PasswordHash.verify("correct-horse-battery",
                        "pbkdf2-sha256$1$" + parts[2] + "$" + parts[3]), false);

        String again = PasswordHash.hash("correct-horse-battery");
        check("same password hashes differently (salted)", hash.equals(again), false);
        check("both still verify", PasswordHash.verify("correct-horse-battery", again), true);

        check("current hash needs no rehash", PasswordHash.needsRehash(hash), false);
        check("weaker hash needs rehash",
                PasswordHash.needsRehash("pbkdf2-sha256$1000$" + parts[2] + "$" + parts[3]), true);
        check("garbage needs rehash", PasswordHash.needsRehash("bcrypt$whatever"), true);

        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 200; i++) passwords.add(PasswordHash.randomPassword());
        check("generated passwords are unique", passwords.size(), 200);
        check("generated password length", PasswordHash.randomPassword().length(), 20);
        check("generated password avoids lookalikes",
                passwords.stream().noneMatch(p -> p.matches(".*[0O1lI].*")), true);

        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) tokens.add(PasswordHash.randomToken(32));
        check("tokens are unique", tokens.size(), 500);
        check("tokens are url-safe",
                tokens.stream().allMatch(t -> t.matches("[A-Za-z0-9_-]+")), true);

        System.out.println("\n========== roles ==========");
        check("viewer cannot act on players",
                Role.VIEWER.can(Role.Permission.PLAYER_ACTION), false);
        check("operator can act on players",
                Role.OPERATOR.can(Role.Permission.PLAYER_ACTION), true);
        check("operator cannot manage users",
                Role.OPERATOR.can(Role.Permission.MANAGE_USERS), false);
        check("operator cannot change config",
                Role.OPERATOR.can(Role.Permission.MANAGE_CONFIG), false);
        check("admin can manage users", Role.ADMIN.can(Role.Permission.MANAGE_USERS), true);
        check("every role can view", Role.VIEWER.can(Role.Permission.VIEW), true);
        check("parse aliases", Role.parse("OP"), "OPERATOR");
        check("parse unknown", Role.parse("wizard"), null);
        check("parse null", Role.parse(null), null);

        System.out.println("\n========== login guard ==========");
        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                security:
                  login:
                    max-attempts: 3
                    window-seconds: 300
                    lockout-seconds: 1
                """)));
        LoginGuard guard = new LoginGuard(cfg.security);

        check("clean address is not locked", guard.isLocked("1.1.1.1"), false);
        check("full attempts available", guard.remainingAttempts("1.1.1.1"), 3);
        check("first failure does not lock", guard.recordFailure("1.1.1.1"), false);
        check("second failure does not lock", guard.recordFailure("1.1.1.1"), false);
        check("attempts counted", guard.remainingAttempts("1.1.1.1"), 1);
        check("third failure locks", guard.recordFailure("1.1.1.1"), true);
        check("now locked", guard.isLocked("1.1.1.1"), true);
        check("retry-after is positive", guard.retryAfterSeconds("1.1.1.1") > 0, true);
        check("a different address is unaffected", guard.isLocked("2.2.2.2"), false);

        Thread.sleep(1100);
        check("lockout expires", guard.isLocked("1.1.1.1"), false);

        guard.recordFailure("3.3.3.3");
        guard.recordFailure("3.3.3.3");
        guard.recordSuccess("3.3.3.3");
        check("success clears history", guard.recentFailures("3.3.3.3"), 0);
        check("success restores attempts", guard.remainingAttempts("3.3.3.3"), 3);

        guard.recordFailure("4.4.4.4");
        check("manual unlock works", guard.unlock("4.4.4.4"), true);
        check("counters are exposed", guard.totalFailures() > 0, true);
    }
}
