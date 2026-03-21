package net.survivalfun.core.listeners.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Handles staff chat redirect prefixes when using ChatControl:
 * - In staff chat: &lt;message → send that message to default (global) only, stay in staff chat.
 * - In global chat: #message → send that message to staff-chat only, stay in global.
 * If the prefix is used in the wrong chat, the message is sent raw (no stripping of # or &lt;).
 */
public class StaffChatListener implements Listener {

    private final PluginStart plugin;
    private final Database database;

    public StaffChatListener(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    /**
     * Run at LOWEST so we cancel the chat event before any other listener (e.g. ChatControl,
     * format listeners) can broadcast it. Then we replace with the channel command so the message
     * only goes to the target channel (Essentials-style).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("allium.staffchat")) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isEmpty()) {
            return;
        }

        boolean inStaffChat = database.getStaffChatMode(player.getUniqueId());
        String playerName = player.getName();

        // In staff chat: <message → send to default only (one-time), stay in staff chat
        if (inStaffChat && message.startsWith("<")) {
            String toDefault = message.substring(1).trim();
            if (!toDefault.isEmpty()) {
                redirectMessage(event, playerName, "default", toDefault);
                return;
            }
        }

        // In global chat: #message → send to staff-chat only (one-time), stay in global.
        // Cancel so the message is never broadcast to global; only the command runs.
        if (!inStaffChat && message.startsWith("#")) {
            String toStaff = message.substring(1).trim();
            if (!toStaff.isEmpty()) {
                redirectMessage(event, playerName, "staff-chat", toStaff);
                return;
            }
        }

        // Wrong chat (e.g. # in staff chat or < in global): do nothing, message goes through raw
    }

    private void redirectMessage(@NotNull AsyncChatEvent event, String playerName, String channelName, String message) {
        event.setCancelled(true);
        String cmd = "channel sendas " + playerName + " " + channelName + " " + message;
        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }
}
