package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.config.GodViewConfig;
import dev.godview.i18n.Messages;
import dev.godview.listener.ConsoleCapture;
import dev.godview.listener.EventCapture;
import dev.godview.storage.MemoryStorage;
import dev.godview.storage.Storage;
import dev.godview.util.Text;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.util.List;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Console capture through a real Log4j appender, and the message tables.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class ConsoleSuiteTest {

    @Test
    void verifies() throws Exception {
        messages();
        ansi();
        console();
    }

    // ---------------------------------------------------------------- messages

    static void messages() {
        System.out.println("========== messages ==========");
        Messages en = Messages.of("en_US");
        Messages zh = Messages.of("zh_CN");

        check("english locale", en.locale(), "en_US");
        check("chinese locale", zh.locale(), "zh_CN");
        check("unknown locale falls back", Messages.of("de_DE").locale(), "en_US");
        check("null locale falls back", Messages.of(null).locale(), "en_US");
        check("hyphenated locale", Messages.of("zh-CN").locale(), "zh_CN");
        check("bare language", Messages.of("zh").locale(), "zh_CN");

        check("plain lookup", en.get("reload.ok"), "Reloaded config.yml.");
        check("translated lookup differs", zh.get("reload.ok").equals(en.get("reload.ok")), false);
        check("chinese is actually chinese", zh.get("reload.ok"), "已重载 config.yml。");

        check("one placeholder", en.get("user.removed", "Steve"), "Removed account 'Steve'.");
        check("two placeholders", en.get("user.added", "Steve", "admin"),
                "Created account 'Steve' with role admin.");
        check("placeholders in chinese", zh.get("user.added", "Steve", "admin"),
                "已创建账号 'Steve',角色 admin。");
        check("missing arg becomes empty", en.get("user.added", "Steve"),
                "Created account 'Steve' with role .");
        check("unknown key returns the key", en.get("nope.not.here"), "nope.not.here");
        check("no args on a templated string keeps braces",
                en.get("user.removed"), "Removed account '{0}'.");

        // Every Chinese key must exist in English too, or the fallback is a dead end.
        int missing = 0;
        for (String key : new String[]{"usage.header", "status.title", "user.added", "link.created",
                "sessions.revoked", "unlock.done", "error.no-permission"}) {
            if (en.get(key).equals(key)) missing++;
            if (zh.get(key).equals(key)) missing++;
        }
        check("no missing keys in the sampled set", missing, 0);
    }

    // -------------------------------------------------------------------- text

    static void ansi() {
        System.out.println("\n========== ansi stripping ==========");
        String esc = String.valueOf((char) 0x1B);
        check("colour sequence removed",
                Text.stripAnsi(esc + "[0;32mINFO" + esc + "[m done"), "INFO done");
        check("plain text untouched", Text.stripAnsi("nothing here"), "nothing here");
        check("null becomes empty", Text.stripAnsi(null), "");
        check("bare escape removed", Text.stripAnsi("a" + esc + "b"), "a");
        check("truncated sequence does not loop",
                Text.stripAnsi("x" + esc + "[0;3"), "x");
        check("multiple sequences",
                Text.stripAnsi(esc + "[31ma" + esc + "[32mb" + esc + "[0m"), "ab");
        check("combined with colour codes",
                Text.stripColors(Text.stripAnsi(esc + "[31m" + Text.COLOR_CHAR + "cred")), "red");
    }

    // ----------------------------------------------------------------- console

    static void console() throws Exception {
        System.out.println("\n========== console capture ==========");
        // The default root level is ERROR, which would hide everything we want to observe.
        Configurator.setRootLevel(Level.TRACE);

        GodViewConfig cfg = GodViewConfig.from(YamlConfiguration.loadConfiguration(new StringReader("""
                storage:
                  type: memory
                capture:
                  console-errors: true
                  console-buffer-lines: 100
                """)));
        Storage storage = new MemoryStorage(cfg.storage);
        storage.start();

        EventCapture events = new EventCapture(null, cfg, () -> storage, () -> null, null);
        ConsoleCapture console = new ConsoleCapture(100, true, () -> null, events);
        console.attach();
        check("attached", console.isAttached(), true);

        Logger log = LogManager.getLogger("TestPlugin");
        try {
            log.info("server started");
            log.warn("something looks off");
            log.error("something broke");
            Thread.sleep(50);

            List<ConsoleCapture.Line> all = console.recent(50);
            check("lines captured", all.size() >= 3, true);
            check("newest last", all.get(all.size() - 1).message(), "something broke");
            check("level recorded", all.get(all.size() - 1).level(), "ERROR");
            check("logger name shortened", all.get(all.size() - 1).logger(), "TestPlugin");
            check("thread recorded", all.get(all.size() - 1).thread().isEmpty(), false);
            check("error flag", all.get(all.size() - 1).isError(), true);
            check("info is not an error", all.get(all.size() - 3).isError(), false);

            // Level filtering
            List<ConsoleCapture.Line> warnPlus = console.recent(50, "WARN");
            check("level filter excludes info",
                    warnPlus.stream().noneMatch(l -> l.message().equals("server started")), true);
            check("level filter includes warn",
                    warnPlus.stream().anyMatch(l -> l.message().equals("something looks off")), true);
            check("level filter includes error",
                    warnPlus.stream().anyMatch(l -> l.message().equals("something broke")), true);
            check("ERROR filter excludes warn",
                    console.recent(50, "ERROR").stream()
                            .noneMatch(l -> l.message().equals("something looks off")), true);

            // Formatting is stripped on the way in.
            String esc = String.valueOf((char) 0x1B);
            log.info(esc + "[32mcoloured" + esc + "[m and " + Text.COLOR_CHAR + "asectioned");
            Thread.sleep(50);
            check("console line is stripped",
                    console.recent(1).get(0).message(), "coloured and sectioned");

            // Errors become stored events.
            List<Storage.Event> errors = storage.queryEvents(
                    new Storage.EventQuery(0, Long.MAX_VALUE, "console_error", null, null, 50, 0));
            check("error became an event", errors.size(), 1);
            check("event detail", errors.get(0).detail(), "something broke");
            check("event carries the logger",
                    errors.get(0).meta().contains("TestPlugin"), true);
            check("warn did not become an event",
                    errors.stream().noneMatch(e -> e.detail().contains("looks off")), true);

            // A thrown exception contributes its type and first frames.
            log.error("it failed", new IllegalStateException("boom"));
            Thread.sleep(50);
            List<ConsoleCapture.Line> withStack = console.recent(10);
            check("exception line added",
                    withStack.stream().anyMatch(l -> l.message().contains("IllegalStateException: boom")),
                    true);
            check("stack frames added",
                    withStack.stream().anyMatch(l -> l.message().startsWith("    at ")), true);
            check("exception event names the type",
                    storage.queryEvents(new Storage.EventQuery(0, Long.MAX_VALUE, "console_error",
                            null, "IllegalStateException", 10, 0)).size(), 1);

            // The ring is bounded.
            for (int i = 0; i < 300; i++) log.info("filler " + i);
            Thread.sleep(150);
            check("buffer is capped", console.buffered() <= 100, true);
            check("oldest lines evicted",
                    console.recent(200).stream().noneMatch(l -> l.message().equals("server started")),
                    true);
            check("newest line kept",
                    console.recent(1).get(0).message(), "filler 299");

            // Error events are rate limited so an exception loop cannot flood storage.
            long before = storage.queryEvents(new Storage.EventQuery(0, Long.MAX_VALUE,
                    "console_error", null, null, 500, 0)).size();
            for (int i = 0; i < 200; i++) log.error("flood " + i);
            Thread.sleep(300);
            long after = storage.queryEvents(new Storage.EventQuery(0, Long.MAX_VALUE,
                    "console_error", null, null, 500, 0)).size();
            check("error events are rate limited", after - before <= 60, true);
            check("some errors still recorded", after - before > 0, true);
            check("suppressed count reported", console.suppressedErrors() > 0, true);
            check("all lines still buffered regardless",
                    console.recent(50).stream().anyMatch(l -> l.message().equals("flood 199")), true);

            long linesBefore = console.totalLines();
            console.detach();
            check("detached", console.isAttached(), false);
            log.error("after detach");
            Thread.sleep(50);
            check("nothing captured after detach", console.totalLines(), linesBefore);
        } finally {
            console.detach();
            storage.close();
        }
    }
}
