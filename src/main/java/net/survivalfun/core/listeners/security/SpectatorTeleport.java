package net.survivalfun.core.listeners.security;

import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.security.FlightRestoration;
import net.survivalfun.core.commands.NV;
import net.survivalfun.core.commands.TP;
import net.survivalfun.core.util.SchedulerAdapter;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SpectatorTeleport implements Listener {

    private final PluginStart plugin;
    private Database database;
    private final NV nv;
    private final Logger logger;
    private final Map<UUID, Location> survivalLocations = new HashMap<>();
    private final AtomicInteger loadedLocationsCount = new AtomicInteger(0);
    private final Map<UUID, SpectatorEntryState> spectatorEntryStates = new ConcurrentHashMap<>();
    
    /**
     * Gets the map of all saved survival locations
     * @return Unmodifiable map of player UUIDs to their saved locations
     */
    public Map<UUID, Location> getSurvivalLocations() {
        return Map.copyOf(survivalLocations);
    }

    public SpectatorTeleport(@NotNull PluginStart plugin, NV nv) {
        this.plugin = plugin;
        this.database = getDatabase(); // Get database from plugin
        this.nv = nv;
        this.logger = plugin.getLogger();

        if (this.database == null) {
            Text.sendDebugLog(ERROR, "Database is null in SpectatorTeleport constructor! Spectator location saving will not work.");
        } else {
            createLocationTable();
            loadAllSavedLocations(); // Load saved locations after creating table
        }
        try {
            loadAllSavedLocations();
        } catch (Exception e) {
            logger.severe("Failed to load saved spectator locations: " + e.getMessage());
            e.printStackTrace();
        }

        // Register this listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private Database getDatabase() {
        return plugin.getDatabase();
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
                // Add null check for world
                if (location.getWorld() == null) {
                    Text.sendDebugLog(WARN, "Cannot save location for player " + playerUUID + " - world is null");
                    continue;
                }

                database.executeUpdate(
                        "REPLACE INTO player_spectator_locations (player_uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        playerUUID.toString(),
                        location.getWorld().getName(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()
                );
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to save player location to database during shutdown", e);
            }
        }
        if(plugin.getConfig().getBoolean("debug-mode")){
            Text.sendDebugLog(INFO, "Saved " + survivalLocations.size() + " spectator locations to database");
        }
        // Placeholder for saving locations
        logger.info("Saving all spectator locations");
        // Add actual save logic here, e.g., iterating over survivalLocations and saving to database
    }

    /**
     * Loads all saved locations from the database into the in-memory cache
     * Loads all saved spectator locations from the database into the in-memory cache
     */
    public final void loadAllSavedLocations() {
        if (database == null) {
            return;
        }

        try {
            String uuidQuery = "SELECT DISTINCT player_uuid FROM player_spectator_locations";
            boolean hasMore = true;
            int offset = 0;
            final int batchSize = 50;

            while (hasMore) {
                for (int i = 0; i < batchSize; i++) {
                    String singleQuery = uuidQuery + " LIMIT 1 OFFSET " + (offset + i);
                    Map<String, Object> uuidRow = database.queryRow(singleQuery);

                    if (uuidRow == null) {
                        hasMore = false;
                        break;
                    }

                    // Safely handle UUID conversion with validation
                    String uuidStr = (String) uuidRow.get("player_uuid");
                    if (uuidStr == null || uuidStr.isEmpty()) {
                        Text.sendDebugLog(WARN, "Skipping invalid UUID entry: null or empty");
                        continue;
                    }
                    
                    UUID playerUUID;
                    try {
                        playerUUID = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        Text.sendDebugLog(WARN, "Skipping invalid UUID format: " + uuidStr);
                        continue;
                    }

                    // Get the latest location for this player
                    String locationQuery = "SELECT world, x, y, z, yaw, pitch FROM player_spectator_locations " +
                            "WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 1";
                    Map<String, Object> locationRow = database.queryRow(locationQuery, playerUUID.toString());

                    if (locationRow != null) {
                        String world = (String)locationRow.get("world");
                        double x = (Double)locationRow.get("x");
                        double y = (Double)locationRow.get("y");
                        double z = (Double)locationRow.get("z");
                        float yaw = ((Number)locationRow.get("yaw")).floatValue();
                        float pitch = ((Number)locationRow.get("pitch")).floatValue();

                        Location location = new Location(
                                plugin.getServer().getWorld(world),
                                x, y, z, yaw, pitch
                        );

                        survivalLocations.put(playerUUID, location);
                        loadedLocationsCount.incrementAndGet();
                    }
                }

                offset += batchSize;
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to load saved spectator locations", e);
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
            Text.sendDebugLog(ERROR, "Failed to create spectator locations table", e);
        }
    }

    /**
     * Handle gamemode changes to save location when entering spectator and restore when leaving
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode oldGameMode = player.getGameMode();
        GameMode newGameMode = event.getNewGameMode();
        UUID playerId = player.getUniqueId();

        // Player is entering spectator mode from a non-spectator mode
        if (newGameMode == GameMode.SPECTATOR && oldGameMode != GameMode.SPECTATOR) {
            // Store the player's current night vision state if they have it
            boolean hadNightVision = player.hasPotionEffect(PotionEffectType.NIGHT_VISION);
            
            Location currentLocation = player.getLocation();
            boolean hadAllowFlight = player.getAllowFlight();
            boolean wasFlying = player.isFlying();
            boolean wasInAir = isInAir(currentLocation);

            // Store the entry state including night vision status
            spectatorEntryStates.put(playerId, new SpectatorEntryState(hadAllowFlight, wasFlying, wasInAir, hadNightVision));

            // Save their current location
            savePlayerLocation(player);

            // Only apply night vision if they don't already have it and have permission
            if ((player.hasPermission("allium.nv") || player.hasPermission("allium.gamemode.spectator")) && !hadNightVision) {
                // Schedule for next tick to ensure gamemode change completes first
                SchedulerAdapter.runAtEntityLater(player, () -> {
                    // Add night vision effect (infinite duration = 999999)
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 
                        PotionEffect.INFINITE_DURATION, 0, false, false, true));
                }, 1L);
            }
        }
        // Player is leaving spectator mode
        else if (oldGameMode == GameMode.SPECTATOR && newGameMode != GameMode.SPECTATOR) {
            boolean debugMode = plugin.getConfig().getBoolean("debug-mode");
            SpectatorEntryState entryState = spectatorEntryStates.get(playerId);
            if (entryState != null && !entryState.hadNightVision() && 
                player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
            if (newGameMode == GameMode.CREATIVE) {
                if (entryState != null) {
                    spectatorEntryStates.remove(player.getUniqueId());
                    if (debugMode) {
                        plugin.getLogger().fine("Cleared spectator entry state for " + player.getName() + " (switching to creative)");
                    }
                }
                return;
            }

            final boolean entryHadAllowFlight = entryState != null && entryState.hadAllowFlight();
            final boolean hasTeleportPermission = player.hasPermission("allium.gamemode.spectator.teleport");

            SchedulerAdapter.runAtEntityLater(player, () -> {
                if (!player.isOnline()) {
                    return;
                }

                if (hasTeleportPermission) {
                    Location loc = player.getLocation();
                    if (isSuffocating(loc)) {
                        Text.sendDebugLog(INFO, "Player " + player.getName() + " is suffocating after leaving spectator mode, teleporting to saved location");
                        teleportToSavedLocation(player);
                    }
                } else {
                    teleportToSavedLocation(player);
                }

                restoreSpectatorFlight(player, entryState);

                ensurePostSpectatorSafety(player, entryState, entryHadAllowFlight);
            }, 1L);

            if (entryState != null) {
                spectatorEntryStates.remove(player.getUniqueId());
            }
        }
    }

    private void ensurePostSpectatorSafety(Player player, SpectatorEntryState entryState, boolean entryHadAllowFlight) {
        if (!player.isOnline() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        boolean hasFlightNow = player.getAllowFlight();
        boolean needsLegacySafety = entryState != null && entryState.shouldUseSafetySlowFalling();
        boolean lostFlightCapability = !entryHadAllowFlight && !hasFlightNow;

        if (player.isFlying()) {
            return;
        }

        if ((needsLegacySafety || lostFlightCapability) && isInAir(player.getLocation())) {
            applySlowFallingSafety(player);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("allium.gamemode.spectator.teleport")) {
            Location loc = player.getLocation();
            if (!TP.isSuffocationRisk(loc)) {
                return;
            }
        }

        // If player joins in spectator mode and has a saved location, prepare for teleport when they leave spectator
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // Preload their location into cache
            loadSavedLocation(player.getUniqueId());

            // Apply night vision if they have permission
            if (player.hasPermission("allium.nv")) {
                SchedulerAdapter.runAtEntityLater(player, () -> {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
                }, 5L);
            }
        }
    }

    public void saveSurvivalLocation(Player player) {
        UUID uuid = player.getUniqueId();
        Location location = player.getLocation();
        
        survivalLocations.put(uuid, location);
        
        if (database != null) {
            try {
                database.executeUpdate(
                    "INSERT INTO player_spectator_locations (player_uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uuid.toString(),
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
                );
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to save spectator location for " + player.getName(), e);
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
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    Text.sendDebugLog(ERROR, "Spectator location database is null");
                }
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
            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().fine("Saved spectator return location for " + player.getName());
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to save spectator location for " + player.getName(), e);
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
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().fine("Teleported " + player.getName() + " to their saved location");
                }

                // Apply slow falling if the survival location is in the air
                if (isInAir(savedLocation)) {
                    plugin.getFlyOnRejoinListener().applySlowFallingUntilLanded(player);
                }
            } else {
                Text.sendDebugLog(WARN, "Could not teleport " + player.getName() + " - world " + savedLocation.getWorld().getName() + " is not loaded");
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
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    Text.sendDebugLog(ERROR, "loadSavedLocation: Database is null");
                }
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
                String worldName = valueAsString(locationData, "world");
                Number xNumber = valueAsNumber(locationData, "x");
                Number yNumber = valueAsNumber(locationData, "y");
                Number zNumber = valueAsNumber(locationData, "z");
                Number yawNumber = valueAsNumber(locationData, "yaw");
                Number pitchNumber = valueAsNumber(locationData, "pitch");

                if (worldName == null || xNumber == null || yNumber == null || zNumber == null ||
                        yawNumber == null || pitchNumber == null) {
                    Text.sendDebugLog(WARN, "Incomplete spectator location data for player " + playerUUID + ", skipping teleport.");
                    return null;
                }

                double x = xNumber.doubleValue();
                double y = yNumber.doubleValue();
                double z = zNumber.doubleValue();
                float yaw = yawNumber.floatValue();
                float pitch = pitchNumber.floatValue();

                // Get the world by name
                org.bukkit.World world = plugin.getServer().getWorld(worldName);

                if (world != null) {
                    // Create and cache the location
                    Location location = new Location(world, x, y, z, yaw, pitch);
                    survivalLocations.put(playerUUID, location);
                    return location;
                } else {
                    Text.sendDebugLog(WARN, "Could not find world " + worldName + " for player " + playerUUID);
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to load spectator location for " + playerUUID, e);
        }

        return null;
    }

    private Object getCaseInsensitive(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value != null) {
            return value;
        }
        value = data.get(key.toLowerCase());
        if (value != null) {
            return value;
        }
        return data.get(key.toUpperCase());
    }

    private Number valueAsNumber(Map<String, Object> data, String key) {
        Object value = getCaseInsensitive(data, key);
        if (value instanceof Number number) {
            return number;
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                Text.sendDebugLog(WARN, "Invalid numeric value for key '" + key + "' while loading spectator location: " + value);
            }
        }
        return null;
    }

    private String valueAsString(Map<String, Object> data, String key) {
        Object value = getCaseInsensitive(data, key);
        return value != null ? value.toString() : null;
    }

    /**
     * Clears the location cache to free up memory
     */
    public void clearCache() {
        survivalLocations.clear();
    }

    /**
     * Checks if a location is in the air (no solid block below)
     * @param location The location to check
     * @return true if the location is in the air, false otherwise
     */
    private boolean isInAir(Location location) {
        Location below = location.clone().subtract(0, 1, 0);
        return location.getBlock().getType().isAir() && !below.getBlock().getType().isSolid();
    }

    private boolean isSuffocating(Location location) {
        Material footBlock = location.getBlock().getType();
        Material headBlock = location.clone().add(0, 1, 0).getBlock().getType();
        return footBlock.isSolid() || headBlock.isSolid();
    }

    private void restoreSpectatorFlight(Player player, SpectatorEntryState entryState) {
        try {
            if (entryState != null && entryState.hadAllowFlight()) {
                player.setAllowFlight(true);
                if (entryState.wasFlying()) {
                    SchedulerAdapter.runAtEntityLater(player, () -> {
                        if (player.isOnline() && player.getGameMode() != GameMode.SPECTATOR) {
                            try {
                                player.setFlying(true);
                            } catch (IllegalArgumentException ignored) {
                                Text.sendDebugLog(WARN, "Could not re-enable flying immediately for " + player.getName());
                            }
                        }
                    }, 1L);
                }
            }
        } finally {
            spectatorEntryStates.remove(player.getUniqueId());
        }
    }

    private void applySlowFallingSafety(Player player) {
        FlightRestoration restoration = plugin.getFlyOnRejoinListener();
        if (restoration != null) {
            restoration.applySlowFallingUntilLanded(player);
        } else {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60, 0, true, false, true));
        }
    }

    private static final class SpectatorEntryState {
        private final boolean allowFlight;
        private final boolean flying;
        private final boolean inAir;
        private final boolean hadNightVision;

        public SpectatorEntryState(boolean allowFlight, boolean flying, boolean inAir, boolean hadNightVision) {
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.inAir = inAir;
            this.hadNightVision = hadNightVision;
        }

    private boolean hadAllowFlight() {
        return allowFlight;
    }

    private boolean wasFlying() {
        return flying;
    }

    public boolean shouldUseSafetySlowFalling() {
        return inAir && !allowFlight;
    }
    
    public boolean hadNightVision() {
        return hadNightVision;
    }
}
}