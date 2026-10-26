package codes.castled.allium.managers.chat;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.managers.core.Text;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Manages chat messages for staff moderation features
 */
public class ChatMessageManager {

    private static final int MAX_MESSAGES_PER_PLAYER = 100; // Limit to prevent memory issues
    private static final int MAX_GLOBAL_MESSAGES = 200; // Global chat history limit

    /** Sender id used for packet-captured copies (see PacketChatTrackerImpl). */
    private static final UUID SYSTEM_SENDER_ID = new UUID(0, 0);

    // Thread-safe storage for chat messages
    private final Map<UUID, Deque<ChatMessage>> playerMessages =
        new ConcurrentHashMap<>();
    // Old hover commands remain in clients after a plugin reload. Start each
    // runtime in a new range so those commands cannot delete a newer line.
    private static final AtomicLong MESSAGE_IDS = new AtomicLong(System.currentTimeMillis() * 1000L);
    private final AtomicLong messageIdCounter = MESSAGE_IDS;
    private final String markerPrefix = "allium:chat:" + UUID.randomUUID() + ":";

    // Per-player chat history for packet-based deletion
    private final Map<UUID, Deque<ChatMessage>> playerChatHistory =
        new ConcurrentHashMap<>();

    /**
     * Plain text -> id of the logical message that text belongs to, so the per-viewer
     * copies the packet tracker captures can be given the same id as the message that
     * produced them. Deletion then works by id alone instead of relying on the
     * content+timestamp heuristic, which silently misses copies whose timestamps have
     * drifted apart.
     */
    private final Map<String, LogicalMessageRef> logicalIdsByPlainText =
        new ConcurrentHashMap<>();

    /** How long a rendered message stays claimable by its per-viewer packet copies. */
    private static final long LOGICAL_LINK_TTL_MS = 10_000;

    private record LogicalMessageRef(ChatMessage message, long registeredAt) {}

    // Global chat history (ordered by timestamp)
    private final Deque<ChatMessage> globalChatHistory =
        new ConcurrentLinkedDeque<>();

    /**
     * Represents a chat message that can be deleted by staff
     */
    public static class ChatMessage {

        private final long messageId;
        private final UUID senderId;
        private final String senderName;
        private final Component originalMessage;
        private final long timestamp;
        private final AtomicBoolean deleted;

        public ChatMessage(
            long messageId,
            UUID senderId,
            String senderName,
            Component originalMessage
        ) {
            this(messageId, senderId, senderName, originalMessage, new AtomicBoolean());
        }

        private ChatMessage(long messageId, UUID senderId, String senderName,
                            Component originalMessage, AtomicBoolean deleted) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.originalMessage = originalMessage;
            this.timestamp = System.currentTimeMillis();
            this.deleted = deleted;
        }

        public long getMessageId() {
            return messageId;
        }

        public UUID getSenderId() {
            return senderId;
        }

        public String getSenderName() {
            return senderName;
        }

        public Component getOriginalMessage() {
            return originalMessage;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isDeleted() {
            return deleted.get();
        }

        public void setDeleted(boolean deleted) {
            this.deleted.set(deleted);
        }
    }

    /**
     * Store a chat message for potential deletion by staff
     */
    public long storeMessage(Player sender, Component message) {
        return storeMessage(sender.getUniqueId(), sender.getName(), message);
    }

    /**
     * Stores a chat message for potential deletion by staff (PacketEvents version)
     * @param senderId The UUID of the sender (can be system UUID for system messages)
     * @param senderName The name of the sender
     * @param message The formatted message component
     * @return The unique message ID for deletion reference
     */
    public long storeMessage(
        UUID senderId,
        String senderName,
        Component message
    ) {
        long messageId = messageIdCounter.incrementAndGet();

        ChatMessage chatMessage = new ChatMessage(
            messageId,
            senderId,
            senderName,
            message
        );

        // Get or create the sender's message list
        Deque<ChatMessage> messages = playerMessages.computeIfAbsent(
            senderId,
            k -> new ConcurrentLinkedDeque<>()
        );

        messages.addLast(chatMessage);

        // Limit the number of stored messages per sender to prevent memory issues
        while (
            messages.size() > MAX_MESSAGES_PER_PLAYER &&
            messages.pollFirst() != null
        ) {
            // Trim oldest entries until within limit
        }

        return messageId;
    }

    /**
     * Stores a chat message and returns the actual stored instance so callers
     * share the same reference across collections (playerMessages + playerChatHistory).
     * This avoids the bug where a separate ChatMessage object for the same logical
     * message gets a different {@code deleted} flag.
     */
    public ChatMessage storeMessageObject(
        UUID senderId,
        String senderName,
        Component message
    ) {
        // If this is a per-viewer copy of a message we already stored, reuse that
        // message's id so a single /delmsg removes every copy of it.
        ChatMessage logical = findLogicalMessage(message);
        long messageId = logical == null ? messageIdCounter.incrementAndGet() : logical.getMessageId();

        ChatMessage chatMessage = new ChatMessage(
            messageId,
            senderId,
            senderName,
            message,
            logical == null ? new AtomicBoolean() : logical.deleted
        );

        Deque<ChatMessage> messages = playerMessages.computeIfAbsent(
            senderId,
            k -> new ConcurrentLinkedDeque<>()
        );

        messages.addLast(chatMessage);

        while (
            messages.size() > MAX_MESSAGES_PER_PLAYER &&
            messages.pollFirst() != null
        ) {
            // Trim oldest entries until within limit
        }

        return chatMessage;
    }

