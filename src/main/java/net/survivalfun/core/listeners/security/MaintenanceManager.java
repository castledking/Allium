package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.PermissionCache;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import net.milkbowl.vault.permission.Permission;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Listener for handling server maintenance mode, restricting access to authorized players.
 */
public class MaintenanceManager implements Listener {

    private final PluginStart plugin;
    private final Permission perms;
    private final PermissionCache permissionCache;
    private FileConfiguration maintenanceConfig;
    private File configFile;

    public MaintenanceManager(PluginStart plugin, Permission perms) {
        this.plugin = plugin;
        this.perms = perms;
        this.permissionCache = new PermissionCache(plugin);
        loadMaintenanceConfig();
    }

    private void loadMaintenanceConfig() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        configFile = new File(plugin.getDataFolder(), "maintenance.yml");

        if (!configFile.exists()) {
            plugin.saveResource("maintenance.yml", false);
            Text.sendDebugLog(INFO, "Created maintenance.yml configuration file");
        }

        maintenanceConfig = YamlConfiguration.loadConfiguration(configFile);
        Text.sendDebugLog(INFO, "Loaded maintenance configuration");
    }

    public void reloadConfig() {
        maintenanceConfig = YamlConfiguration.loadConfiguration(configFile);
        Text.sendDebugLog(INFO, "Reloaded maintenance configuration");
    }

    public boolean isMaintenanceEnabled() {
        return maintenanceConfig.getBoolean("enabled", false);
    }

    public void setMaintenance(boolean enabled) {
        maintenanceConfig.set("enabled", enabled);
        saveConfig();
    }

    public void setMaintenance(boolean enabled, String reason) {
        maintenanceConfig.set("enabled", enabled);
        if (reason != null && !reason.isEmpty()) {
            maintenanceConfig.set("default-reason", reason);
        }
        saveConfig();
    }

    public String getKickMessage() {
        return maintenanceConfig.getString("kick-message", "&cThis server is currently in maintenance.\n&7Please try again later.")
                .replace("%reason%", maintenanceConfig.getString("default-reason", "Scheduled maintenance"));
    }

    public String getKickMessage(UUID playerId) {
        String customMessage = maintenanceConfig.getString("custom-messages." + playerId.toString());
        if (customMessage != null) {
            return customMessage;
        }
        return getKickMessage();
    }

    public boolean isWhitelisted(UUID playerId, String playerName) {
        // Check UUID whitelist
        List<String> uuidWhitelist = maintenanceConfig.getStringList("whitelist.uuids");
        if (uuidWhitelist.contains(playerId.toString())) {
            return true;
        }

        // Check username whitelist (for Bedrock players via Floodgate)
        List<String> usernameWhitelist = maintenanceConfig.getStringList("whitelist.usernames");
        if (playerName != null && usernameWhitelist.contains(playerName)) {
            return true;
        }

        return false;
    }

    private void saveConfig() {
        try {
            maintenanceConfig.save(configFile);
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Could not save maintenance config: " + e.getMessage());
        }
    }

    /**
     * Handles pre-login checks to enforce maintenance mode.
     *
     * @param event The AsyncPlayerPreLoginEvent.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!isMaintenanceEnabled()) {
            Text.sendDebugLog(INFO, "Maintenance mode is disabled. Allowing player: " + event.getName());
            return;
        }

        String playerName = event.getName();
        UUID playerUUID = event.getUniqueId();
        Text.sendDebugLog(INFO, "Maintenance mode is enabled. Checking player: " + playerName);

        // Check if player is whitelisted
        if (isWhitelisted(playerUUID, playerName)) {
            Text.sendDebugLog(INFO, "Player " + playerName + " is whitelisted for maintenance access");
            return;
        }

        // Check for permission bypass
        boolean bypass = hasMaintenanceBypass(playerUUID, playerName);
        Text.sendDebugLog(INFO, "Player " + playerName + " bypass: " + bypass);

        if (!bypass) {
            String reason = maintenanceConfig.getString("default-reason", "Scheduled maintenance");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.colorize(getKickMessage(playerUUID)));
            Text.sendDebugLog(INFO, "Blocked " + playerName + " from joining during maintenance");
        } else {
            Text.sendDebugLog(INFO, "Player " + playerName + " has bypass permission and is allowed to join during maintenance.");
            Text.sendDebugLog(INFO, "Player " + playerName + " bypassed maintenance via Vault permissions");
        }
    }

    /**
     * Checks if a player has maintenance bypass permission.
     */
    private boolean hasMaintenanceBypass(UUID playerUUID, String playerName) {
        if (playerName == null || playerUUID == null) {
            Text.sendDebugLog(WARN, "Null player name or UUID provided to hasMaintenanceBypass");
            return false;
        }

        // Always check permissions directly on the current thread
        // Vault's permission checks are thread-safe
        checkAndLogPermission("allium.*", playerUUID, playerName);
        return checkBypassPermissions(playerUUID, playerName);
    }

    private boolean checkBypassPermissions(UUID playerUUID, String playerName) {
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "=== Starting maintenance bypass check for " + playerName + " (" + playerUUID + ") ===");
            Text.sendDebugLog(INFO, "Vault Permission Service: " + (perms != null ? "Available" : "Not available"));
            Text.sendDebugLog(INFO, "Permission Cache: " + (permissionCache != null ? "Available" : "Not available"));
        }
    
        // Check if player is OP first (bypasses all permission checks)
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
            if (offlinePlayer != null && offlinePlayer.isOp()) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "Player is OP, allowing bypass");
                }
                return true;
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking operator status: " + e.getMessage());
        }
    
        // Check config bypass list
        if (plugin.getConfig().isList("maintenance.bypass")) {
            List<String> bypassList = plugin.getConfig().getStringList("maintenance.bypass");
            boolean inBypassList = bypassList.contains(playerUUID.toString()) || 
                                 (permissionCache != null && permissionCache.hasPermission(playerUUID, "core.lockdown.bypass")) || 
                                 bypassList.contains(playerName);
            
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "Config bypass list check - In list: " + inBypassList);
            }
            
            if (inBypassList) {
                return true;
            }
        }
    
        // If Vault is available, check permissions
        if (perms != null) {
            try {
                // ... existing Vault permission check code ...
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error checking Vault permissions for " + playerName + ": " + e.getMessage());
            }
        } else {
            Text.sendDebugLog(WARN, "Vault permission service not available! Falling back to database check.");
        }
    
        // Fall back to database/Vault (via PermissionCache) check
        try {
            boolean hasAlliumWildcard = permissionCache != null && permissionCache.hasPermission(playerUUID, "allium.*");
            boolean hasPerm = permissionCache != null && permissionCache.hasPermission(playerUUID, "allium.lockdown.bypass");
            boolean hasAlliumAdmin = permissionCache != null && permissionCache.hasPermission(playerUUID, "allium.admin");
            boolean hasCoreLockdownBypass = permissionCache != null && permissionCache.hasPermission(playerUUID, "core.lockdown.bypass");
            boolean hasCoreAdmin = permissionCache != null && permissionCache.hasPermission(playerUUID, "core.admin");
            boolean bypass = hasAlliumWildcard || hasCoreLockdownBypass || hasPerm || hasAlliumAdmin || hasCoreAdmin;
            
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "=== Permission Check (Vault/DB via PermissionCache) ===");
                Text.sendDebugLog(INFO, "allium.*: " + hasAlliumWildcard + 
                    ", allium.lockdown.bypass: " + hasPerm + 
                    ", allium.admin: " + hasAlliumAdmin + 
                    ", core.lockdown.bypass: " + hasCoreLockdownBypass + 
                    ", core.admin: " + hasCoreAdmin +
                    " (Total bypass: " + bypass + ")");
            }
            
            return bypass;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking database permissions: " + e.getMessage());
        }
    
        return false;
    }
    
    // Helper method to check permission with both UUID and name
    private boolean checkPermission(UUID playerUUID, String playerName, String permission) {
        if (perms == null) return false;
        
        try {
            // Try to get the player if online
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                if (player.hasPermission(permission)) {
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "Permission " + permission + " granted to online player " + playerName);
                    }
                    return true;
                }
            }
            
            // For offline players, check the permission cache
            if (permissionCache != null && permissionCache.hasPermission(playerUUID, permission)) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "Permission " + permission + " granted via permission cache for " + playerName);
                }
                return true;
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking permission " + permission + " for " + playerName + ": " + 
                (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        
        return false;
    }
    
    // Helper method to check and log permission status
    private void checkAndLogPermission(String permission, UUID playerUUID, String playerName) {
        if (perms == null) return;
        
        try {
            boolean hasAlliumWildcard = permissionCache.hasPermission(playerUUID, "allium.*");
            boolean uuidCheck = permissionCache.hasPermission(playerUUID, permission) || 
                              permissionCache.hasPermission(playerUUID, permission);
            
            boolean nameCheck = false;
            if (playerName != null) {
                nameCheck = permissionCache.hasPermission(playerUUID, permission) || 
                           permissionCache.hasPermission(playerUUID, permission);
            }
            
            Text.sendDebugLog(INFO, String.format("Permission %s - Wildcard: %s, UUID check: %s, Name check: %s",
                permission, hasAlliumWildcard, uuidCheck, nameCheck));
                
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking permission " + permission + ": " + 
                (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * Caches player permissions upon joining.
     *
     * @param event The PlayerJoinEvent.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        int delay = 20;
        // Defer by ~1s to allow services to initialize
        p.getScheduler().runDelayed(plugin, (io.papermc.paper.threadedregions.scheduler.ScheduledTask task) -> permissionCache.cachePermissions(p), null, delay);
    }
}