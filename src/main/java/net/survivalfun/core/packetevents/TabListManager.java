package net.survivalfun.core.packetevents;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Abstraction for tab list management via packet-level updates.
 * Implementations may use PacketEvents when available, or provide no-op behavior.
 */
public interface TabListManager {

    /**
     * Whether this implementation actually performs tab list updates.
     * No-op returns false; PacketEvents impl returns true.
     */
    default boolean supportsTabListUpdates() {
        return true;
    }

    boolean shouldBeVisibleInTabList(Player viewer, Player target);

    void sendTabListAddPacket(Player targetPlayer, List<Player> viewers);

    void forceSendTabListAddPacket(Player targetPlayer, List<Player> viewers);

    void sendTabListRemovePacket(Player targetPlayer, List<Player> viewers);

    void forceSendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers);

    void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers);

    void ensureAllPlayersVisibleInTabLists();

    void sendInitialTabListState(Player joinedPlayer);

    void sendTabListRemoveForLeavingPlayer(Player leavingPlayer);

    void updateTablistAfterPartyEvent(Player player, boolean hideNonParty);

    void shutdown();
}
