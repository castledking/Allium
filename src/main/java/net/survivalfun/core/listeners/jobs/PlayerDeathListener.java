package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import java.util.UUID;

import java.util.HashMap;
import java.util.Map;

public class PlayerDeathListener implements Listener {
    // Cache for performance optimization
    private static final Map<UUID, Location> deathLocations = new HashMap<>();
    private static final Map<UUID, Long> deathTimes = new HashMap<>();
    private final PluginStart plugin;
    private final Database database;

    public PlayerDeathListener(PluginStart plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        
        // Initialize the static database reference for use in static methods
        staticDatabase = database;
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        Location deathLoc = player.getLocation();
        long deathTime = System.currentTimeMillis();
        
        // Store the death location and time in memory cache
        deathLocations.put(playerId, deathLoc);
        deathTimes.put(playerId, deathTime);
        
        // Store the death location in database with DEATH location type
        database.savePlayerLocation(playerId, LocationType.DEATH, deathLoc, deathTime);
        
        // Log the death location with more detailed information
        plugin.getLogger().info("Player " + player.getName() + " died at Location{world=" + 
                deathLoc.getWorld().getName() + ",x=" + deathLoc.getX() + ",y=" + 
                deathLoc.getY() + ",z=" + deathLoc.getZ() + "}. Death location saved.");
    }

    // Reference to the database instance for static methods
    private static Database staticDatabase;
    
    /**
     * Gets the death location for a player
     * @param player The player to get the death location for
     * @return The death location, or null if not found
     */
    public static Location getDeathLocation(Player player) {
        UUID playerId = player.getUniqueId();
        
        // First check the in-memory cache for faster access
        Location cachedLocation = deathLocations.get(playerId);
        if (cachedLocation != null) {
            return cachedLocation;
        }
        
        // If not in cache and we have a database reference, try to retrieve from database
        if (staticDatabase != null) {
            Location dbLocation = staticDatabase.getPlayerLocation(playerId, LocationType.DEATH);
            
            // If found in database, update the cache for future access
            if (dbLocation != null) {
                deathLocations.put(playerId, dbLocation);
                
                // Also retrieve and cache the death time
                long deathTime = staticDatabase.getPlayerLocationTimestamp(playerId, LocationType.DEATH);
                deathTimes.put(playerId, deathTime);
                
                return dbLocation;
            }
        }
        
        return null;
    }

    /**
     * Gets the time when a player died
     * @param playerId The UUID of the player
     * @return The time of death in milliseconds since epoch, or 0 if not found
     */
    public static long getDeathTime(UUID playerId) {
        // First check the in-memory cache for faster access
        Long cachedTime = deathTimes.get(playerId);
        if (cachedTime != null) {
            return cachedTime;
        }
        
        // If not in cache and we have a database reference, try to retrieve from database
        if (staticDatabase != null) {
            long dbTime = staticDatabase.getPlayerLocationTimestamp(playerId, LocationType.DEATH);
            
            // If found in database, update the cache for future access
            if (dbTime > 0) {
                deathTimes.put(playerId, dbTime);
                return dbTime;
            }
        }
        
        return 0L;
    }

    /**
     * Clears the death location for a player
     * @param player The player to clear the death location for
     */
    public static void clearDeathLocation(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Clear from in-memory cache
        deathLocations.remove(playerId);
        deathTimes.remove(playerId);
        
        // Clear from database if we have a reference
        if (staticDatabase != null) {
            // Use the location type to delete the specific location
            staticDatabase.deletePlayerLocation(playerId, LocationType.DEATH);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().info("Player " + player.getName() + " is respawning...");
        
        // Check if player has a bed spawn point
        Location bedLocation = player.getBedSpawnLocation();
        if (bedLocation != null) {
            plugin.getLogger().info("Respawning " + player.getName() + " at their bed location");
            event.setRespawnLocation(bedLocation);
            return;
        }
        
        // Check if this is already a bed or anchor spawn from the event
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            plugin.getLogger().info("Using existing bed/anchor respawn location for " + player.getName());
            return;
        }
        
        // If respawn location is already set by another plugin, don't override it
        if (event.getRespawnLocation() != null) {
            plugin.getLogger().info("Using existing respawn location for " + player.getName());
            return;
        }

        // Get the spawn location from database
        Map<String, Object> spawnData = database.getSpawnLocation();
        if (spawnData == null) {
            plugin.getLogger().warning("Spawn location is not set! Using default world spawn.");
            return;
        }

        World world = plugin.getServer().getWorld((String) spawnData.get("world"));
        if (world == null) {
            plugin.getLogger().warning("Invalid spawn world: " + spawnData.get("world"));
            return;
        }

        Location spawnLocation = new Location(
                world,
                (double) spawnData.get("x"),
                (double) spawnData.get("y"),
                (double) spawnData.get("z"),
                ((Number) spawnData.get("yaw")).floatValue(),
                ((Number) spawnData.get("pitch")).floatValue()
        );

        // Set the respawn location to our custom spawn
        plugin.getLogger().info("Setting respawn location to server spawn for " + player.getName());
        event.setRespawnLocation(spawnLocation);
    }
}
