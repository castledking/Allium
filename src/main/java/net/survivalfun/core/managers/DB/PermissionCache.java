package net.survivalfun.core.managers.DB;

import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PermissionCache {
    private final Database database;
    private final PluginStart plugin;
    private final Map<UUID, Map<String, Boolean>> permissionCache = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public PermissionCache(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    private Permission getVaultPermission() {
        return plugin.getVaultPermission();
    }

    private void updateCache(UUID playerId, String permission, boolean hasPermission) {
        lock.writeLock().lock();
        try {
            permissionCache.computeIfAbsent(playerId, k -> new HashMap<>())
                          .put(permission, hasPermission);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @SuppressWarnings("deprecation")
    public void cachePermissions(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        
        // Use the global region scheduler for async tasks in Folia
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            try {
                Permission vaultPermission = getVaultPermission();
                
                // Cache critical permissions
                String[] criticalPermissions = {"allium.lockdown.bypass", "allium.admin"};
                
                // Use CompletableFuture to run database operations async
                CompletableFuture<Map<String, Boolean>> future = CompletableFuture.supplyAsync(() -> {
                    Map<String, Boolean> permissions = new HashMap<>();
                    
                    for (String perm : criticalPermissions) {
                        boolean hasPerm = false;
                        try {
                            // Check if player is still online
                            Player onlinePlayer = Bukkit.getPlayer(playerId);
                            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                                return null; // Player went offline, abort
                            }
                            
                            // Use the synchronous version with the online player
                            if (vaultPermission != null) {
                                hasPerm = vaultPermission.playerHas("", onlinePlayer, perm);
                            } else {
                                // Run database operation async to avoid blocking
                                try {
                                    hasPerm = database.playerHasPermission(playerId, perm);
                                } catch (Exception dbEx) {
                                    if (plugin.isDebugMode()) {
                                        Text.sendDebugLog(WARN, "Database permission check failed for " + perm + ": " + dbEx.getMessage());
                                    }
                                    hasPerm = false;
                                }
                            }
                        } catch (Exception e) {
                            if (plugin.isDebugMode()) {
                                Text.sendDebugLog(WARN, "Error checking permission " + perm + " for " + playerName + ": " + e.getMessage());
                            }
                        }
                        permissions.put(perm, hasPerm);
                    }
                    return permissions;
                }, executorService);
                
                // Handle the result when complete
                future.thenAccept(permissions -> {
                    if (permissions != null) {
                        // Update the cache with write lock
                        lock.writeLock().lock();
                        try {
                            permissionCache.put(playerId, permissions);
                            Text.sendDebugLog(INFO, "Cached permissions for player: " + playerName);
                        } finally {
                            lock.writeLock().unlock();
                        }
                    }
                });
                
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to cache permissions for " + playerName, e);
            }
        }, 1L); // 1 tick delay to ensure we're on the right thread
    }

    @SuppressWarnings("deprecation")
    public boolean hasPermission(UUID playerId, String permission) {
        // Try read lock first to check cache
        lock.readLock().lock();
        try {
            Map<String, Boolean> permissions = permissionCache.get(playerId);
            if (permissions != null && permissions.containsKey(permission)) {
                return permissions.get(permission);
            }
        } finally {
            lock.readLock().unlock();
        }

        // If not in cache, do a direct check (this should be rare)
        Permission vaultPermission = getVaultPermission();
        boolean hasPermission;
        
        try {
            if (vaultPermission != null) {
                // For offline players during login events, try UUID-based check first
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    hasPermission = vaultPermission.playerHas("", player, permission);
                } else {
                    // For offline players, try UUID-based check with Vault using OfflinePlayer
                    try {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
                        hasPermission = vaultPermission.playerHas("", offlinePlayer, permission);
                        Text.sendDebugLog(INFO, "PermissionCache OfflinePlayer check for " + permission + " for " + playerId + " result: " + hasPermission);
                    } catch (Exception e) {
                        Text.sendDebugLog(WARN, "Vault OfflinePlayer check failed for " + permission + " falling back to database: " + e.getMessage());
                        try {
                            hasPermission = database.playerHasPermission(playerId, permission);
                        } catch (Exception dbEx) {
                            if (plugin.isDebugMode()) {
                                Text.sendDebugLog(WARN, "Database permission check failed for " + permission + ": " + dbEx.getMessage());
                            }
                            hasPermission = false;
                        }
                    }
                }
            } else {
                try {
                    // Fallback to database if Vault is not available
                    hasPermission = database.playerHasPermission(playerId, permission);
                } catch (Exception dbEx) {
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(WARN, "Database permission check failed for " + permission + ": " + dbEx.getMessage());
                    }
                    hasPermission = false;
                }
            }
            
            // Update cache with the result
            updateCache(playerId, permission, hasPermission);
            return hasPermission;
            
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking permission " + permission + " for " + playerId + ": " + e.getMessage());
            return false;
        }
    }

    public void clearCache(UUID playerId) {
        permissionCache.remove(playerId);
    }

    public void clearAllCaches() {
        permissionCache.clear();
    }
}