package dev.godview.alert;

import dev.godview.config.GodViewConfig;
import dev.godview.util.JsonWriter;
import dev.godview.util.Text;
import dev.godview.web.api.EventStream;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The delivery channels, and the one HTTP client they share.
 *
 * <p>Grouped in one file because they differ only in the shape of a JSON body and a URL —
 * separating six near-identical POSTs into six files would spread the interesting part
 * (the payload each service wants) across a package for no gain.
 *
 * <p>Timeouts are short and deliberate. An alert is worth delivering promptly or not at
 * all: a notification about a five-second stall that arrives ninety seconds later has
 * already been overtaken by the operator opening the panel. Nothing is retried, for the
 * same reason.
 *
 * <p>Alert text is truncated before it goes out. Chat and console content reaches these
 * payloads, and a book pasted into chat should not become a rejected 40 KB request.
 */
public final class Notifiers {

    /** Longest an alert body may be. Comfortably inside every service's own limit. */
    static final int MAX_BODY = 1500;
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // Never follow redirects: an alert URL that starts redirecting is a
            // configuration mistake or a hijack, not something to chase silently.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Notifiers() {
    }

    /** Builds every channel the config describes, enabled or not. */
    public static List<Notifier> build(GodViewConfig.Alerts alerts, Supplier<EventStream> stream) {
        GodViewConfig.Channels channels = alerts.channels();
        List<Notifier> out = new ArrayList<>(6);
        out.add(new Panel(channels.panel(), stream));
        out.add(new Discord(channels.discord()));
        out.add(new Telegram(channels.telegram()));
        out.add(new Webhook(channels.webhook()));
        out.add(new ServerChan(channels.serverchan()));
        out.add(new Bark(channels.bark()));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------- panel

    /**
     * The panel itself. Not really a notification channel — it publishes onto the live
     * stream so an open dashboard shows the alert immediately, with no network involved.
     */
    static final class Panel implements Notifier {
        private final boolean enabled;
        private final Supplier<EventStream> stream;

        Panel(boolean enabled, Supplier<EventStream> stream) {
            this.enabled = enabled;
            this.stream = stream;
        }

        @Override
        public String id() {
            return "panel";
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void send(Alert alert) {
            EventStream live = stream.get();
            if (live == null) return;
            JsonWriter w = new JsonWriter(512);
            alert.writeJson(w);
            live.publish("alert", w.done());
        }
    }

    // ----------------------------------------------------------------- discord

    static final class Discord implements Notifier {
        private final GodViewConfig.Discord config;

        Discord(GodViewConfig.Discord config) {
            this.config = config;
        }

        @Override
        public String id() {
            return "discord";
        }

        @Override
        public boolean enabled() {
            return config.enabled() && !config.webhook().isBlank();
        }

        @Override
        public String problem() {
            if (!config.enabled()) return "";
            return config.webhook().isBlank() ? "alerts.channels.discord.webhook is empty" : "";
        }

        @Override
        public void send(Alert alert) throws Exception {
            JsonWriter w = new JsonWriter(1024);
            w.obj();
            // The mention goes in content rather than the embed: Discord only pings from
            // the message body, and an alert nobody is pinged for is a log line.
            if (!config.mention().isBlank() && alert.isCritical()) {
                w.field("content", config.mention());
            }
            w.name("embeds").arr()
                    .obj()
                    .field("title", Text.truncate(alert.title(), 240))
                    .field("description", Text.truncate(alert.message(), MAX_BODY))
                    .field("color", alert.color())
                    .name("footer").obj().field("text", "GodView - " + alert.ruleId()).end()
                    .field("timestamp", java.time.Instant.ofEpochMilli(alert.at()).toString())
                    .end()
                    .end();
            postJson(config.webhook(), w.end().done(), Map.of());
        }
    }

    // ---------------------------------------------------------------- telegram

    static final class Telegram implements Notifier {
        private final GodViewConfig.Telegram config;

        Telegram(GodViewConfig.Telegram config) {
            this.config = config;
        }

        @Override
        public String id() {
            return "telegram";
        }

        @Override
        public boolean enabled() {
            return config.enabled() && !config.botToken().isBlank() && !config.chatId().isBlank();
        }

        @Override
        public String problem() {
            if (!config.enabled()) return "";
            if (config.botToken().isBlank()) return "alerts.channels.telegram.bot-token is empty";
            if (config.chatId().isBlank()) return "alerts.channels.telegram.chat-id is empty";
            return "";
        }

        @Override
        public void send(Alert alert) throws Exception {
            String text = alert.marker() + " " + alert.title() + "\n"
                    + Text.truncate(alert.message(), MAX_BODY);
            String body = new JsonWriter(1024).obj()
                    .field("chat_id", config.chatId())
                    .field("text", text)
                    // Plain text on purpose: alert bodies contain player names and chat
                    // lines, and any markup mode would let those break the message.
                    .field("disable_web_page_preview", true)
                    .end().done();
            postJson("https://api.telegram.org/bot" + config.botToken() + "/sendMessage",
                    body, Map.of());
        }
    }

    // ----------------------------------------------------------------- webhook

    /** A generic POST, for anything that is not one of the named services. */
    static final class Webhook implements Notifier {
        private final GodViewConfig.Webhook config;

        Webhook(GodViewConfig.Webhook config) {
            this.config = config;
        }

        @Override
        public String id() {
            return "webhook";
        }

        @Override
        public boolean enabled() {
            return config.enabled() && !config.url().isBlank();
        }

        @Override
        public String problem() {
            if (!config.enabled()) return "";
            return config.url().isBlank() ? "alerts.channels.webhook.url is empty" : "";
        }

        @Override
        public void send(Alert alert) throws Exception {
            JsonWriter w = new JsonWriter(512);
            alert.writeJson(w);
            // Qualified: this class has its own send(Alert), and an unqualified call
            // would resolve to it rather than the transport helper.
            Notifiers.send(config.url(), config.method(), w.done(), config.headers());
        }
    }

    // -------------------------------------------------------------- serverchan

    /** ServerChan (Server酱) — a push service widely used on Chinese-hosted servers. */
    static final class ServerChan implements Notifier {
        private final GodViewConfig.ServerChan config;

        ServerChan(GodViewConfig.ServerChan config) {
            this.config = config;
        }

        @Override
        public String id() {
            return "serverchan";
        }

        @Override
        public boolean enabled() {
            return config.enabled() && !config.key().isBlank();
        }

        @Override
        public String problem() {
            if (!config.enabled()) return "";
            return config.key().isBlank() ? "alerts.channels.serverchan.key is empty" : "";
        }

        @Override
        public void send(Alert alert) throws Exception {
            String form = "title=" + encode(alert.marker() + " " + alert.title())
                    + "&desp=" + encode(Text.truncate(alert.message(), MAX_BODY));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://sctapi.ftqq.com/" + config.key() + ".send"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            check(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
        }
    }

    // -------------------------------------------------------------------- bark

    /** Bark — an iOS push service driven entirely by the URL path. */
    static final class Bark implements Notifier {
        private final GodViewConfig.Bark config;

        Bark(GodViewConfig.Bark config) {
            this.config = config;
        }

        @Override
        public String id() {
            return "bark";
        }

        @Override
        public boolean enabled() {
            return config.enabled() && !config.url().isBlank();
        }

        @Override
        public String problem() {
            if (!config.enabled()) return "";
            return config.url().isBlank() ? "alerts.channels.bark.url is empty" : "";
        }

        @Override
        public void send(Alert alert) throws Exception {
            String base = config.url().endsWith("/")
                    ? config.url().substring(0, config.url().length() - 1)
                    : config.url();
            // Bark takes title and body as path segments, so both must be encoded or a
            // slash in a chat message would silently become part of the URL.
            String url = base + "/" + encodePath(alert.marker() + " " + alert.title())
                    + "/" + encodePath(Text.truncate(alert.message(), 400));
            // Critical alerts ask for a sound that can wake somebody up.
            if (alert.isCritical()) url += "?level=critical&sound=alarm";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            check(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static void postJson(String url, String body, Map<String, String> headers)
            throws Exception {
        send(url, "POST", body, headers);
    }

    private static void send(String url, String method, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "GodView");

        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException e) {
                // A header name the JDK refuses (Host, Content-Length…). Skipping one bad
                // entry is better than failing the whole delivery.
            }
        }

        HttpRequest.BodyPublisher payload =
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request = "GET".equalsIgnoreCase(method)
                ? builder.GET().build()
                : builder.method(method.toUpperCase(java.util.Locale.ROOT), payload).build();

        check(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /** Turns a non-2xx response into a failure the dispatcher can record and show. */
    private static void check(HttpResponse<String> response) throws Exception {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        String body = response.body() == null ? "" : Text.truncate(response.body(), 200);
        throw new java.io.IOException("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Path-segment encoding: {@code +} means a plus in a path, not a space. */
    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
