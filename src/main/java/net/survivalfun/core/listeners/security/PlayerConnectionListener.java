package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.PlayerInventories;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerConnectionListener implements Listener {

    private final Map<UUID, Long> playerLoginTimes;
    private final PluginStart plugin;

    public PlayerConnectionListener(PluginStart plugin) {
        this.plugin = plugin;
        this.playerLoginTimes = plugin.getPlayerLoginTimes();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        
        // Load player inventory
        PlayerInventories inventories = plugin.getDatabase().loadPlayerInventories(playerUUID);
        if (inventories != null) {
            player.getInventory().setContents(inventories.getSurvivalInventory());
            player.getInventory().setArmorContents(inventories.getSurvivalArmor());
            player.getInventory().setItemInOffHand(inventories.getSurvivalOffhand());
        }
        
        // Store login time for online duration tracking
        playerLoginTimes.put(playerUUID, System.currentTimeMillis());
        
        // Update last seen date in database
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = plugin.getDatabase().updatePlayerLastSeen(playerUUID, playerName);
            if (!success) {
                plugin.getLogger().log(Level.WARNING, "Failed to update last seen date for player: " + playerName);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        
        // Remove from login times map
        playerLoginTimes.remove(playerUUID);
        
        // Update last seen date in database when player quits
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = plugin.getDatabase().updatePlayerLastSeen(playerUUID, playerName);
            if (!success) {
                plugin.getLogger().log(Level.WARNING, "Failed to update last seen date for player: " + playerName);
            }
        });
    }
}
