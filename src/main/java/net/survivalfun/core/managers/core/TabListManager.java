package net.survivalfun.core.managers.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * PacketEvents-based tab list manager that ensures all players remain visible in the tab list
 * regardless of party-based entity hiding. This solves the issue where hidden players
 * were also being removed from the tab list.
 */
public class TabListManager extends PacketListenerAbstract implements Listener {

    private final PluginStart plugin;
    private final PartyManager partyManager;
    private final VanishManager vanishManager;
    private final boolean hideNonPartyMembersTab;
    private final int minTabListSize;

    public TabListManager(PluginStart plugin, PartyManager partyManager, VanishManager vanishManager) {
        super(PacketListenerPriority.MONITOR); // Use MONITOR priority to run AFTER TAB plugin
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.vanishManager = vanishManager;
        this.hideNonPartyMembersTab = plugin.getConfig().getBoolean("party-manager.hide-non-party-members-tab", false);
        this.minTabListSize = plugin.getConfig().getInt("party-manager.min-tablist-size", 0);
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            } catch (Throwable e) {
                Text.sendDebugLog(WARN, "Failed to register TabListManager: " + e.getMessage());
            }
        }, 20L);
    }

    /**
     * Intercept tablist removal packets and cancel them if the player should remain visible
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(event);
            Player viewer = (Player) event.getPlayer();

            // If hide-non-party-members-tab is false, cancel ALL removal packets
            // This ensures all players remain visible in tablist regardless of entity visibility
            if (!hideNonPartyMembersTab) {
                event.setCancelled(true);
                return;
            }

            // Check each UUID being removed
            for (java.util.UUID targetUUID : packet.getProfileIds()) {
                Player targetPlayer = plugin.getServer().getPlayer(targetUUID);

                // If player is offline (quitting), allow the removal
                if (targetPlayer == null || !targetPlayer.isOnline()) {
                    continue; // Don't cancel removal for offline players
                }

                if (shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    // Cancel the removal packet for this player
                    event.setCancelled(true);
                    break; // Cancel the entire packet if any player should remain visible
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
            Player viewer = (Player) event.getPlayer();

            // Check if this is a UPDATE_LISTED action (player being added or removed from tablist)
            if (packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED)) {
                // If hide-non-party-members-tab is false, cancel ONLY remove updates (isListed = false)
                if (!hideNonPartyMembersTab) {
                    // Check each player info being updated
                    for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo : packet.getEntries()) {
                        // Only process if this player is being unlisted (removed from tablist)
                        if (!playerInfo.isListed()) {
                            Player targetPlayer = plugin.getServer().getPlayer(playerInfo.getGameProfile().getUUID());

                            // If player is offline (quitting), allow the removal
                            if (targetPlayer == null || !targetPlayer.isOnline()) {
                                continue; // Don't cancel removal for offline players
                            }

                            // When hideNonPartyMembersTab is false, prevent removal of online players
                            // This ensures radius-based entity visibility doesn't affect tablist visibility
                            event.setCancelled(true);
                            break; // Cancel the entire packet if any player should remain visible
                        }
                    }
                } else {
                    // When hide-non-party-members-tab is true, use party-based visibility rules
                    // Check each player info being removed
                    for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo : packet.getEntries()) {
                        // Only process if this player is being unlisted (removed from tablist)
                        if (!playerInfo.isListed()) {
                            Player targetPlayer = plugin.getServer().getPlayer(playerInfo.getGameProfile().getUUID());

                            // If player is offline (quitting), allow the removal
                            if (targetPlayer == null || !targetPlayer.isOnline()) {
                                continue; // Don't cancel removal for offline players
                            }

                            if (shouldBeVisibleInTabList(viewer, targetPlayer)) {
                                // Cancel the removal packet for this player
                                event.setCancelled(true);
                                break; // Cancel the entire packet if any player should remain visible
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if a target player should be visible in a viewer's tablist
     * @param viewer The viewing player
     * @param target The target player
     * @return true if target should be visible in viewer's tablist
     */
    public boolean shouldBeVisibleInTabList(Player viewer, Player target) {
        // Check vanish state first - vanished players are only visible to players who can see them
        if (vanishManager.isVanished(target)) {
            return vanishManager.canSee(viewer, target);
        }

        // Spectators can always see everyone in tab list
        if (viewer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }

        // If hide-non-party-members-tab is false, everyone is visible in tablist
        // This ensures that radius-based entity visibility doesn't affect tablist visibility
        if (!hideNonPartyMembersTab) {
            return true;
        }

        // Check if both players are in the same party
        Party viewerParty = partyManager.getPlayerParty(viewer.getUniqueId());
        Party targetParty = partyManager.getPlayerParty(target.getUniqueId());

        // Party members are always visible to each other in tablist
        if (viewerParty != null && targetParty != null && viewerParty.equals(targetParty)) {
            return true;
        }

        // Non-party members are hidden from tablist when hide-non-party-members-tab is true
        return false;
    }

    /**
     * Create a PlayerInfoEntry for a Bukkit player
     * @param player The Bukkit player
     * @return PlayerInfoEntry for PacketEvents
     */
    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo createPlayerInfoEntry(Player player) {
        // Create basic UserProfile - the ADD_PLAYER packet should include skin lookup
        UserProfile gameProfile = new UserProfile(
            player.getUniqueId(),
            player.getName()
        );

        // Get player's ping/latency
        int latency = 0; // Default to 0 if ping is not available

        // Convert Bukkit GameMode to PacketEvents GameMode
        GameMode gameMode = convertBukkitGameModeToPacketEvents(player.getGameMode());

        // Create PlayerInfo with correct constructor
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            gameProfile,                    // UserProfile
            true,                          // listed in tab list
            latency,                       // ping in milliseconds
            gameMode,                      // PacketEvents GameMode
            Component.text(player.getDisplayName()), // display name component
            null                           // chat session (can be null)
        );
    }

    public void updateTablistAfterPartyEvent(Player player, boolean hideNonParty) {
       Party playerParty = partyManager.getPlayerParty(player.getUniqueId());

       for (Player otherPlayer : plugin.getServer().getOnlinePlayers()) {
           if (!otherPlayer.equals(player) && otherPlayer.isOnline()) {
               Party otherParty = partyManager.getPlayerParty(otherPlayer.getUniqueId());
               boolean sameParty = playerParty != null && otherParty != null && playerParty.equals(otherParty);

               if (sameParty) {
                   // Always show party members
                   sendTabListAddPacket(otherPlayer, List.of(player));
               } else if (!hideNonParty) {
                   // Show non-party members if config allows
                   sendTabListAddPacket(otherPlayer, List.of(player));
               } else {
                   // Hide non-party members when config requires it
                   sendTabListRemovePacket(otherPlayer, List.of(player));
               }
           }
       }
   }

    /**
     * Send a PacketEvents-based tablist update to add a player back to tablist for specific viewers
     * @param targetPlayer The player to add to tablist
     * @param viewers The players who should see the target in tablist
     */
    public void sendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        sendTabListAddPacketInternal(targetPlayer, viewers, true);
    }

    public void forceSendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        sendTabListAddPacketInternal(targetPlayer, viewers, false);
    }

    private void sendTabListAddPacketInternal(Player targetPlayer, List<Player> viewers, boolean respectVisibilityRules) {
        try {
            List<Player> playersToAdd = new ArrayList<>();
            for (Player viewer : viewers) {
                if (!respectVisibilityRules || shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    playersToAdd.add(viewer);
                }
            }

            if (playersToAdd.isEmpty()) {
                return;
            }

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry = createPlayerInfoEntry(targetPlayer);
            WrapperPlayServerPlayerInfoUpdate addPacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                entry
            );
            WrapperPlayServerPlayerInfoUpdate listedPacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                entry
            );
            WrapperPlayServerPlayerInfoUpdate latencyPacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                entry
            );
            WrapperPlayServerPlayerInfoUpdate gameModePacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                entry
            );
            WrapperPlayServerPlayerInfoUpdate orderPacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER,
                entry
            );

            WrapperPlayServerPlayerInfoUpdate displayNamePacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                entry
            );
            WrapperPlayServerPlayerInfoUpdate skinPacket = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT,
                entry
            );

            for (Player viewer : playersToAdd) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, addPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, listedPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, latencyPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, gameModePacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, displayNamePacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, orderPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, skinPacket);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist add packet for " + targetPlayer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Send a PacketEvents-based tablist update to remove a player from tablist for specific viewers
     * @param targetPlayer The player to remove from tablist
     * @param viewers The players who should no longer see the target in tablist
     */
    public void sendTabListRemovePacket(Player targetPlayer, List<Player> viewers) {
        try {
            List<Player> playersToRemove = new ArrayList<>();
            for (Player viewer : viewers) {
                if (!shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    playersToRemove.add(viewer);
                }
            }

            // Create player info remove packet
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(targetPlayer.getUniqueId());

            for (Player viewer : playersToRemove) {
                // Send packet to viewer to remove target from their tablist
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }

            if (!playersToRemove.isEmpty()) {
                // Removed debug log to reduce spam
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packet for " + targetPlayer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Send tablist add packets for multiple target players to a single viewer
     * @param targetPlayers The players to add to tablist
     * @param viewer The single player who should see all targets in tablist
     */
    public void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        sendTabListAddPacketForMultiplePlayers(targetPlayers, viewers, true);
    }

    public void forceSendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        sendTabListAddPacketForMultiplePlayers(targetPlayers, viewers, false);
    }

    private void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers, boolean respectVisibilityRules) {
        try {
            for (Player targetPlayer : targetPlayers) {
                sendTabListAddPacketInternal(targetPlayer, viewers, respectVisibilityRules);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist add packets for multiple players: " + e.getMessage());
        }
    }

    /**
     * Ensure all online players remain visible in tab lists when hideNonPartyMembersTab is disabled
     * This method can be called periodically or when visibility changes occur
     */
    public void ensureAllPlayersVisibleInTabLists() {
        if (hideNonPartyMembersTab) {
            return; // Only apply when tab list hiding is disabled
        }

        List<Player> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());

        for (Player viewer : onlinePlayers) {
            for (Player target : onlinePlayers) {
                if (!viewer.equals(target) && shouldBeVisibleInTabList(viewer, target)) {
                    // Send add packet to ensure this target is visible to this viewer
                    sendTabListAddPacket(target, List.of(viewer));
                }
            }
        }
    }

    /**
     * Send tab list remove packets for a player who is leaving to viewers who should see them
     * @param leavingPlayer The player who is leaving
     */
    public void sendTabListRemoveForLeavingPlayer(Player leavingPlayer) {
        try {
            List<Player> allOtherPlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            allOtherPlayers.remove(leavingPlayer);

            // Create player info remove packet
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(leavingPlayer.getUniqueId());

            // Only send remove packets to viewers who should actually see the leaving player in their tab list
            // This ensures vanished players don't receive remove packets for players they can't see
            for (Player viewer : allOtherPlayers) {
                if (shouldBeVisibleInTabList(viewer, leavingPlayer)) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
                }
            }

            // Removed debug log to reduce spam
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packets for leaving player " + leavingPlayer.getName() + ": " + e.getMessage());
        }
    }
    public void sendInitialTabListState(Player joinedPlayer) {
        try {
            List<Player> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            onlinePlayers.remove(joinedPlayer);

            List<Player> playersToShow = new ArrayList<>();

            // First, add players who should normally be visible
            for (Player otherPlayer : onlinePlayers) {
                if (shouldBeVisibleInTabList(joinedPlayer, otherPlayer)) {
                    playersToShow.add(otherPlayer);
                }
            }

            // If we have a minimum tablist size requirement and not enough players, add closest ones
            // Only applies when hide-non-party-members-tab is true (party-based visibility)
            if (minTabListSize > 0 && playersToShow.size() < minTabListSize && hideNonPartyMembersTab) {
                List<Player> remainingPlayers = new ArrayList<>(onlinePlayers);
                remainingPlayers.removeAll(playersToShow);

                // Sort remaining players by distance to joined player
                remainingPlayers.sort((p1, p2) -> {
                    double dist1 = joinedPlayer.getLocation().distance(p1.getLocation());
                    double dist2 = joinedPlayer.getLocation().distance(p2.getLocation());
                    return Double.compare(dist1, dist2);
                });

                // Add closest players until we reach minimum size
                for (Player closestPlayer : remainingPlayers) {
                    if (playersToShow.size() >= minTabListSize) break;
                    playersToShow.add(closestPlayer);
                }
            }

            // Send tablist packets for all players that should be shown
            for (Player otherPlayer : playersToShow) {
                // Send add packet for the other player to the joined player
                sendTabListAddPacket(otherPlayer, List.of(joinedPlayer));
                
                // Send add packet for the joined player to the other player (if they should see each other)
                if (shouldBeVisibleInTabList(otherPlayer, joinedPlayer)) {
                    sendTabListAddPacket(joinedPlayer, List.of(otherPlayer));
                }
            }

            // Removed debug log to reduce spam
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send initial tab list state for " + joinedPlayer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Convert Bukkit GameMode to PacketEvents GameMode
     * @param bukkitGameMode Bukkit GameMode
     * @return PacketEvents GameMode
     */
    private GameMode convertBukkitGameModeToPacketEvents(org.bukkit.GameMode bukkitGameMode) {
        return switch (bukkitGameMode) {
            case SURVIVAL -> GameMode.SURVIVAL;
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SPECTATOR -> GameMode.SPECTATOR;
        };
    }

    /**
     * Stop the tablist refresh task
     */
    public void shutdown() {
        // Unregister the packet listener
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error unregistering tablist manager listener: " + e.getMessage());
        }
    }
}
