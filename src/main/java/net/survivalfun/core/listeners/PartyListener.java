package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.packetevents.TabListManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Listener for party-related player events.
 */
public class PartyListener implements Listener {
    private final PluginStart plugin;
    private final PartyManager partyManager;

    public PartyListener(PluginStart plugin, PartyManager partyManager, TabListManager tabListManager) {
        this.plugin = plugin;
        this.partyManager = partyManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        partyManager.onPlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        partyManager.onPlayerQuit(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!partyManager.isPartyLocatorBarEnabled()) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            partyManager.onPlayerMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!partyManager.isPartyLocatorBarEnabled()) return;
        partyManager.updateVisibilityForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!partyManager.isPartyLocatorBarEnabled()) return;
        partyManager.updateVisibilityForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        if (!partyManager.isPartyLocatorBarEnabled()) return;
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> partyManager.updateVisibilityForPlayer(player));
    }
}
