package net.survivalfun.core.listeners.chat;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.chat.ChatMessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/**
 * PacketEvents-based chat message tracker that captures ALL chat messages
 * including system messages, plugin messages, and player messages at the packet level
 */
public class PacketChatTracker extends PacketListenerAbstract {
    
    private final PluginStart plugin;
    private final ChatMessageManager chatMessageManager;
    private final Set<UUID> playersBeingResent = new HashSet<>();
    
    public PacketChatTracker(PluginStart plugin, ChatMessageManager chatMessageManager) {
        this(plugin, chatMessageManager, PacketListenerPriority.NORMAL);
    }
    
    public PacketChatTracker(PluginStart plugin, ChatMessageManager chatMessageManager, PacketListenerPriority priority) {
        super(priority);
        this.plugin = plugin;
        this.chatMessageManager = chatMessageManager;
    }
    
    @Override
    public void onPacketSend(PacketSendEvent event) {
        // Handle different types of chat packets
        if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            handleSystemChatMessage(event);
        } else if (event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE) {
            handlePlayerChatMessage(event);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Not used currently
    }
    
    /**
     * Handles system chat messages (server messages, plugin messages, etc.)
     */
    private void handleSystemChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerSystemChatMessage systemChatPacket = new WrapperPlayServerSystemChatMessage(event);
            
            // Get the message component
            Component messageComponent = systemChatPacket.getMessage();
            if (messageComponent == null) {
                return;
            }
            
            // Get the receiving player
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            
            // Skip tracking if this player is currently being resent (to prevent infinite loop)
            if (playersBeingResent.contains(player.getUniqueId())) {
                return;
            }
            
            // Store the system message with a special sender (system)
            UUID systemUUID = new UUID(0, 0); // Special UUID for system messages
            long messageId = chatMessageManager.storeMessage(systemUUID, "SYSTEM", messageComponent);
            
            // Create ChatMessage object for per-player tracking
            ChatMessageManager.ChatMessage chatMessage = new ChatMessageManager.ChatMessage(
                messageId, systemUUID, "SYSTEM", messageComponent
            );
            
            // Track this message for the receiving player
            chatMessageManager.trackMessageForPlayer(player.getUniqueId(), chatMessage);
            
