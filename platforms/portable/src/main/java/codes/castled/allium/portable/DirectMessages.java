package codes.castled.allium.portable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Session reply-target tracking for /msg, /reply, and /rtoggle on native mod adapters. */
public final class DirectMessages {
    private final Map<UUID, UUID> lastReceivedFrom = new ConcurrentHashMap<UUID, UUID>();
    private final Map<UUID, UUID> lastSentTo = new ConcurrentHashMap<UUID, UUID>();

    public void recordExchange(UUID sender, UUID recipient) {
        if (sender == null || recipient == null) return;
        lastSentTo.put(sender, recipient);
        lastReceivedFrom.put(recipient, sender);
    }

    public UUID replyTarget(UUID player, boolean replyToLastRecipient) {
        if (player == null) return null;
        UUID primary = replyToLastRecipient ? lastSentTo.get(player) : lastReceivedFrom.get(player);
        if (primary != null) return primary;
        return replyToLastRecipient ? lastReceivedFrom.get(player) : lastSentTo.get(player);
    }
}
