package dev.godview.web.api;

import dev.godview.map.MapService;
import dev.godview.metrics.PlayerSnapshot;
import dev.godview.metrics.WorldStats;
import dev.godview.util.JsonWriter;
import dev.godview.web.Router;
import dev.godview.web.WebExchange;
import dev.godview.web.auth.Role;

import java.io.IOException;

/**
 * The map endpoints.
 *
 * <p>A tile that is not ready yet answers {@code 202 Accepted} with a {@code Retry-After}
 * rather than blocking until it is. The panel treats that as "draw the placeholder and ask
 * again", which keeps a full-screen pan — dozens of tiles at once — from pinning every
 * HTTP worker while the capture queue drains one tick at a time.
 *
 * <p>Tiles carry a long cache lifetime and an ETag, because the expensive part is
 * producing them. The panel scrolling back over ground it has already seen should cost a
 * 304, not another render.
 */
public final class MapRoutes {

    /** Browser cache lifetime for a rendered tile. Shorter than the server-side TTL. */
    private static final int TILE_CACHE_SECONDS = 60;

    private final ApiContext ctx;
    private final java.util.function.Supplier<MapService> map;

    public MapRoutes(ApiContext ctx, java.util.function.Supplier<MapService> map) {
        this.ctx = ctx;
        this.map = map;
    }

    public void register(Router router) {
        router.get("/api/map/status", Role.Permission.VIEW, this::status);
        router.get("/api/map/markers", Role.Permission.VIEW, this::markers);
        router.get("/api/map/tile/{world}/{x}/{z}", Role.Permission.VIEW, this::tile);
        // Forcing a redraw is cheap but it does put work on the tick budget, so it needs
        // more than read access.
        router.post("/api/map/invalidate/{world}", Role.Permission.WORLD_ACTION, this::invalidate);
    }

    private void status(WebExchange exchange) throws IOException {
        MapService service = map.get();
        if (service == null) {
            exchange.json(200, new JsonWriter(96).obj()
                    .field("enabled", false).field("running", false).end());
            return;
        }
        JsonWriter w = new JsonWriter(1024);
        service.writeStatusJson(w);
        exchange.json(200, w);
    }

    /**
     * Everything the map draws on top of the tiles: players, spawn, world bounds.
     *
     * <p>Served from the metrics caches, so panning the map never costs tick time — only
     * the tiles themselves do.
     */
    private void markers(WebExchange exchange) throws IOException {
        String world = exchange.query("world", null);

        JsonWriter w = new JsonWriter(2048);
        w.obj().name("players").arr();
        for (PlayerSnapshot player : ctx.metrics().players()) {
            if (world != null && !world.equals(player.world())) continue;
            w.obj()
                    .field("name", player.name())
                    .field("uuid", player.uuid())
                    .field("world", player.world())
                    .field("x", player.x())
                    .field("y", player.y())
                    .field("z", player.z())
                    .field("health", player.health())
                    .field("gamemode", player.gamemode())
                    .end();
        }
        w.end().name("worlds").arr();
        for (WorldStats stats : ctx.metrics().worlds()) {
            w.obj()
                    .field("name", stats.name())
                    .field("environment", stats.environment())
                    .field("players", stats.players())
                    .field("chunks", stats.chunks())
                    .name("hotspots").arr();
            for (WorldStats.Hotspot hotspot : stats.hotspots()) {
                w.obj()
                        .field("x", hotspot.x() * 16 + 8)
                        .field("z", hotspot.z() * 16 + 8)
                        .field("entities", hotspot.entities())
                        .end();
            }
            w.end().end();
        }
        exchange.json(200, w.end().end());
    }

    private void tile(WebExchange exchange) throws IOException {
        MapService service = map.get();
        if (service == null) {
            exchange.error(503, "map_disabled", "The map is not running.");
            return;
        }

        int tileX;
        int tileZ;
        try {
            tileX = Integer.parseInt(exchange.param("x"));
            tileZ = Integer.parseInt(exchange.param("z"));
        } catch (NumberFormatException e) {
            exchange.error(400, "bad_coordinates", "Tile coordinates must be integers.");
            return;
        }

        int scale = exchange.queryInt("scale", 1);
        MapService.TileResult result = service.tile(exchange.param("world"), tileX, tileZ, scale);

        if (result instanceof MapService.TileResult.Ready ready) {
            String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(ready.png()))
                    + '-' + ready.png().length + "\"";
            if (etag.equals(exchange.header("If-None-Match"))) {
                exchange.setHeader("ETag", etag);
                exchange.send(304, new byte[0], false);
                return;
            }
            exchange.setHeader("ETag", etag);
            exchange.setHeader("Cache-Control", "private, max-age=" + TILE_CACHE_SECONDS);
            exchange.setHeader("X-GodView-Cache", ready.fromCache() ? "hit" : "miss");
            // PNG is already compressed; gzip would add CPU and bytes.
            exchange.bytes(200, "image/png", ready.png(), false);
            return;
        }

        if (result instanceof MapService.TileResult.Pending pending) {
            exchange.setHeader("Retry-After", "1");
            exchange.json(202, new JsonWriter(160).obj()
                    .field("status", "rendering")
                    .field("capturedChunks", pending.capturedChunks())
                    .field("totalChunks", pending.totalChunks())
                    .end());
            return;
        }

        MapService.TileResult.Unavailable unavailable = (MapService.TileResult.Unavailable) result;
        exchange.error(404, "tile_unavailable", unavailable.reason());
    }

    private void invalidate(WebExchange exchange) throws IOException {
        MapService service = map.get();
        if (service == null) {
            exchange.error(503, "map_disabled", "The map is not running.");
            return;
        }
        String world = exchange.param("world");
        service.invalidateWorld(world);
        exchange.json(200, new JsonWriter(128).obj()
                .field("ok", true)
                .field("message", "Cached tiles for " + world + " will be redrawn.")
                .end());
    }
}
