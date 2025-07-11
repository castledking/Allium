package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;

import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

public class MaintenanceListener implements Listener {

    private final PluginStart plugin;

    public MaintenanceListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(PlayerPreLoginEvent event) {
        if (plugin.getDatabase().isMaintenanceEnabled()) {
            String playerName = event.getName(); // Get player name from event
            UUID playerId = event.getUniqueId();
            
            plugin.getLogger().info("Maintenance mode is enabled. Checking player: " + playerName);
            
            // Check bypass permission - you'll need to implement this differently
            boolean hasBypass = checkBypass(playerId); 
            
            plugin.getLogger().info("Player " + playerName + " bypass: " + hasBypass);
            
            if (!hasBypass) {
                String reason = plugin.getDatabase().getMaintenanceReason();
                event.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, 
                            Text.colorize("&cLockdown: &7" + reason));
                plugin.getLogger().info("Blocked " + playerName + " from joining during maintenance");
            } else {
                plugin.getLogger().info("Player " + playerName + " has bypass permission and is allowed to join during maintenance.");
            }
        } else {
            plugin.getLogger().info("Maintenance mode is disabled. Allowing player: " + event.getName());
        }
    }

    private boolean checkBypass(UUID playerId) {
        // Check if LuckPerms is available via service provider
        
        RegisteredServiceProvider<net.luckperms.api.LuckPerms> provider = plugin.getServer().getServicesManager().getRegistration(net.luckperms.api.LuckPerms.class);
        if (provider != null) {
            net.luckperms.api.LuckPerms luckPerms = provider.getProvider();
            try {
                net.luckperms.api.model.user.User user = luckPerms.getUserManager().loadUser(playerId).join();
                if (user != null) {
                    // Check permissions
                    if (user.getCachedData().getPermissionData().checkPermission("core.lockdown.bypass").asBoolean() ||
                        user.getCachedData().getPermissionData().checkPermission("core.admin").asBoolean()) {
                        return true;
                    }
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "Error checking LuckPerms permissions for " + playerId, e);
            }
        }

        // Fallback to database check
        return plugin.getDatabase().playerHasPermission(playerId, "core.lockdown.bypass") ||
               plugin.getDatabase().playerHasPermission(playerId, "core.admin") ||
               plugin.getDatabase().isPlayerOp(playerId);
    }
}
