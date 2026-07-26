package dev.godview.integration;

import dev.godview.util.JsonWriter;

/**
 * One optional plugin GodView can read from.
 *
 * <p>Every integration is optional twice over: the plugin may not be installed, and the
 * admin may have switched it off in {@code integrations:}. Neither case is an error, and
 * neither may produce a stack trace on startup — a panel that refuses to work because
 * LuckPerms is absent would be a worse tool than one that quietly shows no groups.
 *
 * <p>Implementations must survive their target plugin being reloaded or disabled
 * underneath them. In practice that means holding on to nothing that can go stale: look
 * the provider up per call, and treat every failure as "unavailable" rather than
 * propagating it into a request handler.
 */
public interface Integration {

    /** Stable lower-case identifier, matching the key in {@code integrations:}. */
    String id();

    /** Human-readable name, as the other plugin calls itself. */
    String displayName();

    /** True when the target plugin is present, enabled and usable right now. */
    boolean available();

    /** The target plugin's version, or an empty string when it is not installed. */
    String version();

    /**
     * Why an integration that looks installed is not usable — a version mismatch, a
     * provider that never registered. Empty when there is nothing to report.
     */
    default String problem() {
        return "";
    }

    default void writeJson(JsonWriter w) {
        w.obj()
                .field("id", id())
                .field("name", displayName())
                .field("available", available())
                .field("version", version())
                .field("problem", problem())
                .end();
    }
}
