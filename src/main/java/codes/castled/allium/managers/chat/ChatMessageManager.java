package codes.castled.allium.managers.chat;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.managers.core.Text;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong messageIdCounter = new AtomicLong(0);

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

    private record LogicalMessageRef(long messageId, long registeredAt) {}

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
        private volatile boolean deleted;

        public ChatMessage(
            long messageId,
            UUID senderId,
            String senderName,
            Component originalMessage
        ) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.originalMessage = originalMessage;
            this.timestamp = System.currentTimeMillis();
            this.deleted = false;
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
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
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
        long messageId = claimLogicalId(message);
        if (messageId == 0) {
            messageId = messageIdCounter.incrementAndGet();
        }

        ChatMessage chatMessage = new ChatMessage(
            messageId,
            senderId,
            senderName,
            message
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
        logicalIdsByPlainText.put(plain, new LogicalMessageRef(messageId, now));
    }

    /** Returns the logical id registered for this text, or 0 if there is none. */
    private long claimLogicalId(Component message) {
        if (message == null || logicalIdsByPlainText.isEmpty()) {
            return 0;
        }
        String plain = PlainTextComponentSerializer.plainText()
            .serialize(message)
            .trim();
        if (plain.isEmpty()) {
            return 0;
        }

        LogicalMessageRef ref = logicalIdsByPlainText.get(plain);
        if (ref == null) {
            return 0;
        }
        if (System.currentTimeMillis() - ref.registeredAt() > LOGICAL_LINK_TTL_MS) {
            logicalIdsByPlainText.remove(plain, ref);
            return 0;
        }
        return ref.messageId();
    }

    /** Time window (ms) in which an orphaned packet copy may be matched by content. */
    private static final long DUPLICATE_TIME_MS = 3000;

    /**
     * Mark a message as deleted by message ID in all collections.
     * Per-viewer packet copies adopt the id of the message they were rendered from
     * (see {@link #storeMessageObject}), so id matching removes them too. Only copies that
     * failed to adopt an id fall back to content matching - see {@link #isOrphanedCopy}.
     */
    public boolean deleteMessage(long messageId) {
        ChatMessage target = getMessage(messageId);
        if (target == null) return false;

        String targetPlain = PlainTextComponentSerializer.plainText()
            .serialize(target.getOriginalMessage())
            .trim();
        long targetTime = target.getTimestamp();
        if (targetPlain.isEmpty()) targetPlain = null;

        Set<Long> adoptedIds = collectSenderOwnedIds();
        boolean found = false;

        found |= markMatching(
            playerMessages.values(),
            messageId,
            targetPlain,
            targetTime,
            adoptedIds
        );
        // Per-player chat histories and the global history (both used for resend) hold the
        // same ChatMessage instances, but sweep them anyway so nothing is missed if a copy
        // was trimmed out of playerMessages already.
        found |= markMatching(
            playerChatHistory.values(),
            messageId,
            targetPlain,
            targetTime,
            adoptedIds
        );
        found |= markMatching(
            List.of(globalChatHistory),
            messageId,
            targetPlain,
            targetTime,
            adoptedIds
        );

        return found;
    }

    private boolean markMatching(
        Collection<? extends Collection<ChatMessage>> collections,
        long messageId,
        String targetPlain,
        long targetTime,
        Set<Long> adoptedIds
    ) {
        boolean found = false;
        for (Collection<ChatMessage> messages : collections) {
            for (ChatMessage message : messages) {
                if (
                    message.getMessageId() == messageId ||
                    isOrphanedCopy(message, targetPlain, targetTime, adoptedIds)
                ) {
                    message.setDeleted(true);
                    found = true;
                }
            }
        }
        return found;
    }

    /** Ids of messages stored under a real sender, i.e. ids a packet copy can adopt. */
    private Set<Long> collectSenderOwnedIds() {
        Set<Long> ids = new HashSet<>();
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (!SYSTEM_SENDER_ID.equals(message.getSenderId())) {
                    ids.add(message.getMessageId());
                }
            }
        }
        return ids;
    }

    /**
     * True for a packet-captured copy that never adopted the id of the message it was
     * rendered from and whose content and timing line up with the message being deleted.
     * Those orphans are the only copies id matching cannot reach, so they are the only ones
     * content matching may touch - widening it any further makes deleting one message also
     * delete an identical message sent moments later.
     */
    private boolean isOrphanedCopy(
        ChatMessage message,
        String targetPlain,
        long targetTime,
        Set<Long> adoptedIds
    ) {
        if (targetPlain == null) return false;
        if (!SYSTEM_SENDER_ID.equals(message.getSenderId())) return false;
        if (adoptedIds.contains(message.getMessageId())) return false;
        String plain = PlainTextComponentSerializer.plainText()
            .serialize(message.getOriginalMessage())
            .trim();
        if (plain.isEmpty()) return false;
        long diff = Math.abs(message.getTimestamp() - targetTime);
        if (diff > DUPLICATE_TIME_MS) return false;
        // One contains the other so we match both "Prefix Name: hello" and "hello"
        return plain.contains(targetPlain) || targetPlain.contains(plain);
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
            Comparator.comparingLong(ChatMessage::getTimestamp).reversed()
        );
        if (matches.size() > amount) {
            return new ArrayList<>(matches.subList(0, amount));
        }
        return matches;
    }
}
