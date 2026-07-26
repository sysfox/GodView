package dev.godview.web.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing for panel accounts.
 *
 * <p>PBKDF2-HMAC-SHA256, because it is the only strong KDF in the JDK — bcrypt, scrypt and
 * Argon2 would all mean shading a dependency into a plugin jar, and a monitoring plugin
 * should not be shipping its own crypto library. The iteration count follows OWASP's
 * current PBKDF2-SHA256 guidance.
 *
 * <p>The cost is deliberate: about a tenth of a second per verification. That is why login
 * is rate-limited by {@link LoginGuard} — without it, the hash that protects passwords
 * would itself be a denial-of-service vector.
 *
 * <p>Stored form is self-describing:
 * {@code pbkdf2-sha256$<iterations>$<salt-b64>$<hash-b64>}. The iteration count travels
 * with the hash, so raising it later leaves existing accounts verifiable and
 * {@link #needsRehash} marks them for an upgrade on next login.
 */
public final class PasswordHash {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private PasswordHash() {
    }

    /** @return the stored representation, safe to write to a config file */
    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return PREFIX + '$' + ITERATIONS + '$' + ENCODER.encodeToString(salt) + '$'
                + ENCODER.encodeToString(key);
    }

    /**
     * Checks a password against a stored hash.
     *
     * <p>Returns false rather than throwing for malformed input: a corrupted line in
     * {@code users.yml} should lock one account out, not break the login endpoint.
     */
    public static boolean verify(char[] password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1000) return false;   // refuse a downgraded work factor
            byte[] salt = DECODER.decode(parts[2]);
            byte[] expected = DECODER.decode(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            // Time-constant: a byte-by-byte comparison would leak the prefix length.
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when a stored hash uses fewer iterations than we now require. */
    public static boolean needsRehash(String stored) {
        if (stored == null) return true;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return true;
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * A readable random password for {@code /godview user add}, which has to be typed by
     * a human at least once.
     *
     * <p>The alphabet drops the characters that get misread when someone copies a password
     * out of a server console: no 0/O, no 1/l/I. 20 characters over this 58-symbol
     * alphabet is about 117 bits, so the loss of eight symbols costs nothing that matters.
     */
    public static String randomPassword() {
        final String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** URL-safe random token for sessions, CSRF and one-time login links. */
    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // PBKDF2WithHmacSHA256 is mandatory in every JDK we support, so this cannot
            // happen from configuration — it would mean a broken JVM.
            throw new IllegalStateException("PBKDF2 is unavailable on this JVM", e);
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }

    /** Convenience for call sites holding a String; prefer the char[] form where possible. */
    public static boolean verify(String password, String stored) {
        return verify(password == null ? null : password.toCharArray(), stored);
    }

    public static String hash(String password) {
        return hash(password.toCharArray());
    }

    /** Length in bytes of a UTF-8 password, used to enforce a sane minimum. */
    static int byteLength(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }
}
