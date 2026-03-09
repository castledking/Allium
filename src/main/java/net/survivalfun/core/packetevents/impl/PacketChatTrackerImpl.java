package net.survivalfun.core.packetevents.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.chat.ChatMessageManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.packetevents.ChatPacketTracker;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * PacketEvents-based chat message tracker. Loaded only when PacketEvents plugin is available.
 */
public class PacketChatTrackerImpl extends PacketListenerAbstract implements ChatPacketTracker {

    private final PluginStart plugin;
    private final ChatMessageManager chatMessageManager;
    private final Set<UUID> playersBeingResent = new HashSet<>();
    /** Guard so clear+resend runs only once even if scheduler fires twice (e.g. Purpur with Folia APIs). */
    private final AtomicBoolean resendAllInProgress = new AtomicBoolean(false);

    /**
     * Invisible Unicode characters for clearing chat. Each resend uses a random permutation
     * of these (no repeat until all used) to make it harder for clients to detect or filter.
     */
    private static final char[] INVISIBLE_UNICODE = {
        ' ',                                    // Regular space
        '\u2000', '\u2001', '\u2002', '\u2003', // En quad, Em quad, En space, Em space
        '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A' // Various spaces
    };

    public PacketChatTrackerImpl(PluginStart plugin, ChatMessageManager chatMessageManager) {
        this(plugin, chatMessageManager, PacketListenerPriority.NORMAL);
    }

