package dev.godview.web;

import dev.godview.web.auth.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps method and path to a handler, and records what each route requires.
 *
 * <p>The permission lives on the route rather than inside the handler, so authorization is
 * decided by the table and cannot be forgotten in a handler body. A route registered with
 * {@link #get} needs a permission; only the handful registered through
 * {@link #publicRoute} are reachable without a session, and they are few enough to read
 * at a glance in {@code ApiRoutes}.
 *
 * <p>Patterns support one form of variable, {@code /api/players/{name}}, which matches a
 * single path segment. There is no wildcard and no regex: this table is small and fixed,
 * and a router that can express surprising matches is a router that eventually authorizes
 * a surprising request.
 *
 * <p>Longer patterns are matched first, so {@code /api/worlds/overworld/entities} wins
 * over {@code /api/worlds/{name}} regardless of registration order.
 */
public final class Router {

    /** What a handler does with a request. */
    @FunctionalInterface
    public interface Handler {
        void handle(WebExchange exchange) throws Exception;
    }

    private final List<Route> routes = new ArrayList<>();

    /**
     * One registered route.
     *
     * @param required the permission a session must hold, or null when the route is public
     */
    record Route(String method, String[] segments, Role.Permission required, Handler handler,
                 boolean streaming) {

        int specificity() {
            int score = segments.length * 10;
            for (String segment : segments) {
                if (!isVariable(segment)) score++;   // literals beat variables
            }
            return score;
        }

        static boolean isVariable(String segment) {
            return segment.length() > 2 && segment.charAt(0) == '{'
                    && segment.charAt(segment.length() - 1) == '}';
        }
    }

    public Router get(String pattern, Role.Permission required, Handler handler) {
        return add("GET", pattern, required, handler, false);
    }

    public Router post(String pattern, Role.Permission required, Handler handler) {
        return add("POST", pattern, required, handler, false);
    }

    public Router put(String pattern, Role.Permission required, Handler handler) {
        return add("PUT", pattern, required, handler, false);
    }

    public Router delete(String pattern, Role.Permission required, Handler handler) {
        return add("DELETE", pattern, required, handler, false);
    }

    /**
     * A long-lived streaming response, exempt from the response-time bookkeeping and never
     * gzipped. Used for the SSE endpoint.
     */
    public Router stream(String pattern, Role.Permission required, Handler handler) {
        return add("GET", pattern, required, handler, true);
    }

    /**
     * A route reachable without a session — login, the login page, and the health check.
     * Deliberately verbose to name, because every one of these is an attack surface.
     */
    public Router publicRoute(String method, String pattern, Handler handler) {
        return add(method, pattern, null, handler, false);
    }

    private Router add(String method, String pattern, Role.Permission required, Handler handler,
                       boolean streaming) {
        Route route = new Route(method.toUpperCase(Locale.ROOT), split(pattern), required,
                handler, streaming);
        routes.add(route);
        routes.sort((a, b) -> b.specificity() - a.specificity());
        return this;
    }

    /** The result of matching a request against the table. */
    public record Match(Route route, Map<String, String> params) {

        public Role.Permission required() {
            return route.required();
        }

        public boolean isPublic() {
            return route.required() == null;
        }

        public boolean streaming() {
            return route.streaming();
        }

        public Handler handler() {
            return route.handler();
        }
    }

    /**
     * Finds the route for a request.
     *
     * @return the match, or null when nothing matches the path
     */
    public Match match(String method, String path) {
        String[] parts = split(path);
        String verb = method.toUpperCase(Locale.ROOT);
        // HEAD is served by the GET handler; the exchange suppresses the body.
        if ("HEAD".equals(verb)) verb = "GET";

        for (Route route : routes) {
            if (!route.method().equals(verb)) continue;
            Map<String, String> params = bind(route.segments(), parts);
            if (params != null) return new Match(route, params);
        }
        return null;
    }

    /** True when the path exists under some other method, so we can answer 405 not 404. */
    public boolean pathExists(String path) {
        String[] parts = split(path);
        for (Route route : routes) {
            if (bind(route.segments(), parts) != null) return true;
        }
        return false;
    }

    /** Methods allowed on a path, for the {@code Allow} header on a 405. */
    public String allowedMethods(String path) {
        String[] parts = split(path);
        List<String> methods = new ArrayList<>();
        for (Route route : routes) {
            if (bind(route.segments(), parts) != null && !methods.contains(route.method())) {
                methods.add(route.method());
            }
        }
        return String.join(", ", methods);
    }

    public int size() {
        return routes.size();
    }

    /** @return the captured variables, or null when the pattern does not match */
    private static Map<String, String> bind(String[] pattern, String[] path) {
        if (pattern.length != path.length) return null;
        Map<String, String> params = null;
        for (int i = 0; i < pattern.length; i++) {
            String expected = pattern[i];
            if (Route.isVariable(expected)) {
                if (path[i].isEmpty()) return null;
                if (params == null) params = new HashMap<>(4);
                params.put(expected.substring(1, expected.length() - 1), path[i]);
            } else if (!expected.equals(path[i])) {
                return null;
            }
        }
        return params == null ? Map.of() : Map.copyOf(params);
    }

    private static String[] split(String path) {
        String trimmed = path;
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("/");
    }
}
