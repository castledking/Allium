package net.survivalfun.core.packetevents;

import org.bukkit.entity.Player;

/**
 * No-op implementation when PacketEvents is not available.
 */
public class ChatPacketTrackerNoOp implements ChatPacketTracker {

    @Override
    public boolean supportsResend() {
        return false;
    }

    @Override
    public void resendChatHistoryToPlayer(Player player) {
        // No-op
    }

    @Override
    public void resendChatHistoryToAllPlayers() {
        // No-op
    }
}
