package dev.godview.web.auth;

import java.util.Locale;
import java.util.Set;

/**
 * What a panel account is allowed to do.
 *
 * <p>Three roles rather than per-permission accounts: a Minecraft server's operator team
 * is small, and a permission matrix nobody understands is worse than a coarse split
 * everybody does. The separation that actually matters is between reading the panel,
 * acting on players, and changing who else can log in.
 *
 * <p>{@link #ADMIN} is the only role that can manage accounts or touch configuration —
 * so an operator who is compromised cannot grant themselves more, or lock the owner out.
 */
public enum Role {

    /** Read-only. Charts, logs, the map, player list. Cannot change anything. */
    VIEWER(Set.of(Permission.VIEW)),

    /** Day-to-day moderation: kick, ban, teleport, run allowed commands. */
    OPERATOR(Set.of(Permission.VIEW, Permission.PLAYER_ACTION, Permission.COMMAND,
            Permission.WORLD_ACTION)),

    /** Everything, including panel accounts and configuration. */
    ADMIN(Set.of(Permission.VIEW, Permission.PLAYER_ACTION, Permission.COMMAND,
            Permission.WORLD_ACTION, Permission.MANAGE_USERS, Permission.MANAGE_CONFIG));

    /** The individual capabilities a role grants. */
    public enum Permission {
        /** Read any panel data. */
        VIEW,
        /** Kick, ban, mute, teleport, edit inventories. */
        PLAYER_ACTION,
        /** Run console commands, subject to the configured command filter. */
        COMMAND,
        /** Weather, time, world save, entity cleanup. */
        WORLD_ACTION,
        /** Create, remove and re-password panel accounts. */
        MANAGE_USERS,
        /** Edit config.yml and reload the plugin. */
        MANAGE_CONFIG
    }

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return null when the name matches no role, so callers can report the typo */
    public static Role parse(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "viewer", "read", "readonly" -> VIEWER;
            case "operator", "op", "mod" -> OPERATOR;
            case "admin", "owner" -> ADMIN;
            default -> null;
        };
    }
}
