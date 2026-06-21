package codes.castled.allium.packetevents.impl;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.packetevents.ChatPacketTracker;
import codes.castled.allium.util.SchedulerAdapter;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * PacketEvents-based chat message tracker. Loaded only when PacketEvents plugin is available.
 */
public class PacketChatTrackerImpl
    extends PacketListenerAbstract
    implements ChatPacketTracker
{

    private static final UUID SYSTEM_UUID = new UUID(0, 0);
    private static final long RESEND_DUPLICATE_WINDOW_MS = 3000L;

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
        ' ', // Regular space
        '\u2000',
        '\u2001',
        '\u2002',
        '\u2003', // En quad, Em quad, En space, Em space
        '\u2004',
        '\u2005',
        '\u2006',
        '\u2007',
        '\u2008',
        '\u2009',
        '\u200A', // Various spaces
    };

    public PacketChatTrackerImpl(
        PluginStart plugin,
        ChatMessageManager chatMessageManager
    ) {
        this(plugin, chatMessageManager, PacketListenerPriority.NORMAL);
    }

    public PacketChatTrackerImpl(
        PluginStart plugin,
        ChatMessageManager chatMessageManager,
        PacketListenerPriority priority
    ) {
        super(priority);
        this.plugin = plugin;
        this.chatMessageManager = chatMessageManager;
        SchedulerAdapter.runLater(() -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            } catch (Throwable e) {
                Text.sendDebugLog(
                    WARN,
                    "Failed to register PacketEvents chat tracker: " +
                        e.getMessage()
                );
            }
        }, 20L);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (
            event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE
        ) {
            handleSystemChatMessage(event);
        } else if (
            event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE
        ) {
            handleChatMessage(event);
        } else if (
            event.getPacketType() == PacketType.Play.Server.DISGUISED_CHAT
        ) {
            handleDisguisedChatMessage(event);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Not used
    }

    private void handleChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerChatMessage chatPacket =
                new WrapperPlayServerChatMessage(event);
            com.github.retrooper.packetevents.protocol.chat.message.ChatMessage msg =
                chatPacket.getMessage();
            if (msg == null) return;
            Component messageComponent = msg.getChatContent();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(
                systemUUID,
                "SYSTEM",
                messageComponent
            );
            ChatMessageManager.ChatMessage chatMessage =
                new ChatMessageManager.ChatMessage(
                    messageId,
                    systemUUID,
                    "SYSTEM",
                    messageComponent
                );
            chatMessageManager.trackMessageForPlayer(
                player.getUniqueId(),
                chatMessage
            );
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg =
                e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getName();
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
            WrapperPlayServerDisguisedChat disguisedPacket =
                new WrapperPlayServerDisguisedChat(event);
            Component messageComponent = disguisedPacket.getMessage();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(
                systemUUID,
                "SYSTEM",
                messageComponent
            );
            ChatMessageManager.ChatMessage chatMessage =
                new ChatMessageManager.ChatMessage(
                    messageId,
                    systemUUID,
                    "SYSTEM",
                    messageComponent
                );
            chatMessageManager.trackMessageForPlayer(
                player.getUniqueId(),
                chatMessage
            );
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg =
                e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getName();
            Text.sendDebugLog(
                WARN,
                "Error tracking disguised chat message: " + msg
            );
            if (debug) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                Text.sendDebugLog(WARN, sw.toString());
            }
        }
    }

    private void handleSystemChatMessage(PacketSendEvent event) {
        try {
            WrapperPlayServerSystemChatMessage systemChatPacket =
                new WrapperPlayServerSystemChatMessage(event);
            Component messageComponent = systemChatPacket.getMessage();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            if (playersBeingResent.contains(player.getUniqueId())) return;

            // Skip overlay (action bar / title) messages — these are not chat.
            if (systemChatPacket.isOverlay()) return;

            UUID systemUUID = new UUID(0, 0);
            long messageId = chatMessageManager.storeMessage(
                systemUUID,
                "SYSTEM",
                messageComponent
            );
            ChatMessageManager.ChatMessage chatMessage =
                new ChatMessageManager.ChatMessage(
                    messageId,
                    systemUUID,
                    "SYSTEM",
                    messageComponent
                );
            chatMessageManager.trackMessageForPlayer(
                player.getUniqueId(),
                chatMessage
            );
        } catch (Exception e) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode", false);
            String msg =
                e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getName();
            Text.sendDebugLog(
                WARN,
                "Error tracking system chat message: " + msg
            );
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

            int configuredClearLines = Math.max(
                0,
                plugin
                    .getConfig()
                    .getInt("chat.deletion_resend.clear_lines", 500)
            );
            int clearLines = Math.min(configuredClearLines, 180);
            int poolSize = INVISIBLE_UNICODE.length;
            List<Integer> indices = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) indices.add(i);
            Collections.shuffle(indices, ThreadLocalRandom.current());
            for (int i = 0; i < clearLines; i++) {
                char c = INVISIBLE_UNICODE[indices.get(i % poolSize)];
                player.sendMessage(Component.text(String.valueOf(c)));
            }

            if (
                plugin
                    .getConfig()
                    .getBoolean("chat.deletion_resend.header_enabled", true)
            ) {
                String header = plugin
                    .getConfig()
                    .getString(
                        "chat.deletion_resend.header",
                        "Chat re-synced by staff; a message was deleted"
                    );
                player.sendMessage(Text.colorize(header));
            }

            // Build resend history from per-player chat history (packet-tracked).
            // For each entry, prefer the formatted message from playerMessages
            // (built by FormatChatListener) over the raw packet component, since
            // the formatted message has proper prefix/name coloring without
            // raw §x hex encoding artifacts.
            List<ChatMessageManager.ChatMessage> rawHistory =
                chatMessageManager.getPlayerChatHistory(player.getUniqueId());
            if (rawHistory.isEmpty()) {
                rawHistory = chatMessageManager.getRecentGlobalChatHistory(50);
            }

            // Build a lookup of messageId -> formatted ChatMessage from playerMessages
            // so we can substitute the properly-formatted component for each entry.
            java.util.Map<Long, ChatMessageManager.ChatMessage> formattedById =
                new java.util.HashMap<>();
            java.util.Map<
                String,
                java.util.List<ChatMessageManager.ChatMessage>
            > formattedBySender = new java.util.HashMap<>();
            for (java.util.Deque<
                ChatMessageManager.ChatMessage
            > q : chatMessageManager.getPlayerMessagesMap().values()) {
                for (ChatMessageManager.ChatMessage cm : q) {
                    if (cm.isDeleted()) continue;
                    formattedById.put(cm.getMessageId(), cm);
                    formattedBySender
                        .computeIfAbsent(
                            cm.getSenderName() != null
                                ? cm.getSenderName()
                                : "",
                            k -> new java.util.ArrayList<>()
                        )
                        .add(cm);
                }
            }

            List<ChatMessageManager.ChatMessage> chatHistory =
                dedupeResendHistory(rawHistory);
            int maxMessages = 50;
            int startIndex = Math.max(0, chatHistory.size() - maxMessages);

            for (int i = startIndex; i < chatHistory.size(); i++) {
                ChatMessageManager.ChatMessage message = chatHistory.get(i);
                if (message.isDeleted()) continue;

                // Prefer the formatted message if we have a matching entry.
                // Match by messageId first, then by sender+content proximity.
                Component toSend = message.getOriginalMessage();
                boolean isPlayerMessage =
                    message.getSenderId() != null &&
                    !message.getSenderId().equals(SYSTEM_UUID);

                if (isPlayerMessage) {
                    // Try to find the formatted version of this message
                    ChatMessageManager.ChatMessage formatted =
                        findFormattedMessage(
                            message,
                            formattedById,
                            formattedBySender
                        );
                    if (formatted != null) {
                        toSend = formatted.getOriginalMessage();
                    }
                }

                boolean canDelete =
                    player.hasPermission("allium.staff") ||
                    player.hasPermission("allium.deletemsg") ||
                    player.hasPermission("allium.delmsg");

                if (canDelete && isPlayerMessage) {
                    String mode = plugin
                        .getConfig()
                        .getString("chat-hover.delete-hover-mode", "prefix")
                        .toLowerCase();
                    if (!"none".equals(mode)) {
                        toSend = applyStaffAugmentByMode(
                            toSend,
                            mode,
                            message.getSenderName(),
                            message.getMessageId()
                        );
                    }
                }
                player.sendMessage(toSend);
            }
        } catch (Exception e) {
            Text.sendDebugLog(
                WARN,
                "Failed to resend chat history to " +
                    player.getName() +
                    ": " +
                    e.getMessage()
            );
        } finally {
            playersBeingResent.remove(player.getUniqueId());
        }
    }

    private List<ChatMessageManager.ChatMessage> dedupeResendHistory(
        List<ChatMessageManager.ChatMessage> history
    ) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<ChatMessageManager.ChatMessage> deduped = new ArrayList<>(
            history.size()
        );
        for (ChatMessageManager.ChatMessage candidate : history) {
            if (candidate == null || candidate.isDeleted()) {
                continue;
            }

            if (deduped.isEmpty()) {
                deduped.add(candidate);
                continue;
            }

            ChatMessageManager.ChatMessage previous = deduped.get(
                deduped.size() - 1
            );
            if (isDuplicateForResend(previous, candidate)) {
                if (preferSecond(previous, candidate)) {
                    deduped.set(deduped.size() - 1, candidate);
                }
            } else {
                deduped.add(candidate);
            }
        }

        return deduped;
    }

    private boolean isDuplicateForResend(
        ChatMessageManager.ChatMessage first,
        ChatMessageManager.ChatMessage second
    ) {
        long diff = Math.abs(first.getTimestamp() - second.getTimestamp());
        if (diff > RESEND_DUPLICATE_WINDOW_MS) {
            return false;
        }

        String firstPlain = plain(first.getOriginalMessage()).trim();
        String secondPlain = plain(second.getOriginalMessage()).trim();
        if (firstPlain.isEmpty() || secondPlain.isEmpty()) {
            return false;
        }

        return (
            firstPlain.equals(secondPlain) ||
            firstPlain.contains(secondPlain) ||
            secondPlain.contains(firstPlain)
        );
    }

    private boolean preferSecond(
        ChatMessageManager.ChatMessage first,
        ChatMessageManager.ChatMessage second
    ) {
        boolean firstSystem = SYSTEM_UUID.equals(first.getSenderId());
        boolean secondSystem = SYSTEM_UUID.equals(second.getSenderId());
        if (firstSystem != secondSystem) {
            return !secondSystem;
        }
        return second.getTimestamp() >= first.getTimestamp();
    }

    /**
     * Try to find the FormatChatListener-formatted version of a packet-tracked
     * message. Matches by messageId first, then falls back to sender-name +
     * content proximity (same plain text within a short time window).
     */
    private ChatMessageManager.ChatMessage findFormattedMessage(
        ChatMessageManager.ChatMessage raw,
        java.util.Map<Long, ChatMessageManager.ChatMessage> byId,
        java.util.Map<
            String,
            java.util.List<ChatMessageManager.ChatMessage>
        > bySender
    ) {
        // Direct ID match
        if (raw.getMessageId() > 0) {
            ChatMessageManager.ChatMessage exact = byId.get(raw.getMessageId());
            if (exact != null) return exact;
        }

        // Match by sender name + content proximity
        String senderName = raw.getSenderName();
        if (senderName == null) return null;
        java.util.List<ChatMessageManager.ChatMessage> candidates =
            bySender.get(senderName);
        if (candidates == null || candidates.isEmpty()) return null;

        String rawPlain = plain(raw.getOriginalMessage()).trim();
        long rawTime = raw.getTimestamp();

        ChatMessageManager.ChatMessage best = null;
        long bestDiff = Long.MAX_VALUE;
        for (ChatMessageManager.ChatMessage candidate : candidates) {
            String candPlain = plain(candidate.getOriginalMessage()).trim();
            // Content must match (one contains the other)
            if (!rawPlain.isEmpty() && !candPlain.isEmpty()) {
                if (
                    !rawPlain.contains(candPlain) &&
                    !candPlain.contains(rawPlain)
                ) {
                    continue;
                }
            }
            long diff = Math.abs(candidate.getTimestamp() - rawTime);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        // Only accept if within 3 seconds
        return (bestDiff <= 3000L) ? best : null;
    }

    private Component applyStaffAugmentByMode(
        Component message,
        String mode,
        String senderName,
        long messageId
    ) {
        switch (mode) {
            case "name":
            case "player":
                // Use contains-match instead of exact-match so gradient/MiniMessage
                // display names (which may be split into per-character components) are
                // still matched against the raw sender name.
                java.util.function.Predicate<Component> nameMatcher = comp -> {
                    String compPlain = plain(comp).trim();
                    if (compPlain.isEmpty()) return false;
                    // Exact match
                    if (compPlain.equals(senderName)) return true;
                    // The component's plain text contains the sender name (handles
                    // gradient names where the full name spans multiple sibling
                    // components — we match the first component that contains the
                    // name text).
                    if (compPlain.contains(senderName)) return true;
                    // The sender name contains the component text (handles per-character
                    // gradient components — match the first non-blank char component).
                    return (
                        senderName.contains(compPlain) && !compPlain.isBlank()
                    );
                };
                return augmentFirstMatch(message, nameMatcher, messageId);
            case "suffix":
                return augmentLastInteractive(message, messageId);
            case "prefix":
            default:
                Component augmented = augmentFirstInteractive(
                    message,
                    messageId
                );
                if (augmented == message && !"none".equals(mode)) {
                    // Fall back to name-based matching when no interactive component
                    // was found. Use the same lenient contains-match for gradient names.
                    java.util.function.Predicate<Component> fallbackMatcher =
                        comp -> {
                            String compPlain = plain(comp).trim();
                            if (compPlain.isEmpty()) return false;
                            if (compPlain.equals(senderName)) return true;
                            if (compPlain.contains(senderName)) return true;
                            return (
                                senderName.contains(compPlain) &&
                                !compPlain.isBlank()
                            );
                        };
                    return augmentFirstMatch(
                        message,
                        fallbackMatcher,
                        messageId
                    );
                }
                return augmented;
        }
    }

    private Component augmentFirstInteractive(Component root, long messageId) {
        java.util.List<Component> flat = new java.util.ArrayList<>();
        flatten(root, flat);
        Component target = null;
        for (Component c : flat) {
            if (hasInteractive(c)) {
                target = c;
                break;
            }
        }
        if (target == null) {
            for (Component c : flat) {
                if (!plain(c).isBlank()) {
                    target = c;
                    break;
                }
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
            if (hasInteractive(flat.get(i))) {
                target = flat.get(i);
                break;
            }
        }
        if (target == null) {
            for (int i = flat.size() - 1; i >= 0; i--) {
                if (!plain(flat.get(i)).isBlank()) {
                    target = flat.get(i);
                    break;
                }
            }
        }
        if (target == null) return root;
        Component updated = addStaffHoverAndClick(target, messageId);
        return replaceExact(root, target, updated);
    }

    private Component augmentFirstMatch(
        Component root,
        java.util.function.Predicate<Component> match,
        long messageId
    ) {
        java.util.concurrent.atomic.AtomicBoolean done =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        return transform(root, c -> {
            if (!done.get() && match.test(c)) {
                done.set(true);
                return addStaffHoverAndClick(c, messageId);
            }
            return c;
        });
    }

    private Component transform(
        Component comp,
        java.util.function.Function<Component, Component> fn
    ) {
        Component mapped = fn.apply(comp);
        java.util.List<Component> children = mapped.children();
        if (children.isEmpty()) return mapped;
        java.util.List<Component> newChildren = new java.util.ArrayList<>(
            children.size()
        );
        for (Component child : children) {
            newChildren.add(transform(child, fn));
        }
        return mapped.children(newChildren);
    }

    private void flatten(Component comp, java.util.List<Component> out) {
        out.add(comp);
        for (Component c : comp.children()) flatten(c, out);
    }

    private Component replaceExact(
        Component root,
        Component target,
        Component replacement
    ) {
        if (root == target) return replacement;
        java.util.List<Component> children = root.children();
        if (children.isEmpty()) return root;
        java.util.List<Component> newChildren = new java.util.ArrayList<>(
            children.size()
        );
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
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
            c
        );
    }

    private Component addStaffHoverAndClick(Component comp, long messageId) {
        net.kyori.adventure.text.event.HoverEvent<?> hv = comp.hoverEvent();
        Component hvText = null;
        if (
            hv != null &&
            hv.action() ==
                net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT
        ) {
            Object value = hv.value();
            if (value instanceof Component) hvText = (Component) value;
        }
        if (hvText == null) hvText = Component.empty();
        String existing = plain(hvText);
        if (!existing.contains("Click to delete this message")) {
            Component staffLine = Component.text(
                "Click to delete this message ",
                net.kyori.adventure.text.format.NamedTextColor.RED
            )
                .append(
                    Component.text(
                        "[",
                        net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY
                    )
                )
                .append(
                    Component.text(
                        String.valueOf(messageId),
                        net.kyori.adventure.text.format.NamedTextColor.WHITE
                    )
                )
                .append(
                    Component.text(
                        "]",
                        net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY
                    )
                );
            hvText = hvText.append(Component.newline()).append(staffLine);
        }
        return comp
            .hoverEvent(
                net.kyori.adventure.text.event.HoverEvent.showText(hvText)
            )
            .clickEvent(
                net.kyori.adventure.text.event.ClickEvent.suggestCommand(
                    "/delmsg " + messageId
                )
            );
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
            Text.sendDebugLog(
                WARN,
                "Failed to unregister chat packet tracker: " + e.getMessage()
            );
        }
    }
}
