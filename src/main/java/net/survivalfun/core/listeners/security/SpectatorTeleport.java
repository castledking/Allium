package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.utils.NV;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SpectatorTeleport implements Listener {

    private final PluginStart plugin;
    private Database database;
    private final NV nv;
    private final Logger logger;

    // Cache for player locations to reduce database calls
    private final Map<UUID, Location> survivalLocations = new HashMap<>();
    // For debugging - track operations
    private final AtomicInteger savedLocationsCount = new AtomicInteger(0);
    private final AtomicInteger loadedLocationsCount = new AtomicInteger(0);
    private final AtomicInteger teleportedPlayersCount = new AtomicInteger(0);


    public SpectatorTeleport(@NotNull PluginStart plugin, NV nv) {
        this.plugin = plugin;
        this.database = getDatabase(); // Get database from plugin
        this.nv = nv;
        this.logger = plugin.getLogger();

        if (this.database == null) {
            plugin.getLogger().severe("Database is null in SpectatorTeleport constructor! Spectator location saving will not work.");
        } else {
            createLocationTable();
            loadAllSavedLocations(); // Load saved locations after creating table
        }

        // Register this listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        logger.info("SpectatorTeleport listener has been registered");

        // Schedule periodic logging of cache status
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            logCacheStatus("Periodic check");
        }, 6000L, 6000L); // Log every 5 minutes (6000 ticks)
    }
    private Database getDatabase() {
        return plugin.getDatabase();
    }
    /**
     * Logs the current status of the location cache and operation counters
     */
    private void logCacheStatus(String context) {
        logger.info("=== Spectator Teleport Debug Info (" + context + ") ===");
        logger.info("Cache size: " + survivalLocations.size() + " locations");
        logger.info("Total saved locations: " + savedLocationsCount.get());
        logger.info("Total loaded locations: " + loadedLocationsCount.get());
        logger.info("Total teleported players: " + teleportedPlayersCount.get());

        // Log memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        logger.info("Memory usage: " + usedMemory + "MB / " + totalMemory + "MB");
        logger.info("=============================================");
    }

    /**
     * Ensures all cached locations are saved to the database
     * Call this method during onDisable in the main plugin class
     */
    public void saveAllLocations() {
        if (database == null) {
            return;
        }

        for (Map.Entry<UUID, Location> entry : survivalLocations.entrySet()) {
            UUID playerUUID = entry.getKey();
            Location location = entry.getValue();

            try {
                String query = "REPLACE INTO spectator_locations (player_uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)";
                database.executeUpdate(query,
                        playerUUID.toString(),
                        location.getWorld().getName(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()
                );
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player location to database during shutdown", e);
            }
        }

        plugin.getLogger().info("Saved " + survivalLocations.size() + " spectator locations to database");
    }


    /**
     * Loads all saved locations from the database into the in-memory cache
     */
    public void loadAllSavedLocations() {
        if (database == null) {
            plugin.getLogger().severe("Cannot load spectator locations: database is null");
            return;
        }

        try {
            String query = "SELECT player_uuid, world, x, y, z, yaw, pitch FROM player_spectator_locations";
            database.executeQuery(query, rs -> {
                while (rs.next()) {
                    UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
                    String world = rs.getString("world");
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");
                    float yaw = rs.getFloat("yaw");
                    float pitch = rs.getFloat("pitch");

                    Location location = new Location(
                            plugin.getServer().getWorld(world),
                            x, y, z, yaw, pitch
                    );

                    survivalLocations.put(playerUUID, location);
                }

                plugin.getLogger().info("Loaded " + survivalLocations.size() + " saved spectator locations");
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load saved spectator locations", e);
        }
    }


    /**
     * Creates the table for storing player locations
     */
    private void createLocationTable() {
        if (database == null) {
            return;
        }
        try {
            // Create a table to store player survival locations
            database.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_spectator_locations (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "world VARCHAR(64) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT NOT NULL, " +
                            "pitch FLOAT NOT NULL, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create spectator locations table", e);
        }
    }

    /**
     * Handle gamemode changes to save location when entering spectator and restore when leaving
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode oldGameMode = player.getGameMode();
        GameMode newGameMode = event.getNewGameMode();

        // Player is entering spectator mode from a non-spectator mode
        if (newGameMode == GameMode.SPECTATOR && oldGameMode != GameMode.SPECTATOR) {
            // Save their current location
            savePlayerLocation(player);

            // Apply night vision if they have permission
            if (player.hasPermission("core.nv")) {
                // Schedule for next tick to ensure gamemode change completes first
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    // Add night vision effect (infinite duration = 999999)
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 999999, 0, false, false, true));
                }, 1L);
            }

        }
        // Player is leaving spectator mode
        else if (oldGameMode == GameMode.SPECTATOR && newGameMode != GameMode.SPECTATOR) {
            // Remove night vision if they have it
            if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION) && player.hasPermission("core.nv")) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }

            // Schedule teleport for next tick to ensure gamemode change completes first
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                teleportToSavedLocation(player);
            }, 1L);
        }

    }

    /**
     * Handle player join to check if they were in spectator mode when they left
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Skip if player has bypass permission
        if (player.hasPermission("core.gamemode.spectator.bypass.teleport")) {
            return;
        }

        // If player joins in spectator mode and has a saved location, prepare for teleport when they leave spectator
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // Preload their location into cache
            loadSavedLocation(player.getUniqueId());

            // Apply night vision if they have permission
            if (player.hasPermission("core.nv")) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 999999999
                            , 0, false, false));
                }, 5L);
            }

        }
    }


    /**
     * Saves a player's current location to the database
     */
    private void savePlayerLocation(Player player) {
        if(database == null){
            database = getDatabase();
            if(database == null){
                plugin.getLogger().severe("Database is null in savePlayerLocation! Spectator location saving will not work.");
                return;
            }
        }
        Location location = player.getLocation();
        UUID playerUUID = player.getUniqueId();

        // Cache the location
        survivalLocations.put(playerUUID, location.clone());

        // Save to database
        try {
            database.executeUpdate(
                    "MERGE INTO player_spectator_locations KEY(player_uuid) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    playerUUID.toString(),
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );

            plugin.getLogger().fine("Saved spectator return location for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save spectator location for " + player.getName(), e);
        }
    }

    /**
     * Teleports a player to their saved location
     */
    private void teleportToSavedLocation(Player player) {
        UUID playerUUID = player.getUniqueId();
        Location savedLocation = null;

        // Try to get from cache first
        if (survivalLocations.containsKey(playerUUID)) {
            savedLocation = survivalLocations.get(playerUUID);
        } else {
            // Load from database if not in cache
            savedLocation = loadSavedLocation(playerUUID);
        }

        // Teleport if we have a location
        if (savedLocation != null) {
            // Ensure the world is loaded
            if (savedLocation.getWorld() != null) {
                // Ensure the chunk is loaded for safe teleportation
                savedLocation.getChunk().load();

                // Teleport the player
                player.teleport(savedLocation);
                plugin.getLogger().fine("Teleported " + player.getName() + " to their saved location");
            } else {
                plugin.getLogger().warning("Could not teleport " + player.getName() +
                        " - world " + savedLocation.getWorld().getName() + " is not loaded");
            }
        }
    }

    /**
     * Loads a player's saved location from the database
     */
    private Location loadSavedLocation(UUID playerUUID) {
        if (database == null) {
            database = getDatabase();
            if (database == null) {
                plugin.getLogger().severe("Database is null in loadSavedLocation! Spectator location loading will not work.");
                return null;
            }
        }
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
                    // Create and cache the location
                    Location location = new Location(world, x, y, z, yaw, pitch);
                    survivalLocations.put(playerUUID, location);
                    return location;
                } else {
                    plugin.getLogger().warning("Could not find world " + worldName +
                            " for player " + playerUUID);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load spectator location for " + playerUUID, e);
        }

        return null;
    }

    /**
     * Clears the location cache to free up memory
     */
    public void clearCache() {
        survivalLocations.clear();
    }
}
