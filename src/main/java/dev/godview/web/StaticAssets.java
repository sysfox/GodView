package dev.godview.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Serves the panel's own files out of the plugin jar.
 *
 * <p>Everything under {@code /web} in the jar is reachable; nothing else is. Requests are
 * resolved against that fixed prefix after rejecting any {@code ..} segment, so no crafted
 * path can escape into the rest of the jar or the filesystem.
 *
 * <p>Assets are read once and held in memory. The whole panel is a few hundred kilobytes,
 * and re-reading a zip entry on every request would be both slower and pointlessly harder
 * on a server's disk.
 *
 * <p>Unknown paths that are not obviously files fall back to {@code index.html}, so the
 * panel's client-side routes survive a page refresh. Paths that look like files
 * (they have an extension) 404 properly instead — a missing script should be a visible
 * error, not a silent page of HTML delivered as JavaScript.
 */
public final class StaticAssets {

    private static final String PREFIX = "web";
    private static final String INDEX = "index.html";

    private final ClassLoader loader;
    private final Map<String, Asset> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> missing = new ConcurrentHashMap<>();
    private final boolean devNoCache;

    /**
     * @param devNoCache when true, hashed assets are still revalidated on every request —
     *                   useful while building the panel, wasteful in production
     */
    public StaticAssets(ClassLoader loader, boolean devNoCache) {
        this.loader = loader;
        this.devNoCache = devNoCache;
    }

    private record Asset(byte[] data, String contentType, String etag, boolean compressible) {
    }

    /**
     * Serves a file for the request path.
     *
     * @return false when nothing matched and the caller should send a 404
     */
    public boolean serve(WebExchange exchange, String path) throws IOException {
        String resource = resolve(path);
        if (resource == null) return false;

        Asset asset = load(resource);
        if (asset == null) {
            // A request for a specific file that does not exist is an error. Anything else
            // is a client-side route, which the shell page handles.
            if (hasExtension(resource)) return false;
            asset = load(INDEX);
            if (asset == null) return false;
        }

        // A matching ETag means the browser already has this byte-for-byte.
        String ifNoneMatch = exchange.header("If-None-Match");
        if (asset.etag().equals(ifNoneMatch)) {
            exchange.setHeader("ETag", asset.etag());
            exchange.setHeader("Cache-Control", cacheControl(resource));
            exchange.send(304, new byte[0], false);
            return true;
        }

        exchange.setHeader("ETag", asset.etag());
        exchange.setHeader("Cache-Control", cacheControl(resource));
        exchange.bytes(200, asset.contentType(), asset.data(), asset.compressible());
        return true;
    }

    /** True when the panel's shell page is present in the jar. */
    public boolean available() {
        return load(INDEX) != null;
    }

    /** Drops the in-memory copies. Used on reload so a rebuilt panel is picked up. */
    public void clearCache() {
        cache.clear();
        missing.clear();
    }

    // ---------------------------------------------------------------- internals

    /** @return the jar-relative resource name, or null when the path is not servable */
    private static String resolve(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) return INDEX;

        for (String segment : trimmed.split("/")) {
            // "." and an empty segment are harmless but pointless; ".." is an escape attempt.
            if (segment.equals("..")) return null;
            if (segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) return null;
        }
        return trimmed;
    }

    private Asset load(String resource) {
        Asset cached = cache.get(resource);
        if (cached != null) return cached;
        if (missing.containsKey(resource)) return null;

        try (InputStream in = loader.getResourceAsStream(PREFIX + "/" + resource)) {
            if (in == null) {
                missing.put(resource, Boolean.TRUE);
                return null;
            }
            byte[] data = in.readAllBytes();
            String type = contentType(resource);
            Asset asset = new Asset(data, type, etag(data), compressible(type));
            cache.put(resource, asset);
            return asset;
        } catch (IOException e) {
            missing.put(resource, Boolean.TRUE);
            return null;
        }
    }

    /**
     * CRC32 rather than a cryptographic digest: this only needs to change when the bytes
     * change, and the bytes are ours. A collision would serve a stale asset to one browser,
     * not create a security problem.
     */
    private static String etag(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return "\"" + Long.toHexString(crc.getValue()) + '-' + data.length + "\"";
    }

    private String cacheControl(String resource) {
        if (devNoCache || INDEX.equals(resource)) {
            // The shell page names the hashed assets, so it must never be cached hard —
            // a stale index would point at files that no longer exist after an update.
            return "no-cache";
        }
        return "public, max-age=604800";
    }

    private static boolean hasExtension(String resource) {
        int slash = resource.lastIndexOf('/');
        int dot = resource.lastIndexOf('.');
        return dot > slash && dot < resource.length() - 1;
    }

    private static boolean compressible(String contentType) {
        // Images and fonts are already compressed; a second pass only adds CPU and bytes.
        return contentType.startsWith("text/")
                || contentType.contains("javascript")
                || contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("svg");
    }

    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("mjs", "text/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("webp", "image/webp");
        TYPES.put("gif", "image/gif");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff", "font/woff");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("ttf", "font/ttf");
        TYPES.put("map", "application/json; charset=utf-8");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("webmanifest", "application/manifest+json");
    }

    static String contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /** Renders a minimal page for when the panel assets are missing from the jar. */
    static byte[] placeholderPage() {
        return ("<!doctype html><meta charset=\"utf-8\">"
                + "<title>GodView</title>"
                + "<style>body{font:14px system-ui;margin:3rem auto;max-width:34rem;line-height:1.6}"
                + "code{background:#eee;padding:.1rem .3rem;border-radius:3px}</style>"
                + "<h1>GodView</h1>"
                + "<p>The API is running, but the panel's front-end files are not in this jar.</p>"
                + "<p>The REST API is available under <code>/api</code>.</p>")
                .getBytes(StandardCharsets.UTF_8);
    }
}
