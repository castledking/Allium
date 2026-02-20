package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.jobs.CancelTeleportation;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Spawn implements CommandExecutor {
    private final PluginStart plugin;
    private final Database database;
    private final Lang lang;
    private final Map<UUID, SchedulerAdapter.TaskHandle> teleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CancelTeleportation> teleportListeners = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private int teleportDelay;
    private int spawnCooldown;
    private double teleportLeniency;

    public Spawn(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.lang = plugin.getLangManager();
        loadConfig();
    }

    private void loadConfig() {
        teleportDelay = plugin.getConfig().getInt("teleport.delay", 3) * 20; // Convert to ticks
        spawnCooldown = plugin.getConfig().getInt("teleport.cooldown", 30);
        teleportLeniency = plugin.getConfig().getDouble("teleport.leniency", 0.5);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle setspawn first (player-only)
        if (label.equalsIgnoreCase("setspawn")) {
            if (!(sender instanceof Player)) {
                Text.sendErrorMessage(sender, "not-a-player", lang);
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("allium.setspawn")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label.toLowerCase());
                return true;
            }

            setSpawn(player);
            return true;
        }

        // /spawn with optional [player]
        if (args.length == 1) {
            String targetName = args[0];
            // Console can always use, players need both permissions
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (!(p.hasPermission("allium.spawn") && p.hasPermission("allium.spawn.others"))) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label.toLowerCase());
                    return true;
                }
            }

            Player target = Bukkit.getPlayer(targetName);
            if (target == null) {
                if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                    Text.sendErrorMessage(sender, "player-not-online", lang, "{player}", Bukkit.getOfflinePlayer(targetName).getName());
                    return true;
                } else {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", targetName);
                    return true;
                }
            }

            // Teleport target directly (no delay for admin-initiated teleports)
            teleportPlayer(target);

            // Handle handcuffs after successful teleport
            try {
                plugin.getHandcuffsListener().handleSuccessfulTeleport(target);
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error handling handcuffs after admin teleport: " + e.getMessage());
            }

            sender.sendMessage(lang.get("tp.success")
                .replace("{name}", target.getName())
                .replace("{target}", "to spawn"));
            // Notify target as well
            target.sendMessage(lang.get("tp.success")
                .replace("{name}", "to")
                .replace("{target}", "spawn"));
            return true;
        }

        // From here on, only players can self-teleport
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("command-usage")
                .replace("{cmd}", "spawn")
                .replace("{args}", "[player]"));
            return true;
        }

        Player player = (Player) sender;

        // Handle /spawn (self)
        if (!player.hasPermission("allium.spawn")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label.toLowerCase());
            return true;
        }

        // Check cooldown
        if (!player.hasPermission("allium.tpa.nocooldown") && hasCooldown(player.getUniqueId())) {
            long remaining = getRemainingCooldown(player.getUniqueId());
            // use lang.yml cooldown key
            Text.sendErrorMessage(player, "cooldown", lang,
                "{time}", Text.formatTime((int) remaining),
                                "{cmd}", label.toLowerCase());
            return true;
        }

        // Check if player has permission to bypass the delay
        if (!player.hasPermission("allium.tpa.nodelay")) {
            teleportToSpawn(player);
            return true;
        }

        teleportToSpawn(player);
        return true;
    }

    private void setSpawn(Player player) {
        Location location = player.getLocation();
        // Ensure yaw and pitch are saved as float (not double)
        float yaw = location.getYaw();
        float pitch = location.getPitch();
        boolean success = database.setSpawnLocation(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                yaw,
                pitch
        );

        if (success) {
            player.sendMessage(Text.colorize("&aSpawn location has been set!"));
            Text.sendDebugLog(SUCCESS, "Set spawn location for " + player.getName() + " at " + location.getWorld().getName() + ": x" + location.getX() + ", y" + location.getY() + ", z" + location.getZ() + " yaw=" + yaw + " pitch=" + pitch);
        } else {
            player.sendMessage(Text.colorize("&cFailed to set spawn location. Please check the console for errors."));
            Text.sendDebugLog(WARN, "Failed to set spawn location for " + player.getName());
        }
    }

    public void teleportToSpawn(Player player) {
        // Cancel any existing teleport task for this player
        cancelTeleportTask(player.getUniqueId());

        // Check if player has permission to bypass the delay
        if (player.hasPermission("allium.spawn.nodelay")) {
            teleportPlayer(player);
            return;
        }

        // Start the teleportation with delay
        startTeleportCountdown(player);
    }

    private void startTeleportCountdown(Player player) {
        UUID playerId = player.getUniqueId();
        Location startLocation = player.getLocation().clone();
        lastLocations.put(playerId, startLocation);

        // Register the cancellation listener
        CancelTeleportation cancelListener = new CancelTeleportation(plugin, player, startLocation, teleportLeniency);
        Bukkit.getPluginManager().registerEvents(cancelListener, plugin);
        teleportListeners.put(playerId, cancelListener);
        
        // Notify player
        int delaySeconds = Math.max((int) Math.ceil(teleportDelay / 20.0), 0);
        player.sendMessage(lang.get("tp.delay")
            .replace("{delay}", Text.formatTime(delaySeconds)));
            
        // Schedule the teleport with delay using Folia's scheduler
        SchedulerAdapter.TaskHandle task = SchedulerAdapter.runLater(() -> {
            if (!player.isOnline()) {
                cancelTeleportTask(playerId);
                return;
            }

            if (cancelListener.isCancelled()) {
                cancelTeleportTask(playerId);
                return;
            }

            SchedulerAdapter.runAtEntity(player, () -> {
                if (!player.isOnline()) {
                    cancelTeleportTask(playerId);
                    return;
                }
                teleportPlayer(player);
                cancelTeleportTask(playerId);
            });

        }, teleportDelay);
        
        teleportTasks.put(playerId, task);
    }

    /**
     * Safely parses a coordinate value from the database.
     * @param value The value to parse (can be Number, String, or null)
     * @param coordName The name of the coordinate (for error messages)
     * @param defaultValue The default value to return if parsing fails
     * @return The parsed coordinate value, or defaultValue if parsing fails
     */
    private double parseCoordinate(Object value, String coordName, double defaultValue) {
        if (value == null) {
            Text.sendDebugLog(WARN, "Spawn " + coordName + " is null, using default: " + defaultValue);
            return defaultValue;
        }
        
        try {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            } else if (value instanceof String) {
                return Double.parseDouble((String) value);
            } else {
                Text.sendDebugLog(WARN, "Cannot parse " + coordName + " value " + value + " of type " + value.getClass().getSimpleName() + ", using default: " + defaultValue);
                return defaultValue;
            }
        } catch (NumberFormatException e) {
            Text.sendDebugLog(WARN, "Invalid number format for " + coordName + " value: " + value + ", using default: " + defaultValue);
            return defaultValue;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error parsing " + coordName + " value: " + value, e);
            return defaultValue;
        }
    }
    
    /**
     * Teleports a player to the spawn location with proper thread safety and error handling.
     * This method is designed to work with Folia's region threading model.
     * 
     * @param player The player to teleport
     */
    private void teleportPlayer(Player player) {
        if (player == null) {
            Text.sendDebugLog(WARN, "Attempted to teleport a null player to spawn");
            return;
        }
        
        if (!player.isOnline()) {
            Text.sendDebugLog(INFO, "Player " + player.getName() + " is offline, cancelling teleport to spawn");
            return;
        }
    
        final String playerName = player.getName();
        
        try {
            // Check if player has permission to teleport
            if (!player.hasPermission("allium.spawn")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "spawn");
                return;
            }
            
            // Get spawn location data
            Map<String, Object> spawnData = database.getSpawnLocation();
            if (spawnData == null || spawnData.isEmpty()) {
                player.sendMessage(Text.colorize("&cSpawn location is not set. Please ask an administrator to set it with /setspawn."));
                Text.sendDebugLog(INFO, playerName + "tried using /spawn but it wasn't set.", true);
                return;
            }
            
            // Process the spawn data and handle teleportation
            processSpawnData(player, spawnData);
            
            // Apply cooldown if needed
            if (!player.hasPermission("allium.tpa.nocooldown")) {
                setCooldown(player.getUniqueId());
            }
            
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Unexpected error during spawn teleport for " + playerName, e);
            if (player.isOnline()) {
                player.sendMessage(Text.colorize("&cAn error occurred while teleporting to spawn. Please try again later."));
            }
        }
    }
    
    private void processSpawnData(Player player, Map<String, Object> spawnData) {
        final String playerName = player.getName();
        
        try {
            // Early return if player is offline
            if (!player.isOnline()) {
                return;
            }

            // Log the spawn data we received
            Text.sendDebugLog(INFO, "Raw spawn data from database: " + spawnData);
            
            
            // Get world name and validate
            String worldName = String.valueOf(spawnData.get("world"));
            if (worldName == null || worldName.isEmpty() || worldName.equals("null")) {
                Text.sendDebugLog(WARN, "Spawn world name is null or empty in database.");
                if (player.hasPermission("allium.staff") || player.hasPermission("allium.admin")) {
                    player.sendMessage(Text.colorize("&cSpawn world name is null or empty in database."));
                }
                Text.sendErrorMessage(player, "contact-admin", lang);
                return;
            }
            
            // Get the world
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                String errorMsg = "Spawn world '" + worldName + "' is not loaded. Available worlds: " + 
                    Bukkit.getWorlds().stream().map(World::getName).collect(java.util.stream.Collectors.joining(", "));
                Text.sendDebugLog(WARN, errorMsg);
                Text.sendErrorMessage(player, "contact-admin", lang);
                return;
            }
            Text.sendDebugLog(INFO, "Found world: " + world.getName());

            // Parse coordinates with better error handling
            double x, y, z;
            float yaw, pitch;
            
            try {
                x = parseCoordinate(spawnData.get("x"), "x", 0.0);
                y = parseCoordinate(spawnData.get("y"), "y", 64.0);
                z = parseCoordinate(spawnData.get("z"), "z", 0.0);
                yaw = (float) parseCoordinate(spawnData.get("yaw"), "yaw", 0.0);
                pitch = (float) parseCoordinate(spawnData.get("pitch"), "pitch", 0.0);
                
                Text.sendDebugLog(INFO, String.format("Parsed coordinates - X: %.2f, Y: %.2f, Z: %.2f, Yaw: %.2f, Pitch: %.2f", 
                    x, y, z, yaw, pitch));
                    
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error parsing spawn coordinates", e);
                Text.sendErrorMessage(player, "contact-admin", lang);
                return;
            }
            
            // Create the spawn location with center of block adjustment
            Location spawnLocation = new Location(world, x, y, z, yaw, pitch);
            Text.sendDebugLog(INFO, "Created spawn location: " + spawnLocation);
            
            // Store player reference for the teleport task
            final Player finalPlayer = player;
            final String finalPlayerName = playerName;
            final Location destinationLocation = spawnLocation.clone();

            Bukkit.getRegionScheduler().run(plugin, destinationLocation, scheduledTask -> {
                Text.sendDebugLog(INFO, "Executing spawn teleport logic on destination region for " + finalPlayerName);

                Location safeLocation = findSafeLocation(destinationLocation);
                if (safeLocation == null) {
                    Text.sendDebugLog(WARN, "Could not find a safe location at spawn");
                    SchedulerAdapter.runAtEntity(finalPlayer, () -> Text.sendErrorMessage(finalPlayer, "contact-admin", lang));
                    return;
                }

                Text.sendDebugLog(INFO, "Safe location found: " + safeLocation);

                final Location finalSafeLocation = safeLocation.clone();

                SchedulerAdapter.runAtEntity(finalPlayer, () -> {
                    if (!finalPlayer.isOnline()) {
                        Text.sendDebugLog(WARN, "Player went offline before teleport could start");
                        return;
                    }

                    // Handle handcuffs after successful teleport
                    try {
                        plugin.getHandcuffsListener().handleSuccessfulTeleport(finalPlayer);
                    } catch (Exception e) {
                        Text.sendDebugLog(WARN, "Error handling handcuffs after teleport: " + e.getMessage());
                    }

                    finalPlayer.teleportAsync(finalSafeLocation).thenAccept(success -> {

                        if (success) {
                            Text.sendDebugLog(INFO, "Teleport successful! New location: " + finalPlayer.getLocation());
                            finalPlayer.sendMessage(Text.colorize("&aYou have been teleported to spawn!"));
                        } else {
                            Text.sendErrorMessage(finalPlayer, "contact-admin", lang);
                        }
                    }).exceptionally(throwable -> {
                        Text.sendDebugLog(ERROR, "Exception during teleport process", throwable);
                        Text.sendErrorMessage(finalPlayer, "contact-admin", lang);
                        return null;
                    });
                });
            });
            
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Unexpected error during spawn teleport for " + playerName, e);
            Text.sendErrorMessage(player, "contact-admin", lang);
        }
    }
    private Location findSafeLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            Text.sendDebugLog(WARN, "World is null in findSafeLocation");
            return null;
        }
        
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        
        Text.sendDebugLog(INFO, "Checking safety at Y=" + y + " (world min: " + world.getMinHeight() + ", max: " + world.getMaxHeight() + ")");
        
        // Check if the target location is safe
        boolean isSafe = isSafeLocation(world, x, y, z);
        Text.sendDebugLog(INFO, "Location " + x + "," + y + "," + z + " is " + (isSafe ? "safe" : "not safe"));
        
        if (isSafe) {
            Location safeLoc = new Location(world, x + 0.5, y, z + 0.5, location.getYaw(), location.getPitch());
            Text.sendDebugLog(INFO, "Using original location (safe): " + safeLoc);
            return safeLoc;
        }
        
        // Look for a safe spot above
        Text.sendDebugLog(INFO, "Searching for safe location above...");
        for (int i = y + 1; i < world.getMaxHeight(); i++) {
            if (isSafeLocation(world, x, i, z)) {
                Location safeLoc = new Location(world, x + 0.5, i, z + 0.5, location.getYaw(), location.getPitch());
                Text.sendDebugLog(INFO, "Found safe location above at Y=" + i + ": " + safeLoc);
                return safeLoc;
            }
        }
        
        // Look for a safe spot below
        Text.sendDebugLog(INFO, "No safe location found above, searching below...");
        for (int i = y - 1; i > world.getMinHeight(); i--) {
            if (isSafeLocation(world, x, i, z)) {
                Location safeLoc = new Location(world, x + 0.5, i, z + 0.5, location.getYaw(), location.getPitch());
                Text.sendDebugLog(INFO, "Found safe location below at Y=" + i + ": " + safeLoc);
                return safeLoc;
            }
        }
        
        return null;
    }
    private boolean isSafeLocation(World world, int x, int y, int z) {
        // Check if the block below is solid and the two blocks above are passable
        Material below = world.getBlockAt(x, y - 1, z).getType();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        
        boolean isSafe = !below.isAir() && feet.isAir() && head.isAir();
        
        if (isSafe) {
            Text.sendDebugLog(INFO, "Location " + x + "," + y + "," + z + " is safe - " +
                "Below: " + below + ", Feet: " + feet + ", Head: " + head);
        }
        
        return isSafe;
    }

    private void cancelTeleportTask(UUID playerId) {
        // Synchronize access to prevent race conditions
        synchronized (teleportTasks) {
            // Cancel any pending teleport task
            SchedulerAdapter.TaskHandle task = teleportTasks.remove(playerId);
            if (task != null) {
                task.cancel();
            }

            // Unregister the teleport cancellation listener
            CancelTeleportation listener = teleportListeners.remove(playerId);
            if (listener != null) {
                HandlerList.unregisterAll(listener);
            }

            lastLocations.remove(playerId);
        }
    }

    // Cooldown management methods
    private boolean hasCooldown(UUID playerId) {
        if (cooldowns.containsKey(playerId)) {
            long cooldownTime = cooldowns.get(playerId);
            return System.currentTimeMillis() < cooldownTime;
        }
        return false;
    }

    private long getRemainingCooldown(UUID playerId) {
        if (!hasCooldown(playerId)) return 0;
        return (cooldowns.get(playerId) - System.currentTimeMillis()) / 1000 + 1; // +1 to round up
    }

    private void setCooldown(UUID playerId) {
        cooldowns.put(playerId, System.currentTimeMillis() + (spawnCooldown * 1000L));
    }
    
    
    
    

    public void onDisable() {
        // Cancel all pending teleport tasks when the plugin is disabled
        for (SchedulerAdapter.TaskHandle handle : teleportTasks.values()) {
            if (handle != null) handle.cancel();
        }
    }
}
