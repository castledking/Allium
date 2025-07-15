package net.survivalfun.core.listeners.security;

import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.PermissionCache;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Listener for handling server maintenance mode, restricting access to authorized players.
 */
public class MaintenanceListener implements Listener {

    private final PluginStart plugin;
    private final Permission perms; // Vault permission service
    private final PermissionCache permissionCache;

    public MaintenanceListener(PluginStart plugin, Permission perms) {
        this.plugin = plugin;
        this.perms = perms;
        this.permissionCache = new PermissionCache(plugin.getDatabase());
    }

    /**
     * Handles pre-login checks to enforce maintenance mode.
     *
     * @param event The AsyncPlayerPreLoginEvent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getDatabase().isMaintenanceEnabled()) {
            plugin.getLogger().info("Maintenance mode is disabled. Allowing player: " + event.getName());
            return;
        }

        String playerName = event.getName();
        UUID playerUUID = event.getUniqueId();
        plugin.getLogger().info("Maintenance mode is enabled. Checking player: " + playerName);

        boolean bypass = hasMaintenanceBypass(playerUUID, playerName);
        
        plugin.getLogger().info("Player " + playerName + " bypass: " + bypass);

        if (!bypass) {
            String reason = plugin.getDatabase().getMaintenanceReason();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.colorize("&cLockdown: &7" + (reason != null ? reason : "Server Maintenance")));
            plugin.getLogger().info("Blocked " + playerName + " from joining during maintenance");
        } else {
            plugin.getLogger().info("Player " + playerName + " has bypass permission and is allowed to join during maintenance.");
        }
    }

    /**
     * Checks if a player has maintenance bypass permission.
     */
    private boolean hasMaintenanceBypass(UUID playerUUID, String playerName) {
        // Method 1: Check using Vault Permission service
        if (perms != null) {
            try {
                boolean bypass = perms.playerHas((String) null, playerName, "core.lockdown.bypass") || perms.playerHas((String) null, playerName, "core.admin");
                boolean hasMaintenanceBypass = perms.playerHas((String) null, playerName, "core.maintenance.bypass");
                
                plugin.getLogger().info("Vault permission check for " + playerName + 
                    " - lockdown.bypass: " + bypass + 
                    ", maintenance.bypass: " + hasMaintenanceBypass);
                
                return bypass || hasMaintenanceBypass;
            } catch (Exception e) {
                plugin.getLogger().warning("Error checking Vault permissions for " + playerName + ": " + e.getMessage());
            }
        }

        // Method 2: Check using permission cache (database fallback)
        try {
            boolean hasLockdownBypass = permissionCache.hasPermission(playerUUID, "core.lockdown.bypass");
            boolean hasAdmin = permissionCache.hasPermission(playerUUID, "core.admin");
            
            plugin.getLogger().info("Database permission check for " + playerName + 
                " - lockdown.bypass: " + hasLockdownBypass + 
                ", admin: " + hasAdmin);
            
            return hasLockdownBypass || hasAdmin;
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking database permissions for " + playerName + ": " + e.getMessage());
        }

        // Method 3: Config fallback
        plugin.getLogger().warning("Using config fallback for bypass check for " + playerName);
        return plugin.getConfig().getStringList("maintenance.bypass").contains(playerUUID.toString()) ||
               plugin.getConfig().getStringList("maintenance.bypass").contains(playerName);
    }

    /**
     * Caches player permissions upon joining.
     *
     * @param event The PlayerJoinEvent.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Cache permissions after a short delay to ensure all services are ready
        new BukkitRunnable() {
            @Override
            public void run() {
                permissionCache.cachePermissions(event.getPlayer());
            }
        }.runTaskLater(plugin, 20L); // Wait 1 second
    }
}