    /**
     * Announces that {@code rendered} was just sent out under {@code messageId}, so the
     * per-viewer copies the packet tracker is about to capture adopt the same id.
     */
    public void registerLogicalMessage(long messageId, Component rendered) {
        if (rendered == null) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText()
            .serialize(rendered)
            .trim();
        if (plain.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        logicalIdsByPlainText.values()
            .removeIf(ref -> now - ref.registeredAt() > LOGICAL_LINK_TTL_MS);
        ChatMessage logical = getMessage(messageId);
        if (logical != null) logicalIdsByPlainText.put(plain, new LogicalMessageRef(logical, now));
    }

    /**
     * Invisible, non-interactive identity metadata on an empty translation. It never
     * changes the text, hover, click action, or shift-click behavior of visible
     * components. IDs survive glyph/formatting changes and identical chat lines.
     */
    public Component withMessageId(long messageId, Component message) {
        return message.append(marker(markerPrefix + "id:" + messageId));
    }

    /** Replay identity replaces the old one-tick global tracking suppression. */
    public Component asReplay(Component message) {
        return message.append(marker(markerPrefix + "replay"));
    }

    private Component marker(String identity) {
        // An empty text node is removed by Adventure/InteractiveChat compaction.
        // An empty translation with an empty fallback has no visible output,
        // but remains intact through those optimizers and JSON serialization.
        return Component.translatable("").fallback("").insertion(identity);
    }

    public boolean isReplay(Component message) {
        return findMarker(message, markerPrefix + "replay") != null;
    }

    public boolean isDeletedMessage(Component message) {
        // Packet cancellation needs an explicit identity. A new unrelated line
        // with coincidentally identical text must never be blocked.
        return isDeletedIdentity(findMarker(message, markerPrefix + "id:"));
    }

    /** Also usable by bridges whose component classes are relocated/shaded. */
    public boolean isDeletedIdentity(String identity) {
        String prefix = markerPrefix + "id:";
        if (identity == null || !identity.startsWith(prefix)) return false;
        try {
            ChatMessage logical = getMessage(Long.parseLong(identity.substring(prefix.length())));
            return logical != null && logical.isDeleted();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String findMarker(Component message, String prefix) {
        if (message == null) return null;
        String insertion = message.insertion();
        if (insertion != null && insertion.startsWith(prefix)) return insertion;
        for (Component child : message.children()) {
            String found = findMarker(child, prefix);
            if (found != null) return found;
        }
        return null;
    }

    private ChatMessage findLogicalMessage(Component message) {
        if (message == null) return null;
        String prefix = markerPrefix + "id:";
        String marker = findMarker(message, prefix);
        if (marker != null) {
            try {
                ChatMessage logical = getMessage(Long.parseLong(marker.substring(prefix.length())));
                if (logical != null) return logical;
            } catch (NumberFormatException ignored) { }
        }
        if (logicalIdsByPlainText.isEmpty()) return null;
        String plain = PlainTextComponentSerializer.plainText()
            .serialize(message)
            .trim();
        if (plain.isEmpty()) {
            return null;
        }

        LogicalMessageRef ref = logicalIdsByPlainText.get(plain);
        if (ref == null) {
            return null;
        }
        if (System.currentTimeMillis() - ref.registeredAt() > LOGICAL_LINK_TTL_MS) {
            logicalIdsByPlainText.remove(plain, ref);
            return null;
        }
        return ref.message();
    }

    /**
     * Mark a message as deleted by message ID in all collections.
     * Per-viewer packet copies adopt the id of the message they were rendered from
     * (see {@link #storeMessageObject}), so id matching removes them too without
     * deleting a different message just because its text happens to overlap.
     */
    public boolean deleteMessage(long messageId) {
        ChatMessage target = getMessage(messageId);
        if (target == null) return false;

        // Set the shared flag before sweeping collections. A packet capture
        // created concurrently but inserted later already shares this state.
        target.setDeleted(true);
        boolean found = false;

        found |= markMatching(
            playerMessages.values(),
            messageId
        );
        // Per-player chat histories and the global history (both used for resend) hold the
        // same ChatMessage instances, but sweep them anyway so nothing is missed if a copy
        // was trimmed out of playerMessages already.
        found |= markMatching(
            playerChatHistory.values(),
            messageId
        );
        found |= markMatching(
            List.of(globalChatHistory),
            messageId
        );

        return found;
    }

    private boolean markMatching(
        Collection<? extends Collection<ChatMessage>> collections,
        long messageId
    ) {
        boolean found = false;
        for (Collection<ChatMessage> messages : collections) {
            for (ChatMessage message : messages) {
                if (
                    message.getMessageId() == messageId
                ) {
                    message.setDeleted(true);
                    found = true;
                }
            }
        }
        return found;
    }

    /**
     * Get all non-deleted messages for a specific player (for chat reconstruction)
     */
    public List<ChatMessage> getActiveMessages(UUID playerId) {
        Deque<ChatMessage> messages = playerMessages.get(playerId);
        if (messages == null) {
            return new ArrayList<>();
        }

        return messages
            .stream()
            .filter(msg -> !msg.isDeleted())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * Get all non-deleted messages from all players (for global chat reconstruction)
     */
    public List<ChatMessage> getAllActiveMessages() {
        List<ChatMessage> allMessages = new ArrayList<>();

        for (Deque<ChatMessage> messages : playerMessages.values()) {
            allMessages.addAll(
                messages
                    .stream()
                    .filter(msg -> !msg.isDeleted())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll)
            );
        }

        // Sort by timestamp
        allMessages.sort(Comparator.comparingLong(ChatMessage::getTimestamp));

        return allMessages;
    }

    /**
     * Clear old messages (cleanup method)
     */
    public void cleanupOldMessages(long maxAgeMillis) {
        long cutoffTime = System.currentTimeMillis() - maxAgeMillis;

        for (Deque<ChatMessage> messages : playerMessages.values()) {
            messages.removeIf(msg -> msg.getTimestamp() < cutoffTime);
        }

        // Remove empty lists
        playerMessages.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Track a message for a specific player (for packet-based deletion)
     */
    public void trackMessageForPlayer(UUID playerId, ChatMessage message) {
        Deque<ChatMessage> playerHistory = playerChatHistory.computeIfAbsent(
            playerId,
            k -> new ConcurrentLinkedDeque<>()
        );
        playerHistory.addLast(message);

        // Limit per-player history
        while (
            playerHistory.size() > MAX_MESSAGES_PER_PLAYER &&
            playerHistory.pollFirst() != null
        ) {
            // Trim oldest entries until within limit
        }

        // Add to global history
        globalChatHistory.addLast(message);
        while (
            globalChatHistory.size() > MAX_GLOBAL_MESSAGES &&
            globalChatHistory.pollFirst() != null
        ) {
            // Trim oldest entries until within limit
        }
    }

    /**
     * Get chat history for a specific player (excluding deleted messages)
     */
    public List<ChatMessage> getPlayerChatHistory(UUID playerId) {
        Deque<ChatMessage> history = playerChatHistory.get(playerId);
        if (history == null) {
            return new ArrayList<>();
        }

        return history
            .stream()
            .filter(msg -> !msg.isDeleted())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * Get recent global chat history (excluding deleted messages)
     */
    public List<ChatMessage> getRecentGlobalChatHistory(int maxMessages) {
        int toSkip = Math.max(0, globalChatHistory.size() - maxMessages);
        return globalChatHistory
            .stream()
            .filter(msg -> !msg.isDeleted())
            .skip(toSkip)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * Get a specific message by ID
     */
    public ChatMessage getMessage(long messageId) {
        ChatMessage fallback = null;
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (message.getMessageId() != messageId) {
                    continue;
                }
                // Per-viewer packet copies share the id of the message they came from;
                // prefer the original so the sender name and timestamp are the real ones.
                if (!SYSTEM_SENDER_ID.equals(message.getSenderId())) {
                    return message;
                }
                if (fallback == null) {
                    fallback = message;
                }
            }
        }
        // A packet's shared sender queue may have trimmed its entry while a
        // viewer still has it. Such messages must remain deletable by their ID.
        if (fallback == null) {
            for (Deque<ChatMessage> history : playerChatHistory.values()) {
                for (ChatMessage message : history) {
                    if (message.getMessageId() == messageId) return message;
                }
            }
        }
        return fallback;
    }

    /**
     * Returns the internal playerMessages map so the packet tracker can
     * look up the FormatChatListener-formatted version of a message.
     */
    public Map<UUID, Deque<ChatMessage>> getPlayerMessagesMap() {
        return playerMessages;
    }

    public List<ChatMessage> getRecentActiveMessagesBySender(
        String senderName,
        UUID senderId,
        int amount
    ) {
        if (amount <= 0) {
            return List.of();
        }

        List<ChatMessage> matches = new ArrayList<>();
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (message.isDeleted()) {
                    continue;
                }
                if (
                    senderId != null && senderId.equals(message.getSenderId())
                ) {
                    matches.add(message);
                    continue;
                }
                if (
                    senderName != null &&
                    message.getSenderName() != null &&
                    message.getSenderName().equalsIgnoreCase(senderName)
                ) {
                    matches.add(message);
                }
            }
        }

        matches.sort(
            Comparator.comparingLong(ChatMessage::getTimestamp)
                .thenComparingLong(ChatMessage::getMessageId).reversed()
        );
        if (matches.size() > amount) {
            return new ArrayList<>(matches.subList(0, amount));
        }
        return matches;
    }
}
