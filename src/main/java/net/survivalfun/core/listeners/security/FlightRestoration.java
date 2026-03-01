package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.survivalfun.core.managers.core.Text;

public class FlightRestoration implements Listener {
    private final PluginStart plugin;
    private final Database database;
    private final Set<UUID> playersWithslowFallingOnQuit = new HashSet<>();
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> slowFallTasks = new ConcurrentHashMap<>();

    public FlightRestoration(PluginStart plugin) {
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
            
            // Cancel any running landing check task for this player
            io.papermc.paper.threadedregions.scheduler.ScheduledTask t = slowFallTasks.remove(player.getUniqueId());
            if (t != null) t.cancel();
            
            // Enable flight for creative mode
            player.setAllowFlight(true);
            player.setFlying(true);
            
            if (debugMode) {
                Text.sendDebugLog(INFO, "Removed slow falling from " + player.getName() + 
                        " and enabled flight due to Creative mode");
            }
            return;
        }

        
        // Check if player is switching from Creative to Survival
        if ((newGamemode == GameMode.SURVIVAL || newGamemode == GameMode.ADVENTURE) && oldGamemode == GameMode.CREATIVE) {
            // If player is not on the ground, apply slow falling
            if (!player.isOnGround()) {
                applyOrRefreshSlowFalling(player);
                startSlowFallLandingCheck(player);

                if (debugMode) {
                    Text.sendDebugLog(INFO, "Applied slow falling to " + player.getName() +
                            " after switching from Creative to Survival while in air");
                }
            } else if (player.hasPermission("allium.gamemode.creative")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE
                        , 20 * 3, 0, false, true, true));
            }
            
            // Enable flight if player has permission
            if (player.hasPermission("allium.fly")) {
                player.setAllowFlight(true);
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Enabled flight for " + player.getName() + 
                            " after switching to Survival (allium.fly permission)");
                }
            }

            // If the player has the permission for flight, enable flight
            if (player.hasPermission("allium.fly")) {
                player.setAllowFlight(true);
            }
        }
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Always save player state regardless of permissions
        savePlayerState(player);
        
        if(!player.hasPermission("allium.fly")) {
            return;
        }
        
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            playersWithslowFallingOnQuit.add(player.getUniqueId());
            Text.sendDebugLog(INFO, player.getName() + " has logged out with Slow Falling effect");
        }
        
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();


        // Check if player was in spectator mode when they quit
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // Restore flight state for spectators - spectators always can fly
            player.setAllowFlight(true);
            try {
                player.setFlying(true);
            } catch (IllegalArgumentException e) {
                Text.sendDebugLog(WARN, "Could not set flying for spectator " + player.getName() + ": " + e.getMessage());
            }
            Text.sendDebugLog(INFO, "Restored flight for spectator: " + player.getName());
            return;
        }

        // Handle creative mode players
        if (player.getGameMode() == GameMode.CREATIVE) {
            if (!player.hasPermission("allium.fly")) {
                player.getScheduler().runDelayed(plugin, (task) -> {
                    player.setAllowFlight(false);
                    player.setFlying(false);
                    Text.sendDebugLog(INFO, "Disabled flight for creative player without allium.fly permission: " + player.getName() + " (vanilla behavior)");
                }, null, 5L);
                return;
            }
            player.getScheduler().runDelayed(plugin, (task) -> {
                player.setAllowFlight(true);
                try {
                    player.setFlying(true);
                } catch (IllegalArgumentException e) {
                    Text.sendDebugLog(WARN, "Could not set flying for creative player " + player.getName() + ": " + e.getMessage());
                }
                Text.sendDebugLog(INFO, "Ensured flight is enabled for " + player.getName() + " in creative mode");
            }, null, 5L);
            return;
        }

        // Handle spectator mode without permission
        if (player.getGameMode() == GameMode.SPECTATOR && !player.hasPermission("allium.gamemode.spectator")) {
            player.getScheduler().runDelayed(plugin, (task) -> {
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                        .replace("{gamemode}", GameMode.SURVIVAL.toString()));
                
                Text.sendDebugLog(INFO, "Reset " + player.getName() + "'s gamemode from SPECTATOR to SURVIVAL (no permission)");
                teleportToSavedLocation(player);
            }, null, 5L);
            return;
        }

        // Handle flight status restoration for survival/adventure modes
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            // Handle players with allium.fly permission
            if (player.hasPermission("allium.fly")) {
                Database.PlayerFlightData flightData = database.getPlayerFlightStatus(uuid);
                
                
                if (flightData != null) {
                    Text.sendDebugLog(INFO, "Retrieved flight data for " + player.getName() + 
                        " - Flying: " + flightData.isFlying() + ", AllowFlight: " + flightData.allowFlight());
                } else {
                    Text.sendDebugLog(INFO, "No flight data found for " + player.getName());
                }
                

                // Only restore flight if player was actually flying when they logged out (vanilla behavior)
                if (flightData != null && flightData.isFlying() && !player.isOnGround()) {
                    // Enable flight and restore flying state INSTANTLY to prevent falling
                    player.setAllowFlight(true);
                    try {
                        player.setFlying(true);
                        Text.sendDebugLog(INFO, "Successfully restored flying state instantly for " + player.getName());
                    } catch (IllegalArgumentException e) {
                        Text.sendDebugLog(WARN, "Could not restore flying state for " + player.getName() + ": " + e.getMessage());
                        // Fallback to slow falling if flying cannot be set
                        applySlowFallingUntilLanded(player);
                        Text.sendDebugLog(INFO, "Applied slow falling fallback for " + player.getName());
                    }
                } else {
                    // Vanilla behavior: flight disabled on join unless they were flying
                    player.setAllowFlight(false);
                    player.setFlying(false);
                    
                    Text.sendDebugLog(INFO, "Vanilla behavior: flight disabled for " + player.getName() + " (allium.fly permission, but wasn't flying)");
                }
            } else {
                // Players without allium.fly permission - vanilla behavior (no slow falling protection)
                player.setAllowFlight(false);
                player.setFlying(false);
                

                Text.sendDebugLog(INFO, "Disabled flight for " + player.getName() + " (no allium.fly permission, vanilla behavior)");
            
            }

            Text.sendDebugLog(INFO, "Flight restoration completed for " + player.getName());
        }

        // Restore spectator gamemode only when player actually joined in spectator and has permission
        try {
            GameMode savedGamemode = database.getPlayerGameMode(uuid);
            if (savedGamemode == GameMode.SPECTATOR) {
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    if (player.hasPermission("allium.gamemode.spectator")) {
                        Text.sendDebugLog(INFO, "Confirmed spectator state for " + player.getName());
                    } else {
                        player.setGameMode(GameMode.SURVIVAL);
                        player.setAllowFlight(false);
                        player.setFlying(false);
                        Text.sendDebugLog(INFO, "Cleared unauthorized spectator state for " + player.getName());
                    }
                } else {
                    Text.sendDebugLog(INFO, "Removing stale spectator state for " + player.getName());
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error loading saved gamemode for " + player.getName() + " (UUID: " + uuid + ")", e);
        }
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
        String playerName = player.getName();
        boolean canFly = player.hasPermission("allium.fly");
        boolean debugMode = plugin.getConfig().getBoolean("debug-mode");
    
        // Save flight status if player has flight permission
        if (canFly) {
            database.savePlayerFlightStatus(player);
            if (debugMode) {
                Text.sendDebugLog(INFO, "Saved flight state for " + player.getName() +
                    " - Flying: " + player.isFlying() + ", AllowFlight: " + player.getAllowFlight());
            }
        }
    
        // Save spectator gamemode if player has permission and is in spectator mode
        if (player.hasPermission("allium.gamemode.spectator") &&
            player.getGameMode() == GameMode.SPECTATOR) {
            try {
                database.savePlayerGameMode(uuid, playerName, GameMode.SPECTATOR);

                if (debugMode) {
                    Text.sendDebugLog(INFO, "Saved spectator gamemode for " + player.getName());
                }
            } catch (SQLException e) {
                Text.sendDebugLog(ERROR, "Error saving spectator gamemode for " + player.getName() + " (UUID: " + uuid + ")", e);
            }
        }
    }

    /**
     * Applies slow falling to a player until they reach the ground
     * @param player The player to apply slow falling to
     */
    public void applySlowFallingUntilLanded(Player player) {
        removeExistingSlowFallTask(player.getUniqueId());

        applyOrRefreshSlowFalling(player);
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                (t) -> {
                    if (!player.isOnline()) {
                        t.cancel();
                        return;
                    }

                    if (player.isOnGround()) {
                        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                        t.cancel();
                        return;
                    }

                    applyOrRefreshSlowFalling(player);
                },
                null,
                1L,
                40L
        );
        slowFallTasks.put(player.getUniqueId(), task);
    }

    private void applyOrRefreshSlowFalling(Player player) {
        if (!player.isOnline()) {
            return;
        }

        PotionEffect current = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current == null || current.getDuration() <= 40) {
            // Must run on entity's region for Folia - addPotionEffect modifies entity state
            SchedulerAdapter.runAtEntity(player, () -> {
                if (player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOW_FALLING,
                            20 * 60,
                            0,
                            false,
                            false,
                            true
                    ));
                }
            });
        }
    }

    private void removeExistingSlowFallTask(UUID uuid) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask existing = slowFallTasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }
    }

    /**
     * Starts a task to check when a player with slow falling lands
     * @param player The player to check
     */
    private void startSlowFallLandingCheck(Player player) {
        // Cancel existing task for this player first
        io.papermc.paper.threadedregions.scheduler.ScheduledTask existing = slowFallTasks.remove(player.getUniqueId());
        if (existing != null) existing.cancel();

        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = player.getScheduler().runAtFixedRate(
            plugin,
            (t) -> {
                if (!player.isOnline()) {
                    // Player is offline, save their state for when they return
                    playersWithslowFallingOnQuit.add(player.getUniqueId());
                    t.cancel();
                    return;
                }

                if (player.isOnGround()) {
                    // Player has landed, remove the slow falling effect
                    player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE
                            , 20 * 3, 0, false, true, true));
                    t.cancel();
                }
            },
            null,
            5L,
            5L
        );
        slowFallTasks.put(player.getUniqueId(), task);
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
                    Text.sendDebugLog(INFO, "Teleported " + player.getName()
                            + " to their saved location after spectator reset");
                } else {
                    Text.sendDebugLog(WARN, "Could not find world " + worldName +
                            " for player " + player.getName() + " during spectator reset");
                }
            } else {
                // If no saved location, just teleport to spawn
                player.teleport(player.getWorld().getSpawnLocation());
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    Text.sendDebugLog(INFO, "No saved location found for " + player.getName() +
                            ", teleported to spawn after spectator reset");
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to teleport " + player.getName()
                    + " after spectator reset", e);
            // Fallback to spawn teleport
            player.teleport(player.getWorld().getSpawnLocation());
        }
    }
}
