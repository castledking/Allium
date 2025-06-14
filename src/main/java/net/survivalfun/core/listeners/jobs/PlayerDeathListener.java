package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
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
    private static final Map<UUID, Location> deathLocations = new HashMap<>();
    private static final Map<UUID, Long> deathTimes = new HashMap<>();
    private final PluginStart plugin;
    private final Database database;

    public PlayerDeathListener(PluginStart plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        // Store the death location and time
        deathLocations.put(playerId, player.getLocation());
        deathTimes.put(playerId, System.currentTimeMillis());
        plugin.getLogger().info("Player " + player.getName() + " died at " + player.getLocation());
    }

    /**
     * Gets the death location for a player
     * @param player The player to get the death location for
     * @return The death location, or null if not found
     */
    /**
     * Gets the death location for a player
     * @param player The player to get the death location for
     * @return The death location, or null if not found
     */
    public static Location getDeathLocation(Player player) {
        return deathLocations.get(player.getUniqueId());
    }

    /**
     * Gets the time when a player died
     * @param playerId The UUID of the player
     * @return The time of death in milliseconds since epoch, or 0 if not found
     */
    public static long getDeathTime(UUID playerId) {
        return deathTimes.getOrDefault(playerId, 0L);
    }

    /**
     * Clears the death location for a player
     * @param player The player to clear the death location for
     */
    public static void clearDeathLocation(Player player) {
        UUID playerId = player.getUniqueId();
        deathLocations.remove(playerId);
        deathTimes.remove(playerId);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().info("Player " + player.getName() + " is respawning...");
        
        // If respawn location is already set (e.g., by bed), don't override it
        if (event.getRespawnLocation() != null && !event.isBedSpawn() && !event.isAnchorSpawn()) {
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
        event.setRespawnLocation(spawnLocation);
    }
}
