package net.survivalfun.core.managers.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages chat messages for staff moderation features
 */
public class ChatMessageManager {
    
    private static final int MAX_MESSAGES_PER_PLAYER = 100; // Limit to prevent memory issues
    private static final int MAX_GLOBAL_MESSAGES = 200; // Global chat history limit
    
    // Thread-safe storage for chat messages
    private final Map<UUID, Deque<ChatMessage>> playerMessages = new ConcurrentHashMap<>();
    private final AtomicLong messageIdCounter = new AtomicLong(0);
    
    // Per-player chat history for packet-based deletion
    private final Map<UUID, Deque<ChatMessage>> playerChatHistory = new ConcurrentHashMap<>();
    
    // Global chat history (ordered by timestamp)
    private final Deque<ChatMessage> globalChatHistory = new ConcurrentLinkedDeque<>();
    
    /**
     * Represents a chat message that can be deleted by staff
     */
    public static class ChatMessage {
        private final long messageId;
        private final UUID senderId;
        private final String senderName;
        private final Component originalMessage;
        private final long timestamp;
        private boolean deleted;
        
        public ChatMessage(long messageId, UUID senderId, String senderName, Component originalMessage) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.originalMessage = originalMessage;
            this.timestamp = System.currentTimeMillis();
            this.deleted = false;
        }
        
        public long getMessageId() { return messageId; }
        public UUID getSenderId() { return senderId; }
        public String getSenderName() { return senderName; }
        public Component getOriginalMessage() { return originalMessage; }
        public long getTimestamp() { return timestamp; }
        public boolean isDeleted() { return deleted; }
        public void setDeleted(boolean deleted) { this.deleted = deleted; }
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
    public long storeMessage(UUID senderId, String senderName, Component message) {
        long messageId = messageIdCounter.incrementAndGet();
        
        ChatMessage chatMessage = new ChatMessage(messageId, senderId, senderName, message);
        
        // Get or create the sender's message list
        Deque<ChatMessage> messages = playerMessages.computeIfAbsent(senderId, k -> new ConcurrentLinkedDeque<>());

        messages.addLast(chatMessage);

        // Limit the number of stored messages per sender to prevent memory issues
        while (messages.size() > MAX_MESSAGES_PER_PLAYER && messages.pollFirst() != null) {
            // Trim oldest entries until within limit
        }
        
        return messageId;
    }
    
    /** Time window (ms) to treat as same logical message when deleting duplicates (e.g. formatted vs packet raw). */
    private static final long DUPLICATE_TIME_MS = 3000;

    /**
     * Mark a message as deleted by message ID in all collections.
     * Also marks any "duplicate" messages (same content, within a short time) so that
     * both the formatted copy (from FormatChatListener) and the raw copy (from PacketEvents) are removed.
     */
    public boolean deleteMessage(long messageId) {
        ChatMessage target = getMessage(messageId);
        if (target == null) return false;

        String targetPlain = PlainTextComponentSerializer.plainText().serialize(target.getOriginalMessage()).trim();
        long targetTime = target.getTimestamp();
        if (targetPlain.isEmpty()) targetPlain = null;

        boolean found = false;

        // Mark by ID and by content+time in playerMessages
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (message.getMessageId() == messageId || isDuplicateContent(message, targetPlain, targetTime)) {
                    message.setDeleted(true);
                    found = true;
                }
            }
        }

        // Mark in per-player chat histories (used for resend)
        for (Deque<ChatMessage> history : playerChatHistory.values()) {
            for (ChatMessage message : history) {
                if (message.getMessageId() == messageId || isDuplicateContent(message, targetPlain, targetTime)) {
                    message.setDeleted(true);
                    found = true;
                }
            }
        }

        // Mark in global chat history (used for resend)
        for (ChatMessage message : globalChatHistory) {
            if (message.getMessageId() == messageId || isDuplicateContent(message, targetPlain, targetTime)) {
                message.setDeleted(true);
                found = true;
            }
        }

        return found;
    }

    /**
     * True if this message is a duplicate of the target (same content within time window).
     * Catches packet-tracked "raw" copy when staff deletes the formatted copy.
     */
    private boolean isDuplicateContent(ChatMessage message, String targetPlain, long targetTime) {
        if (targetPlain == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(message.getOriginalMessage()).trim();
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
        
        return messages.stream()
                .filter(msg -> !msg.isDeleted())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Get all non-deleted messages from all players (for global chat reconstruction)
     */
    public List<ChatMessage> getAllActiveMessages() {
        List<ChatMessage> allMessages = new ArrayList<>();
        
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            allMessages.addAll(messages.stream()
                    .filter(msg -> !msg.isDeleted())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
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
        Deque<ChatMessage> playerHistory = playerChatHistory.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        playerHistory.addLast(message);

        // Limit per-player history
        while (playerHistory.size() > MAX_MESSAGES_PER_PLAYER && playerHistory.pollFirst() != null) {
            // Trim oldest entries until within limit
        }
        
        // Add to global history
        globalChatHistory.addLast(message);
        while (globalChatHistory.size() > MAX_GLOBAL_MESSAGES && globalChatHistory.pollFirst() != null) {
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
        
        return history.stream()
                .filter(msg -> !msg.isDeleted())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Get recent global chat history (excluding deleted messages)
     */
    public List<ChatMessage> getRecentGlobalChatHistory(int maxMessages) {
        int toSkip = Math.max(0, globalChatHistory.size() - maxMessages);
        return globalChatHistory.stream()
                .filter(msg -> !msg.isDeleted())
                .skip(toSkip)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Get a specific message by ID
     */
    public ChatMessage getMessage(long messageId) {
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (message.getMessageId() == messageId) {
                    return message;
                }
            }
        }
        return null;
    }

    public List<ChatMessage> getRecentActiveMessagesBySender(String senderName, UUID senderId, int amount) {
        if (amount <= 0) {
            return List.of();
        }

        List<ChatMessage> matches = new ArrayList<>();
        for (Deque<ChatMessage> messages : playerMessages.values()) {
            for (ChatMessage message : messages) {
                if (message.isDeleted()) {
                    continue;
                }
                if (senderId != null && senderId.equals(message.getSenderId())) {
                    matches.add(message);
                    continue;
                }
                if (senderName != null && message.getSenderName() != null
                        && message.getSenderName().equalsIgnoreCase(senderName)) {
                    matches.add(message);
                }
            }
        }

        matches.sort(Comparator.comparingLong(ChatMessage::getTimestamp).reversed());
        if (matches.size() > amount) {
            return new ArrayList<>(matches.subList(0, amount));
        }
        return matches;
    }

}
