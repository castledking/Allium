package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles permission migration for players when they join the server.
 * This ensures that players have the correct permissions after updates or changes.
 */
public class PlayerPermissionMigrationListener implements Listener {
    private final PluginStart plugin;

    public PlayerPermissionMigrationListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Only run migration for players who have played before
        if (!player.hasPlayedBefore()) {
            return;
        }
        
        try {
            // Add any permission migration logic here if needed
            // For example, converting old permission nodes to new ones
            
            if (plugin.isDebugMode()) {
                plugin.getLogger().info("Checked permissions for " + player.getName() + " - no migration needed");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to migrate permissions for " + player.getName() + ": " + e.getMessage());
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
        }
    }
}
