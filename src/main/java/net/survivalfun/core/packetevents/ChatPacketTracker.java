package net.survivalfun.core.packetevents;

import org.bukkit.entity.Player;

/**
 * Abstraction for chat packet tracking via PacketEvents.
 * Implementations may use PacketEvents when available, or provide no-op behavior.
 */
public interface ChatPacketTracker {

    /**
     * Whether this implementation supports chat history resend.
     */
    boolean supportsResend();

    /**
     * Resend chat history to a specific player.
     */
    void resendChatHistoryToPlayer(Player player);

    /**
     * Resend chat history to all online players.
     */
    void resendChatHistoryToAllPlayers();

    /**
     * Shutdown and unregister listeners.
     */
    default void shutdown() {
        // No-op by default
    }
}