            // Apply delete functionality for staff members
            Component enhancedMessage = applyDeleteFunctionality(messageComponent, systemUUID, "SYSTEM", messageId);
            
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            Text.sendDebugLog(WARN, "Error tracking system chat message: " + msg);
            if (debug) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                Text.sendDebugLog(WARN, sw.toString());
            }
        }
    }
    
    /**
     * Handles player chat messages
     * Note: Player chat messages are already properly tracked by FormatChatListener,
     * so we skip tracking them here to prevent duplicates
     */
    private void handlePlayerChatMessage(PacketSendEvent event) {
        try {
            // Skip tracking player chat messages since FormatChatListener already handles them
            // This prevents duplicate messages in chat history (one formatted, one raw)
            
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("debug-mode", false)) {
                Text.sendDebugLog(WARN, "Error in player chat message handler: " + e.getMessage());
            }
        }
    }
    
    /**
     * Applies delete functionality to chat messages for staff members
     * This enhances messages with hover events containing delete options
     */
    private Component applyDeleteFunctionality(Component originalMessage, UUID senderUUID, String senderName, long messageId) {
        // Check if any online players have staff permission
        boolean hasStaffOnline = false;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("allium.deletemsg")) {
                hasStaffOnline = true;
                break;
            }
        }
        
        if (!hasStaffOnline) {
            return originalMessage; // No staff online, return original message
        }
        
        // For system messages and plugin messages, we'll add delete functionality to the entire message
        // For player messages, the delete functionality is already handled by FormatChatListener
        
        // Check if this is a system message or plugin message (not handled by FormatChatListener)
        if (senderUUID == null || senderUUID.equals(new UUID(0, 0)) || senderName.equals("SYSTEM")) {
            // Add message ID in the format '&7ID: &f<id>'
            return originalMessage.append(Component.space())
                .append(Component.text("Message ID: ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                .append(Component.text(messageId, net.kyori.adventure.text.format.NamedTextColor.WHITE));
        }
        
        return originalMessage; // Return original for player messages (handled by FormatChatListener)
    }
    
    /**
     * Resend chat history to a player with deleted messages removed
     * This uses PacketEvents to send fresh chat packets without deleted messages
     */
    public void resendChatHistoryToPlayer(Player player) {
        try {
            // Mark this player as being resent to prevent infinite loop
            playersBeingResent.add(player.getUniqueId());
            
            // Clear the player's chat by sending lines with varying Unicode spaces
            int clearLines = Math.max(0, plugin.getConfig().getInt("clear_lines", 400));
            String[] unicodeSpaces = {" ", " ", " ", " ", " ", " ", " ", " ", " ", " ", " ", " "};
            for (int i = 0; i < clearLines; i++) {
                String spaceChar = unicodeSpaces[i % unicodeSpaces.length];
                player.sendMessage(Component.text(spaceChar));
            }
            
            // Optional header to indicate a re-sync occurred
            if (plugin.getConfig().getBoolean("chat.deletion_resend.header_enabled", true)) {
                String header = plugin.getConfig().getString("chat.deletion_resend.header", "Chat re-synced by staff; a message was deleted");
                player.sendMessage(header);
            }
            
            // Get the player's chat history (excluding deleted messages)
            List<ChatMessageManager.ChatMessage> chatHistory = chatMessageManager.getPlayerChatHistory(player.getUniqueId());
            
            // Limit to recent messages to avoid spam
            int maxMessages = 50;
            int startIndex = Math.max(0, chatHistory.size() - maxMessages);
            
            // Resend each message
            int resentCount = 0;
            for (int i = startIndex; i < chatHistory.size(); i++) {
                ChatMessageManager.ChatMessage message = chatHistory.get(i);
                if (!message.isDeleted()) {
                    // Start from the stored original message
                    Component toSend = message.getOriginalMessage();

                    // Determine permissions and message type
                    boolean canDelete = player.hasPermission("allium.staff")
                            || player.hasPermission("allium.deletemsg")
                            || player.hasPermission("allium.delmsg");
                    boolean isPlayerMessage = message.getSenderId() != null && !message.getSenderId().equals(new java.util.UUID(0, 0));

                    if (canDelete && isPlayerMessage) {
                        // Respect chat-hover.delete-hover-mode (prefix | name | suffix | none)
                        String mode = plugin.getConfig().getString("chat-hover.delete-hover-mode", "prefix").toLowerCase();
                        if (!"none".equals(mode)) {
                            toSend = applyStaffAugmentByMode(toSend, mode, message.getSenderName(), message.getMessageId());
                        }
                    }

                    player.sendMessage(toSend);
                    resentCount++;
                }
            }

        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to resend chat history to " + player.getName() + ": " + e.getMessage());
        } finally {
            // Always remove the player from the resend set to prevent permanent blocking
            playersBeingResent.remove(player.getUniqueId());
        }
    }

    // Augment only the configured segment with staff delete hover/click
    private Component applyStaffAugmentByMode(Component message, String mode, String senderName, long messageId) {
        java.util.function.Predicate<Component> matcher;
        switch (mode) {
            case "name":
            case "player":
                matcher = comp -> plain(comp).equals(senderName);
                break;
            case "suffix":
                // Match the last interactive component (has hover/click)
                return augmentLastInteractive(message, messageId);
            case "prefix":
            default:
                // Prefer first interactive; if none augmented, fall back to player name
                Component augmented = augmentFirstInteractive(message, messageId);
                if (augmented == message && !"none".equals(mode)) {
                    return augmentFirstMatch(message, comp -> plain(comp).equals(senderName), messageId);
                }
                return augmented;
        }
        return augmentFirstMatch(message, matcher, messageId);
    }

    private Component augmentFirstInteractive(Component root, long messageId) {
        // Prefer first interactive; if none, fall back to first non-empty text leaf
        java.util.List<Component> flat = new java.util.ArrayList<>();
        flatten(root, flat);
        Component target = null;
        for (Component c : flat) {
            if (hasInteractive(c)) { target = c; break; }
        }
        if (target == null) {
            for (Component c : flat) {
                if (!plain(c).isBlank()) { target = c; break; }
            }
        }
        if (target == null) return root;
        Component updated = addStaffHoverAndClick(target, messageId);
        return replaceExact(root, target, updated);
    }

    private Component augmentLastInteractive(Component root, long messageId) {
        // Prefer last interactive; if none, fall back to last non-empty text leaf
        java.util.List<Component> flat = new java.util.ArrayList<>();
        flatten(root, flat);
        Component target = null;
        for (int i = flat.size() - 1; i >= 0; i--) {
            if (hasInteractive(flat.get(i))) { target = flat.get(i); break; }
        }
        if (target == null) {
            for (int i = flat.size() - 1; i >= 0; i--) {
                if (!plain(flat.get(i)).isBlank()) { target = flat.get(i); break; }
            }
        }
        if (target == null) return root;
        Component updated = addStaffHoverAndClick(target, messageId);
        return replaceExact(root, target, updated);
    }

    private Component augmentFirstMatch(Component root, java.util.function.Predicate<Component> match, long messageId) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        return transform(root, c -> {
            if (!done.get() && match.test(c)) {
                done.set(true);
                return addStaffHoverAndClick(c, messageId);
            }
            return c;
        });
    }

    // Utility: depth-first transform
    private Component transform(Component comp, java.util.function.Function<Component, Component> fn) {
        Component mapped = fn.apply(comp);
        java.util.List<Component> children = mapped.children();
        if (children.isEmpty()) return mapped;
        java.util.List<Component> newChildren = new java.util.ArrayList<>(children.size());
        for (Component child : children) {
            newChildren.add(transform(child, fn));
        }
        return mapped.children(newChildren);
    }

    // Utility: flatten
    private void flatten(Component comp, java.util.List<Component> out) {
        out.add(comp);
        for (Component c : comp.children()) flatten(c, out);
    }

    // Utility: replace exact instance in tree
    private Component replaceExact(Component root, Component target, Component replacement) {
        if (root == target) {
            return replacement;
        }
        java.util.List<Component> children = root.children();
        if (children.isEmpty()) return root;
        java.util.List<Component> newChildren = new java.util.ArrayList<>(children.size());
        boolean any = false;
        for (Component c : children) {
            Component replaced = replaceExact(c, target, replacement);
            if (replaced != c) any = true;
            newChildren.add(replaced);
        }
        return any ? root.children(newChildren) : root;
    }

    private boolean hasInteractive(Component c) {
        return c.hoverEvent() != null || c.clickEvent() != null;
    }

    private String plain(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private Component addStaffHoverAndClick(Component comp, long messageId) {
        // Avoid duplicate line
        net.kyori.adventure.text.event.HoverEvent<?> hv = comp.hoverEvent();
        Component hvText = null;
        if (hv != null && hv.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT) {
            Object value = hv.value();
            if (value instanceof Component) hvText = (Component) value;
        }
        if (hvText == null) hvText = Component.empty();
        String existing = plain(hvText);
        if (!existing.contains("Click to delete this message")) {
            Component staffLine = Component.text("Click to delete this message ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(Component.text("[", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(messageId), net.kyori.adventure.text.format.NamedTextColor.WHITE))
                .append(Component.text("]", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
            hvText = hvText.append(Component.newline()).append(staffLine);
        }
        return comp.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hvText))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/delmsg " + messageId));
    }

    /**
     * Resend chat history to all online players with deleted messages removed
     */
    public void resendChatHistoryToAllPlayers() {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            resendChatHistoryToPlayer(onlinePlayer);
        }
    }
}