    public PacketChatTrackerImpl(PluginStart plugin, ChatMessageManager chatMessageManager, PacketListenerPriority priority) {
        super(priority);
        this.plugin = plugin;
        this.chatMessageManager = chatMessageManager;
        SchedulerAdapter.runLater(() -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            } catch (Throwable e) {
                Text.sendDebugLog(WARN, "Failed to register PacketEvents chat tracker: " + e.getMessage());
            }
        }, 20L);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            handleSystemChatMessage(event);
        } else if (event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE) {
            handleChatMessage(event);
        } else if (event.getPacketType() == PacketType.Play.Server.DISGUISED_CHAT) {
            handleDisguisedChatMessage(event);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Not used
    }

    private void handleChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerChatMessage chatPacket = new WrapperPlayServerChatMessage(event);
            com.github.retrooper.packetevents.protocol.chat.message.ChatMessage msg = chatPacket.getMessage();
            if (msg == null) return;
            Component messageComponent = msg.getChatContent();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(systemUUID, "SYSTEM", messageComponent);
            ChatMessageManager.ChatMessage chatMessage = new ChatMessageManager.ChatMessage(
                messageId, systemUUID, "SYSTEM", messageComponent
            );
            chatMessageManager.trackMessageForPlayer(player.getUniqueId(), chatMessage);
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            Text.sendDebugLog(WARN, "Error tracking chat message: " + msg);
            if (debug) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                Text.sendDebugLog(WARN, sw.toString());
            }
        }
    }

    private void handleDisguisedChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerDisguisedChat disguisedPacket = new WrapperPlayServerDisguisedChat(event);
            Component messageComponent = disguisedPacket.getMessage();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(systemUUID, "SYSTEM", messageComponent);
            ChatMessageManager.ChatMessage chatMessage = new ChatMessageManager.ChatMessage(
                messageId, systemUUID, "SYSTEM", messageComponent
            );
            chatMessageManager.trackMessageForPlayer(player.getUniqueId(), chatMessage);
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            Text.sendDebugLog(WARN, "Error tracking disguised chat message: " + msg);
            if (debug) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                Text.sendDebugLog(WARN, sw.toString());
            }
        }
    }

    private void handleSystemChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerSystemChatMessage systemChatPacket = new WrapperPlayServerSystemChatMessage(event);
            Component messageComponent = systemChatPacket.getMessage();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(systemUUID, "SYSTEM", messageComponent);
            ChatMessageManager.ChatMessage chatMessage = new ChatMessageManager.ChatMessage(
                messageId, systemUUID, "SYSTEM", messageComponent
            );
            chatMessageManager.trackMessageForPlayer(player.getUniqueId(), chatMessage);
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

    @Override
    public boolean supportsResend() {
        return true;
    }

    @Override
    public void resendChatHistoryToPlayer(Player player) {
        try {
            playersBeingResent.add(player.getUniqueId());

            int clearLines = Math.max(0, plugin.getConfig().getInt("chat.deletion_resend.clear_lines", 500));
            int poolSize = INVISIBLE_UNICODE.length;
            List<Integer> indices = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) indices.add(i);
            Collections.shuffle(indices, ThreadLocalRandom.current());
            for (int i = 0; i < clearLines; i++) {
                char c = INVISIBLE_UNICODE[indices.get(i % poolSize)];
                player.sendMessage(Component.text(String.valueOf(c)));
            }

            if (plugin.getConfig().getBoolean("chat.deletion_resend.header_enabled", true)) {
                String header = plugin.getConfig().getString("chat.deletion_resend.header", "Chat re-synced by staff; a message was deleted");
                player.sendMessage(Text.colorize(header));
            }

            List<ChatMessageManager.ChatMessage> chatHistory = chatMessageManager.getPlayerChatHistory(player.getUniqueId());
            if (chatHistory.isEmpty()) {
                chatHistory = chatMessageManager.getRecentGlobalChatHistory(50);
            }
            int maxMessages = 50;
            int startIndex = Math.max(0, chatHistory.size() - maxMessages);

            for (int i = startIndex; i < chatHistory.size(); i++) {
                ChatMessageManager.ChatMessage message = chatHistory.get(i);
                if (!message.isDeleted()) {
                    Component toSend = message.getOriginalMessage();
                    boolean canDelete = player.hasPermission("allium.staff")
                            || player.hasPermission("allium.deletemsg")
                            || player.hasPermission("allium.delmsg");
                    boolean isPlayerMessage = message.getSenderId() != null && !message.getSenderId().equals(new UUID(0, 0));

                    if (canDelete && isPlayerMessage) {
                        String mode = plugin.getConfig().getString("chat-hover.delete-hover-mode", "prefix").toLowerCase();
                        if (!"none".equals(mode)) {
                            toSend = applyStaffAugmentByMode(toSend, mode, message.getSenderName(), message.getMessageId());
                        }
                    }
                    player.sendMessage(toSend);
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to resend chat history to " + player.getName() + ": " + e.getMessage());
        } finally {
            playersBeingResent.remove(player.getUniqueId());
        }
    }

    private Component applyStaffAugmentByMode(Component message, String mode, String senderName, long messageId) {
        java.util.function.Predicate<Component> matcher;
        switch (mode) {
            case "name":
            case "player":
                matcher = comp -> plain(comp).equals(senderName);
                break;
            case "suffix":
                return augmentLastInteractive(message, messageId);
            case "prefix":
            default:
                Component augmented = augmentFirstInteractive(message, messageId);
                if (augmented == message && !"none".equals(mode)) {
                    return augmentFirstMatch(message, comp -> plain(comp).equals(senderName), messageId);
                }
                return augmented;
        }
        return augmentFirstMatch(message, matcher, messageId);
    }

    private Component augmentFirstInteractive(Component root, long messageId) {
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

    private void flatten(Component comp, java.util.List<Component> out) {
        out.add(comp);
        for (Component c : comp.children()) flatten(c, out);
    }

    private Component replaceExact(Component root, Component target, Component replacement) {
        if (root == target) return replacement;
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

    @Override
    public void resendChatHistoryToAllPlayers() {
        if (!resendAllInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                resendChatHistoryToPlayer(onlinePlayer);
            }
        } finally {
            resendAllInProgress.set(false);
        }
    }

    @Override
    public void shutdown() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to unregister chat packet tracker: " + e.getMessage());
        }
    }
}
