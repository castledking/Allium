package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * PlaceholderAPI expansion for general player-related placeholders
 * Supports placeholders like %allium_fly%, etc.
 */
public class GeneralPlaceholder extends PlaceholderExpansion {
    
    private final PluginStart plugin;
    private final Database database;
    
    public GeneralPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
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
    public boolean persist() {
        return true;
    }
    
    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }
        
        // Handle fly status placeholder: yes | no | temporary
        if (identifier.equals("fly")) {
            return getPlayerFlyStatus(player);
        }
        // Formatted tfly time remaining (e.g. 10m 30s)
        if (identifier.equals("fly_time")) {
            return getPlayerFlyTime(player);
        }
        
        // Handle god mode status placeholder
        if (identifier.equals("god")) {
            return getPlayerGodStatus(player);
        }
        
        // Handle reply target placeholder
        if (identifier.equals("reply")) {
            return getReplyTarget(player);
        }
        
        // Handle unread mail count placeholder
        if (identifier.equals("mail_unread")) {
            return String.valueOf(getUnreadMailCount(player));
        }
        
        // Handle unclaimed gifts count placeholder
        if (identifier.equals("mail_gifts")) {
            return String.valueOf(getUnclaimedGiftsCount(player));
        }
        
        // Handle display name placeholder
        if (identifier.equals("displayname")) {
            return getPlayerDisplayName(player);
        }
        
        return null;
    }
    
    /**
     * Get the player's fly status: yes (permanent fly on), no (fly off), temporary (tfly on).
     */
    private String getPlayerFlyStatus(Player player) {
        if (player == null) return "no";
        if (!player.getAllowFlight()) return "no";
        if (player.hasPermission("allium.fly")) return "yes";
        net.survivalfun.core.tfly.TFlyManager mgr = plugin.getTFlyManager();
        if (mgr != null && mgr.isTFlyEnabled(player.getUniqueId())) return "temporary";
        return "yes"; // allow flight on but no tfly state (e.g. spectator)
    }
    
    /**
     * Get formatted tfly time remaining (e.g. 10m 30s). For offline or no tfly, returns 0s.
     */
    private String getPlayerFlyTime(Player player) {
        if (player == null) return "0s";
        net.survivalfun.core.tfly.TFlyManager mgr = plugin.getTFlyManager();
        if (mgr == null) return "0s";
        long secs = mgr.getTFlyTime(player.getUniqueId());
        return net.survivalfun.core.tfly.TFlyManager.formatTime(secs);
    }
    
    /**
     * Get the player's god mode status (yes/no)
     */
    private String getPlayerGodStatus(OfflinePlayer player) {
        if (player == null) {
            return "no";
        }
        
        // Check if player is online
        if (player.isOnline()) {
            // For online players, check their PersistentDataContainer
            try {
                NamespacedKey key = new NamespacedKey(plugin, "godmode");
                Integer godMode = player.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
                
                // God mode is enabled if the value is 1
                return (godMode != null && godMode == 1) ? "yes" : "no";
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to retrieve god mode status for player " + player.getName() + ": " + e.getMessage());
                return "no";
            }
        } else {
            // For offline players, we cannot access PersistentDataContainer
            // God mode data is stored in the player's data file, not accessible when offline
            return "no";
        }
    }
    
    /**
     * Get the name of the player that the specified player can reply to
     */
    private String getReplyTarget(Player player) {
        try {
            // Get the Msg instance from the plugin
            net.survivalfun.core.commands.Msg msgCommand = plugin.getMsgCommand();
            if (msgCommand == null) {
                return "";
            }
            
            // Use reflection to access the lastMessageSender map
            java.lang.reflect.Field lastMessageSenderField = msgCommand.getClass().getDeclaredField("lastMessageSender");
            lastMessageSenderField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            Map<UUID, UUID> lastMessageSender = (Map<UUID, UUID>) lastMessageSenderField.get(msgCommand);
            
            // Get the UUID of the last player who messaged this player
            UUID lastSenderUUID = lastMessageSender.get(player.getUniqueId());
            if (lastSenderUUID == null) {
                return "";
            }
            
            // Get the player's name
            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(lastSenderUUID);
            return targetPlayer.getName() != null ? targetPlayer.getName() : "";
            
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to get reply target for player " + player.getName() + ": " + e.getMessage());
            return "";
        }
    }
    
    /**
     * Get the number of unread mail messages for a player
     */
    private String getUnreadMailCount(OfflinePlayer player) {
        if (player == null) return "0";
        try {
            String playerUUID = player.getUniqueId().toString();
            
            Map<String, Object> result = database.queryRow(
                "SELECT COUNT(*) AS count FROM mail_messages WHERE recipient = ? AND is_read = FALSE",
                playerUUID
            );
            
            return result != null && result.get("count") != null ? 
                   String.valueOf(result.get("count")) : "0";
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error getting unread mail count for " + 
                                      (player.getName() != null ? player.getName() : player.getUniqueId()), e);
            return "0";
        }
    }
    
    /**
     * Get the number of unclaimed gifts for a player
     */
    private String getUnclaimedGiftsCount(OfflinePlayer player) {
        if (player == null) return "0";
        try {
            String playerUUID = player.getUniqueId().toString();
            
            Map<String, Object> result = database.queryRow(
                "SELECT COUNT(*) AS count FROM mail_gifts WHERE recipient = ? AND is_claimed = FALSE",
                playerUUID
            );
            
            return result != null && result.get("count") != null ? 
                   String.valueOf(result.get("count")) : "0";
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error getting unclaimed gifts count for " + 
                                      (player.getName() != null ? player.getName() : player.getUniqueId()), e);
            return "0";
        }
    }
    
    /**
     * Get the player's display name with formatting
     */
    private String getPlayerDisplayName(Player player) {
        if (player == null) {
            return "";
        }
        
        try {
            // Get the display name component
            net.kyori.adventure.text.Component displayNameComponent = player.displayName();
            if (displayNameComponent == null) {
                return player.getName();
            }
            
            // Convert the component to a legacy string with formatting codes
            String displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(displayNameComponent);
            
            // If the display name is empty or just the player's name, return the player's name
            if (displayName.isEmpty() || displayName.equals(player.getName())) {
                return player.getName();
            }
            
            // Return the formatted display name
            return displayName;
            
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error getting display name for " + player.getName() + ": " + e.getMessage());
            return player.getName();
        }
    }

}
