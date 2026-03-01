package net.survivalfun.core.packetevents;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * No-op implementation when PacketEvents is not available.
 * Returns sensible defaults: all players visible in tab list.
 */
public class TabListManagerNoOp implements TabListManager {

    @Override
    public boolean supportsTabListUpdates() {
        return false;
    }

    @Override
    public boolean shouldBeVisibleInTabList(Player viewer, Player target) {
        return true;
    }

    @Override
    public void sendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        // No-op
    }

    @Override
    public void forceSendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        // No-op
    }

    @Override
    public void sendTabListRemovePacket(Player targetPlayer, List<Player> viewers) {
        // No-op
    }

    @Override
    public void forceSendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        // No-op
    }

    @Override
    public void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        // No-op
    }

    @Override
    public void ensureAllPlayersVisibleInTabLists() {
        // No-op
    }

    @Override
    public void sendInitialTabListState(Player joinedPlayer) {
        // No-op
    }

    @Override
    public void sendTabListRemoveForLeavingPlayer(Player leavingPlayer) {
        // No-op
    }

    @Override
    public void updateTablistAfterPartyEvent(Player player, boolean hideNonParty) {
        // No-op
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
