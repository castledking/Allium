package net.survivalfun.core.packetevents.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Party;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.managers.core.VanishManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.packetevents.TabListManager;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * PacketEvents-based tab list manager. Loaded only when PacketEvents plugin is available.
 * Cancels removal packets and periodically re-adds players so they stay visible in tab
 * (entity hidden via hidePlayer when beyond radius).
 */
public class TabListManagerPacketEventsImpl extends PacketListenerAbstract implements TabListManager, Listener {

    private static final long TABLIST_REFRESH_INTERVAL_TICKS = 1L; // Run every tick - re-add must win the race against server/TAB removals

    private final PluginStart plugin;
    private final PartyManager partyManager;
    private final VanishManager vanishManager;
    private final int minTabListSize;
    private SchedulerAdapter.TaskHandle tabListRefreshTask;

    public TabListManagerPacketEventsImpl(PluginStart plugin, PartyManager partyManager, VanishManager vanishManager) {
        super(PacketListenerPriority.LOWEST); // Run FIRST - cancel server's removal before TAB/others process it
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.vanishManager = vanishManager;
        this.minTabListSize = plugin.getConfig().getInt("party-manager.min-tablist-size", 0);

        // Brief delay for PacketEvents to be ready
        SchedulerAdapter.runLater(() -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
                // Periodically re-add all players to tab lists to counteract server/TAB
                // removing them when hidePlayer removes from tab
                tabListRefreshTask = SchedulerAdapter.runTimer(() -> {
                        try {
                            ensureAllPlayersVisibleInTabLists();
                        } catch (Throwable t) {
                            Text.sendDebugLog(WARN, "Tab list refresh task error: " + t.getMessage());
                        }
                }, TABLIST_REFRESH_INTERVAL_TICKS, TABLIST_REFRESH_INTERVAL_TICKS);
            } catch (Throwable e) {
                Text.sendDebugLog(WARN, "Failed to register TabListManager: " + e.getMessage());
            }
        }, 2L); // Start quickly - LOWEST runs first, we cancel removals before TAB processes
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(event);
            Player viewer = (Player) event.getPlayer();

            for (java.util.UUID targetUUID : packet.getProfileIds()) {
                Player targetPlayer = plugin.getServer().getPlayer(targetUUID);
                if (targetPlayer == null || !targetPlayer.isOnline()) continue;
                if (shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    event.setCancelled(true);
                    break;
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
            Player viewer = (Player) event.getPlayer();

            // Filter ADD_PLAYER entries - remove players that should not be visible (e.g. non-party beyond radius)
            // Prevents TAB/other plugins from re-adding players to tab list, which can affect locator bar
            if (packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = packet.getEntries();
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> toRemove = new ArrayList<>();
                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo : entries) {
                    Player targetPlayer = plugin.getServer().getPlayer(playerInfo.getGameProfile().getUUID());
                    if (targetPlayer == null || !targetPlayer.isOnline()) continue;
                    if (!shouldBeVisibleInTabList(viewer, targetPlayer)) {
                        toRemove.add(playerInfo);
                    }
                }
                if (!toRemove.isEmpty()) {
                    List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> filtered = new ArrayList<>(entries);
                    filtered.removeAll(toRemove);
                    if (filtered.isEmpty()) {
                        event.setCancelled(true);
                    } else {
                        packet.setEntries(filtered);
                    }
                }
            }
            if (packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED)) {
                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo : packet.getEntries()) {
                    if (!playerInfo.isListed()) {
                        Player targetPlayer = plugin.getServer().getPlayer(playerInfo.getGameProfile().getUUID());
                        if (targetPlayer == null || !targetPlayer.isOnline()) continue;
                        if (shouldBeVisibleInTabList(viewer, targetPlayer)) {
                            event.setCancelled(true);
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean shouldBeVisibleInTabList(Player viewer, Player target) {
        if (vanishManager.isVanished(target)) {
            return vanishManager.canSee(viewer, target);
        }
        if (viewer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }
        // Everyone stays in tab (entity hidden via hidePlayer when beyond radius)
        return true;
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo createPlayerInfoEntry(Player player) {
        UserProfile gameProfile = new UserProfile(player.getUniqueId(), player.getName());
        int latency = 0;
        GameMode gameMode = convertBukkitGameModeToPacketEvents(player.getGameMode());
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            gameProfile, true, latency, gameMode,
            Component.text(player.getDisplayName()), null
        );
    }

    @Override
    public void updateTablistAfterPartyEvent(Player player, boolean hideNonParty) {
        Party playerParty = partyManager.getPlayerParty(player.getUniqueId());
        for (Player otherPlayer : plugin.getServer().getOnlinePlayers()) {
            if (!otherPlayer.equals(player) && otherPlayer.isOnline()) {
                Party otherParty = partyManager.getPlayerParty(otherPlayer.getUniqueId());
                boolean sameParty = playerParty != null && otherParty != null && playerParty.equals(otherParty);
                if (sameParty) {
                    sendTabListAddPacket(otherPlayer, List.of(player));
                } else if (!hideNonParty) {
                    sendTabListAddPacket(otherPlayer, List.of(player));
                } else {
                    sendTabListRemovePacket(otherPlayer, List.of(player));
                }
            }
        }
    }

    @Override
    public void sendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        sendTabListAddPacketInternal(targetPlayer, viewers, true);
    }

    @Override
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
            if (playersToAdd.isEmpty()) return;

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry = createPlayerInfoEntry(targetPlayer);
            // Single packet with all actions - client expects combined add when re-adding to tab list
            EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> addActions = EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME
            );
            for (Player viewer : playersToAdd) {
                WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(addActions, entry);
                // Use sendPacketSilently to bypass TAB/other plugins that might modify or block our add
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist add packet for " + targetPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void sendTabListRemovePacket(Player targetPlayer, List<Player> viewers) {
        try {
            List<Player> playersToRemove = new ArrayList<>();
            for (Player viewer : viewers) {
                if (!shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    playersToRemove.add(viewer);
                }
            }
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(targetPlayer.getUniqueId());
            for (Player viewer : playersToRemove) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packet for " + targetPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        sendTabListAddPacketForMultiplePlayers(targetPlayers, viewers, true);
    }

    @Override
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

    @Override
    public void ensureAllPlayersVisibleInTabLists() {
        List<Player> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player viewer : onlinePlayers) {
            if (!viewer.isOnline()) continue;
            List<Player> toAdd = new ArrayList<>();
            for (Player target : onlinePlayers) {
                if (!viewer.equals(target) && target.isOnline() && shouldBeVisibleInTabList(viewer, target)) {
                    toAdd.add(target);
                }
            }
            if (!toAdd.isEmpty()) {
                forceSendTabListAddPacketForMultiplePlayers(toAdd, List.of(viewer));
            }
        }
    }

    @Override
    public void sendTabListRemoveForLeavingPlayer(Player leavingPlayer) {
        try {
            List<Player> allOtherPlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            allOtherPlayers.remove(leavingPlayer);
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(leavingPlayer.getUniqueId());
            for (Player viewer : allOtherPlayers) {
                if (shouldBeVisibleInTabList(viewer, leavingPlayer)) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packets for leaving player " + leavingPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void sendInitialTabListState(Player joinedPlayer) {
        try {
            List<Player> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            onlinePlayers.remove(joinedPlayer);
            List<Player> playersToShow = new ArrayList<>();

            for (Player otherPlayer : onlinePlayers) {
                if (shouldBeVisibleInTabList(joinedPlayer, otherPlayer)) {
                    playersToShow.add(otherPlayer);
                }
            }

            if (minTabListSize > 0 && playersToShow.size() < minTabListSize) {
                List<Player> remainingPlayers = new ArrayList<>(onlinePlayers);
                remainingPlayers.removeAll(playersToShow);
                remainingPlayers.sort((p1, p2) -> {
                    double dist1 = joinedPlayer.getLocation().distance(p1.getLocation());
                    double dist2 = joinedPlayer.getLocation().distance(p2.getLocation());
                    return Double.compare(dist1, dist2);
                });
                for (Player closestPlayer : remainingPlayers) {
                    if (playersToShow.size() >= minTabListSize) break;
                    playersToShow.add(closestPlayer);
                }
            }

            for (Player otherPlayer : playersToShow) {
                sendTabListAddPacket(otherPlayer, List.of(joinedPlayer));
                if (shouldBeVisibleInTabList(otherPlayer, joinedPlayer)) {
                    sendTabListAddPacket(joinedPlayer, List.of(otherPlayer));
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send initial tab list state for " + joinedPlayer.getName() + ": " + e.getMessage());
        }
    }

    private GameMode convertBukkitGameModeToPacketEvents(org.bukkit.GameMode bukkitGameMode) {
        return switch (bukkitGameMode) {
            case SURVIVAL -> GameMode.SURVIVAL;
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SPECTATOR -> GameMode.SPECTATOR;
        };
    }

    @Override
    public void shutdown() {
        try {
            if (tabListRefreshTask != null) {
                tabListRefreshTask.cancel();
                tabListRefreshTask = null;
            }
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error unregistering tablist manager listener: " + e.getMessage());
        }
    }
}
