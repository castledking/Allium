package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.utils.core.managers.NV;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Listener;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class SpectatorTeleport implements Listener {
    private final PluginStart plugin;
    private final NV nvCommand;
    private final Logger logger;
    private final Map<UUID, Location> survivalLocations = new HashMap<>();
    private final AtomicInteger loadedLocationsCount = new AtomicInteger(0);

    public SpectatorTeleport(PluginStart plugin, NV nvCommand) {
        this.plugin = plugin;
        this.nvCommand = nvCommand;
        this.logger = plugin.getLogger();
        try {
            loadAllSavedLocations();
        } catch (Exception e) {
            logger.severe("Failed to load saved spectator locations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveAllLocations() {
        // Placeholder for saving locations
        logger.info("Saving all spectator locations");
        // Add actual save logic here, e.g., iterating over survivalLocations and saving to database
    }

    /**
     * Loads all saved spectator locations from the database into the in-memory cache
     */
    public final void loadAllSavedLocations() {
        if (plugin.getDatabase() == null) {
            if (plugin.getConfig().getBoolean("debug-mode")) {
                logger.severe("Cannot load spectator locations: database is null");
            }
            return;
        }

        try {
            // Define data class for location results
            record LocationData(UUID playerUUID, String world, double x, double y, double z, float yaw, float pitch) {}

            // Query to get the latest spectator location for each player
            String sql = "SELECT player_uuid, world, x, y, z, yaw, pitch " +
                        "FROM player_locations " +
                        "WHERE location_type = 'SPECTATOR' AND player_uuid IS NOT NULL AND player_uuid != '' " +
                        "AND (player_uuid, timestamp) IN (" +
                        "    SELECT player_uuid, MAX(timestamp) " +
                        "    FROM player_locations " +
                        "    WHERE location_type = 'SPECTATOR' AND player_uuid IS NOT NULL AND player_uuid != '' " +
                        "    GROUP BY player_uuid" +
                        ") ORDER BY timestamp DESC";

            // Process in batches
            final int batchSize = 50;
            int offset = 0;
            boolean hasMore = true;

            while (hasMore) {
                String paginatedQuery = sql + " LIMIT ? OFFSET ?";
                List<LocationData> locations = plugin.getDatabase().executeQuery(
                    paginatedQuery,
                    rs -> {
                        List<LocationData> results = new ArrayList<>();
                        try {
                            while (rs.next()) {
                                String playerUUIDStr = rs.getString("player_uuid");
                                String world = rs.getString("world");
                                double x = rs.getDouble("x");
                                double y = rs.getDouble("y");
                                double z = rs.getDouble("z");
                                float yaw = rs.getFloat("yaw");
                                float pitch = rs.getFloat("pitch");

                                // Validate UUID
                                try {
                                    UUID playerUUID = UUID.fromString(playerUUIDStr);
                                    results.add(new LocationData(playerUUID, world, x, y, z, yaw, pitch));
                                } catch (IllegalArgumentException e) {
                                    if (plugin.getConfig().getBoolean("debug-mode")) {
                                        logger.warning("Invalid UUID format: " + playerUUIDStr);
                                    }
                                }
                            }
                        } catch (SQLException e) {
                            logger.warning("Error processing result set: " + e.getMessage());
                        }
                        return results;
                    },
                    batchSize,
                    offset
                );

                if (locations == null || locations.isEmpty()) {
                    hasMore = false;
                    break;
                }

                for (LocationData data : locations) {
                    // Validate world
                    World bukkitWorld = plugin.getServer().getWorld(data.world());
                    if (bukkitWorld == null) {
                        if (plugin.getConfig().getBoolean("debug-mode")) {
                            logger.warning("Invalid world '" + data.world() + "' for UUID: " + data.playerUUID());
                        }
                        continue;
                    }

                    // Create and store location
                    Location location = new Location(bukkitWorld, data.x(), data.y(), data.z(), data.yaw(), data.pitch());
                    survivalLocations.put(data.playerUUID(), location);
                    loadedLocationsCount.incrementAndGet();

                    if (plugin.getConfig().getBoolean("debug-mode")) {
                        logger.info("Loaded spectator location for UUID: " + data.playerUUID() + " at " + 
                                   data.world() + " (" + data.x() + ", " + data.y() + ", " + data.z() + ")");
                    }
                }

                offset += batchSize;
            }

            if (plugin.getConfig().getBoolean("debug-mode")) {
                logger.info("Loaded " + loadedLocationsCount.get() + " spectator locations from database");
            }
        } catch (SQLException e) {
            logger.severe("Failed to load saved spectator locations: " + e.getMessage());
            e.printStackTrace();
        }
    }
}