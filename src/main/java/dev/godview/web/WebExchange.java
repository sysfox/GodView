package dev.godview.web;

import com.sun.net.httpserver.HttpExchange;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.web.auth.SessionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * One HTTP request and its response, with the awkward parts of
 * {@code com.sun.net.httpserver} hidden.
 *
 * <p>The JDK server is used rather than Netty or Jetty because it is already in the JVM.
 * A monitoring plugin that shades a web framework would be several megabytes and a
 * classloader conflict waiting to happen on a server that already runs Netty.
 *
 * <p>Two rules the raw API makes easy to break, enforced here: a response is sent exactly
 * once, and the request body is always fully drained. Skipping either leaves a connection
 * wedged until it times out.
 *
 * <p>Request bodies are capped. The cap is checked while reading, not from
 * {@code Content-Length}, since that header is whatever the client chose to send.
 */
public final class WebExchange {

    /** Read chunk size for request bodies. */
    private static final int READ_CHUNK = 8192;
    /** Below this a gzip frame costs more than it saves. */
    private static final int GZIP_MIN_BYTES = 512;

    private final HttpExchange http;
    private final String path;
    private final Map<String, String> query;
    private final String clientIp;
    private final boolean gzipAllowed;
    private final boolean secure;

    private Map<String, String> params = Map.of();
    private SessionManager.Session session;
    private byte[] body;
    private boolean responded;
    private boolean streaming;

