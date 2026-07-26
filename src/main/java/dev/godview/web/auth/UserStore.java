package dev.godview.web.auth;

import dev.godview.util.JsonWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Panel accounts, stored in {@code users.yml} beside the config.
 *
 * <p>Kept out of {@code config.yml} on purpose. Config files get pasted into forum posts
 * and bug reports; a file that holds password hashes should not be the one an admin
 * reaches for when asking for help. It is also in {@code .gitignore} by default.
 *
 * <p>There is no default account and no default password. Until an admin runs
 * {@code /godview user add}, nobody can log in — a panel that ships with
 * {@code admin/admin} on port 8899 would be found by scanners within the hour.
 *
 * <p>Names are matched case-insensitively but stored as typed, so {@code Steve} logging in
 * as {@code steve} works while the panel still greets them properly.
 */
public final class UserStore {

    /** Anything shorter is not worth the PBKDF2 cost protecting. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final Path file;
    private final Logger log;
    private final Map<String, User> users = new LinkedHashMap<>();

    public UserStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /**
     * One panel account.
     *
     * @param linkedUuid the in-game player this account belongs to, set when the account
     *                   was created through {@code /godview link}; null otherwise
     */
    public record User(String name, Role role, String passwordHash, long createdAt,
                       long lastLoginAt, String lastLoginIp, boolean enabled, String linkedUuid) {

        public String key() {
            return name.toLowerCase(Locale.ROOT);
        }

        /** Without the hash — safe to send to the panel. */
        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("name", name)
                    .field("role", role.lower())
                    .field("createdAt", createdAt)
                    .field("lastLoginAt", lastLoginAt)
                    .field("lastLoginIp", lastLoginIp)
                    .field("enabled", enabled)
                    .field("linkedUuid", linkedUuid)
                    .end();
        }
    }

    /** Why an account operation was refused. {@link #OK} means it succeeded. */
    public enum Result {
        OK,
        ALREADY_EXISTS,
        NOT_FOUND,
        PASSWORD_TOO_SHORT,
        INVALID_ROLE,
        INVALID_NAME,
        LAST_ADMIN;

        public boolean ok() {
            return this == OK;
        }

        public String message() {
            return switch (this) {
                case OK -> "ok";
                case ALREADY_EXISTS -> "that account already exists";
                case NOT_FOUND -> "no such account";
                case PASSWORD_TOO_SHORT -> "password must be at least "
                        + MIN_PASSWORD_LENGTH + " characters";
                case INVALID_ROLE -> "role must be viewer, operator or admin";
                case INVALID_NAME -> "name must be 1-32 characters of letters, digits, _ . or -";
                case LAST_ADMIN -> "that is the only admin account left";
            };
        }
    }

    // ------------------------------------------------------------------ loading

    public synchronized void load() {
        users.clear();
        if (!Files.exists(file)) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("users");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;

            Role role = Role.parse(section.getString("role", "viewer"));
            if (role == null) {
                log.warning("users.yml: account '" + key + "' has an unknown role — treating it as viewer");
                role = Role.VIEWER;
            }
            String hash = section.getString("password", "");
            if (hash.isBlank()) {
                log.warning("users.yml: account '" + key + "' has no password hash — skipped");
                continue;
            }
            User user = new User(
                    section.getString("name", key),
                    role,
                    hash,
                    section.getLong("created-at", 0L),
                    section.getLong("last-login-at", 0L),
                    section.getString("last-login-ip", ""),
                    section.getBoolean("enabled", true),
                    section.getString("linked-uuid", null));
            users.put(user.key(), user);
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "GodView panel accounts.",
                "",
                "Managed with /godview user add|passwd|remove|list.",
                "Passwords are PBKDF2-HMAC-SHA256; there is no way to recover one,",
                "only to set a new password.",
                "Keep this file out of pastebins and version control."));

        for (User user : users.values()) {
            String path = "users." + user.key();
            yaml.set(path + ".name", user.name());
            yaml.set(path + ".role", user.role().lower());
            yaml.set(path + ".password", user.passwordHash());
            yaml.set(path + ".created-at", user.createdAt());
            yaml.set(path + ".last-login-at", user.lastLoginAt());
            yaml.set(path + ".last-login-ip", user.lastLoginIp());
            yaml.set(path + ".enabled", user.enabled());
            if (user.linkedUuid() != null) yaml.set(path + ".linked-uuid", user.linkedUuid());
        }

        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            yaml.save(file.toFile());
            restrictPermissions();
        } catch (IOException e) {
            log.log(Level.SEVERE, "GodView could not write " + file.getFileName()
                    + " — account changes will be lost on restart", e);
        }
    }

    /**
     * Narrows the file to the owner where the filesystem supports it.
     *
     * <p>Best-effort by design: this silently does nothing on Windows and on filesystems
     * without POSIX permissions, and a failure here must not stop an admin creating an
     * account.
     */
    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Not a POSIX filesystem, or not permitted. Nothing actionable.
        }
    }

    // ----------------------------------------------------------------- queries

    public synchronized boolean isEmpty() {
        return users.isEmpty();
    }

    public synchronized int size() {
        return users.size();
    }

    public synchronized User get(String name) {
        return name == null ? null : users.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized List<User> list() {
        return List.copyOf(users.values());
    }

    /** Finds the account created by {@code /godview link} for a given player. */
    public synchronized User byLinkedUuid(String uuid) {
        if (uuid == null) return null;
        for (User user : users.values()) {
            if (uuid.equals(user.linkedUuid())) return user;
        }
        return null;
    }

    /**
     * Checks credentials.
     *
     * <p>An unknown account still pays the full PBKDF2 cost against a throwaway hash.
     * Returning early would make "no such user" measurably faster than "wrong password",
     * which is enough to enumerate valid account names over a few hundred requests.
     *
     * @return the account on success, null on any failure
     */
    public User authenticate(String name, String password) {
        User user = get(name);
        if (user == null || !user.enabled()) {
            PasswordHash.verify(password, DUMMY_HASH);
            return null;
        }
        if (!PasswordHash.verify(password, user.passwordHash())) return null;
        return user;
    }

    /**
     * A real hash of a random password, computed once at class load. Verifying against it
     * costs exactly what verifying a genuine account costs.
     */
    private static final String DUMMY_HASH = PasswordHash.hash(PasswordHash.randomPassword());

    // ----------------------------------------------------------------- mutation

    /** Creates an account. The caller supplies the password so it can be shown once. */
    public synchronized Result add(String name, Role role, String password, String linkedUuid) {
        if (!validName(name)) return Result.INVALID_NAME;
        if (role == null) return Result.INVALID_ROLE;
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return Result.PASSWORD_TOO_SHORT;
        }
        if (users.containsKey(name.toLowerCase(Locale.ROOT))) return Result.ALREADY_EXISTS;

        long now = System.currentTimeMillis();
        User user = new User(name, role, PasswordHash.hash(password), now, 0L, "", true, linkedUuid);
        users.put(user.key(), user);
        save();
        return Result.OK;
    }

    public synchronized Result setPassword(String name, String password) {
        User user = get(name);
        if (user == null) return Result.NOT_FOUND;
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return Result.PASSWORD_TOO_SHORT;
        }
        users.put(user.key(), new User(user.name(), user.role(), PasswordHash.hash(password),
                user.createdAt(), user.lastLoginAt(), user.lastLoginIp(), user.enabled(),
                user.linkedUuid()));
        save();
        return Result.OK;
    }

    public synchronized Result setRole(String name, Role role) {
        User user = get(name);
        if (user == null) return Result.NOT_FOUND;
        if (role == null) return Result.INVALID_ROLE;
        // Demoting the last admin would leave nobody able to manage accounts.
        if (user.role() == Role.ADMIN && role != Role.ADMIN && countAdmins() <= 1) {
            return Result.LAST_ADMIN;
        }
        users.put(user.key(), new User(user.name(), role, user.passwordHash(), user.createdAt(),
                user.lastLoginAt(), user.lastLoginIp(), user.enabled(), user.linkedUuid()));
        save();
        return Result.OK;
    }

    public synchronized Result setEnabled(String name, boolean enabled) {
        User user = get(name);
        if (user == null) return Result.NOT_FOUND;
        if (!enabled && user.role() == Role.ADMIN && countAdmins() <= 1) return Result.LAST_ADMIN;
        users.put(user.key(), new User(user.name(), user.role(), user.passwordHash(),
                user.createdAt(), user.lastLoginAt(), user.lastLoginIp(), enabled,
                user.linkedUuid()));
        save();
        return Result.OK;
    }

    public synchronized Result remove(String name) {
        User user = get(name);
        if (user == null) return Result.NOT_FOUND;
        if (user.role() == Role.ADMIN && countAdmins() <= 1) return Result.LAST_ADMIN;
        users.remove(user.key());
        save();
        return Result.OK;
    }

    /** Records a successful login, upgrading the stored hash if the work factor has risen. */
    public synchronized void recordLogin(String name, String ip, String password) {
        User user = get(name);
        if (user == null) return;
        String hash = user.passwordHash();
        if (password != null && PasswordHash.needsRehash(hash)) {
            hash = PasswordHash.hash(password);
        }
        users.put(user.key(), new User(user.name(), user.role(), hash, user.createdAt(),
                System.currentTimeMillis(), ip, user.enabled(), user.linkedUuid()));
        save();
    }

    private int countAdmins() {
        int count = 0;
        for (User user : users.values()) {
            if (user.role() == Role.ADMIN && user.enabled()) count++;
        }
        return count;
    }

    /** Conservative on purpose: the name ends up as a YAML key and in the audit log. */
    static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
