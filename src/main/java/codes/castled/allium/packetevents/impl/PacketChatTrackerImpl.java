package codes.castled.allium.packetevents.impl;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.managers.chat.ChatHistoryClear;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final PluginStart plugin;
    private final ChatMessageManager chatMessageManager;
    private final Set<UUID> playersBeingResent = ConcurrentHashMap.newKeySet();
    /** Guard so clear+resend runs only once even if scheduler fires twice (e.g. Purpur with Folia APIs). */
    private final AtomicBoolean resendAllInProgress = new AtomicBoolean(false);
    private final AtomicBoolean resendAllRequested = new AtomicBoolean(false);
    private final org.bukkit.event.Listener interactiveChatListener = new org.bukkit.event.Listener() {};

    private boolean shouldIgnoreTracking(Player player, Component message) {
        return player == null || chatMessageManager.isReplay(message)
            || ChatHistoryClear.isWhitespace(message);
    }

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
                registerInteractiveChatGuard();
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
        if (event.isCancelled()) return;
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
            if (chatMessageManager.isDeletedMessage(messageComponent)) { event.setCancelled(true); return; }
            if (shouldIgnoreTracking(player, messageComponent)) return;

            UUID systemUUID = new UUID(0, 0);
            ChatMessageManager.ChatMessage chatMessage =
                chatMessageManager.storeMessageObject(
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
            if (chatMessageManager.isDeletedMessage(messageComponent)) { event.setCancelled(true); return; }
            if (shouldIgnoreTracking(player, messageComponent)) return;

            UUID systemUUID = new UUID(0, 0);
            ChatMessageManager.ChatMessage chatMessage =
                chatMessageManager.storeMessageObject(
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
            // Action bars are not part of chat history or chat moderation.
            if (systemChatPacket.isOverlay()) return;
            Component messageComponent = systemChatPacket.getMessage();
            if (messageComponent == null) return;

            Player player = event.getPlayer();
            if (chatMessageManager.isDeletedMessage(messageComponent)) { event.setCancelled(true); return; }
            if (shouldIgnoreTracking(player, messageComponent)) return;

            UUID systemUUID = new UUID(0, 0);
            ChatMessageManager.ChatMessage chatMessage =
                chatMessageManager.storeMessageObject(
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
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        if (!playersBeingResent.add(playerId)) return;
        try {
            int clearEntries = plugin.getConfig().getInt("chat.deletion_resend.clear_lines", 500);
            ChatHistoryClear.send(clearEntries, entry -> sendClearEntry(player, entry));

            if (plugin.getConfig().getBoolean("chat.deletion_resend.header_enabled", true)) {
                String header = plugin.getConfig().getString("chat.deletion_resend.header",
                    "Chat re-synced by staff; a message was deleted");
                player.sendMessage(chatMessageManager.asReplay(Text.colorize(header)));
            }

            // Replay only what this viewer received. An empty history is not
            // permission to substitute the global (possibly private) history.
            var byId = new java.util.LinkedHashMap<Long, ChatMessageManager.ChatMessage>();
            for (var entry : chatMessageManager.getPlayerChatHistory(playerId)) {
                if (!entry.isDeleted()) byId.put(entry.getMessageId(), entry);
            }
            List<ChatMessageManager.ChatMessage> history = new ArrayList<>(byId.values());
            int start = Math.max(0, history.size() - 50);
            boolean canDelete = player.hasPermission("allium.staff")
                || player.hasPermission("allium.deletemsg") || player.hasPermission("allium.delmsg");
            for (int i = start; i < history.size(); i++) {
                var message = history.get(i);
                if (message.isDeleted()) continue;
                Component toSend = message.getOriginalMessage();
                var logical = chatMessageManager.getMessage(message.getMessageId());
                if (canDelete && logical != null && !SYSTEM_UUID.equals(logical.getSenderId())) {
                    String mode = plugin.getConfig().getString("chat-hover.delete-hover-mode", "prefix")
                        .toLowerCase(java.util.Locale.ROOT);
                    if (!"none".equals(mode)) {
                        toSend = applyStaffAugmentByMode(toSend, mode, logical.getSenderName(), message.getMessageId());
                    }
                }
                // IC can deliver this asynchronously, well after the main-thread
                // replay returns. The marker, not a timer, suppresses recapture.
                player.sendMessage(chatMessageManager.asReplay(toSend));
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to resend chat history to " + player.getName() + ": " + e.getMessage());
        } finally {
            playersBeingResent.remove(playerId);
        }
    }

    private void sendClearEntry(Player player, Component entry) {
        // Blank clearing packets need no glyph/item/chat formatting. Sending
        // directly also avoids queuing hundreds of IC formatting jobs and keeps
        // the clear ahead of the history on the player's connection.
        var api = PacketEvents.getAPI();
        if (api != null && api.getPlayerManager() != null) {
            api.getPlayerManager().sendPacketSilently(player, new WrapperPlayServerSystemChatMessage(false, entry));
        } else {
            player.sendMessage(chatMessageManager.asReplay(entry));
        }
    }

    @SuppressWarnings("unchecked")
    private void registerInteractiveChatGuard() {
        var manager = plugin.getServer().getPluginManager();
        var interactiveChat = manager.getPlugin("InteractiveChat");
        if (interactiveChat == null || !interactiveChat.isEnabled()) return;
        try {
            Class<? extends org.bukkit.event.Event> type = Class.forName(
                "com.loohp.interactivechat.api.events.PreChatPacketSendEvent", false,
                interactiveChat.getClass().getClassLoader()).asSubclass(org.bukkit.event.Event.class);
            var component = type.getMethod("getComponent");
            // InteractiveChat shades Adventure in its release JAR. Traverse
            // its public component interface instead of casting it to ours.
            var insertion = component.getReturnType().getMethod("insertion");
            var children = component.getReturnType().getMethod("children");
            var sendOriginal = type.getMethod("setSendOriginalIfCancelled", boolean.class);
            manager.registerEvent(type, interactiveChatListener, org.bukkit.event.EventPriority.HIGHEST,
                (listener, event) -> {
                    try {
                        Object rendered = component.invoke(event);
                        if (isDeletedExternalMessage(rendered, insertion, children)
                            && event instanceof org.bukkit.event.Cancellable cancellable) {
                            // Stop a message already queued in IC from arriving
                            // after deletion, including IC's original-packet fallback.
                            sendOriginal.invoke(event, false);
                            cancellable.setCancelled(true);
                        }
                    } catch (ReflectiveOperationException error) {
                        throw new org.bukkit.event.EventException(error);
                    }
                }, plugin, false);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("Could not register InteractiveChat's delayed-deletion guard: " + error.getMessage());
        }
    }

    private boolean isDeletedExternalMessage(Object node, java.lang.reflect.Method insertion,
                                             java.lang.reflect.Method children)
            throws ReflectiveOperationException {
        if (node == null) return false;
        if (chatMessageManager.isDeletedIdentity((String) insertion.invoke(node))) return true;
        for (Object child : (List<?>) children.invoke(node)) {
            if (isDeletedExternalMessage(child, insertion, children)) return true;
        }
        return false;
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
            resendAllRequested.set(true);
            return;
        }
        try {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                resendChatHistoryToPlayer(onlinePlayer);
            }
        } finally {
            // Coalesce another deletion that arrived while this pass was being
            // built; never silently drop the requested follow-up rebuild.
            SchedulerAdapter.runLater(() -> {
                resendAllInProgress.set(false);
                if (resendAllRequested.getAndSet(false)) resendChatHistoryToAllPlayers();
            }, 1L);
        }
    }

    @Override
    public void shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(interactiveChatListener);
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
