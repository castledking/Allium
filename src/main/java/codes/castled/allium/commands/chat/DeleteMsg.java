package codes.castled.allium.commands.chat;

import net.kyori.adventure.text.Component;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles the deletion of chat messages by staff members
 */
public class DeleteMsg implements CommandExecutor {

    private static final char[] CLEAR_CHARS = {
            ' ',
            '\u2000', '\u2001', '\u2002', '\u2003',
            '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A'
    };
    
    private final PluginStart plugin;
    private final ChatMessageManager chatMessageManager;
    private final AtomicBoolean resendInProgress = new AtomicBoolean(false);
    
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

            if (args.length != 1 && args.length != 2) {
                player.sendMessage(Component.text("§cUsage: /delmsg <messageId> or /delmsg <player> <amount>"));
                return true;
            }

            if (args.length == 1) {
                long messageId;
                try {
                    messageId = Long.parseLong(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.text("§cInvalid message ID. Must be a number."));
                    return true;
                }

                ChatMessageManager.ChatMessage message = chatMessageManager.getMessage(messageId);
                if (message == null) {
                    player.sendMessage(Component.text("§cMessage not found."));
                    return true;
                }

                if (message.isDeleted()) {
                    player.sendMessage(Component.text("§cMessage has already been deleted."));
                    return true;
                }

                if (!deleteMessageIds(List.of(messageId))) {
                    player.sendMessage(Component.text("§cFailed to delete message."));
                    return true;
                }

                player.sendMessage(Component.text("§aMessage deleted successfully."));
                queueResend();
                String senderName = message.getSenderName() != null ? message.getSenderName() : "unknown";
                Text.sendDebugLog(INFO, "Staff member " + player.getName() + " deleted message " + messageId + " from " + senderName);
                return true;
            }

            int amount;
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("§cInvalid amount. Must be a number."));
                return true;
            }
            if (amount <= 0) {
                player.sendMessage(Component.text("§cAmount must be at least 1."));
                return true;
            }

            String targetName = args[0];
            UUID targetId = resolvePlayerUuid(targetName);
            List<ChatMessageManager.ChatMessage> matches = chatMessageManager.getRecentActiveMessagesBySender(targetName, targetId, amount);
            if (matches.isEmpty()) {
                player.sendMessage(Component.text("§cNo recent messages found for " + targetName + "."));
                return true;
            }

            matches.sort(Comparator.comparingLong(ChatMessageManager.ChatMessage::getTimestamp).reversed());
            List<Long> ids = matches.stream()
                    .map(ChatMessageManager.ChatMessage::getMessageId)
                    .toList();
            int deleted = deleteMessageIds(ids) ? ids.size() : 0;
            if (deleted == 0) {
                player.sendMessage(Component.text("§cFailed to delete messages."));
                return true;
            }

            player.sendMessage(Component.text("§aDeleted " + deleted + " recent message(s) from " + targetName + "."));
            queueResend();
            Text.sendDebugLog(INFO, "Staff member " + player.getName() + " deleted " + deleted
                    + " recent message(s) from " + targetName);

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

    private boolean deleteMessageIds(List<Long> messageIds) {
        boolean any = false;
        for (Long messageId : messageIds) {
            if (messageId == null) {
                continue;
            }
            if (chatMessageManager.deleteMessage(messageId)) {
                any = true;
                if (plugin.getDiscordSrvMessageBridge() != null) {
                    plugin.getDiscordSrvMessageBridge().requestDiscordDelete(messageId);
                }
            }
        }
        return any;
    }

    private void queueResend() {
        if (!resendInProgress.compareAndSet(false, true)) {
            return;
        }
        SchedulerAdapter.runLater(() -> {
            try {
                if (plugin.getChatPacketTracker().supportsResend()) {
                    plugin.getChatPacketTracker().resendChatHistoryToAllPlayers();
                } else {
                    plugin.retryChatPacketTrackerInitIfNeeded();
                    if (plugin.getChatPacketTracker().supportsResend()) {
                        plugin.getChatPacketTracker().resendChatHistoryToAllPlayers();
                    } else {
                        Text.sendDebugLog(WARN, "/delmsg: PacketEvents unavailable - using clear-only fallback.");
                        clearChatFallback();
                    }
                }
            } finally {
                // Delay release so successive /delmsg commands within 1 tick
                // don't schedule overlapping resends while PacketEvents is still
                // processing the outgoing packets from the current resend.
                SchedulerAdapter.runLater(() -> resendInProgress.set(false), 1L);
            }
        }, 1L);
    }

    private UUID resolvePlayerUuid(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayerIfCached(name);
        return offline != null ? offline.getUniqueId() : null;
    }

    /**
     * Fallback when PacketEvents is unavailable: clear chat only.
     */
    private void clearChatFallback() {
        try {
            int configured = Math.max(0, plugin.getConfig().getInt("chat.deletion_resend.clear_lines", 500));
            int clearLines = Math.min(configured, 120);
            int batchSize = 20;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p == null || !p.isOnline()) {
                    continue;
                }
                for (int batch = 0; batch < clearLines; batch += batchSize) {
                    int end = Math.min(batch + batchSize, clearLines);
                    StringBuilder sb = new StringBuilder();
                    for (int i = batch; i < end; i++) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(CLEAR_CHARS[i % CLEAR_CHARS.length]);
                    }
                    p.sendMessage(Component.text(sb.toString()));
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error clearing chat in fallback path: " + e.getMessage());
        }
    }
}
