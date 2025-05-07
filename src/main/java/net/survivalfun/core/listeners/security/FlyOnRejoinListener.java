package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
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

public class FlyOnRejoinListener implements Listener {
    private final PluginStart plugin;
    private final Database database;
    private final Set<UUID> playersWithslowFallingOnQuit = new HashSet<>();

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
        Player player = event.getPlayer();

        // Check if player is switching from Creative to Survival
        if (newGamemode == GameMode.SURVIVAL && oldGamemode == GameMode.CREATIVE) {
            // Skip applying slow falling if player has permission to keep creative mode
            // This means the gamemode change will likely be reverted by CreativeManager
            if (player.hasPermission("core.gamemode.creative") ||
                    player.hasPermission("core.gamemode")) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if(!player.isOnGround()) {
                        player.setAllowFlight(true);
                        player.setFlying(true);
                    } else {
                        player.setAllowFlight(true);
                    }
                    plugin.getLogger().info("Applied delayed flight for " + player.getName() +
                            " as they have permission to keep creative mode");
                }, 10L); // 10 ticks = 0.5 seconds
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Skipping slow falling for " + player.getName() +
                            " as they have permission to keep creative mode");
                }

                return;
            }

            // If player is not on the ground, apply slow falling
            if (!player.isOnGround()) {
                // Apply slow falling effect
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING
                        , 20 * 60 * 5, 0, false, true, true));

                // Start the landing check task
                startSlowFallLandingCheck(player);

                // Log the action
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Applied slow falling to " + player.getName() +
                            " after switching from Creative to Survival while in air");
                    }
            }
        }
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
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

        // Save flight status
        database.savePlayerFlightStatus(player);
        if(plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().fine("Saved flight status to database for " + player.getName());
        }

        // Save slow falling status
        boolean hasSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        database.savePlayerSlowFallingStatus(player.getUniqueId(), hasSlowFalling);
        plugin.getLogger().fine("Saved slow falling status to database for " + player.getName() + ": "
                + hasSlowFalling);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (playersWithslowFallingOnQuit.contains(player.getUniqueId())) {
            if(!player.isOnGround()) {
                if(!player.hasPermission("core.fly")){
                    return;
                }
                applySlowFallingUntilLanded(player);
            }
            playersWithslowFallingOnQuit.remove(player.getUniqueId());
            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().log(Level.INFO, "Player " + player.getName()
                        + " has logged in with Slow Falling effect");
            }
        }

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
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Reset " + player.getName()
                            + "'s gamemode from SPECTATOR to SURVIVAL (no permission)");
                }
                // Teleport to saved survival location
                teleportToSavedLocation(player);
            }, 5L);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Check if player already has slow falling effect (from previous session)
            if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING ) && player.hasPermission("core.fly")) {
                PotionEffect slowFalling = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
                if (slowFalling != null) {
                    int remainingTicks = slowFalling.getDuration(); // Get current duration in ticks
                    double remainingSeconds = remainingTicks / 20.0; // Convert ticks to seconds

                    double currentY = player.getLocation().getY();
                    double requiredTime = currentY / 2.5; // Calculate required seconds for falling safely

                    if (remainingSeconds < requiredTime) {
                        int newDurationTicks = (int) Math.ceil(requiredTime * 20); // Convert time to ticks
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING
                                , newDurationTicks, 0, false, true, true));
                        if(plugin.getConfig().getBoolean("debug-mode")) {
                            plugin.getLogger().info("Reapplied slow falling to " + player.getName()
                                    + " with duration " + newDurationTicks + " ticks to ensure safe landing.");
                        }
                    }
                }
            }
        }, 5L);

        if (player.getGameMode() == GameMode.CREATIVE) {
            // Players in creative mode should always have flight enabled
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.setAllowFlight(true);
            // Don't force flying state, let the player decide
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().fine("Ensured flight is enabled for " + player.getName()
                            + " in creative mode");
                }
            }, 5L);
        }

        // Handle normal flight status restoration
        if (player.hasPermission("core.fly")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                UUID playerUUID = player.getUniqueId();
                Database.PlayerFlightData flightData = database.getPlayerFlightStatus(playerUUID);

                if (flightData != null) {
                    player.setAllowFlight(flightData.allowFlight());
                    if (flightData.isFlying()) {
                        player.setFlying(true);
                    } else if (flightData.allowFlight() && !player.isOnGround()) {
                        // Player wasn't flying but had flight enabled and is in the air
                        // Give them slow falling until they reach the ground
                        applySlowFallingUntilLanded(player);
                    }
                    if (plugin.getConfig().getBoolean("debug-mode")) {
                        plugin.getLogger().fine("Restored flight status for " + player.getName()
                                + " from database");
                    }
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
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0
                , false, true, true));

        // Start the landing check
        startSlowFallLandingCheck(player);
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
