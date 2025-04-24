package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class FlyOnRejoinListener implements Listener {
    private final PluginStart plugin;
    private final Database database;

    public FlyOnRejoinListener(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("FlyOnRejoinListener has been registered");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGamemodeSwitch(PlayerGameModeChangeEvent event) {
        GameMode newGamemode = event.getNewGameMode();
        GameMode oldGamemode = event.getPlayer().getGameMode();

        // Check if player is switching from Creative to Survival
        if (newGamemode == GameMode.SURVIVAL && oldGamemode == GameMode.CREATIVE) {
            Player player = event.getPlayer();

            // If player is not on the ground, apply slow falling
            if (!player.isOnGround()) {
                // Apply slow falling effect
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60 * 5, 0, false, true, true));

                // Start the landing check task
                startSlowFallLandingCheck(player);

                // Log the action
                plugin.getLogger().info("Applied slow falling to " + player.getName() + " after switching from Creative to Survival while in air");
            }
        }
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Save flight status
        database.savePlayerFlightStatus(player);
        plugin.getLogger().fine("Saved flight status to database for " + player.getName());

        // Save slow falling status
        boolean hasSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        database.savePlayerSlowFallingStatus(player.getUniqueId(), hasSlowFalling);
        plugin.getLogger().fine("Saved slow falling status to database for " + player.getName() + ": " + hasSlowFalling);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check if player is in spectator mode without permission
        if (player.getGameMode() == GameMode.SPECTATOR && !player.hasPermission("core.gamemode.spectator")) {
            // Schedule the gamemode change and teleport for after the player fully joins
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Set gamemode to survival
                player.setGameMode(GameMode.SURVIVAL);

                // Notify the player
                player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                        .replace("{gamemode}", GameMode.SURVIVAL.toString()));

                // Log the reset
                plugin.getLogger().info("Reset " + player.getName() + "'s gamemode from SPECTATOR to SURVIVAL (no permission)");

                // Teleport to saved survival location
                teleportToSavedLocation(player);
            }, 5L);
        }

        // Always check for slow falling effect on join, regardless of permissions
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Check if player already has slow falling effect (from previous session)
            if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                // They have the effect, so start a new task to check when they land
                plugin.getLogger().info("Player " + player.getName() + " rejoined with slow falling effect, starting landing check");
                startSlowFallLandingCheck(player);
            }
        }, 5L);

        // Handle flight status restoration - but skip for creative mode players
        if (player.getGameMode() == GameMode.CREATIVE) {
            // Players in creative mode should always have flight enabled
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.setAllowFlight(true);
                // Don't force flying state, let the player decide
                plugin.getLogger().fine("Ensured flight is enabled for " + player.getName() + " in creative mode");
            }, 5L);
        }

        // Handle normal flight status restoration
        if (player.hasPermission("core.fly")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                UUID playerUUID = player.getUniqueId();
                Database.PlayerFlightData flightData = database.getPlayerFlightStatus(playerUUID);

                if (flightData != null) {
                    player.setAllowFlight(flightData.isAllowFlight());
                    if (flightData.isFlying()) {
                        player.setFlying(true);
                    } else if (flightData.isAllowFlight() && !player.isOnGround()) {
                        // Player wasn't flying but had flight enabled and is in the air
                        // Give them slow falling until they reach the ground
                        applySlowFallingUntilLanded(player);
                    }
                    plugin.getLogger().fine("Restored flight status for " + player.getName() + " from database");
                }
            }, 5L);

        } else {
            // For players without flight permission, check if they're in survival and in the air
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.getGameMode() == GameMode.SURVIVAL && !player.isOnGround()) {
                    // Player is in survival mode and not on the ground, apply slow falling
                    applySlowFallingUntilLanded(player);
                    plugin.getLogger().fine("Applied slow falling to " + player.getName() + " after rejoin (in air)");
                }
            }, 5L);
        }
    }

    /**
     * Applies slow falling to a player until they reach the ground
     * @param player The player to apply slow falling to
     */
    private void applySlowFallingUntilLanded(Player player) {
        // Apply slow falling effect
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60 * 5, 0, false, true, true));

        // Start the landing check
        startSlowFallLandingCheck(player);
    }

    /**
     * Starts a task to check when a player with slow falling lands
     * @param player The player to check
     */
    private void startSlowFallLandingCheck(Player player) {
        // Create a task that checks if the player has landed
        new BukkitRunnable() {
            private int checkCount = 0;

            @Override
            public void run() {
                checkCount++;

                // Log status periodically
                if (checkCount % 20 == 0) {
                    plugin.getLogger().fine("SlowFall check for " + player.getName() +
                            ": online=" + player.isOnline() + ", onGround=" + player.isOnGround());
                }

                // If player is offline or on ground, remove effect and cancel task
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }

                if (player.isOnGround()) {
                    player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                    plugin.getLogger().info("Removed slow falling from " + player.getName() + " after landing");

                    // Clear the database flag
                    database.savePlayerSlowFallingStatus(player.getUniqueId(), false);

                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 5L, 5L); // Check more frequently (every 1/4 second)
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
                    plugin.getLogger().fine("Teleported " + player.getName() + " to their saved location after spectator reset");
                } else {
                    plugin.getLogger().warning("Could not find world " + worldName +
                            " for player " + player.getName() + " during spectator reset");
                }
            } else {
                // If no saved location, just teleport to spawn
                player.teleport(player.getWorld().getSpawnLocation());
                plugin.getLogger().info("No saved location found for " + player.getName() +
                        ", teleported to spawn after spectator reset");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to teleport " + player.getName() + " after spectator reset", e);
            // Fallback to spawn teleport
            player.teleport(player.getWorld().getSpawnLocation());
        }
    }
}
