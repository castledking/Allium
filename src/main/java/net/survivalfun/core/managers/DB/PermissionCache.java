package net.survivalfun.core.managers.DB;

import net.milkbowl.vault2.permission.Permission;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PermissionCache {
    private final Database database;
    private final PluginStart plugin;
    private final Map<UUID, Map<String, Boolean>> permissionCache = new HashMap<>();

    public PermissionCache(Database database) {
        this.database = database;
        this.plugin = (PluginStart) Bukkit.getPluginManager().getPlugin("Allium");
    }

    private Permission getVaultPermission() {
        RegisteredServiceProvider<Permission> rsp = Bukkit.getServicesManager().getRegistration(Permission.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public void cachePermissions(Player player) {
        UUID playerId = player.getUniqueId();
        Map<String, Boolean> permissions = new HashMap<>();
        Permission vaultPermission = getVaultPermission();

        // Cache critical permissions
        String[] criticalPermissions = {"core.lockdown.bypass", "core.admin"};
        for (String perm : criticalPermissions) {
            boolean hasPerm;
            if (vaultPermission != null) {
                hasPerm = vaultPermission.playerHas(null, player, perm);
            } else {
                hasPerm = database.playerHasPermission(playerId, perm);
            }
            permissions.put(perm, hasPerm);
        }

        permissionCache.put(playerId, permissions);
        plugin.getLogger().info("Cached permissions for player: " + player.getName());
    }

    public boolean hasPermission(UUID playerId, String permission) {
        Map<String, Boolean> permissions = permissionCache.get(playerId);
        if (permissions != null && permissions.containsKey(permission)) {
            return permissions.get(permission);
        }

        // Fallback to Vault or Database
        Permission vaultPermission = getVaultPermission();
        if (vaultPermission != null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            boolean hasPerm = vaultPermission.playerHas(null, player, permission);
            permissionCache.computeIfAbsent(playerId, k -> new HashMap<>()).put(permission, hasPerm);
            return hasPerm;
        } else {
            boolean hasPerm = database.playerHasPermission(playerId, permission);
            permissionCache.computeIfAbsent(playerId, k -> new HashMap<>()).put(permission, hasPerm);
            return hasPerm;
        }
    }

    public void clearCache(UUID playerId) {
        permissionCache.remove(playerId);
    }

    public void clearAllCaches() {
        permissionCache.clear();
    }
}