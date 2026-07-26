package dev.godview.listener;

import dev.godview.config.GodViewConfig;
import dev.godview.storage.Storage;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import dev.godview.util.Text;
import dev.godview.web.api.EventStream;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns server events into the history the panel shows.
 *
 * <p>Handlers are registered one at a time through
 * {@link PluginManager#registerEvent}, driven by the {@code capture:} flags, rather than
 * as annotated methods on a class Bukkit registers wholesale. That is what makes the
 * config comment literally true: a disabled event type has no handler at all, so it costs
 * nothing — not a listener invocation, not a branch. It matters most for the block events,
 * which fire thousands of times a second on a busy server and are off by default.
 *
 * <p>Everything registers at {@link EventPriority#MONITOR} with {@code ignoreCancelled},
 * because this class observes and never interferes: we want the final decision another
 * plugin made, and a chat message some filter cancelled should not appear in the log as if
 * it were sent.
 *
 * <p>Player-supplied text is truncated before it reaches storage. A book pasted into chat
 * should not become a 30 KB row, or a 30 KB Discord payload downstream.
 *
 * <p>A few accessors here are deprecated in favour of Adventure equivalents that return
 * Components. The panel wants a plain string, so the legacy form is the right call and is
 * suppressed at the individual method rather than left to fill the build log.
 */
public final class EventCapture implements Listener {

    /** Chat lines, commands and death messages are cut to this before being stored. */
    private static final int MAX_DETAIL = 512;

    private final Plugin plugin;
    private final Supplier<Storage> storage;
    private final Supplier<EventStream> stream;
    private final Runnable onPluginChange;

    private volatile GodViewConfig config;
    private volatile boolean registered;

    public EventCapture(Plugin plugin, GodViewConfig config, Supplier<Storage> storage,
                        Supplier<EventStream> stream, Runnable onPluginChange) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
        this.stream = stream;
        this.onPluginChange = onPluginChange;
    }

    // -------------------------------------------------------------- lifecycle

    /** Registers exactly the handlers the config asks for. */
    public void register() {
        if (registered) return;
        registered = true;
        GodViewConfig.Capture capture = config.capture;

        on(capture.joinQuit(), PlayerJoinEvent.class, this::onJoin);
        on(capture.joinQuit(), PlayerQuitEvent.class, this::onQuit);
        on(capture.commands(), PlayerCommandPreprocessEvent.class, this::onPlayerCommand);
        on(capture.commands(), ServerCommandEvent.class, this::onConsoleCommand);
        on(capture.deaths(), PlayerDeathEvent.class, this::onDeath);
        on(capture.kicksBans(), PlayerKickEvent.class, this::onKick);
        on(capture.teleports(), PlayerTeleportEvent.class, this::onTeleport);
        on(capture.worldLoad(), WorldLoadEvent.class, this::onWorldLoad);
        on(capture.worldLoad(), WorldUnloadEvent.class, this::onWorldUnload);
        on(capture.worldLoad(), WorldSaveEvent.class, this::onWorldSave);
        on(capture.blockPlace(), BlockPlaceEvent.class, this::onBlockPlace);
        on(capture.blockBreak(), BlockBreakEvent.class, this::onBlockBreak);
        on(capture.containerOpen(), InventoryOpenEvent.class, this::onContainerOpen);

        // Chat is the one event whose modern and legacy forms both exist, so it gets its
        // own resolution: see ChatCapture.
        if (capture.chat()) ChatCapture.register(plugin, this, this::onChat);

        // Not a capture flag: spike attribution needs to know when the plugin list moves.
        registerPluginWatch();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        registered = false;
    }

    /** Re-registers against a new config, so a reload can turn capture flags on and off. */
    public void reload(GodViewConfig newConfig) {
        unregister();
        this.config = newConfig;
        register();
    }

    public boolean isRegistered() {
        return registered;
    }

    /**
     * Registers one handler, or nothing at all when the flag is off.
     *
     * <p>The executor casts rather than trusting Bukkit, because a handler registered for
     * a superclass also receives its subclasses.
     */
    private <T extends Event> void on(boolean enabled, Class<T> type, Consumer<T> handler) {
        if (!enabled) return;
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (!type.isInstance(event)) return;
                    try {
                        handler.accept(type.cast(event));
                    } catch (Throwable t) {
                        // A failure while recording history must never break gameplay.
                        plugin.getLogger().warning("GodView failed to record a "
                                + type.getSimpleName() + ": " + t);
                    }
                },
                plugin, true);
    }

    private void registerPluginWatch() {
        if (onPluginChange == null) return;
        Consumer<Event> refresh = event -> onPluginChange.run();
        on(true, org.bukkit.event.server.PluginEnableEvent.class, refresh::accept);
        on(true, org.bukkit.event.server.PluginDisableEvent.class, refresh::accept);
    }

    // ---------------------------------------------------------------- handlers

    private void onJoin(PlayerJoinEvent event) {
        record("join", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getWorld().getName(),
                event.getPlayer().getName() + " joined",
                meta("first", !event.getPlayer().hasPlayedBefore()));
    }

    private void onQuit(PlayerQuitEvent event) {
        record("quit", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getWorld().getName(),
                event.getPlayer().getName() + " left", null);
    }

    private void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        record("command", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getWorld().getName(),
                Text.truncate(event.getMessage(), MAX_DETAIL),
                meta("source", "player"));
    }

    private void onConsoleCommand(ServerCommandEvent event) {
        record("command", event.getSender().getName(), null, null,
                Text.truncate("/" + event.getCommand(), MAX_DETAIL),
                meta("source", "console"));
    }

    private void onChat(String player, String uuid, String world, String message) {
        record("chat", player, uuid, world, Text.truncate(message, MAX_DETAIL), null);
    }

    /** {@code getDeathMessage} has an Adventure replacement; the panel wants the string. */
    @SuppressWarnings("deprecation")
    private void onDeath(PlayerDeathEvent event) {
        String message = event.getDeathMessage();
        record("death", event.getEntity().getName(),
                event.getEntity().getUniqueId().toString(),
                event.getEntity().getWorld().getName(),
                Text.truncate(message == null ? event.getEntity().getName() + " died"
                        : Text.stripColors(message), MAX_DETAIL),
                meta("keepInventory", event.getKeepInventory()));
    }

    /** {@code getReason} likewise. */
    @SuppressWarnings("deprecation")
    private void onKick(PlayerKickEvent event) {
        record("kick", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getWorld().getName(),
                Text.truncate(Text.stripColors(event.getReason()), MAX_DETAIL), null);
    }

    private void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        String from = describe(event.getFrom().getWorld() == null ? "?"
                : event.getFrom().getWorld().getName(), event.getFrom().getBlockX(),
                event.getFrom().getBlockY(), event.getFrom().getBlockZ());
        String to = describe(event.getTo().getWorld() == null ? "?"
                : event.getTo().getWorld().getName(), event.getTo().getBlockX(),
                event.getTo().getBlockY(), event.getTo().getBlockZ());
        record("teleport", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getTo().getWorld() == null ? null : event.getTo().getWorld().getName(),
                from + " -> " + to,
                meta("cause", event.getCause().name()));
    }

    private void onWorldLoad(WorldLoadEvent event) {
        record("world_load", "server", null, event.getWorld().getName(),
                "Loaded " + event.getWorld().getName(), null);
    }

    private void onWorldUnload(WorldUnloadEvent event) {
        record("world_load", "server", null, event.getWorld().getName(),
                "Unloaded " + event.getWorld().getName(), null);
    }

    private void onWorldSave(WorldSaveEvent event) {
        record("world_load", "server", null, event.getWorld().getName(),
                "Saved " + event.getWorld().getName(), null);
    }

    private void onBlockPlace(BlockPlaceEvent event) {
        record("block_place", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getBlock().getWorld().getName(),
                event.getBlock().getType().name() + " at "
                        + describe(null, event.getBlock().getX(), event.getBlock().getY(),
                        event.getBlock().getZ()), null);
    }

    private void onBlockBreak(BlockBreakEvent event) {
        record("block_break", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getBlock().getWorld().getName(),
                event.getBlock().getType().name() + " at "
                        + describe(null, event.getBlock().getX(), event.getBlock().getY(),
                        event.getBlock().getZ()), null);
    }

    private void onContainerOpen(InventoryOpenEvent event) {
        if (event.getInventory().getLocation() == null) return;   // a virtual GUI, not a container
        record("container_open", event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                event.getInventory().getLocation().getWorld() == null ? null
                        : event.getInventory().getLocation().getWorld().getName(),
                event.getInventory().getType().name() + " at "
                        + describe(null, event.getInventory().getLocation().getBlockX(),
                        event.getInventory().getLocation().getBlockY(),
                        event.getInventory().getLocation().getBlockZ()), null);
    }

    // ----------------------------------------------------------------- output

    /**
     * Writes one event to storage and pushes it to any open panel.
     *
     * <p>Called from the main thread for most events and from a netty thread for chat.
     * Both destinations are safe from either: storage queues, and the stream queues.
     */
    public void record(String type, String actor, String uuid, String world, String detail,
                       String meta) {
        Storage store = storage.get();
        Storage.Event event = new Storage.Event(System.currentTimeMillis(), type, actor, uuid,
                world, detail, meta);
        if (store != null) store.recordEvent(event);

        EventStream live = stream.get();
        if (live != null && live.subscriberCount() > 0) {
            JsonWriter w = new JsonWriter(256);
            event.writeJson(w);
            live.publish("event", w.done());
        }
    }

    private static String describe(String world, int x, int y, int z) {
        String position = x + "," + y + "," + z;
        return world == null ? position : world + " " + position;
    }

    private static String meta(String key, Object value) {
        return new JsonWriter(64).obj().field(key, value).end().done();
    }

    /** Exposed for the console capture, which builds its own metadata. */
    public static String metaJson(java.util.Map<String, Object> values) {
        return Json.write(values);
    }
}
