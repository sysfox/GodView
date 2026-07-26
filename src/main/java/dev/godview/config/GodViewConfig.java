package dev.godview.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An immutable, validated view of {@code config.yml}.
 *
 * <p>Parsed once on enable and once per {@code /godview reload}; every consumer holds a
 * reference to a whole instance rather than reaching back into the {@link FileConfiguration}.
 * That way a reload swaps one field atomically instead of letting services observe a
 * half-updated config mid-tick.
 *
 * <p>Nothing here throws on bad input. An out-of-range number is clamped and a bad regex
 * is dropped, each appending a line to {@link #warnings()} for the caller to log — a typo
 * in one alert rule must not stop the panel from starting.
 */
public final class GodViewConfig {

    private final List<String> warnings = new ArrayList<>();

    public final String locale;
    public final Web web;
    public final Security security;
    public final Performance performance;
    public final Storage storage;
    public final Capture capture;
    public final MapSettings map;
    public final Integrations integrations;
    public final Alerts alerts;

    private GodViewConfig(FileConfiguration c) {
        this.locale = c.getString("locale", "zh_CN");
        this.web = readWeb(section(c, "web"));
        this.security = readSecurity(section(c, "security"));
        this.performance = readPerformance(section(c, "performance"));
        this.storage = readStorage(section(c, "storage"));
        this.capture = readCapture(section(c, "capture"));
        this.map = readMap(section(c, "map"));
        this.integrations = readIntegrations(section(c, "integrations"));
        this.alerts = readAlerts(section(c, "alerts"));
    }

    public static GodViewConfig from(FileConfiguration c) {
        return new GodViewConfig(c);
    }

    /** Problems found while parsing, in the order they were hit. Empty when the file is clean. */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    // ---------------------------------------------------------------- sections

    public record Web(boolean enabled, String bind, int port, int threads, int maxBodyKb,
                      boolean gzip, boolean behindProxy, List<String> trustedProxies,
                      boolean tlsEnabled, String keystore, String keystorePassword) {

        public int maxBodyBytes() {
            return maxBodyKb * 1024;
        }
    }

    public record Security(int sessionTimeoutMinutes, boolean sessionSlidingRenew,
                           int maxSessionsPerUser, int loginMaxAttempts, int loginWindowSeconds,
                           int lockoutSeconds, List<String> ipAllowlist,
                           List<Pattern> commandBlacklist, List<Pattern> commandWhitelist,
                           boolean requireConfirm, boolean audit) {

        public long sessionTimeoutMillis() {
            return sessionTimeoutMinutes * 60_000L;
        }

        /**
         * Blacklist wins over whitelist, and an empty whitelist means "everything not
         * blacklisted" — the config comment promises exactly this precedence.
         */
        public boolean commandAllowed(String command) {
            for (Pattern p : commandBlacklist) {
                if (p.matcher(command).find()) return false;
            }
            if (commandWhitelist.isEmpty()) return true;
            for (Pattern p : commandWhitelist) {
                if (p.matcher(command).find()) return true;
            }
            return false;
        }
    }

    public record Performance(String profile, boolean idleSuspend, int snapshotIntervalTicks,
                              boolean tickSampler, double spikeThresholdMs,
                              boolean spikeStackCapture, int spikeMaxPerMinute, boolean gcMonitor,
                              int worldStatsIntervalTicks, double degradeTpsBelow,
                              double restoreTpsAbove) {

        public boolean autoProfile() {
            return "auto".equalsIgnoreCase(profile);
        }
    }

    public record Storage(String type, String file, int batchSize, long flushIntervalMs,
                          int eventsDays, int metricsDays, int spikesDays, int auditDays,
                          int alertsDays) {

        public boolean persistent() {
            return "sqlite".equalsIgnoreCase(type);
        }
    }

    public record Capture(boolean joinQuit, boolean chat, boolean commands, boolean deaths,
                          boolean kicksBans, boolean teleports, boolean worldLoad,
                          boolean consoleErrors, boolean blockPlace, boolean blockBreak,
                          boolean containerOpen, int consoleBufferLines) {
    }

    public record MapSettings(boolean enabled, int chunksPerTick, boolean loadUnloaded,
                              int unloadedPerSecond, int tileCacheMb, int tileTtlSeconds,
                              int chunksPerTile, List<String> disabledWorlds) {

        public boolean worldEnabled(String worldName) {
            return enabled && !disabledWorlds.contains(worldName);
        }

        /** Pixels per side of a rendered tile — one pixel per block column. */
        public int tilePixels() {
            return chunksPerTile * 16;
        }
    }

    public record Integrations(boolean luckperms, boolean coreprotect, boolean placeholderapi,
                               boolean vault, boolean spark, boolean essentials) {
    }

    public record Alerts(boolean enabled, String quietHours, int defaultCooldownSeconds,
                         Channels channels, List<RuleDef> rules) {
    }

    public record Channels(boolean panel, Discord discord, Telegram telegram, Webhook webhook,
                           ServerChan serverchan, Bark bark) {
    }

    public record Discord(boolean enabled, String webhook, String mention) {
    }

    public record Telegram(boolean enabled, String botToken, String chatId) {
    }

    public record Webhook(boolean enabled, String url, String method, Map<String, String> headers) {
    }

    public record ServerChan(boolean enabled, String key) {
    }

    public record Bark(boolean enabled, String url) {
    }

    /**
     * One alert rule as written in the file. The engine in
     * {@code dev.godview.alert} interprets {@code type}; this record only guarantees the
     * shape is well-formed and the pattern compiles.
     *
     * @param cooldownSeconds -1 when the rule omits it, meaning "use the global default"
     */
    public record RuleDef(String id, String type, double threshold, int sustainSeconds,
                          String severity, int cooldownSeconds, Pattern pattern,
                          List<String> channels) {

        public int effectiveCooldown(int globalDefault) {
            return cooldownSeconds >= 0 ? cooldownSeconds : globalDefault;
        }
    }

    // ----------------------------------------------------------------- parsing

    private Web readWeb(ConfigurationSection s) {
        ConfigurationSection tls = s.getConfigurationSection("tls");
        return new Web(
                s.getBoolean("enabled", true),
                s.getString("bind", "0.0.0.0"),
                clampPort(s.getInt("port", 8899), "web.port"),
                clamp(s.getInt("threads", 3), 1, 64, "web.threads"),
                clamp(s.getInt("max-body-kb", 256), 1, 65536, "web.max-body-kb"),
                s.getBoolean("gzip", true),
                s.getBoolean("behind-proxy", false),
                List.copyOf(s.getStringList("trusted-proxies")),
                tls != null && tls.getBoolean("enabled", false),
                tls == null ? "keystore.p12" : tls.getString("keystore", "keystore.p12"),
                tls == null ? "" : tls.getString("password", ""));
    }

    private Security readSecurity(ConfigurationSection s) {
        ConfigurationSection login = section(s, "login");
        return new Security(
                clamp(s.getInt("session-timeout-minutes", 120), 1, 43200, "security.session-timeout-minutes"),
                s.getBoolean("session-sliding-renew", true),
                clamp(s.getInt("max-sessions-per-user", 5), 1, 100, "security.max-sessions-per-user"),
                clamp(login.getInt("max-attempts", 5), 1, 1000, "security.login.max-attempts"),
                clamp(login.getInt("window-seconds", 300), 1, 86400, "security.login.window-seconds"),
                clamp(login.getInt("lockout-seconds", 300), 1, 86400, "security.login.lockout-seconds"),
                List.copyOf(s.getStringList("ip-allowlist")),
                patterns(s.getStringList("command-blacklist"), "security.command-blacklist"),
                patterns(s.getStringList("command-whitelist"), "security.command-whitelist"),
                s.getBoolean("require-confirm", true),
                s.getBoolean("audit", true));
    }

    private Performance readPerformance(ConfigurationSection s) {
        ConfigurationSection degrade = section(s, "degrade");
        String profile = s.getString("profile", "auto").toLowerCase(Locale.ROOT);
        if (!profile.equals("auto") && !profile.equals("low")
                && !profile.equals("balanced") && !profile.equals("full")) {
            warnings.add("performance.profile '" + profile + "' is not one of auto/low/balanced/full — using auto");
            profile = "auto";
        }
        double below = degrade.getDouble("tps-below", 17.0);
        double above = degrade.getDouble("restore-tps-above", 19.0);
        if (above <= below) {
            // Without a gap between the two the governor would oscillate every sample.
            warnings.add("performance.degrade.restore-tps-above (" + above + ") must exceed tps-below ("
                    + below + ") — using " + below + " / " + (below + 2));
            above = below + 2;
        }
        return new Performance(
                profile,
                s.getBoolean("idle-suspend", true),
                clamp(s.getInt("snapshot-interval-ticks", 20), 1, 1200, "performance.snapshot-interval-ticks"),
                s.getBoolean("tick-sampler", true),
                clamp(s.getDouble("spike-threshold-ms", 120), 50, 60_000, "performance.spike-threshold-ms"),
                s.getBoolean("spike-stack-capture", true),
                clamp(s.getInt("spike-max-per-minute", 6), 1, 600, "performance.spike-max-per-minute"),
                s.getBoolean("gc-monitor", true),
                clamp(s.getInt("world-stats-interval-ticks", 100), 20, 12000, "performance.world-stats-interval-ticks"),
                below,
                above);
    }

    private Storage readStorage(ConfigurationSection s) {
        ConfigurationSection r = section(s, "retention");
        String type = s.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        if (!type.equals("sqlite") && !type.equals("memory")) {
            warnings.add("storage.type '" + type + "' is not sqlite or memory — using memory");
            type = "memory";
        }
        return new Storage(
                type,
                s.getString("file", "godview.db"),
                clamp(s.getInt("batch-size", 200), 1, 10_000, "storage.batch-size"),
                clamp(s.getLong("flush-interval-ms", 2000), 100, 600_000, "storage.flush-interval-ms"),
                clamp(r.getInt("events-days", 14), 1, 3650, "storage.retention.events-days"),
                clamp(r.getInt("metrics-days", 30), 1, 3650, "storage.retention.metrics-days"),
                clamp(r.getInt("spikes-days", 30), 1, 3650, "storage.retention.spikes-days"),
                clamp(r.getInt("audit-days", 90), 1, 3650, "storage.retention.audit-days"),
                clamp(r.getInt("alerts-days", 30), 1, 3650, "storage.retention.alerts-days"));
    }

    private Capture readCapture(ConfigurationSection s) {
        return new Capture(
                s.getBoolean("join-quit", true),
                s.getBoolean("chat", true),
                s.getBoolean("commands", true),
                s.getBoolean("deaths", true),
                s.getBoolean("kicks-bans", true),
                s.getBoolean("teleports", false),
                s.getBoolean("world-load", true),
                s.getBoolean("console-errors", true),
                s.getBoolean("block-place", false),
                s.getBoolean("block-break", false),
                s.getBoolean("container-open", false),
                clamp(s.getInt("console-buffer-lines", 400), 50, 20_000, "capture.console-buffer-lines"));
    }

    private MapSettings readMap(ConfigurationSection s) {
        int chunksPerTile = s.getInt("chunks-per-tile", 8);
        if (Integer.bitCount(chunksPerTile) != 1 || chunksPerTile < 1 || chunksPerTile > 32) {
            // Tile coordinates are derived by bit-shifting chunk coordinates.
            warnings.add("map.chunks-per-tile must be a power of two between 1 and 32, got "
                    + chunksPerTile + " — using 8");
            chunksPerTile = 8;
        }
        return new MapSettings(
                s.getBoolean("enabled", true),
                clamp(s.getInt("chunks-per-tick", 4), 1, 64, "map.chunks-per-tick"),
                s.getBoolean("load-unloaded", false),
                clamp(s.getInt("unloaded-per-second", 2), 1, 64, "map.unloaded-per-second"),
                clamp(s.getInt("tile-cache-mb", 24), 1, 1024, "map.tile-cache-mb"),
                clamp(s.getInt("tile-ttl-seconds", 300), 1, 86400, "map.tile-ttl-seconds"),
                chunksPerTile,
                List.copyOf(s.getStringList("disabled-worlds")));
    }

    private Integrations readIntegrations(ConfigurationSection s) {
        return new Integrations(
                s.getBoolean("luckperms", true),
                s.getBoolean("coreprotect", true),
                s.getBoolean("placeholderapi", true),
                s.getBoolean("vault", true),
                s.getBoolean("spark", true),
                s.getBoolean("essentials", true));
    }

    private Alerts readAlerts(ConfigurationSection s) {
        ConfigurationSection ch = section(s, "channels");
        ConfigurationSection wh = section(ch, "webhook");

        Map<String, String> headers = new LinkedHashMap<>();
        ConfigurationSection headerSection = wh.getConfigurationSection("headers");
        if (headerSection != null) {
            for (String key : headerSection.getKeys(false)) {
                headers.put(key, String.valueOf(headerSection.get(key)));
            }
        }

        Channels channels = new Channels(
                section(ch, "panel").getBoolean("enabled", true),
                new Discord(section(ch, "discord").getBoolean("enabled", false),
                        section(ch, "discord").getString("webhook", ""),
                        section(ch, "discord").getString("mention", "")),
                new Telegram(section(ch, "telegram").getBoolean("enabled", false),
                        section(ch, "telegram").getString("bot-token", ""),
                        section(ch, "telegram").getString("chat-id", "")),
                new Webhook(wh.getBoolean("enabled", false),
                        wh.getString("url", ""),
                        wh.getString("method", "POST").toUpperCase(Locale.ROOT),
                        Map.copyOf(headers)),
                new ServerChan(section(ch, "serverchan").getBoolean("enabled", false),
                        section(ch, "serverchan").getString("key", "")),
                new Bark(section(ch, "bark").getBoolean("enabled", false),
                        section(ch, "bark").getString("url", "")));

        List<RuleDef> rules = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("rules")) {
            RuleDef rule = readRule(raw);
            if (rule != null) rules.add(rule);
        }

        return new Alerts(
                s.getBoolean("enabled", true),
                s.getString("quiet-hours", ""),
                clamp(s.getInt("default-cooldown-seconds", 300), 0, 86400, "alerts.default-cooldown-seconds"),
                channels,
                List.copyOf(rules));
    }

    private RuleDef readRule(Map<?, ?> raw) {
        String id = str(raw, "id", "");
        if (id.isEmpty()) {
            warnings.add("an alert rule has no id — skipped");
            return null;
        }
        String type = str(raw, "type", "").toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            warnings.add("alert rule '" + id + "' has no type — skipped");
            return null;
        }

        Pattern pattern = null;
        String patternText = str(raw, "pattern", "");
        if (!patternText.isEmpty()) {
            try {
                pattern = Pattern.compile(patternText);
            } catch (PatternSyntaxException e) {
                warnings.add("alert rule '" + id + "' has an invalid pattern — skipped: " + e.getDescription());
                return null;
            }
        }

        String severity = str(raw, "severity", "warn").toLowerCase(Locale.ROOT);
        if (!severity.equals("info") && !severity.equals("warn") && !severity.equals("critical")) {
            warnings.add("alert rule '" + id + "' has severity '" + severity + "' — using warn");
            severity = "warn";
        }

        List<String> channels = new ArrayList<>();
        Object rawChannels = raw.get("channels");
        if (rawChannels instanceof List<?> list) {
            for (Object o : list) channels.add(String.valueOf(o).toLowerCase(Locale.ROOT));
        }
        if (channels.isEmpty()) channels.add("panel");

        return new RuleDef(id, type,
                dbl(raw, "threshold", 0),
                (int) dbl(raw, "sustain-seconds", 0),
                severity,
                raw.containsKey("cooldown-seconds") ? (int) dbl(raw, "cooldown-seconds", -1) : -1,
                pattern,
                List.copyOf(channels));
    }

    // ---------------------------------------------------------------- helpers

    /** Never null — a missing section behaves as one where every key falls back to its default. */
    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection found = parent.getConfigurationSection(path);
        return found != null ? found : parent.createSection(path);
    }

    private static String str(Map<?, ?> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static double dbl(Map<?, ?> map, String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return def;
    }

    private List<Pattern> patterns(List<String> raw, String path) {
        List<Pattern> out = new ArrayList<>(raw.size());
        for (String p : raw) {
            try {
                out.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                warnings.add(path + " entry '" + p + "' is not a valid regex — ignored: " + e.getDescription());
            }
        }
        return List.copyOf(out);
    }

    private int clamp(int value, int min, int max, String path) {
        if (value < min || value > max) {
            int clamped = Math.min(Math.max(value, min), max);
            warnings.add(path + " must be between " + min + " and " + max + ", got " + value
                    + " — using " + clamped);
            return clamped;
        }
        return value;
    }

    private long clamp(long value, long min, long max, String path) {
        if (value < min || value > max) {
            long clamped = Math.min(Math.max(value, min), max);
            warnings.add(path + " must be between " + min + " and " + max + ", got " + value
                    + " — using " + clamped);
            return clamped;
        }
        return value;
    }

    private double clamp(double value, double min, double max, String path) {
        if (value < min || value > max) {
            double clamped = Math.min(Math.max(value, min), max);
            warnings.add(path + " must be between " + min + " and " + max + ", got " + value
                    + " — using " + clamped);
            return clamped;
        }
        return value;
    }

    private int clampPort(int port, String path) {
        return clamp(port, 1, 65535, path);
    }
}
