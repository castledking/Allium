package net.survivalfun.core.commands.chat;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.chat.ChatMessageManager;

import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.util.SchedulerAdapter;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles the deletion of chat messages by staff members
 */
public class DeleteMsg implements CommandExecutor {
    
    private final PluginStart plugin;
    private final ChatMessageManager chatMessageManager;
    
    public DeleteMsg(PluginStart plugin, ChatMessageManager chatMessageManager) {
        this.plugin = plugin;
        this.chatMessageManager = chatMessageManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }

            Player player = (Player) sender;

            // Check permission (support both legacy and alias)
            boolean hasPerm = player.hasPermission("allium.staff")
                    || player.hasPermission("allium.deletemsg")
                    || player.hasPermission("allium.delmsg");
            if (!hasPerm) {
                player.sendMessage(Component.text("§cYou don't have permission to delete chat messages."));
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(Component.text("§cUsage: /delmsg <messageId>"));
                return true;
            }

            long messageId;
            try {
                messageId = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("§cInvalid message ID. Must be a number."));
                return true;
            }

            // Get the message to check if it exists
            ChatMessageManager.ChatMessage message = chatMessageManager.getMessage(messageId);
            if (message == null) {
                player.sendMessage(Component.text("§cMessage not found."));
                return true;
            }

            if (message.isDeleted()) {
                player.sendMessage(Component.text("§cMessage has already been deleted."));
                return true;
            }

            // Delete the message
            if (chatMessageManager.deleteMessage(messageId)) {
                player.sendMessage(Component.text("§aMessage deleted successfully."));

                // Full clear+resend only works with PacketEvents (tracks all chat including other plugins).
                // Without it, we only have FormatChatListener messages - resending would lose other plugin messages.
                SchedulerAdapter.runLater(() -> {
                    if (plugin.getChatPacketTracker().supportsResend()) {
                        plugin.getChatPacketTracker().resendChatHistoryToAllPlayers();
                    } else {
                        plugin.retryChatPacketTrackerInitIfNeeded();
                        if (plugin.getChatPacketTracker().supportsResend()) {
                            plugin.getChatPacketTracker().resendChatHistoryToAllPlayers();
                        } else {
                            Text.sendDebugLog(WARN, "/delmsg: PacketEvents unavailable - sending header only (no clear/resend). Run with debug-mode: true and check logs for [delmsg] PacketEvents status.");
                            sendHeaderToAllPlayers();
                        }
                    }
                }, 1L);

                // Log the deletion
                String senderName = message.getSenderName() != null ? message.getSenderName() : "unknown";
                Text.sendDebugLog(INFO, "Staff member " + player.getName() + " deleted message " + messageId + " from " + senderName);
            } else {
                player.sendMessage(Component.text("§cFailed to delete message."));
            }

            return true;
        } catch (Exception ex) {
            // Prevent command exception bubbling; log diagnostic
            Text.sendDebugLog(WARN, "/delmsg failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()));
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            if (plugin.getConfig().getBoolean("debug-mode", false)) {
                Text.sendDebugLog(WARN, sw.toString());
            }
            if (sender != null) {
                sender.sendMessage(Component.text("§cAn error occurred while deleting the message."));
            }
            return true;
        }
    }

    /**
     * Fallback when PacketEvents unavailable: send config header only.
     * Full clear+resend requires PacketEvents to track messages from all plugins.
     */
    private void sendHeaderToAllPlayers() {
        try {
            if (!plugin.getConfig().getBoolean("chat.deletion_resend.header_enabled", true)) return;
            String header = plugin.getConfig().getString("chat.deletion_resend.header", "&8&oChat re-synced by staff; a message was deleted");
            Component msg = Text.colorize(header);
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p != null && p.isOnline()) p.sendMessage(msg);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error sending deletion header: " + e.getMessage());
        }
    }
}
