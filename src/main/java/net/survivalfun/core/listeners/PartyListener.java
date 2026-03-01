package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.packetevents.TabListManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for party-related player events.
 */
public class PartyListener implements Listener {
    private final PartyManager partyManager;

    public PartyListener(PluginStart plugin, PartyManager partyManager, TabListManager tabListManager) {
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
}
