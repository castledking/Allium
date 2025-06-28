package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.commands.utils.TP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Spawn implements CommandExecutor {
    private final PluginStart plugin;
    private final Database database;
    private final Lang lang;
    private final Map<UUID, BukkitTask> teleportTasks = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int teleportDelay;
    private int spawnCooldown;

    public Spawn(PluginStart plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.lang = plugin.getLangManager();
        loadConfig();
    }

    private void loadConfig() {
        teleportDelay = plugin.getConfig().getInt("teleport.delay", 3);
        spawnCooldown = plugin.getConfig().getInt("teleport.cooldown", 30);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Text.colorize("&cThis command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        if (label.equalsIgnoreCase("setspawn")) {
            if (!player.hasPermission("core.setspawn")) {
                player.sendMessage(Text.colorize("&cYou don't have permission to set the spawn point."));
                return true;
            }

            setSpawn(player);
            return true;
        }

        // Handle /spawn command
        if (!player.hasPermission("core.spawn")) {
            player.sendMessage(Text.colorize("&cYou don't have permission to use this command."));
            return true;
        }

        // Check cooldown
        if (!player.hasPermission("core.tpa.nocooldown") && hasCooldown(player.getUniqueId())) {
            long remaining = getRemainingCooldown(player.getUniqueId());
            // use lang.yml cooldown key
            Text.sendErrorMessage(player, "cooldown", lang, "{time}", Text.formatTime((int) remaining), "{cmd}", label.toLowerCase());
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
            plugin.getLogger().info("[SFCore] Set spawn location: " + location.getWorld().getName() + " " + location.getX() + ", " + location.getY() + ", " + location.getZ() + " yaw=" + yaw + " pitch=" + pitch);
        } else {
            player.sendMessage(Text.colorize("&cFailed to set spawn location. Please check the console for errors."));
            plugin.getLogger().warning("[SFCore] Failed to set spawn location for " + player.getName());
        }
    }

    private void teleportToSpawn(Player player) {
        // Cancel any existing teleport task for this player
        cancelTeleportTask(player.getUniqueId());

        // Check if player has permission to bypass the delay
        if (player.hasPermission("core.tpa.nodelay")) {
            teleportPlayer(player);
            return;
        }

        // Start the teleportation with delay
        startTeleportCountdown(player);
    }

    private void startTeleportCountdown(Player player) {
        UUID playerId = player.getUniqueId();
        lastLocations.put(playerId, player.getLocation());

        // Send initial message
        player.sendMessage(Text.colorize("&eTeleporting to spawn in &6" + teleportDelay + " &eseconds. Don't move!"));

        // Schedule the teleport task
        BukkitTask task = new BukkitRunnable() {
            int timeLeft = teleportDelay;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTeleportTask(playerId);
                    return;
                }

                // Check if player has moved
                if (hasMoved(player, lastLocations.get(playerId))) {
                    player.sendMessage(Text.colorize("&cTeleportation cancelled because you moved!"));
                    cancelTeleportTask(playerId);
                    return;
                }


                if (timeLeft <= 0) {
                    teleportPlayer(player);
                    cancelTeleportTask(playerId);
                } else {
                    // Update the last location for movement check
                    lastLocations.put(playerId, player.getLocation());
                    timeLeft--;
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // Run every second

        teleportTasks.put(playerId, task);
    }

    private void teleportPlayer(Player player) {
        Map<String, Object> spawnData = database.getSpawnLocation();
        if (spawnData == null) {
            player.sendMessage(Text.colorize("&cSpawn location is not set."));
            return;
        }
        
        // Set cooldown if player doesn't have bypass
        if (!player.hasPermission("core.tpa.nocooldown")) {
            setCooldown(player.getUniqueId());
        }

        World world = Bukkit.getWorld((String) spawnData.get("world"));
        if (world == null) {
            player.sendMessage(Text.colorize("&cInvalid spawn world. Please contact an administrator."));
            return;
        }

        // Save player's current location to the back history
        savePlayerLocationToBackHistory(player);

        Location spawnLocation = new Location(
                world,
                (double) spawnData.get("x"),
                (double) spawnData.get("y"),
                (double) spawnData.get("z"),
                ((Number) spawnData.get("yaw")).floatValue(),
                ((Number) spawnData.get("pitch")).floatValue()
        );

        player.teleport(spawnLocation);
        
        // Check if player has selected pets via /tppet and teleport them
        teleportPlayerPets(player, spawnLocation);
        
        // Check if player has selected entities via /tpe and teleport them
        teleportPlayerEntities(player, spawnLocation);
        
        player.sendMessage(Text.colorize("&aTeleported to spawn!"));
    }

    private boolean hasMoved(Player player, Location lastLocation) {
        if (lastLocation == null) return false;
        Location currentLoc = player.getLocation();
        return currentLoc.getX() != lastLocation.getX() ||
                currentLoc.getY() != lastLocation.getY() ||
                currentLoc.getZ() != lastLocation.getZ();
    }

    private void cancelTeleportTask(UUID playerId) {
        BukkitTask task = teleportTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        lastLocations.remove(playerId);
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
    
    /**
     * Teleports a player's selected pets to a location if they have any
     * This uses the pet selection system from the TP command
     * 
     * @param player The player whose pets to teleport
     * @param location The location to teleport pets to
     */
    private void teleportPlayerPets(Player player, Location location) {
        try {
            // Get the TP command instance from the plugin's command map
            CommandExecutor cmdExecutor = plugin.getCommand("tp").getExecutor();
            
            // Check if the executor is our TP class
            if (cmdExecutor instanceof TP) {
                TP tpCommand = (TP) cmdExecutor;
                
                // Check if player has selected pets and teleport them
                if (tpCommand.hasPets(player)) {
                    // First teleport the pets using the existing method
                    tpCommand.teleportSelectedPets(player, location);
                    plugin.getLogger().info("[SFCore] Teleported pets for player " + player.getName() + " to spawn");
                    
                    // Then run cleanup immediately to deselect pets and show auto-disable message
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            // Get the selectedPets field
                            java.lang.reflect.Field selectedPetsField = TP.class.getDeclaredField("selectedPets");
                            selectedPetsField.setAccessible(true);
                            
                            // Get the selectedPets map
                            @SuppressWarnings("unchecked")
                            Map<UUID, Set<UUID>> selectedPets = 
                                (Map<UUID, Set<UUID>>) selectedPetsField.get(tpCommand);
                            
                            // Clear the player's selected pets
                            Set<UUID> playerPets = selectedPets.remove(player.getUniqueId());
                            
                            if (playerPets != null && !playerPets.isEmpty()) {
                                // Remove glowing effect from pets
                                for (UUID petId : playerPets) {
                                    org.bukkit.entity.Entity pet = Bukkit.getEntity(petId);
                                    if (pet != null) {
                                        pet.setGlowing(false);
                                    }
                                }
                                
                                // Get the TP command's lang manager for consistent messaging
                                java.lang.reflect.Field langField = TP.class.getDeclaredField("lang");
                                langField.setAccessible(true);
                                Lang tpLang = (Lang) langField.get(tpCommand);
                                
                                // Get the first color code and disabled style
                                String firstColorOfToggle = tpLang.getFirstColorCode("tp.tppet-toggle");
                                String disabledStyle = tpLang.get("styles.state.false");
                                
                                // Show auto-disable message
                                if (player.isOnline()) {
                                    player.sendMessage(tpLang.get("tp.tppet-toggle")
                                            .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                                            .replace("{name}", "")
                                            .replace("{info}", "Auto-disabled after teleport to spawn"));
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("[SFCore] Error cleaning up pet teleport: " + e.getMessage());
                            // Fallback message if we can't access the TP lang
                            if (player.isOnline()) {
                                player.sendMessage(Text.colorize("&cPet teleport mode auto-disabled after teleport to spawn"));
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[SFCore] Error teleporting pets: " + e.getMessage());
        }
    }
    
    /**
     * Teleports a player's selected entities to a location if they have any
     * This uses the entity selection system from the TP command (/tpe)
     * Uses the latest entity teleportation technology with instant teleporting
     * and persistence for selected entities
     * 
     * @param player The player whose entities to teleport
     * @param location The location to teleport entities to
     */
    private void teleportPlayerEntities(Player player, Location location) {
        try {
            // Get the TP command instance from the plugin's command map
            CommandExecutor cmdExecutor = plugin.getCommand("tp").getExecutor();
            
            // Check if the executor is our TP class
            if (cmdExecutor instanceof TP) {
                TP tpCommand = (TP) cmdExecutor;
                
                // Check if player has permission to teleport entities
                if (player.hasPermission("core.tpmob")) {
                    try {
                        // Get the hasSelectedEntities method from TP class
                        java.lang.reflect.Method hasSelectedEntitiesMethod = TP.class.getDeclaredMethod("hasSelectedEntities", Player.class);
                        hasSelectedEntitiesMethod.setAccessible(true);
                        
                        // Check if player has selected entities
                        boolean hasEntities = (boolean) hasSelectedEntitiesMethod.invoke(tpCommand, player);
                        
                        if (hasEntities) {
                            // Get the selectedEntities field
                            java.lang.reflect.Field selectedEntitiesField = TP.class.getDeclaredField("selectedEntities");
                            selectedEntitiesField.setAccessible(true);
                            
                            // Get the selectedEntities map
                            @SuppressWarnings("unchecked")
                            Map<UUID, Map<UUID, org.bukkit.entity.Entity>> selectedEntities = 
                                (Map<UUID, Map<UUID, org.bukkit.entity.Entity>>) selectedEntitiesField.get(tpCommand);
                            
                            // Get player's selected entities
                            Map<UUID, org.bukkit.entity.Entity> playerEntities = selectedEntities.get(player.getUniqueId());
                            if (playerEntities != null && !playerEntities.isEmpty()) {
                                // Convert map values to list for teleportation
                                java.util.List<org.bukkit.entity.Entity> entitiesToTeleport = 
                                    new java.util.ArrayList<>(playerEntities.values());
                                
                                // Get the EntityTeleportListener class to disable distance checking
                                java.lang.reflect.Method disableDistanceCheckingMethod = null;
                                try {
                                    // Find the EntityTeleportListener inner class
                                    Class<?>[] innerClasses = TP.class.getDeclaredClasses();
                                    for (Class<?> innerClass : innerClasses) {
                                        if (innerClass.getSimpleName().equals("EntityTeleportListener")) {
                                            // Get the instance of the listener
                                            java.lang.reflect.Field listenerField = TP.class.getDeclaredField("entityTeleportListener");
                                            listenerField.setAccessible(true);
                                            Object listener = listenerField.get(tpCommand);
                                            
                                            // Get the disableDistanceChecking method
                                            disableDistanceCheckingMethod = innerClass.getDeclaredMethod("disableDistanceChecking");
                                            disableDistanceCheckingMethod.setAccessible(true);
                                            
                                            // Call the method to disable distance checking
                                            disableDistanceCheckingMethod.invoke(listener);
                                            plugin.getLogger().info("[SFCore] Disabled distance checking for entity teleportation");
                                        }
                                    }
                                } catch (Exception e) {
                                    plugin.getLogger().warning("[SFCore] Could not disable distance checking: " + e.getMessage());
                                }
                                
                                // Now teleport entities directly without delay
                                int successCount = 0;
                                double radius = 2.0;
                                int numEntities = entitiesToTeleport.size();
                                
                                plugin.getLogger().info("[SFCore] Teleporting " + numEntities + " entities INSTANTLY for player " + player.getName());
                                
                                // Teleport each entity to a position around the player
                                for (int i = 0; i < entitiesToTeleport.size(); i++) {
                                    org.bukkit.entity.Entity entity = entitiesToTeleport.get(i);
                                    
                                    // Skip invalid entities
                                    if (entity == null || !entity.isValid()) {
                                        plugin.getLogger().warning("[SFCore] Skipping invalid entity during teleport");
                                        continue;
                                    }
                                    
                                    // Check if entity is in the same world as the target location
                                    if (!entity.getWorld().equals(location.getWorld())) {
                                        plugin.getLogger().warning("[SFCore] Entity is in a different world, cannot teleport");
                                        continue;
                                    }
                                    
                                    // Calculate position in a circle around the player
                                    double angle = 2 * Math.PI * i / numEntities;
                                    double x = location.getX() + radius * Math.cos(angle);
                                    double z = location.getZ() + radius * Math.sin(angle);
                                    
                                    // Create the teleport location
                                    Location entityLocation = new Location(
                                        location.getWorld(),
                                        x,
                                        location.getY(),
                                        z,
                                        location.getYaw(),
                                        location.getPitch()
                                    );
                                    
                                    // Teleport the entity
                                    boolean success = entity.teleport(entityLocation);
                                    
                                    if (success) {
                                        successCount++;
                                        plugin.getLogger().info("[SFCore] Successfully teleported entity " + entity.getType().name() + 
                                                           " to spawn for player " + player.getName());
                                        // Remove glowing effect immediately
                                        entity.setGlowing(false);
                                    }
                                }
                                
                                plugin.getLogger().info("[SFCore] Successfully teleported " + successCount + 
                                    " entities for player " + player.getName() + " to spawn");
                                    
                                // Clear selected entities and show auto-disable message immediately
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    // Clear the player's selected entities
                                    playerEntities.clear();
                                    selectedEntities.remove(player.getUniqueId());
                                    
                                    // Get the TP command's lang manager for consistent messaging
                                    try {
                                        java.lang.reflect.Field langField = TP.class.getDeclaredField("lang");
                                        langField.setAccessible(true);
                                        Lang tpLang = (Lang) langField.get(tpCommand);
                                        
                                        // Get the first color code and disabled style
                                        String firstColorOfToggle = tpLang.getFirstColorCode("tp.tpe-toggle");
                                        String disabledStyle = tpLang.get("styles.state.false");
                                        
                                        // Show auto-disable message
                                        if (player.isOnline()) {
                                            player.sendMessage(tpLang.get("tp.tpe-toggle")
                                                    .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                                                    .replace("{name}", "")
                                                    .replace("{info}", "Auto-disabled after teleport to spawn"));
                                        }
                                    } catch (Exception e) {
                                        plugin.getLogger().warning("[SFCore] Error showing auto-disable message: " + e.getMessage());
                                        // Fallback message if we can't access the TP lang
                                        player.sendMessage(Text.colorize("&cEntity teleport mode auto-disabled after teleport to spawn"));
                                    }
                                    
                                    // Try to unregister the entity teleport listener
                                    try {
                                        java.lang.reflect.Field listenersField = TP.class.getDeclaredField("activeEntityTeleportListeners");
                                        listenersField.setAccessible(true);
                                        @SuppressWarnings("unchecked")
                                        Map<UUID, Object> listeners = (Map<UUID, Object>) listenersField.get(tpCommand);
                                        Object listener = listeners.remove(player.getUniqueId());
                                        
                                        if (listener != null) {
                                            // Call unregisterListener method
                                            Class<?> listenerClass = listener.getClass();
                                            java.lang.reflect.Method unregisterMethod = listenerClass.getDeclaredMethod("unregisterListener");
                                            unregisterMethod.setAccessible(true);
                                            unregisterMethod.invoke(listener);
                                            plugin.getLogger().info("[SFCore] Unregistered entity teleport listener for player " + player.getName());
                                        }
                                    } catch (Exception e) {
                                        plugin.getLogger().warning("[SFCore] Error unregistering entity teleport listener: " + e.getMessage());
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[SFCore] Error teleporting entities: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[SFCore] Error teleporting entities: " + e.getMessage());
        }
    }
    
    /**
     * Saves the player's current location to the back history
     * This allows players to use /back to return to their previous location
     * after using /spawn
     * 
     * @param player The player whose location to save
     */
    private void savePlayerLocationToBackHistory(Player player) {
        try {
            // Get the TP command instance from the plugin's command map
            CommandExecutor cmdExecutor = plugin.getCommand("tp").getExecutor();
            
            // Check if the executor is our TP class
            if (cmdExecutor instanceof TP) {
                TP tpCommand = (TP) cmdExecutor;
                
                // Use reflection to access the lastLocation map in the TP class
                java.lang.reflect.Field lastLocationField = TP.class.getDeclaredField("lastLocation");
                lastLocationField.setAccessible(true);
                
                @SuppressWarnings("unchecked")
                Map<UUID, Location> lastLocation = (Map<UUID, Location>) lastLocationField.get(tpCommand);
                
                // Save the player's current location before teleporting
                lastLocation.put(player.getUniqueId(), player.getLocation());
                
                plugin.getLogger().info("[SFCore] Saved back location for player " + player.getName() + " when using /spawn");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[SFCore] Error saving back location: " + e.getMessage());
        }
    }

    public void onDisable() {
        // Cancel all pending teleport tasks when the plugin is disabled
        for (BukkitTask task : teleportTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        teleportTasks.clear();
        lastLocations.clear();
        cooldowns.clear();
    }
}
