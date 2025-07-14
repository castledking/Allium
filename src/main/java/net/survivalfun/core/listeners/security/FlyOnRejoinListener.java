package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.commands.teleportation.TP;

public class FlyOnRejoinListener implements Listener {
    private final PluginStart plugin;
    private final Database database;
    private final Set<UUID> playersWithslowFallingOnQuit = new HashSet<>();

    public FlyOnRejoinListener(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGamemodeSwitch(PlayerGameModeChangeEvent event) {
        GameMode newGamemode = event.getNewGameMode();
        GameMode oldGamemode = event.getPlayer().getGameMode();
        Player player = event.getPlayer();
        boolean debugMode = plugin.getConfig().getBoolean("debug-mode");

        // Check if player is switching to Creative mode with slow falling active
        if (newGamemode == GameMode.CREATIVE && player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            // Remove slow falling effect
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);
            
            // Cancel any running landing check tasks for this player
            Bukkit.getScheduler().cancelTasks(plugin);
            
            // Enable flight for creative mode
            player.setAllowFlight(true);
            player.setFlying(true);
            
            if (debugMode) {
                plugin.getLogger().info("Removed slow falling from " + player.getName() + 
                        " and enabled flight due to Creative mode");
            }
            return;
        }

        
        // Check if player is switching from Creative to Survival
        if (newGamemode == GameMode.SURVIVAL && oldGamemode == GameMode.CREATIVE) {
            // If player is not on the ground, apply slow falling
            if (!player.isOnGround()) {
                // Apply slow falling effect
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING
                        , 20 * 60 * 5, 0, false, true, true));

                // Start the landing check task
                startSlowFallLandingCheck(player);

                if (debugMode) {
                    plugin.getLogger().info("Applied slow falling to " + player.getName() +
                            " after switching from Creative to Survival while in air");
                }
            } else if (player.hasPermission("core.gamemode.creative")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE
                        , 20 * 3, 0, false, true, true));
            }
            
            // Enable flight if player has permission
            if (player.hasPermission("core.fly")) {
                player.setAllowFlight(true);
                if (debugMode) {
                    plugin.getLogger().info("Enabled flight for " + player.getName() + 
                            " after switching to Survival (core.fly permission)");
                }
            }

            // If the player has the permission for flight, enable flight
            if (player.hasPermission("core.fly")) {
                player.setAllowFlight(true);
            }
        }
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayerState(player);
        if(!player.hasPermission("core.fly")) {
            return;
        }

        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            playersWithslowFallingOnQuit.add(player.getUniqueId());
            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().log(Level.INFO, "Player " + player.getName()
                        + " has logged out with Slow Falling effect");
            }
        }

        // Save logout location for offline whois command
        Location logoutLocation = player.getLocation();
        database.savePlayerLocation(player.getUniqueId(), LocationType.LOGOUT, logoutLocation, System.currentTimeMillis());
        if(plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().fine("Saved logout location to database for " + player.getName() + 
                " at " + logoutLocation.getWorld().getName() + " (" + 
                String.format("%.2f, %.2f, %.2f", logoutLocation.getX(), logoutLocation.getY(), logoutLocation.getZ()) + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean debugMode = plugin.getConfig().getBoolean("debug-mode");

        Boolean teleportToggleState = database.getTeleportToggleState(uuid);
        if (teleportToggleState != null) {
            // Get the TP instance from the plugin
            TP tpInstance = ((PluginStart) plugin).getTpInstance();
            if (tpInstance != null) {
                // Set the teleport toggle state based on database value
                tpInstance.setTeleportToggled(uuid, teleportToggleState);
                
                if (debugMode) {
                    plugin.getLogger().fine("Loaded teleport toggle state for " + player.getName() + 
                                        ": " + teleportToggleState);
                }
            }
        }

        // Check if player was in spectator mode when they quit
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // Restore flight state for spectators
            player.setAllowFlight(true);
            player.setFlying(true);
            if (debugMode) plugin.getLogger().log(Level.INFO, "Restored flight for spectator: " + player.getName());
            return;
        }

        // Handle creative mode players
        if (player.getGameMode() == GameMode.CREATIVE) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.setAllowFlight(true);
                player.setFlying(true);
                if (debugMode) {
                    plugin.getLogger().fine("Ensured flight is enabled for " + player.getName() + " in creative mode");
                }
            }, 5L);
            return;
        }

        // Handle spectator mode without permission
        if (player.getGameMode() == GameMode.SPECTATOR && !player.hasPermission("core.gamemode.spectator")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                        .replace("{gamemode}", GameMode.SURVIVAL.toString()));
                
                if (debugMode) {
                    plugin.getLogger().info("Reset " + player.getName() + "'s gamemode from SPECTATOR to SURVIVAL (no permission)");
                }
                teleportToSavedLocation(player);
            }, 5L);
            return;
        }

        // Handle slow falling from previous session
        if (playersWithslowFallingOnQuit.remove(uuid) && !player.isOnGround() && player.hasPermission("core.fly")) {
            if (debugMode) {
                plugin.getLogger().info("Player " + player.getName() + " has logged in with Slow Falling effect");
            }
            applySlowFallingUntilLanded(player);
        }

        // Handle flight status restoration for survival/adventure modes
        if (player.hasPermission("core.fly") && 
            (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Database.PlayerFlightData flightData = database.getPlayerFlightStatus(uuid);
                if (flightData == null) return;

                player.setAllowFlight(flightData.allowFlight());
                
                if (flightData.isFlying()) {
                    player.setFlying(true);
                } else if (flightData.allowFlight() && !player.isOnGround()) {
                    // Player wasn't flying but had flight enabled and is in the air
                    applySlowFallingUntilLanded(player);
                }
                
                if (debugMode) {
                    plugin.getLogger().fine("Restored flight status for " + player.getName() + " from database");
                }
            }, 5L);
        }

        // Restore spectator gamemode
        String savedGamemode = database.getPlayerGamemode(uuid);
        if ("SPECTATOR".equals(savedGamemode) && 
            player.hasPermission("core.gamemode.spectator")) {
            player.setGameMode(GameMode.SPECTATOR);
        }

        // --- Start of new logic for 'players-to-redeem' (Revised based on new config structure) ---
        // This runs for every player on join.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            java.util.List<String> playersToRedeemEntries = plugin.getConfig().getStringList("players-to-redeem");
            if (playersToRedeemEntries == null || playersToRedeemEntries.isEmpty()) {
                return; // No players to process or config section missing
            }

            String playerUuidString = player.getUniqueId().toString();
            String playerName = player.getName();

            for (String entry : playersToRedeemEntries) {
                if (entry == null || entry.trim().isEmpty()) continue;
                String[] parts = entry.split(":", 2);
                if (parts.length != 2) {
                    if (debugMode) {
                        plugin.getLogger().warning("[RedeemOnJoin] Invalid entry in 'players-to-redeem': " + entry + ". Expected format: UUID:rank_name");
                    }
                    continue;
                }

                String entryUuid = parts[0].trim();
                String rankName = parts[1].trim().toLowerCase(); // Ensure lowercase for consistency

                if (playerUuidString.equals(entryUuid)) {
                    if (debugMode) {
                        plugin.getLogger().info("[RedeemOnJoin] Player " + playerName + " (UUID: " + playerUuidString + ") found in players-to-redeem for rank: " + rankName);
                    }
                    String welcomeMsgFormat = plugin.getLangManager().get("redeem.join");
                                String welcomeMsg = welcomeMsgFormat.replace("{name}", playerName).replace("{rank}", rankName);
                                player.sendMessage(Text.colorize(welcomeMsg));

                }
            }
        }, 20L); 
    }

    /**
     * Saves flight status and spectator gamemode for all online players
     */
    public void saveAllPlayersState() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayerState(player);
        }
    }

    /**
     * Saves a player's flight status and gamemode if they're in spectator mode
     *
     * @param player The player to save state for
     */
    private void savePlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        boolean canFly = player.hasPermission("core.fly");
        
        // Save flight status if player has flight permission
        if (canFly) {
            database.savePlayerFlightStatus(uuid, player.getAllowFlight());
        }
        
        // Save spectator gamemode if player has permission and is in spectator mode
        if (player.hasPermission("core.gamemode.spectator") && 
            player.getGameMode() == GameMode.SPECTATOR) {
            database.savePlayerGamemode(uuid, "SPECTATOR");
        }
    }

    /**
     * Applies slow falling to a player until they reach the ground
     * @param player The player to apply slow falling to
     */
    public void applySlowFallingUntilLanded(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player == null || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                // Check if the player is on the ground
                if (player.isOnGround()) {
                    player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                    this.cancel();
                    return;
                }

                // Apply slow falling
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOW_FALLING,
                        100, // 5 seconds at 20 ticks per second
                        0,
                        false,
                        false,
                        true
                ));
            }
        }.runTaskTimer(plugin, 0L, 5L); // Check every 5 ticks (0.25 seconds)
    }

    /**
     * Starts a task to check when a player with slow falling lands
     * @param player The player to check
     */
    private void startSlowFallLandingCheck(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    // Player is offline, save their state for when they return
                    playersWithslowFallingOnQuit.add(player.getUniqueId());
                    cancel();
                    return;
                }

                if (player.isOnGround()) {
                    // Player has landed, remove the slow falling effect
                    player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE
                            , 20 * 3, 0, false, true, true));
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 5L, 5L); // Check every 5 ticks (0.25 seconds)
    }

    /**
     * Teleports a player to their saved location from spectator mode
     */
    private void teleportToSavedLocation(Player player) {
        UUID playerUUID = player.getUniqueId();

        try {
            // Query the database for the player's location
            Map<String, Object> locationData = database.queryRow(
                    "SELECT world, x, y, z, yaw, pitch FROM player_spectator_locations WHERE player_uuid = ?",
                    playerUUID.toString()
            );

            if (locationData != null && !locationData.isEmpty()) {
                String worldName = (String) locationData.get("world");
                double x = ((Number) locationData.get("x")).doubleValue();
                double y = ((Number) locationData.get("y")).doubleValue();
                double z = ((Number) locationData.get("z")).doubleValue();
                float yaw = ((Number) locationData.get("yaw")).floatValue();
                float pitch = ((Number) locationData.get("pitch")).floatValue();

                // Get the world by name
                org.bukkit.World world = plugin.getServer().getWorld(worldName);

                if (world != null) {
                    // Create the location
                    Location location = new Location(world, x, y, z, yaw, pitch);

                    // Ensure the chunk is loaded for safe teleportation
                    location.getChunk().load();

                    // Teleport the player
                    player.teleport(location);
                    if(plugin.getConfig().getBoolean("debug-mode")) {
                        plugin.getLogger().fine("Teleported " + player.getName()
                                + " to their saved location after spectator reset");
                    }
                } else {
                    plugin.getLogger().warning("Could not find world " + worldName +
                            " for player " + player.getName() + " during spectator reset");
                }
            } else {
                // If no saved location, just teleport to spawn
                player.teleport(player.getWorld().getSpawnLocation());
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("No saved location found for " + player.getName() +
                            ", teleported to spawn after spectator reset");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to teleport " + player.getName()
                    + " after spectator reset", e);
            // Fallback to spawn teleport
            player.teleport(player.getWorld().getSpawnLocation());
        }
    }
}
