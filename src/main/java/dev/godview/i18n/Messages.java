package dev.godview.i18n;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translated strings for the in-game commands.
 *
 * <p>{@code locale} in {@code config.yml} decides which table is used. A missing key falls
 * back to English rather than showing the raw key, so a half-finished translation degrades
 * into a mixed-language message instead of {@code command.user.added} appearing in chat.
 *
 * <p>Deliberately a hand-written map rather than resource bundles. There are a few dozen
 * strings, they are needed before the plugin has a data folder, and {@code .properties}
 * files force either ISO-8859-1 escapes or a UTF-8 loader — neither worth it at this size.
 *
 * <p>Placeholders are {@code {0}}, {@code {1}} … substituted positionally by
 * {@link #get(String, Object...)}. No number or date formatting: every value here is
 * already a string by the time it arrives.
 */
public final class Messages {

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> ZH = new HashMap<>();

    private final Map<String, String> table;
    private final String locale;

    private Messages(Map<String, String> table, String locale) {
        this.table = table;
        this.locale = locale;
    }

    /** @param locale a {@code config.yml} locale such as {@code zh_CN}; unknown values give English */
    public static Messages of(String locale) {
        String normalized = locale == null ? "en_us" : locale.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("zh")) return new Messages(ZH, "zh_CN");
        return new Messages(EN, "en_US");
    }

    public String locale() {
        return locale;
    }

    public String get(String key, Object... args) {
        String template = table.get(key);
        if (template == null) template = EN.get(key);
        if (template == null) return key;
        return format(template, args);
    }

    private static String format(String template, Object... args) {
        if (args.length == 0) return template;
        StringBuilder sb = new StringBuilder(template.length() + 16 * args.length);
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c != '{') {
                sb.append(c);
                continue;
            }
            int close = template.indexOf('}', i);
            if (close < 0) {
                sb.append(c);
                continue;
            }
            try {
                int index = Integer.parseInt(template.substring(i + 1, close));
                sb.append(index < args.length ? String.valueOf(args[index]) : "");
                i = close;
            } catch (NumberFormatException e) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static {
        // ---------------------------------------------------------------- English
        EN.put("usage.header", "GodView commands:");
        EN.put("usage.status", "/godview status - server and panel status");
        EN.put("usage.reload", "/godview reload - reload config.yml");
        EN.put("usage.user", "/godview user add|passwd|role|remove|list");
        EN.put("usage.sessions", "/godview sessions list|revoke <name>");
        EN.put("usage.link", "/godview link - a one-time sign-in link for yourself");
        EN.put("usage.unlock", "/godview unlock <ip|all> - lift a login lockout");

        EN.put("error.no-permission", "You do not have permission to do that.");
        EN.put("error.unknown", "Unknown subcommand. Try /godview for the list.");
        EN.put("error.players-only", "Only a player can run that - it signs you in.");
        EN.put("error.panel-off", "The web panel is not running, so there is nothing to configure.");
        EN.put("error.args", "Not enough arguments. Usage: {0}");

        EN.put("status.title", "GodView status");
        EN.put("status.server", "Server: {0}");
        EN.put("status.tps", "TPS: {0} / MSPT: {1} / players: {2}");
        EN.put("status.memory", "Heap: {0} MB of {1} MB ({2}%)");
        EN.put("status.profile", "Profile: {0} ({1})");
        EN.put("status.panel", "Panel: {0}");
        EN.put("status.panel-off", "disabled");
        EN.put("status.storage", "Storage: {0} - {1} events, {2} metrics, {3} spikes");
        EN.put("status.sessions", "Sessions: {0} active, {1} accounts");
        EN.put("status.viewers", "Live viewers: {0}");
        EN.put("status.spikes", "Spikes in memory: {0}, worst recent tick {1} ms");

        EN.put("reload.ok", "Reloaded config.yml.");
        EN.put("reload.warnings", "Reloaded with {0} warning(s) - see the console.");
        EN.put("reload.restart-note", "Note: bind address, port, threads and TLS need a full restart.");

        EN.put("user.added", "Created account '{0}' with role {1}.");
        EN.put("user.password", "Password: {0}");
        EN.put("user.password-warning",
                "This is shown once and is now in your chat log. Change it after signing in.");
        EN.put("user.exists", "An account named '{0}' already exists.");
        EN.put("user.not-found", "No account named '{0}'.");
        EN.put("user.removed", "Removed account '{0}'.");
        EN.put("user.password-set", "Set a new password for '{0}'.");
        EN.put("user.role-set", "'{0}' is now {1}.");
        EN.put("user.invalid-role", "Role must be viewer, operator or admin.");
        EN.put("user.invalid-name", "A name may be 1-32 characters of letters, digits, _ . or -");
        EN.put("user.last-admin", "'{0}' is the only admin account left.");
        EN.put("user.list-header", "{0} account(s):");
        EN.put("user.list-entry", "  {0} - {1}{2}");
        EN.put("user.list-empty", "No accounts yet. Create one with /godview user add <name> admin");
        EN.put("user.never-signed-in", " (never signed in)");
        EN.put("user.last-seen", " (last sign-in {0} ago from {1})");
        EN.put("user.disabled", " [disabled]");

        EN.put("sessions.revoked", "Ended {0} session(s).");
        EN.put("sessions.list-header", "{0} active session(s):");
        EN.put("sessions.list-entry", "  {0} ({1}) from {2}, idle {3}");
        EN.put("sessions.list-empty", "Nobody is signed in.");

        EN.put("link.created", "Your one-time sign-in link (valid 5 minutes, single use):");
        EN.put("link.warning",
                "Anyone who reads this link can sign in as you. It is in your chat log now.");
        EN.put("link.no-account",
                "You have no panel account. An admin can make one with /godview user add {0} operator");

        EN.put("unlock.done", "Lifted the login lockout on {0}.");
        EN.put("unlock.all", "Lifted {0} login lockout(s).");
        EN.put("unlock.none", "{0} was not locked out.");

        // ---------------------------------------------------------------- Chinese
        ZH.put("usage.header", "GodView 命令:");
        ZH.put("usage.status", "/godview status - 查看服务器与面板状态");
        ZH.put("usage.reload", "/godview reload - 重载 config.yml");
        ZH.put("usage.user", "/godview user add|passwd|role|remove|list");
        ZH.put("usage.sessions", "/godview sessions list|revoke <名称>");
        ZH.put("usage.link", "/godview link - 给自己生成一次性登录链接");
        ZH.put("usage.unlock", "/godview unlock <ip|all> - 解除登录锁定");

        ZH.put("error.no-permission", "你没有权限执行该操作。");
        ZH.put("error.unknown", "未知子命令。输入 /godview 查看列表。");
        ZH.put("error.players-only", "该命令只能由玩家执行——它是给你自己登录用的。");
        ZH.put("error.panel-off", "网页面板未运行,没有可配置的内容。");
        ZH.put("error.args", "参数不足。用法: {0}");

        ZH.put("status.title", "GodView 状态");
        ZH.put("status.server", "服务端: {0}");
        ZH.put("status.tps", "TPS: {0} / MSPT: {1} / 在线: {2}");
        ZH.put("status.memory", "堆内存: {0} MB / {1} MB ({2}%)");
        ZH.put("status.profile", "性能档位: {0} ({1})");
        ZH.put("status.panel", "面板: {0}");
        ZH.put("status.panel-off", "已禁用");
        ZH.put("status.storage", "存储: {0} - {1} 条事件, {2} 条指标, {3} 次卡顿");
        ZH.put("status.sessions", "会话: {0} 个在线, {1} 个账号");
        ZH.put("status.viewers", "实时观看数: {0}");
        ZH.put("status.spikes", "内存中卡顿记录: {0} 条, 近期最差 tick {1} ms");

        ZH.put("reload.ok", "已重载 config.yml。");
        ZH.put("reload.warnings", "已重载,但有 {0} 条警告——详见控制台。");
        ZH.put("reload.restart-note", "注意: 监听地址、端口、线程数和 TLS 需要完整重启才生效。");

        ZH.put("user.added", "已创建账号 '{0}',角色 {1}。");
        ZH.put("user.password", "密码: {0}");
        ZH.put("user.password-warning", "该密码只显示这一次,并且已经进入你的聊天记录。登录后请尽快修改。");
        ZH.put("user.exists", "账号 '{0}' 已存在。");
        ZH.put("user.not-found", "没有名为 '{0}' 的账号。");
        ZH.put("user.removed", "已删除账号 '{0}'。");
        ZH.put("user.password-set", "已为 '{0}' 设置新密码。");
        ZH.put("user.role-set", "'{0}' 的角色已改为 {1}。");
        ZH.put("user.invalid-role", "角色必须是 viewer、operator 或 admin。");
        ZH.put("user.invalid-name", "名称需为 1-32 个字符,只能包含字母、数字、_ . 或 -");
        ZH.put("user.last-admin", "'{0}' 是最后一个管理员账号。");
        ZH.put("user.list-header", "共 {0} 个账号:");
        ZH.put("user.list-entry", "  {0} - {1}{2}");
        ZH.put("user.list-empty", "还没有任何账号。用 /godview user add <名称> admin 创建一个");
        ZH.put("user.never-signed-in", " (从未登录)");
        ZH.put("user.last-seen", " (上次登录在 {0} 前,来自 {1})");
        ZH.put("user.disabled", " [已停用]");

        ZH.put("sessions.revoked", "已结束 {0} 个会话。");
        ZH.put("sessions.list-header", "当前有 {0} 个活动会话:");
        ZH.put("sessions.list-entry", "  {0} ({1}) 来自 {2}, 空闲 {3}");
        ZH.put("sessions.list-empty", "当前没有人登录。");

        ZH.put("link.created", "你的一次性登录链接 (5 分钟内有效,只能用一次):");
        ZH.put("link.warning", "任何看到这条链接的人都能以你的身份登录。它已经进入你的聊天记录。");
        ZH.put("link.no-account", "你还没有面板账号。管理员可以用 /godview user add {0} operator 创建");

        ZH.put("unlock.done", "已解除 {0} 的登录锁定。");
        ZH.put("unlock.all", "已解除 {0} 个登录锁定。");
        ZH.put("unlock.none", "{0} 并未被锁定。");
    }
}
