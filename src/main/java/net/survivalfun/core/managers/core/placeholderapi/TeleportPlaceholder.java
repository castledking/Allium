package net.survivalfun.core.managers.core.placeholderapi;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.jobs.Death;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

public class TeleportPlaceholder extends PlaceholderExpansion {
    private final PluginStart plugin;
    private final Database database;
    private final Map<UUID, Long> teleportCooldowns;
    private final Map<UUID, Long> teleportRequestTimestamps;
    private final Map<UUID, UUID> teleportRequests;

    public TeleportPlaceholder(PluginStart plugin, Map<UUID, Long> teleportCooldowns, 
                             Map<UUID, Long> teleportRequestTimestamps, 
                             Map<UUID, UUID> teleportRequests) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.teleportCooldowns = teleportCooldowns;
        this.teleportRequestTimestamps = teleportRequestTimestamps;
        this.teleportRequests = teleportRequests;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Towkio";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        UUID playerId = player.getUniqueId();

        switch (params.toLowerCase()) {
            case "teleport_cooldown":
                return String.valueOf(getRemainingCooldown(playerId));
            case "teleport_expires":
                return getRequestExpires(playerId);
            case "teleport_requests":
                return String.valueOf(getPendingRequestsCount(playerId));
            case "teleport_toggled":
                return isTeleportToggled(playerId) ? "true" : "false";
            case "back_location":
                return getBackLocation(playerId);
            default:
                return null;
        }
    }

    private long getRemainingCooldown(UUID playerId) {
        if (!teleportCooldowns.containsKey(playerId)) {
            return 0;
        }
        long cooldownEnd = teleportCooldowns.get(playerId);
        long remaining = (cooldownEnd - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    private String getRequestExpires(UUID playerId) {
        if (!teleportRequestTimestamps.containsKey(playerId)) {
            return "0";
        }
        long expirationTime = teleportRequestTimestamps.get(playerId) + (120 * 1000L); // 2 minutes expiration
        long remaining = (expirationTime - System.currentTimeMillis()) / 1000;
        return String.valueOf(Math.max(0, remaining));
    }

    private int getPendingRequestsCount(UUID playerId) {
        // Count how many requests this player has sent or received
        return (int) teleportRequests.entrySet().stream()
                .filter(entry -> entry.getKey().equals(playerId) || entry.getValue().equals(playerId))
                .count();
    }

    private boolean isTeleportToggled(UUID playerId) {
        return plugin.getTpInstance().isTeleportToggled(playerId);
    }

    private String getBackLocation(UUID playerId) {
        Location targetLocation = null;
        
        // First check for death location (same logic as back command)
        if (plugin.getServer().getPlayer(playerId) != null) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.hasPermission("allium.back.ondeath")) {
                Location deathLocation = Death.getDeathLocation(player);
                if (deathLocation != null) {
                    long deathTime = Death.getDeathTime(playerId);
                    if (deathTime > 0 && System.currentTimeMillis() - deathTime <= 120000) {
                        targetLocation = deathLocation;
                    }
                }
            }
        }
        
        // If no death location, fall back to teleport location
        if (targetLocation == null) {
            targetLocation = database.getPlayerLocation(playerId, LocationType.TELEPORT);
        }
        
        if (targetLocation == null) {
            return "none";
        }
        
        return String.format("%s, %d, %d, %d", 
            targetLocation.getWorld().getName(),
            targetLocation.getBlockX(),
            targetLocation.getBlockY(),
            targetLocation.getBlockZ()
        );
    }
}