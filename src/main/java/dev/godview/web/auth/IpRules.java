package dev.godview.web.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which address a request really came from, and whether it is allowed in.
 *
 * <p>Both halves are security-relevant and both are easy to get subtly wrong.
 *
 * <p><b>The client address.</b> {@code X-Forwarded-For} is a request header, which means
 * the client writes it. Trusting it unconditionally would let anyone forge their address
 * and walk straight past the allowlist and the login rate limiter. So it is only consulted
 * when {@code behind-proxy} is on <em>and</em> the immediate peer is in
 * {@code trusted-proxies}. Otherwise the socket address wins, whatever the headers claim.
 *
 * <p>Even then, the rightmost entry that is not itself a trusted proxy is taken, not the
 * leftmost. A client that sends its own {@code X-Forwarded-For} has its value appended to
 * by the proxy, so the leftmost entry is attacker-controlled and the rightmost untrusted
 * one is the address the proxy actually saw.
 *
 * <p><b>The allowlist.</b> Plain addresses and CIDR ranges, compared bit by bit.
 * IPv4-mapped IPv6 addresses ({@code ::ffff:10.0.0.1}) are normalised first, so a rule
 * written as {@code 10.0.0.0/8} still matches a connection that arrived over a
 * dual-stack socket.
 */
public final class IpRules {

    private final List<Rule> allow;
    private final List<Rule> proxies;
    private final boolean behindProxy;

    public IpRules(List<String> allowlist, boolean behindProxy, List<String> trustedProxies) {
        this.allow = parseAll(allowlist);
        this.proxies = parseAll(trustedProxies);
        this.behindProxy = behindProxy;
    }

    /** True when no allowlist is configured, so every address is permitted. */
    public boolean allowsEveryone() {
        return allow.isEmpty();
    }

    /** True when {@code ip} is permitted by the allowlist. */
    public boolean allowed(String ip) {
        if (allow.isEmpty()) return true;
        byte[] address = bytes(ip);
        if (address == null) return false;
        for (Rule rule : allow) {
            if (rule.matches(address)) return true;
        }
        return false;
    }

    /**
     * Resolves the effective client address.
     *
     * @param peerIp     the socket's remote address — the one thing a client cannot forge
     * @param forwarded  the {@code X-Forwarded-For} header value, or null
     * @return the address to use for the allowlist, rate limiting and the audit log
     */
    public String clientIp(String peerIp, String forwarded) {
        if (!behindProxy || forwarded == null || forwarded.isBlank()) return peerIp;
        if (!isTrustedProxy(peerIp)) {
            // The header is present but this connection did not come from a proxy we
            // trust, so it is either a misconfiguration or a spoof. Either way, ignore it.
            return peerIp;
        }

        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalizeHop(hops[i]);
            if (hop.isEmpty()) continue;
            if (isTrustedProxy(hop)) continue;   // another of our own proxies, keep walking left
            return hop;
        }
        // Every hop was a trusted proxy; the peer is the best answer we have.
        return peerIp;
    }

    public boolean isTrustedProxy(String ip) {
        byte[] address = bytes(ip);
        if (address == null) return false;
        for (Rule rule : proxies) {
            if (rule.matches(address)) return true;
        }
        return false;
    }

    /**
     * Entries that could not be parsed, so the caller can warn about them.
     *
     * <p>Blank entries are skipped rather than reported: they come from YAML formatting
     * rather than from someone mistyping an address, and "entry '' is not a valid address"
     * is a confusing thing to read in a server log.
     */
    public static List<String> invalidEntries(List<String> entries) {
        List<String> bad = new ArrayList<>();
        if (entries == null) return List.copyOf(bad);
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            if (parse(entry) == null) bad.add(entry);
        }
        return List.copyOf(bad);
    }

    // ---------------------------------------------------------------- internals

    private static List<Rule> parseAll(List<String> entries) {
        List<Rule> rules = new ArrayList<>();
        if (entries == null) return List.copyOf(rules);
        for (String entry : entries) {
            Rule rule = parse(entry);
            if (rule != null) rules.add(rule);
        }
        return List.copyOf(rules);
    }

    /** @return null when the entry is not a valid address or CIDR range */
    static Rule parse(String entry) {
        if (entry == null) return null;
        String text = entry.trim();
        if (text.isEmpty()) return null;

        int slash = text.indexOf('/');
        String host = slash < 0 ? text : text.substring(0, slash);
        byte[] address = bytes(host);
        if (address == null) return null;

        int prefixBits = address.length * 8;
        if (slash >= 0) {
            try {
                prefixBits = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefixBits < 0 || prefixBits > address.length * 8) return null;
        }
        return new Rule(address, prefixBits);
    }

    /**
     * Parses a literal address to bytes, never doing a DNS lookup.
     *
     * <p>{@link InetAddress#getByName} would happily resolve a hostname, which on the
     * request path would mean a blocking lookup driven by whatever a client sent.
     */
    static byte[] bytes(String ip) {
        if (ip == null) return null;
        String text = ip.trim();
        if (text.isEmpty()) return null;

        // Strip the brackets and any port from forms like [::1]:8080 or 10.0.0.1:54321.
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) return null;
            text = text.substring(1, close);
        } else {
            int colon = text.indexOf(':');
            if (colon >= 0 && text.indexOf(':', colon + 1) < 0) text = text.substring(0, colon);
        }
        int scope = text.indexOf('%');   // fe80::1%eth0
        if (scope >= 0) text = text.substring(0, scope);
        if (text.isEmpty() || !isLiteral(text)) return null;

        try {
            byte[] raw = InetAddress.getByName(text).getAddress();
            return unmapIpv4(raw);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Cheap guard so nothing that could be a hostname reaches the resolver. */
    private static boolean isLiteral(String text) {
        boolean hasColon = text.indexOf(':') >= 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean hex = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (digit || c == '.' || c == ':') continue;
            if (hasColon && hex) continue;   // hex groups are only meaningful in IPv6
            return false;
        }
        return true;
    }

    /** {@code ::ffff:a.b.c.d} and {@code a.b.c.d} must compare equal. */
    private static byte[] unmapIpv4(byte[] raw) {
        if (raw.length != 16) return raw;
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) return raw;
        }
        if ((raw[10] & 0xff) != 0xff || (raw[11] & 0xff) != 0xff) return raw;
        return new byte[]{raw[12], raw[13], raw[14], raw[15]};
    }

    private static String normalizeHop(String hop) {
        String text = hop.trim();
        // Some proxies emit RFC 7239 style values; tolerate the common quoted form.
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** One allowlist or proxy entry: an address and how many leading bits must match. */
    record Rule(byte[] network, int prefixBits) {

        boolean matches(byte[] address) {
            if (address.length != network.length) return false;

            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) return true;

            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
