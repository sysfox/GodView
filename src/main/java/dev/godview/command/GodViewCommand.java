package dev.godview.command;

import dev.godview.GodViewPlugin;
import dev.godview.compat.Compat;
import dev.godview.config.GodViewConfig;
import dev.godview.i18n.Messages;
import dev.godview.metrics.MetricsService;
import dev.godview.storage.Storage;
import dev.godview.util.Text;
import dev.godview.web.WebServer;
import dev.godview.web.auth.PasswordHash;
import dev.godview.web.auth.Role;
import dev.godview.web.auth.SessionManager;
import dev.godview.web.auth.UserStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /godview} command.
 *
 * <p>This is the only way to create the first panel account, which makes it the way in:
 * GodView ships with no default credentials, so until an admin runs
 * {@code /godview user add} nobody can sign in at all.
 *
 * <p>Generated passwords and one-time links are printed straight into chat, which is
 * logged to disk and echoed to the console. Every message that carries one says so — a
 * secret an operator does not know is exposed is far more dangerous than one they do.
 *
 * <p>Account changes are also written to the audit log, so panel accounts created from the
 * game show up in the same history as everything done through the panel.
 */
public final class GodViewCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_ADMIN = "godview.admin";
    private static final String PERMISSION_LINK = "godview.link";

    private final GodViewPlugin plugin;

    public GodViewCommand(GodViewPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        GodViewConfig config = plugin.config();
        return Messages.of(config == null ? "en_US" : config.locale);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = messages();

        if (args.length == 0) {
            usage(sender, m);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // link is the one subcommand an operator without full admin may use.
        if (sub.equals("link")) {
            if (!sender.hasPermission(PERMISSION_LINK) && !sender.hasPermission(PERMISSION_ADMIN)) {
                error(sender, m.get("error.no-permission"));
                return true;
            }
            return link(sender, m);
        }

        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            error(sender, m.get("error.no-permission"));
            return true;
        }

        return switch (sub) {
            case "status" -> status(sender, m);
            case "reload" -> reload(sender, m);
            case "user" -> user(sender, m, args);
            case "sessions" -> sessions(sender, m, args);
            case "unlock" -> unlock(sender, m, args);
            default -> {
                error(sender, m.get("error.unknown"));
                yield true;
            }
        };
    }

    // ------------------------------------------------------------------ status

    private boolean status(CommandSender sender, Messages m) {
        MetricsService metrics = plugin.metrics();
        WebServer web = plugin.web();
        Storage storage = plugin.storage();

        header(sender, m.get("status.title"));
        info(sender, m.get("status.server", Compat.describe()));

        if (metrics != null) {
            MetricsService.Sample s = metrics.latest();
            info(sender, m.get("status.tps", round(s.tps1(), 2), round(s.mspt(), 2),
                    s.players() + "/" + s.maxPlayers()));
            info(sender, m.get("status.memory", s.heapUsed() / 1048576, s.heapMax() / 1048576,
                    round(s.heapPercent(), 1)));
            info(sender, m.get("status.profile", metrics.profile().lower(),
                    metrics.governor().isAuto() ? "auto: " + metrics.governor().lastReason() : "fixed"));
            info(sender, m.get("status.spikes", metrics.recentSpikes().size(),
                    metrics.tickSampler() == null ? "-" : round(metrics.tickSampler().worstPeriodMs(), 1)));
            info(sender, m.get("status.viewers", metrics.viewers()));
        }

        GodViewConfig config = plugin.config();
        if (web != null && web.isRunning()) {
            info(sender, m.get("status.panel",
                    (config.web.tlsEnabled() ? "https://" : "http://")
                            + config.web.bind() + ":" + config.web.port()));
            info(sender, m.get("status.sessions", web.sessions().activeCount(), web.users().size()));
        } else {
            info(sender, m.get("status.panel", m.get("status.panel-off")));
        }

        if (storage != null) {
            Storage.StorageStats stats = storage.stats();
            info(sender, m.get("status.storage", stats.kind(), stats.events(), stats.metrics(),
                    stats.spikes()));
        }
        return true;
    }

    private boolean reload(CommandSender sender, Messages m) {
        GodViewConfig reloaded = plugin.reload();
        int warnings = reloaded.warnings().size();
        if (warnings == 0) {
            ok(sender, m.get("reload.ok"));
        } else {
            warn(sender, m.get("reload.warnings", warnings));
        }
        info(sender, m.get("reload.restart-note"));
        return true;
    }

    // ------------------------------------------------------------------- users

    private boolean user(CommandSender sender, Messages m, String[] args) {
        WebServer web = plugin.web();
        if (web == null) {
            error(sender, m.get("error.panel-off"));
            return true;
        }
        UserStore users = web.users();

        if (args.length < 2) {
            error(sender, m.get("error.args", m.get("usage.user")));
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 4) {
                    error(sender, m.get("error.args", "/godview user add <name> <viewer|operator|admin>"));
                    return true;
                }
                Role role = Role.parse(args[3]);
                if (role == null) {
                    error(sender, m.get("user.invalid-role"));
                    return true;
                }
                String password = PasswordHash.randomPassword();
                UserStore.Result result = users.add(args[2], role, password, null);
                if (!result.ok()) {
                    error(sender, describe(m, result, args[2]));
                    return true;
                }
                ok(sender, m.get("user.added", args[2], role.lower()));
                info(sender, m.get("user.password", password));
                warn(sender, m.get("user.password-warning"));
                audit(sender, "user.add", args[2], "role " + role.lower(), true);
            }
            case "passwd" -> {
                if (args.length < 3) {
                    error(sender, m.get("error.args", "/godview user passwd <name>"));
                    return true;
                }
                String password = PasswordHash.randomPassword();
                UserStore.Result result = users.setPassword(args[2], password);
                if (!result.ok()) {
                    error(sender, describe(m, result, args[2]));
                    return true;
                }
                // A new password invalidates whatever the old one was protecting.
                web.sessions().revokeUser(args[2]);
                ok(sender, m.get("user.password-set", args[2]));
                info(sender, m.get("user.password", password));
                warn(sender, m.get("user.password-warning"));
                audit(sender, "user.passwd", args[2], "password reset, sessions ended", true);
            }
            case "role" -> {
                if (args.length < 4) {
                    error(sender, m.get("error.args", "/godview user role <name> <viewer|operator|admin>"));
                    return true;
                }
                Role role = Role.parse(args[3]);
                if (role == null) {
                    error(sender, m.get("user.invalid-role"));
                    return true;
                }
                UserStore.Result result = users.setRole(args[2], role);
                if (!result.ok()) {
                    error(sender, describe(m, result, args[2]));
                    return true;
                }
                ok(sender, m.get("user.role-set", args[2], role.lower()));
                audit(sender, "user.role", args[2], "role " + role.lower(), true);
            }
            case "remove" -> {
                if (args.length < 3) {
                    error(sender, m.get("error.args", "/godview user remove <name>"));
                    return true;
                }
                UserStore.Result result = users.remove(args[2]);
                if (!result.ok()) {
                    error(sender, describe(m, result, args[2]));
                    return true;
                }
                web.sessions().revokeUser(args[2]);
                ok(sender, m.get("user.removed", args[2]));
                audit(sender, "user.remove", args[2], "account deleted", true);
            }
            case "list" -> {
                List<UserStore.User> all = users.list();
                if (all.isEmpty()) {
                    info(sender, m.get("user.list-empty"));
                    return true;
                }
                header(sender, m.get("user.list-header", all.size()));
                long now = System.currentTimeMillis();
                for (UserStore.User u : all) {
                    String suffix = u.lastLoginAt() == 0
                            ? m.get("user.never-signed-in")
                            : m.get("user.last-seen", Text.duration(now - u.lastLoginAt()),
                                    u.lastLoginIp());
                    if (!u.enabled()) suffix += m.get("user.disabled");
                    info(sender, m.get("user.list-entry", u.name(), u.role().lower(), suffix));
                }
            }
            default -> error(sender, m.get("error.unknown"));
        }
        return true;
    }

    private String describe(Messages m, UserStore.Result result, String name) {
        return switch (result) {
            case ALREADY_EXISTS -> m.get("user.exists", name);
            case NOT_FOUND -> m.get("user.not-found", name);
            case INVALID_ROLE -> m.get("user.invalid-role");
            case INVALID_NAME -> m.get("user.invalid-name");
            case LAST_ADMIN -> m.get("user.last-admin", name);
            default -> result.message();
        };
    }

    // ---------------------------------------------------------------- sessions

    private boolean sessions(CommandSender sender, Messages m, String[] args) {
        WebServer web = plugin.web();
        if (web == null) {
            error(sender, m.get("error.panel-off"));
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("revoke")) {
            int revoked = args.length >= 3
                    ? web.sessions().revokeUser(args[2])
                    : web.sessions().revokeAll();
            ok(sender, m.get("sessions.revoked", revoked));
            audit(sender, "sessions.revoke", args.length >= 3 ? args[2] : "*",
                    revoked + " session(s) ended", true);
            return true;
        }

        List<SessionManager.Session> active = web.sessions().active();
        if (active.isEmpty()) {
            info(sender, m.get("sessions.list-empty"));
            return true;
        }
        header(sender, m.get("sessions.list-header", active.size()));
        long now = System.currentTimeMillis();
        for (SessionManager.Session s : active) {
            info(sender, m.get("sessions.list-entry", s.user(), s.role().lower(), s.ip(),
                    Text.duration(now - s.lastSeenAt())));
        }
        return true;
    }

    private boolean unlock(CommandSender sender, Messages m, String[] args) {
        WebServer web = plugin.web();
        if (web == null) {
            error(sender, m.get("error.panel-off"));
            return true;
        }
        if (args.length < 2) {
            error(sender, m.get("error.args", m.get("usage.unlock")));
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            ok(sender, m.get("unlock.all", web.loginGuard().unlockAll()));
            return true;
        }
        if (web.loginGuard().unlock(args[1])) {
            ok(sender, m.get("unlock.done", args[1]));
        } else {
            info(sender, m.get("unlock.none", args[1]));
        }
        return true;
    }

    // -------------------------------------------------------------------- link

    private boolean link(CommandSender sender, Messages m) {
        if (!(sender instanceof Player player)) {
            error(sender, m.get("error.players-only"));
            return true;
        }
        WebServer web = plugin.web();
        if (web == null) {
            error(sender, m.get("error.panel-off"));
            return true;
        }

        // Match on the linked UUID first, then on the name, so an account created through
        // the panel for this player still works.
        UserStore.User account = web.users().byLinkedUuid(player.getUniqueId().toString());
        if (account == null) account = web.users().get(player.getName());
        if (account == null) {
            error(sender, m.get("link.no-account", player.getName()));
            return true;
        }

        String token = web.sessions().createLoginLink(account);
        String url = baseUrl() + "/api/auth/link/" + token;

        ok(sender, m.get("link.created"));
        // Clickable, because the alternative is an operator copying a 43-character token
        // out of a chat box by hand.
        sender.sendMessage(Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url)));
        warn(sender, m.get("link.warning"));
        audit(sender, "auth.link", account.name(), "one-time link issued in game", true);
        return true;
    }

    /**
     * Best guess at the panel's address.
     *
     * <p>A bind of {@code 0.0.0.0} means "every interface", which is not an address anyone
     * can type, so the server's own configured IP is used when there is one. There is no
     * way to know which hostname the operator actually reaches the panel by, so a
     * placeholder is honest rather than a guess that quietly fails.
     */
    private String baseUrl() {
        GodViewConfig.Web web = plugin.config().web;
        String host = web.bind();
        if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) {
            String serverIp = plugin.getServer().getIp();
            host = serverIp == null || serverIp.isBlank() ? "<your-server-address>" : serverIp;
        }
        return (web.tlsEnabled() ? "https://" : "http://") + host + ":" + web.port();
    }

    // ----------------------------------------------------------------- output

    private void usage(CommandSender sender, Messages m) {
        header(sender, m.get("usage.header"));
        info(sender, m.get("usage.status"));
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            info(sender, m.get("usage.reload"));
            info(sender, m.get("usage.user"));
            info(sender, m.get("usage.sessions"));
            info(sender, m.get("usage.unlock"));
        }
        info(sender, m.get("usage.link"));
    }

    private void audit(CommandSender sender, String action, String target, String detail,
                       boolean success) {
        Storage storage = plugin.storage();
        GodViewConfig config = plugin.config();
        if (storage == null || config == null || !config.security.audit()) return;
        storage.recordAudit(new Storage.AuditEntry(System.currentTimeMillis(),
                sender.getName(), "in-game", action, target, detail, success));
    }

    // Adventure Components rather than legacy ChatColor codes: the codes are deprecated,
    // and a Component is what makes the sign-in link below clickable.

    private static void header(CommandSender sender, String text) {
        sender.sendMessage(prefix(NamedTextColor.AQUA)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    private static void info(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private static void ok(CommandSender sender, String text) {
        sender.sendMessage(prefix(NamedTextColor.GREEN)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    private static void warn(CommandSender sender, String text) {
        sender.sendMessage(prefix(NamedTextColor.YELLOW)
                .append(Component.text(text, NamedTextColor.YELLOW)));
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(prefix(NamedTextColor.RED)
                .append(Component.text(text, NamedTextColor.RED)));
    }

    private static Component prefix(NamedTextColor color) {
        return Component.text("[GodView] ", color);
    }

    private static String round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return String.valueOf(Math.round(value * factor) / factor);
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        boolean admin = sender.hasPermission(PERMISSION_ADMIN);

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("status", "link"));
            if (admin) options.addAll(List.of("reload", "user", "sessions", "unlock"));
            return prefixed(options, args[0]);
        }
        if (!admin) return List.of();

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "user" -> prefixed(List.of("add", "passwd", "role", "remove", "list"), args[1]);
                case "sessions" -> prefixed(List.of("list", "revoke"), args[1]);
                case "unlock" -> prefixed(List.of("all"), args[1]);
                default -> List.of();
            };
        }

        WebServer web = plugin.web();
        if (args.length == 3 && web != null) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            boolean wantsAccount = args[0].equalsIgnoreCase("user")
                    && (sub.equals("passwd") || sub.equals("role") || sub.equals("remove"));
            boolean wantsSessionUser = args[0].equalsIgnoreCase("sessions") && sub.equals("revoke");
            if (wantsAccount || wantsSessionUser) {
                List<String> names = new ArrayList<>();
                for (UserStore.User u : web.users().list()) names.add(u.name());
                return prefixed(names, args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("user")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("role"))) {
            return prefixed(List.of("viewer", "operator", "admin"), args[3]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(option);
        }
        return out;
    }
}
