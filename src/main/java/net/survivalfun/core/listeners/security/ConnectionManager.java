package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.PvpCommand;
import net.survivalfun.core.commands.TP;
import net.survivalfun.core.managers.DB.Database.LocationType;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class ConnectionManager implements Listener {

    private final Map<UUID, Long> playerLoginTimes;
    private final PluginStart plugin;
    public ConnectionManager(PluginStart plugin) {
        this.plugin = plugin;
        this.playerLoginTimes = plugin.getPlayerLoginTimes();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        
        // Store login time for online duration tracking
        playerLoginTimes.put(playerUUID, System.currentTimeMillis());
        
        // Restore nickname from database on join.
        // TAB should only stop Bukkit display/player-list mutation, not the nickname restore itself.
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            String storedNick = plugin.getDatabase().getStoredPlayerDisplayname(playerUUID);
            if (storedNick != null && !storedNick.trim().isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && plugin.getNicknameManager() != null) {
                        plugin.getNicknameManager().restoreDisplayNameFromStored(player, storedNick);
                    }
                });
            }
        });
        
        // Update last seen date in database asynchronously
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            boolean success = plugin.getDatabase().updatePlayerLastSeen(playerUUID, playerName);
            if (!success) {
                Text.sendDebugLog(WARN, "Failed to update last seen date for player: " + playerName);
            }
        });

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            boolean pvpEnabled = plugin.getDatabase().getPvpEnabled(playerUUID);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    PvpCommand.setPvpEnabledState(plugin, player, pvpEnabled);
                }
            });
        });

        String ipAddress = getPlayerIpAddress(player);
        if (ipAddress != null) {
            Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
                plugin.getDatabase().savePlayerIp(playerUUID, playerName, ipAddress);

                if (!plugin.getDatabase().isPlayerAltOnIp(playerUUID, ipAddress)) {
                    return;
                }

                // Check if player is exempted from alt detection
                if (plugin.getDatabase().isPlayerAltExempt(playerUUID)) {
                    Text.sendDebugLog(INFO, "Player " + playerName + " is exempted from alt detection, skipping alt group assignment");
                    return;
                }

                Permission vaultPermission = plugin.getVaultPermission();
                if (vaultPermission == null || !vaultPermission.hasGroupSupport()) {
                    Text.sendDebugLog(WARN, "Unable to add alt group for " + playerName + ": Vault permissions group support is unavailable");
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    try {
                        // Add alt group globally (null world parameter) instead of player's current world
                        if (!vaultPermission.playerInGroup(playerName, "alt", null)) {
                            boolean added = vaultPermission.playerAddGroup(playerName, "alt", null);
                            if (!added) {
                                Text.sendDebugLog(WARN, "Failed to add alt group for " + playerName);
                            }
                        }
                    } catch (Exception e) {
                        Text.sendDebugLog(WARN, "Error while adding alt group for " + playerName, e);
                    }
                });
            });
        }
        
        // Load teleport toggle state asynchronously
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            try (Connection conn = plugin.getDatabase().getConnection();
                PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT state FROM player_teleport_toggle WHERE player_uuid = ?")) {
                
                pstmt.setString(1, playerUUID.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        boolean teleportToggleState = rs.getBoolean("state");
                        // Schedule sync task to update the player's state
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            TP tpInstance = plugin.getTpInstance();
                            if (tpInstance != null) {
                                tpInstance.setTeleportToggled(playerUUID, teleportToggleState);
                                Text.sendDebugLog(INFO, "Loaded teleport toggle state for " + playerName + ": " + teleportToggleState);
                            }
                        });
                    } else {
                        Text.sendDebugLog(INFO, "No teleport toggle state found for " + playerName + " (UUID: " + playerUUID + ")");
                    }
                }
            } catch (SQLException e) {
                Text.sendDebugLog(ERROR, "Error loading teleport toggle state for " + playerName + " (UUID: " + playerUUID + ")", e);
            }
        });

        // --- Start of new logic for 'players-to-redeem' ---
        player.getScheduler().runDelayed(plugin, (task) -> {
            java.util.List<String> playersToRedeemEntries = plugin.getConfig().getStringList("players-to-redeem");
            if (playersToRedeemEntries == null || playersToRedeemEntries.isEmpty()) {
                return;
            }

            String playerUuidString = player.getUniqueId().toString();

            for (String entry : playersToRedeemEntries) {
                if (entry == null || entry.trim().isEmpty()) continue;
                String[] parts = entry.split(":", 2);
                if (parts.length != 2) {
                    Text.sendDebugLog(WARN, "[RedeemOnJoin] Invalid entry in 'players-to-redeem': " + entry + ". Expected format: UUID:rank_name");
                    continue;
                }

                String entryUuid = parts[0].trim();
                String rankName = parts[1].trim().toLowerCase();

                if (playerUuidString.equals(entryUuid)) {
                    Text.sendDebugLog(INFO, "[RedeemOnJoin] Player " + playerName + " (UUID: " + playerUuidString + ") found in players-to-redeem for rank: " + rankName);
                    String welcomeMsgFormat = plugin.getLangManager().get("redeem.join");
                    String welcomeMsg = welcomeMsgFormat.replace("{name}", playerName).replace("{rank}", rankName);
                    player.sendMessage(Text.colorize(welcomeMsg));
                }
            }
        }, null, 20L); 
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        
        // Remove from login times map
        playerLoginTimes.remove(playerUUID);
        
        // Update last seen date in database when player quits
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            boolean success = plugin.getDatabase().updatePlayerLastSeen(playerUUID, playerName);
            if (!success) {
                Text.sendDebugLog(WARN, "Failed to update last seen date for player: " + playerName);
            }
        });
        // Save gamemode when player quits
        try {
            plugin.getDatabase().savePlayerGameMode(playerUUID, playerName, player.getGameMode());
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error saving gamemode for " + playerName + " (UUID: " + playerUUID + ")", e);
        }
        // Save logout location for offline whois command
        Location logoutLocation = player.getLocation();
        plugin.getDatabase().savePlayerLocation(player.getUniqueId(), LocationType.LOGOUT, logoutLocation, System.currentTimeMillis());
        Text.sendDebugLog(INFO, "Saved logout location to database for " + player.getName() +
            " at " + logoutLocation.getWorld().getName() + " (" +
            String.format("%.2f, %.2f, %.2f", logoutLocation.getX(), logoutLocation.getY(), logoutLocation.getZ()) + ")");
    }

    private String getPlayerIpAddress(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }
}
