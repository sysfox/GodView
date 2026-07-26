package dev.godview.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Chat capture, kept apart from the other events because chat is the one Bukkit has two
 * live APIs for.
 *
 * <p>Paper's {@code AsyncChatEvent} is the real one on any modern server; the legacy
 * {@code AsyncPlayerChatEvent} only still fires as a compatibility shim, is deprecated for
 * removal, and loses anything a plugin expressed as a Component. So GodView listens to the
 * modern event and flattens the Component to text itself.
 *
 * <p>Isolated in its own class so the Paper-only types load lazily. A fork without them
 * costs a warning and no chat history, rather than a {@code NoClassDefFoundError} that
 * takes the whole listener registration down with it.
 *
 * <p>Handlers run on a netty thread, not the main thread — both storage and the event
 * stream accept writes from anywhere, which is what makes that safe.
 */
final class ChatCapture {

    private ChatCapture() {
    }

    /** Receives a flattened chat line. */
    interface ChatHandler {
        void accept(String player, String uuid, String world, String message);
    }

    /**
     * Registers the chat handler.
     *
     * @return false when this server has no modern chat event, so the caller can warn
     */
    static boolean register(Plugin plugin, Listener owner, ChatHandler handler) {
        try {
            Bukkit.getPluginManager().registerEvent(AsyncChatEvent.class, owner,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!(event instanceof AsyncChatEvent chat)) return;
                        try {
                            handler.accept(
                                    chat.getPlayer().getName(),
                                    chat.getPlayer().getUniqueId().toString(),
                                    chat.getPlayer().getWorld().getName(),
                                    PlainTextComponentSerializer.plainText()
                                            .serialize(chat.message()));
                        } catch (Throwable t) {
                            plugin.getLogger().warning("GodView failed to record a chat line: " + t);
                        }
                    },
                    plugin, true);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("GodView could not hook chat on this server — "
                    + "chat history will be empty. (" + t + ")");
            return false;
        }
    }
}