    WebExchange(HttpExchange http, String clientIp, boolean gzipEnabled, boolean secure) {
        this.http = http;
        this.clientIp = clientIp;
        this.secure = secure;

        String raw = http.getRequestURI().getRawPath();
        this.path = normalize(raw == null ? "/" : raw);
        this.query = parseQuery(http.getRequestURI().getRawQuery());

        String accept = header("Accept-Encoding");
        this.gzipAllowed = gzipEnabled && accept != null
                && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    // ------------------------------------------------------------------ request

    public String method() {
        return http.getRequestMethod().toUpperCase(Locale.ROOT);
    }

    /** Decoded path with any trailing slash removed, always starting with {@code /}. */
    public String path() {
        return path;
    }

    /** The address the request really came from, after any trusted-proxy resolution. */
    public String clientIp() {
        return clientIp;
    }

    public boolean isSecure() {
        return secure;
    }

    public String header(String name) {
        List<String> values = http.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String query(String key, String def) {
        String value = query.get(key);
        return value == null || value.isEmpty() ? def : value;
    }

    public int queryInt(String key, int def) {
        try {
            String value = query.get(key);
            return value == null ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long queryLong(String key, long def) {
        try {
            String value = query.get(key);
            return value == null ? def : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean queryFlag(String key, boolean def) {
        String value = query.get(key);
        if (value == null) return def;
        return value.isEmpty() || "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /** A path variable captured by the route pattern, e.g. {@code {name}}. */
    public String param(String key) {
        return params.get(key);
    }

    void setParams(Map<String, String> params) {
        this.params = params;
    }

    public SessionManager.Session session() {
        return session;
    }

    void setSession(SessionManager.Session session) {
        this.session = session;
    }

    /** Value of one request cookie, or null. */
    public String cookie(String name) {
        String header = header("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq > 0 && piece.substring(0, eq).trim().equals(name)) {
                return piece.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /**
     * Reads the request body, at most {@code maxBytes}.
     *
     * @throws BodyTooLargeException when the client sends more than the cap
     */
    public byte[] body(int maxBytes) throws IOException {
        if (body != null) return body;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, READ_CHUNK));
        byte[] chunk = new byte[READ_CHUNK];
        try (InputStream in = http.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > maxBytes) throw new BodyTooLargeException(maxBytes);
                out.write(chunk, 0, read);
            }
        }
        body = out.toByteArray();
        return body;
    }

    /** Parses the body as a JSON object. Returns an empty map for an empty or non-object body. */
    public Map<String, Object> json(int maxBytes) throws IOException {
        byte[] raw = body(maxBytes);
        if (raw.length == 0) return Map.of();
        return Json.asObj(Json.parse(new String(raw, StandardCharsets.UTF_8)));
    }

    /** Thrown when a request body exceeds {@code web.max-body-kb}. */
    public static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException(int limit) {
            super("request body exceeds " + limit + " bytes");
        }
    }

    // ----------------------------------------------------------------- response

    public boolean responded() {
        return responded;
    }

    public void setHeader(String name, String value) {
        http.getResponseHeaders().set(name, value);
    }

    public void addHeader(String name, String value) {
        http.getResponseHeaders().add(name, value);
    }

    public void json(int status, String body) throws IOException {
        setHeader("Content-Type", "application/json; charset=utf-8");
        send(status, body.getBytes(StandardCharsets.UTF_8), true);
    }

    /** Serializes and sends whatever the writer has built. */
    public void json(int status, JsonWriter writer) throws IOException {
        json(status, writer.done());
    }

    public void text(int status, String body) throws IOException {
        setHeader("Content-Type", "text/plain; charset=utf-8");
        send(status, body.getBytes(StandardCharsets.UTF_8), true);
    }

    /**
     * A machine-readable error.
     *
     * <p>{@code code} is a stable identifier the panel can branch on; {@code message} is
     * for a human and may change wording freely.
     */
    public void error(int status, String code, String message) throws IOException {
        json(status, new JsonWriter(128)
                .obj()
                .field("error", code)
                .field("message", message)
                .end()
                .done());
    }

    public void noContent() throws IOException {
        send(204, new byte[0], false);
    }

    /** Sends bytes with an explicit content type — used for assets and map tiles. */
    public void bytes(int status, String contentType, byte[] data, boolean compressible)
            throws IOException {
        setHeader("Content-Type", contentType);
        send(status, data, compressible);
    }

    /**
     * Writes the response. Compresses when the client asked for it, the payload is worth
     * compressing and the caller says the content type benefits — already-compressed
     * formats like PNG only get bigger.
     */
    public void send(int status, byte[] data, boolean compressible) throws IOException {
        if (responded) return;
        responded = true;

        boolean head = "HEAD".equals(method());
        byte[] payload = data;

        if (compressible && gzipAllowed && data.length >= GZIP_MIN_BYTES) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(data.length / 3);
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(data);
            }
            payload = compressed.toByteArray();
            setHeader("Content-Encoding", "gzip");
            // Caches and proxies must not serve this to a client that cannot decode it.
            addHeader("Vary", "Accept-Encoding");
        }

        if (head || payload.length == 0) {
            // -1 means "no body". Passing 0 here would mean "chunked, length unknown" and
            // leave the client waiting for content that never arrives.
            http.sendResponseHeaders(status, -1);
            http.close();
            return;
        }

        http.sendResponseHeaders(status, payload.length);
        try (OutputStream out = http.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * Starts a streaming response for Server-Sent Events.
     *
     * <p>Returns the raw stream, which the caller owns until the client disconnects. Not
     * gzipped: compression buffers, and a buffered event stream arrives in bursts instead
     * of live.
     */
    public OutputStream startStream(String contentType) throws IOException {
        if (responded) throw new IllegalStateException("response already sent");
        responded = true;
        streaming = true;
        setHeader("Content-Type", contentType);
        setHeader("Cache-Control", "no-cache, no-store");
        setHeader("X-Accel-Buffering", "no");   // stops nginx buffering the stream
        setHeader("Connection", "keep-alive");
        http.sendResponseHeaders(200, 0);       // 0 = chunked, length not known ahead
        return http.getResponseBody();
    }

    /**
     * True once {@link #startStream} has handed the response stream to a caller.
     *
     * <p>The server checks this to decide whether to close the exchange when the handler
     * returns: a streaming response outlives its handler by design, and closing it would
     * cut the client off immediately.
     */
    public boolean isStreaming() {
        return streaming;
    }

    /** Ends the exchange, releasing the connection. Safe to call more than once. */
    public void close() {
        http.close();
    }

    HttpExchange raw() {
        return http;
    }

    // ---------------------------------------------------------------- internals

    private static String normalize(String rawPath) {
        String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        if (decoded.isEmpty()) return "/";
        if (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded.startsWith("/") ? decoded : "/" + decoded;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // Malformed percent-encoding — skip the parameter rather than fail the request.
            }
        }
        return Map.copyOf(out);
    }
}
