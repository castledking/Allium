package codes.castled.allium.listeners.security;

import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.commands.PvpCommand;
import codes.castled.allium.commands.TP;
import codes.castled.allium.managers.DB.PlayerInventories;
import codes.castled.allium.managers.DB.Database.LocationType;
import codes.castled.allium.managers.core.Text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

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
        // Use delayed task to ensure we apply AFTER Essentials (and other plugins) set their display names
        // This gives Allium's /nick system precedence over EssentialsX /nick
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            String storedNick = plugin.getDatabase().getStoredPlayerDisplayname(playerUUID);
            if (storedNick != null && !storedNick.trim().isEmpty()) {
                // Delay to ensure Essentials' on-join displayname is set first, then we override it
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && plugin.getNicknameManager() != null) {
                        plugin.getNicknameManager().restoreDisplayNameFromStored(player, storedNick);
                    }
                }, 20L); // 1 second delay to ensure Essentials runs first
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
                        // Use LuckPerms API directly to ensure global context
                        // and clean up any world-scoped group.alt nodes
                        assignAltGroupGlobal(player);
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

    /**
     * Assigns the alt group to a player globally using the LuckPerms API directly.
     * Removes any world-scoped group.alt nodes first, then adds a global one.
     * Falls back to Vault API if LuckPerms is not available.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assignAltGroupGlobal(Player player) {
        try {
            // Try to use LuckPerms API directly for precise context control
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckPerms = Bukkit.getServicesManager().load((Class) luckPermsClass);

            if (luckPerms != null) {
                Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
                Object userFuture = userManager.getClass().getMethod("loadUser", java.util.UUID.class).invoke(userManager, player.getUniqueId());

                // Wait for the user to load (it's a CompletableFuture)
                Class<?> cfClass = Class.forName("java.util.concurrent.CompletableFuture");
                Object user = cfClass.getMethod("join").invoke(userFuture);

                // Get the user's nodes
                Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node");
                Class<?> nodeBuilderClass = Class.forName("net.luckperms.api.node.types.InheritanceNode");

                // Build a global group.alt node (no context)
                Object inheritanceNodeBuilder = nodeBuilderClass.getMethod("builder", String.class).invoke(null, "alt");
                Object globalNode = inheritanceNodeBuilder.getClass().getMethod("build").invoke(inheritanceNodeBuilder);

                // Remove any existing group.alt nodes (world-scoped or otherwise)
                java.util.Set<Object> toRemove = new java.util.HashSet<>();
                Collection<?> nodes = (Collection<?>) user.getClass().getMethod("getNodes").invoke(user);
                for (Object node : nodes) {
                    // Check if this is an inheritance node for "alt"
                    String key = (String) node.getClass().getMethod("getKey").invoke(node);
                    if (key.equals("group.alt")) {
                        toRemove.add(node);
                    }
                }

                boolean hadWorldScoped = !toRemove.isEmpty();
                for (Object node : toRemove) {
                    user.getClass().getMethod("removeNode", nodeClass).invoke(user, node);
                }

                // Add the global group.alt node
                user.getClass().getMethod("addNode", nodeClass).invoke(user, globalNode);

                // Save the user
                userManager.getClass().getMethod("saveUser", user.getClass()).invoke(userManager, user);

                if (hadWorldScoped) {
                    Text.sendDebugLog(INFO, "Cleaned up world-scoped alt group for " + player.getName() + " and reassigned globally");
                } else {
                    Text.sendDebugLog(INFO, "Assigned alt group globally for " + player.getName());
                }
                return;
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "LuckPerms API not available for global alt group assignment, falling back to Vault: " + t.getMessage());
        }

        // Fallback to Vault API
        Permission vaultPermission = plugin.getVaultPermission();
        if (vaultPermission != null && vaultPermission.hasGroupSupport()) {
            try {
                // Remove from any world-specific contexts first
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    String worldName = world.getName();
                    if (vaultPermission.playerInGroup(worldName, player.getName(), "alt")) {
                        vaultPermission.playerRemoveGroup(worldName, player.getName(), "alt");
                        Text.sendDebugLog(INFO, "Removed world-scoped alt group (" + worldName + ") for " + player.getName());
                    }
                }

                // Add globally (null world)
                if (!vaultPermission.playerInGroup((String) null, player.getName(), "alt")) {
                    boolean added = vaultPermission.playerAddGroup((String) null, player.getName(), "alt");
                    if (!added) {
                        Text.sendDebugLog(WARN, "Failed to add alt group globally for " + player.getName());
                    }
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error assigning alt group via Vault for " + player.getName(), e);
            }
        }
    }
}
