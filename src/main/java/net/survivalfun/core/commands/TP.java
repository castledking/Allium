package net.survivalfun.core.commands;

// No special imports needed for distance checking

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.jobs.CancelTeleportation;
import net.survivalfun.core.listeners.jobs.Death;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.survivalfun.core.util.SchedulerAdapter;

public class TP implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;
    private final net.survivalfun.core.managers.config.Config config;
    private final Database database;

    // Command aliases for different functionalities
    private final List<String> teleportCommandAliases;
    private final List<String> teleportRequestAliases;
    private final List<String> teleportAcceptAliases;
    private final List<String> teleportDenyAliases;
    private final List<String> teleportHereAliases;
    private final List<String> teleportPositionAliases;
    private final List<String> teleportToggleAliases;
    private final List<String> teleportTopBottomAliases;
    private final List<String> teleportPetAliases;
    private final List<String> teleportEntityAliases;
    private final List<String> teleportOfflineAliases;
    private final List<String> teleportCancelAliases;

    // Maps for teleport requests
    private final Map<UUID, UUID> teleportRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportRequestTimestamps = new ConcurrentHashMap<>();
    private final Set<UUID> teleportHereRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<org.bukkit.entity.Entity>> selectedPets = new ConcurrentHashMap<>();
    private final Map<UUID, PetTeleportListener> activePetTeleportListeners = new ConcurrentHashMap<>();
    private final Map<UUID, EntityTeleportListener> activeEntityTeleportListeners = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, org.bukkit.entity.Entity>> selectedEntities = new ConcurrentHashMap<>();
    private final Set<UUID> teleportToggled = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    
    /**
     * Gets the map of last known locations for teleport back functionality.
     * @return A map of player UUIDs to their last known locations
     */
    public Map<UUID, Location> getLastLocationMap() {
        return lastLocation;
    }
    
    /**
     * Teleports a player's selected pets to a target location
     * This uses the existing pet selection system from the TP command
     * 
     * @param player The player whose pets to teleport
     * @param location The target location
     */
    public void teleportPlayerPets(Player player, Location location) {
        if (player == null || location == null) {
            Text.sendDebugLog(WARN, "Cannot teleport pets: player or location is null");
            return;
        }
        
        UUID playerId = player.getUniqueId();
        List<org.bukkit.entity.Entity> pets = selectedPets.get(playerId);
        
        if (pets == null || pets.isEmpty()) {
            Text.sendDebugLog(INFO, "No pets selected for " + player.getName() + " to teleport");
            return;
        }
        
        Text.sendDebugLog(INFO, "Teleporting " + pets.size() + " pets for " + player.getName() + " to " + location);
        
        // Convert to a list of Entity objects
        List<Entity> validPets = pets.stream()
            .filter(Objects::nonNull)
            .filter(Entity::isValid)
            .map(entity -> (Entity) entity)
            .collect(Collectors.toList());
            
        if (!validPets.isEmpty()) {
            // Schedule teleport after player has arrived on the player's entity scheduler (Folia-safe)
            SchedulerAdapter.runAtEntityLater(player, () -> {
                teleportPets(playerId, player, validPets);
            }, 5L); // 5 tick delay (1/4 second)
        }
    }
    
    /**
     * Teleports a player's selected entities to a target location
     * This uses the existing entity selection system from the TP command
     * 
     * @param player The player whose entities to teleport
     * @param location The target location
     */
    public void teleportPlayerEntities(Player player, Location location) {
        if (player == null || location == null) {
            Text.sendDebugLog(WARN, "Cannot teleport entities: player or location is null");
            return;
        }
        
        UUID playerId = player.getUniqueId();
        Map<UUID, org.bukkit.entity.Entity> entities = selectedEntities.get(playerId);
        
        if (entities == null || entities.isEmpty()) {
            Text.sendDebugLog(INFO, "No entities selected for " + player.getName() + " to teleport");
            return;
        }
        
        List<org.bukkit.entity.Entity> entityList = new ArrayList<>(entities.values());
        Text.sendDebugLog(INFO, "Teleporting " + entityList.size() + " entities for " + player.getName() + " to " + location);
        
        // Convert to a list of Entity objects
        List<Entity> validEntities = entityList.stream()
            .filter(Objects::nonNull)
            .filter(Entity::isValid)
            .map(entity -> (Entity) entity)
            .collect(Collectors.toList());
            
        if (!validEntities.isEmpty()) {
            // Schedule teleport after player has arrived on the player's entity scheduler (Folia-safe)
            SchedulerAdapter.runAtEntityLater(player, () -> {
                teleportEntities(playerId, player, validEntities);
            }, 5L); // 5 tick delay (1/4 second)
        }
    }

    /**
     * Checks if a player has any selected pets
     * @param player The player to check
     * @return true if the player has selected pets, false otherwise
     */
    public boolean hasPets(Player player) {
        if (player == null) return false;
        List<org.bukkit.entity.Entity> pets = selectedPets.get(player.getUniqueId());
        return pets != null && !pets.isEmpty();
    }
    
    /**
     * Checks if a player has any selected entities
     * @param player The player to check
     * @return true if the player has selected entities, false otherwise
     */
    public boolean hasSelectedEntities(Player player) {
        if (player == null) return false;
        Map<UUID, org.bukkit.entity.Entity> entities = selectedEntities.get(player.getUniqueId());
        return entities != null && !entities.isEmpty();
    }

    // Teleport request expiration time in milliseconds (default: 2 minutes)
    private long getRequestExpirationTime() {
        // Get expiration time in seconds from config, default 120 seconds (2 minutes)
        int expireSeconds = config.getInt("teleport.expire");
        if (expireSeconds <= 0) {
            expireSeconds = 120; // Default to 2 minutes if not set or invalid
        }
        // Return duration in ms
        return expireSeconds * 1000L;
    }

    /**
     * Determines if standing at the given location could cause suffocation for a non-spectator player.
     * This checks the blocks at the feet and head positions; if either is a solid block,
     * teleporting a survival/creative player there can cause suffocation.
     *
     * @param location Target location to evaluate
     * @return true if there is a suffocation risk, false otherwise
     */
    public static boolean isSuffocationRisk(Location location) {
        if (location == null) return false;
        World world = location.getWorld();
        if (world == null) return false;

        // Feet and head positions
        Location feet = location.clone();
        Location head = location.clone().add(0, 1, 0);

        Block feetBlock = world.getBlockAt(feet);
        Block headBlock = world.getBlockAt(head);

        boolean feetSolid = feetBlock != null && feetBlock.getType().isSolid();
        boolean headSolid = headBlock != null && headBlock.getType().isSolid();

        return feetSolid || headSolid;
    }

    /**
     * Constructs a new TP handler with the necessary plugin instance and configurations.
     *
     * @param plugin The main plugin instance
     */
    public TP(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.config = plugin.getConfigManager();
        this.database = plugin.getDatabase();

        // Initialize command aliases
        this.teleportCommandAliases = Arrays.asList("tp", "tpo", "teleport", "back");
        this.teleportRequestAliases = Arrays.asList("tpa", "tpahere");
        this.teleportAcceptAliases = Arrays.asList("tpaccept", "tpyes");
        this.teleportDenyAliases = Arrays.asList("tpdeny", "tpno");
        this.teleportHereAliases = Arrays.asList("tphere", "s");
        this.teleportPositionAliases = Collections.singletonList("tppos");
        this.teleportToggleAliases = Arrays.asList("tptoggle");
        this.teleportTopBottomAliases = Arrays.asList("top", "bottom");
        this.teleportPetAliases = Collections.singletonList("tppet");
        this.teleportEntityAliases = Arrays.asList("tpent", "tpentity", "tpmob", "teleportmob", "tpm", "tpe");
        this.teleportOfflineAliases = Arrays.asList("otp", "offlinetp", "tpoffline");
        this.teleportCancelAliases = Arrays.asList("tpcancel", "tpacancel");
        
        // Register player quit listener to handle tppet cleanup when players log out
        plugin.getServer().getPluginManager().registerEvents(new PlayerQuitListener(), plugin);

        // Schedule task to clear expired teleport requests
        SchedulerAdapter.runTimer(this::clearExpiredRequests, 1200L, 1200L);
    }



    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Strip namespace (e.g. "allium:back" -> "back") so namespaced commands work
        String usedCommand = (label.contains(":") ? label.substring(label.indexOf(':') + 1) : label).toLowerCase();

        // Handle basic teleport command
        if (teleportCommandAliases.contains(usedCommand)) {
            if (usedCommand.equals("tp")) {
                return handleTeleport(sender, args, "tp");
            }
            if (usedCommand.equals("back")) {
                return handleTeleport(sender, args, "back");
            }
            return handleTeleport(sender, args, "tpo");
        }

        // Handle teleport request command
        if (teleportRequestAliases.contains(usedCommand)) {
            return handleTeleportRequest(sender, args, usedCommand.equals("tpahere"));
        }

        // Handle teleport accept command
        if (teleportAcceptAliases.contains(usedCommand)) {
            return handleTeleportAccept(sender, args);
        }

        // Handle teleport deny command
        if (teleportDenyAliases.contains(usedCommand)) {
            return handleTeleportDeny(sender, args);
        }

        // Handle teleport here command
        if (teleportHereAliases.contains(usedCommand)) {
            return handleTeleportHere(sender, args);
        }

        // Handle teleport position command
        if (teleportPositionAliases.contains(usedCommand)) {
            return handleTeleportPosition(sender, args);
        }

        // Handle teleport toggle command
        if (teleportToggleAliases.contains(usedCommand)) {
            return handleTeleportToggle(sender, args);
        }

        if (teleportPetAliases.contains(usedCommand)) {
            return handleTeleportPet(sender, args);
        }

        if (teleportEntityAliases.contains(usedCommand)) {
            return handleTeleportEntity(sender, args, usedCommand);
        }

        if (teleportOfflineAliases.contains(usedCommand)) {
            return handleTeleportOffline(sender, args);
        }

        // Handle top/bottom commands
        if (teleportTopBottomAliases.contains(usedCommand)) {
            if (usedCommand.equals("top")) {
                return handleTeleportTop(sender);
            } else { // bottom
                return handleTeleportBottom(sender);
            }
        }

        // Handle teleport cancel command
        if (teleportCancelAliases.contains(usedCommand)) {
            return handleTeleportCancel(sender, args);
        }

        return false;
    }

    /**
     * Handles the /tppet command for selecting and teleporting pets
     *
     * @param sender The sender of the command
     * @param args Command arguments
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportPet(CommandSender sender, String[] args) {
        // Get the target player
        Player targetPlayer;
        UUID targetUUID;

        if (args.length > 0) {
            // Check permission to affect other players
            if (!sender.hasPermission("allium.tppet.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "/tppet on others." );
                return true;
            }

            targetPlayer = plugin.getServer().getPlayer(args[0]);
            if (targetPlayer == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }
            targetUUID = targetPlayer.getUniqueId();
        } else {
            // Ensure sender is a player
            if (!(sender instanceof Player player)) {
                Text.sendErrorMessage(sender, "not-a-player", lang);
                return true;
            }
            targetPlayer = player;
            targetUUID = player.getUniqueId();
        }

        // Check base permission for tppet
        if (!targetPlayer.hasPermission("allium.tppet")) {
            Text.sendErrorMessage(targetPlayer, "no-permission", lang, "{cmd}", "/tppet");
            return true;
        }

        // Get the first color code using the Lang method
        String firstColorOfPetToggle = lang.getFirstColorCode("tp.tppet-toggle");

        // Check if pet teleport mode is already active
        PetTeleportListener existingListener = activePetTeleportListeners.get(targetUUID);
        List<org.bukkit.entity.Entity> playerPets = selectedPets.get(targetUUID);
        if (existingListener != null) {
            // Disable existing pet teleport mode
            existingListener.unregisterListener();
            activePetTeleportListeners.remove(targetUUID);

            // Clear selected pets and remove glows
            if (playerPets != null) {
                for (org.bukkit.entity.Entity pet : playerPets) {
                    removePetGlow(pet);
                }
                playerPets.clear();
                selectedPets.remove(targetUUID);
            }

            // Get the disabled style from lang.yml
            String disabledStyle = lang.get("styles.state.false");
            
            // Send toggle message with disabled state
            String stateMessage = lang.get("tp.tppet-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfPetToggle)
                    .replace("{info}", "");
            
            // Send to both sender and target if different players
            if (sender != targetPlayer) {
                // For others, include "for PlayerName"
                sender.sendMessage(stateMessage.replace("{name}", "for " + targetPlayer.getName()));
                // For target, don't include name
                targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
            } else {
                // For self message, don't include name
                targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
            }
            return true;
        }

        // Determine max number of pets based on permission
        int maxPets = 1; // default
        for (int i = 1; i <= 10; i++) { // Check up to 10 pets
            if (targetPlayer.hasPermission("allium.tppet." + i)) {
                maxPets = i;
            }
        }

        // Create and register new pet teleport listener
        PetTeleportListener listener = new PetTeleportListener(targetPlayer, maxPets);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        activePetTeleportListeners.put(targetUUID, listener);

        // Get the enabled style from lang.yml
        String enabledStyle = lang.get("styles.state.true");
        
        // Send toggle message with enabled state
        String stateMessage = lang.get("tp.tppet-toggle")
                .replace("{state}", enabledStyle + "enabled" + firstColorOfPetToggle)
                .replace("{info}", "§aRight-click a pet to select it.");
        
        // Send to both sender and target if different players
        if (sender != targetPlayer) {
            // For others, include "for PlayerName"
            sender.sendMessage(stateMessage.replace("{name}", "for " + targetPlayer.getName()));
            // For target, don't include name
            targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
        } else {
            // For self message, don't include name
            targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
        }
        return true;
    }

    /**
     * Handles the admin entity teleport command for selecting and teleporting any entity
     *
     * @param sender The sender of the command
     * @param args Command arguments
     * @param usedCommand The command alias that was used
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportEntity(CommandSender sender, String[] args, String usedCommand) {
        // Get the target player
        Player targetPlayer;
        UUID targetUUID;

        if (args.length > 0) {
            // Check permission to affect other players
            if (!sender.hasPermission("allium.tpmob.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "/" + usedCommand + " on others.");
                return true;
            }

            targetPlayer = plugin.getServer().getPlayer(args[0]);
            if (targetPlayer == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }
            targetUUID = targetPlayer.getUniqueId();
        } else {
            // Ensure sender is a player
            if (!(sender instanceof Player player)) {
                Text.sendErrorMessage(sender, "not-a-player", lang);
                return true;
            }
            targetPlayer = player;
            targetUUID = player.getUniqueId();
        }

        // Check admin permission
        if (!targetPlayer.hasPermission("allium.admin")) {
            Text.sendErrorMessage(targetPlayer, "no-permission", lang, "{cmd}", usedCommand);
            return true;
        }

        // Get the first color code using the Lang method
        String firstColorOfToggle = lang.getFirstColorCode("tp.tpe-toggle");

        // Check if entity teleport mode is already active
        EntityTeleportListener existingListener = activeEntityTeleportListeners.get(targetUUID);
        Map<UUID, org.bukkit.entity.Entity> entityMap = selectedEntities.get(targetUUID);
        if (existingListener != null) {
            // Disable existing entity teleport mode
            existingListener.unregisterListener();
            activeEntityTeleportListeners.remove(targetUUID);

            // Clear selected entities and remove glows
            if (entityMap != null) {
                for (org.bukkit.entity.Entity entity : entityMap.values()) {
                    removePetGlow(entity);
                }
                entityMap.clear();
                selectedEntities.remove(targetUUID);
            }

            // Get the disabled style from lang.yml
            String disabledStyle = lang.get("styles.state.false");
            
            // Send toggle message with disabled state
            String stateMessage = lang.get("tp.tpe-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                    .replace("{info}", "");
            
            // Send to both sender and target if different players
            if (sender != targetPlayer) {
                // For others, include "for PlayerName"
                sender.sendMessage(stateMessage.replace("{name}", "for " + targetPlayer.getName()));
                // For target, don't include name
                targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
            } else {
                // For self message, don't include name
                targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
            }
            return true;
        }

        // Determine max number of entities based on permission
        int maxEntities = 1; // default
        for (int i = 1; i <= 20; i++) { // Check up to 20 entities
            if (targetPlayer.hasPermission("allium.tpmob." + i)) {
                maxEntities = i;
            }
        }

        // Create and register new entity teleport listener
        EntityTeleportListener listener = new EntityTeleportListener(targetPlayer, maxEntities);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        activeEntityTeleportListeners.put(targetUUID, listener);

        // Get the enabled style from lang.yml
        String enabledStyle = lang.get("styles.state.true");
        
        // Send toggle message with enabled state
        String stateMessage = lang.get("tp.tpe-toggle")
                .replace("{state}", enabledStyle + "enabled" + firstColorOfToggle)
                .replace("{info}", "§aRight-click any entity to select it. (Admin mode)");
        
        // Send to both sender and target if different players
        if (sender != targetPlayer) {
            // For others, include "for PlayerName"
            sender.sendMessage(stateMessage.replace("{name}", "for " + targetPlayer.getName()));
            // For target, don't include name
            targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
        } else {
            // For self message, don't include name
            targetPlayer.sendMessage(stateMessage.replace("{name}", ""));
        }
        return true;
    }
            
    /**
     * Temporarily disables distance checking for entity teleportation
     * This prevents the "You have moved too far" message when teleporting entities
     * while flying or moving quickly as an admin
     */
    private void disableDistanceChecking() {
        // For admins using /tpe, we're just going to teleport entities instantly
        // This effectively bypasses any distance checking that might occur
        Text.sendDebugLog(INFO, "Distance checking disabled for entity teleportation");
        
        // We don't need a listener since we're teleporting entities instantly
        // This avoids the "moved too far" message that can happen with delayed teleports
    }
    
    private void teleportEntities(UUID playerUUID, Player player, List<Entity> entities) {
        if (player == null || !player.isOnline() || entities == null || entities.isEmpty()) {
            Text.sendDebugLog(WARN, "Cannot teleport entities: invalid parameters");
            return;
        }
        
        // Disable distance checking to prevent "moved too far" messages
        disableDistanceChecking();
        
        // Calculate offset positions in a circle around the player
        double radius = 2.0;
        int numEntities = entities.size();
        
        Text.sendDebugLog(INFO, "Teleporting " + numEntities + " entities INSTANTLY for player " + player.getName() + " (distance checking disabled)");
        
        // Create a copy of the entities list to avoid concurrent modification
        final List<Entity> entityList = new ArrayList<>(entities);
            
            // Get current player location for teleporting
            Location targetLocation = player.getLocation();
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
            
            // Teleport each entity to a position around the player
            for (int i = 0; i < entityList.size(); i++) {
                Entity entity = entityList.get(i);
                
                // Enhanced debug logging
                if (entity == null) {
                    Text.sendDebugLog(WARN, "Skipping null entity during teleport");
                    continue;
                } 
                
                if (!entity.isValid()) {
                    Text.sendDebugLog(WARN, "Skipping invalid entity during teleport: " + entity.getType().name() + " (UUID: " + entity.getUniqueId() + ")");
                    continue;
                }
                
                // Log entity state before teleport
                Text.sendDebugLog(INFO, "Entity pre-teleport state: Type=" + entity.getType().name() + 
                                      ", UUID=" + entity.getUniqueId() + 
                                      ", World=" + entity.getWorld().getName() + 
                                      ", Location=" + entity.getLocation() + 
                                      ", isDead=" + entity.isDead() + 
                                      ", isGlowing=" + entity.isGlowing());
                
                // Check if entity is in the same world as the player
                if (!entity.getWorld().equals(targetLocation.getWorld())) {
                    Text.sendDebugLog(WARN, "Entity is in a different world, cannot teleport: " + entity.getType().name() + " (" + entity.getWorld().getName() + 
                                              " vs " + targetLocation.getWorld().getName() + ")");
                    continue;
                }
                
                // Calculate position in a circle around the player
                double angle = 2 * Math.PI * i / numEntities;
                double x = targetLocation.getX() + radius * Math.cos(angle);
                double z = targetLocation.getZ() + radius * Math.sin(angle);
                
                // Create the teleport location
                Location entityLocation = new Location(
                    targetLocation.getWorld(),
                    x,
                    targetLocation.getY(),
                    z,
                    targetLocation.getYaw(),
                    targetLocation.getPitch()
                );
                
                // Only apply slow falling to passive mobs that are in the air
                boolean isPassiveMob = entity instanceof Animals || 
                                     entity instanceof WaterMob ||
                                     entity instanceof Golem ||
                                     entity.getType() == EntityType.VILLAGER ||
                                     entity.getType() == EntityType.WANDERING_TRADER ||
                                     entity.getType() == EntityType.IRON_GOLEM ||
                                     entity.getType() == EntityType.SNOW_GOLEM;
                Text.sendDebugLog(INFO, "Entity isPassiveMob: " + isPassiveMob + ", isOnGround: " + entity.isOnGround());
                
                // Apply slow falling to passive mobs
                boolean shouldApplySlowFalling = isPassiveMob;

                // Folia-safe: schedule teleport and cleanup on the entity's scheduler
                SchedulerAdapter.runAtEntity(entity, () -> {
                    boolean success = entity.teleport(entityLocation);
                    if (success) {
                        // Remove glowing effect after successful teleport and reset persistence
                        entity.setGlowing(false);
                        if (entity instanceof org.bukkit.entity.Mob) {
                            ((org.bukkit.entity.Mob) entity).setRemoveWhenFarAway(true);
                        }
                        
                        // Apply slow falling to passive mobs in air
                        if (shouldApplySlowFalling && entity instanceof org.bukkit.entity.LivingEntity livingEntity) {
                            // Apply slow falling effect (level 2 for 30 seconds)
                            livingEntity.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOW_FALLING, 
                                30 * 20, // 30 seconds
                                1, // level 2 (0-based)
                                false, // ambient
                                false, // particles
                                true // icon
                            ));
                            
                            // Schedule a task to check when the entity lands and remove the effect
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (!entity.isValid() || livingEntity.isOnGround() || livingEntity.isDead()) {
                                        // Remove the effect if entity is on ground, dead, or invalid
                                        livingEntity.removePotionEffect(PotionEffectType.SLOW_FALLING);
                                        cancel();
                                        return;
                                    }
                                    // Check again in 5 ticks (0.25 seconds)
                                }
                            }.runTaskTimer(plugin, 5L, 5L);
                        }
                        
                        successCount.incrementAndGet();
                        Text.sendDebugLog(INFO, "Successfully teleported entity " + entity.getType().name() + 
                                               " to player " + player.getName() + 
                                               (shouldApplySlowFalling ? " with slow falling" : ""));
                    } else {
                        Text.sendDebugLog(WARN, "Failed to teleport entity " + entity.getType().name() + 
                                                   " to player " + player.getName());
                    }
                });
            }
            
            // Notify the player after allowing entity tasks to run
            SchedulerAdapter.runAtEntityLater(player, () -> {
                int count = successCount.get();
                if (count > 0) {
                    // Get the base message
                    String message = lang.get("tp.tppet-teleported")
                            .replace("{count}", String.valueOf(count));
                    
                    // Fix the pluralization manually
                    if (message.contains("{s|y|ies}")) {
                        if (count == 1) {
                            message = message.replace("{s|y|ies}", "y"); // singular: "entity"
                        } else {
                            message = message.replace("{s|y|ies}", "ies"); // plural: "entities"
                        }
                    } else if (message.contains("{s}")) {
                        // Handle old format for backward compatibility
                        message = message.replace("{s}", count == 1 ? "" : "s");
                    }
                    
                    player.sendMessage(message);
                } else {
                    player.sendMessage(lang.get("tp.tppet-none-teleported"));
                }
            }, 2L);
    }

    private void removePetGlow(org.bukkit.entity.Entity entity) {
        if (entity != null) {
            SchedulerAdapter.runAtEntity(entity, () -> {
                if (entity.isValid()) {
                    entity.setGlowing(false);
                }
            });
        }
    }

    /**
     * Interface for teleport listeners with common functionality
     */
    private interface TeleportListener extends Listener {
        /**
         * Unregister this listener from the event system
         */
        void unregisterListener();
    }

    private class PetTeleportListener implements TeleportListener {
        private final UUID playerUUID;
        private final int maxPets;
        private boolean isRegistered = true;
        private final Player player;
        private final Location startLocation;
        private SchedulerAdapter.TaskHandle checkTaskHandle = null;
        private static final int CHECK_INTERVAL = 20; // Check every second (20 ticks)
        private static final double MAX_DISTANCE = 30.0; // Maximum distance in blocks
        private final Set<String> processedEvents = new HashSet<>();

        public PetTeleportListener(Player player, int maxPets) {
            this.player = player;
            this.playerUUID = player.getUniqueId();
            this.maxPets = maxPets;
            this.startLocation = player.getLocation();
            
            // Start the distance check task on the player's entity scheduler (Folia-safe)
            scheduleNextCheck();
        }

        /**
         * Schedule the next distance check on the player's entity scheduler.
         */
        private void scheduleNextCheck() {
            // Cancel any existing
            if (checkTaskHandle != null) {
                checkTaskHandle.cancel();
            }
            checkTaskHandle = SchedulerAdapter.runAtEntityLater(player, this::checkDistance, CHECK_INTERVAL);
        }

        /**
         * Checks if the player has moved too far from the starting location
         * and cleans up if necessary
         */
        private void checkDistance() {
            if (player == null || !player.isOnline()) {
                cleanup(false);
                return;
            }
            
            // Check distance from start location (using distanceSquared for better performance)
            if (player.getLocation().distanceSquared(startLocation) > MAX_DISTANCE * MAX_DISTANCE) {
                // Player moved too far, cleanup
                cleanup(true);
                return;
            }
            
            // Reschedule next check while still active
            if (isRegistered) {
                scheduleNextCheck();
            }
        }
        
        /**
         * Cleans up resources and unregisters this listener
         * @param showDistanceMessage Whether to show the distance warning message
         */
        private void cleanup(boolean showDistanceMessage) {
            // Only cleanup if not already done
            if (isRegistered) {
                // Cancel the check task
                if (checkTaskHandle != null) {
                    checkTaskHandle.cancel();
                    checkTaskHandle = null;
                }
                
                // Unregister events
                HandlerList.unregisterAll(this);
                isRegistered = false;
                
                // Clear any active pet glows
                List<org.bukkit.entity.Entity> pets = selectedPets.get(playerUUID);
                if (pets != null) {
                    for (org.bukkit.entity.Entity pet : pets) {
                        if (pet != null && pet.isValid()) {
                            removePetGlow(pet);
                        }
                    }
                    pets.clear();
                    selectedPets.remove(playerUUID);
                }
                
                // Remove from active listeners
                activePetTeleportListeners.remove(playerUUID);
                
                // Show distance message if needed
                if (showDistanceMessage && player != null && player.isOnline()) {
                    // Get the first color code and disabled style
                    String firstColorOfPetToggle = lang.getFirstColorCode("tp.tppet-toggle");
                    String disabledStyle = lang.get("styles.state.false");
                    
                    // Notify the player
                    player.sendMessage(lang.get("tp.tppet-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfPetToggle)
                    .replace("{name}", "")
                    .replace("{info}", "§cYou have moved too far from your starting location.")
                    );
                }
            }
        }
        
        /**
         * Unregister this listener from the event system
         */
        public void unregisterListener() {
            cleanup(false);
        }

        @EventHandler
        public void onPlayerInteractEntity(PlayerInteractEntityEvent event) throws Exception {
            // Ignore events if listener is no longer registered
            if (!isRegistered) return;
            // Only handle events for the specific player
            if (!event.getPlayer().getUniqueId().equals(playerUUID)) return;

            // Get the clicked entity
            org.bukkit.entity.Entity clickedEntity = event.getRightClicked();

            // Check if the entity is a tamed animal or compatible pet type
            if (!isTeleportablePet(clickedEntity)) return;

            // Get or create the list of selected pets for this player
            List<org.bukkit.entity.Entity> playerSelectedPets = selectedPets.computeIfAbsent(
                playerUUID, k -> new ArrayList<>()
            );

            // Check if the entity is already selected
            if (playerSelectedPets.contains(clickedEntity)) {
                // Deselect the pet
                playerSelectedPets.remove(clickedEntity);
                removePetGlowEffect(clickedEntity);
                
                // Get the message and first color code
                String tpPetActionMessage = lang.get("tp.tppet-action");
                String firstColorOfPetAction = lang.getFirstColorCode("tp.tppet-action");
                String disabledStyle = lang.get("styles.state.false");
                
                event.getPlayer().sendMessage(tpPetActionMessage
                .replace("{entity}", clickedEntity.getType().name().substring(0, 1).toUpperCase() + clickedEntity.getType().name().substring(1).toLowerCase())
                .replace("{pet}", getPetName(clickedEntity))
                .replace("{state}", disabledStyle + "deselected" + firstColorOfPetAction));

            } else {
                // Check max pets limit
                if (playerSelectedPets.size() >= maxPets) {
                    Text.sendErrorMessage(event.getPlayer(), "tp.tppet-maxpets", lang, "{max}", String.valueOf(maxPets));
                    return;
                }

                // Select the pet
                playerSelectedPets.add(clickedEntity);                
                SchedulerAdapter.runAtEntity(clickedEntity, () -> clickedEntity.setGlowing(true));
                
                // Get the message and first color code
                String tpPetActionMessage = lang.get("tp.tppet-action");
                String firstColorOfPetAction = lang.getFirstColorCode("tp.tppet-action");
                String enabledStyle = lang.get("styles.state.true");
                
                event.getPlayer().sendMessage(tpPetActionMessage
                .replace("{entity}", clickedEntity.getType().name().substring(0, 1).toUpperCase() + clickedEntity.getType().name().substring(1).toLowerCase())
                // get custom name
                .replace("{pet}", getPetName(clickedEntity))
                .replace("{state}", enabledStyle + "selected" + firstColorOfPetAction));
            }

            // Update the selected pets map
            selectedPets.put(playerUUID, playerSelectedPets);

            // Prevent further interaction
            event.setCancelled(true);
        }

        /**
         * Check if an entity is a valid pet for teleportation
         *
         * @param entity The entity to check
         * @return true if the entity can be teleported
         */
        private boolean isTeleportablePet(org.bukkit.entity.Entity entity) {
            // Add more pet types as needed
            return entity instanceof Tameable && ((Tameable) entity).getOwner() != null
                    && ((Tameable) entity).getOwner().getUniqueId().equals(playerUUID);
        }
        private void removePetGlowEffect(org.bukkit.entity.Entity entity) {
            if (entity != null && entity.isValid()) {
                SchedulerAdapter.runAtEntity(entity, () -> entity.setGlowing(false));
            }
        }

        /**
         * Get a human-readable name for a pet
         *
         * @param entity The pet entity
         * @return The name of the pet
         */
        private String getPetName(org.bukkit.entity.Entity entity) {
            if (entity instanceof Tameable) {
                return entity.getType().name().toLowerCase();
            }
            return "pet";
        }
    }

    /**
     * EntityTeleportListener class for admin entity teleportation
     * Allows admins to select any entity (except players) for teleportation
     */
    private class EntityTeleportListener implements TeleportListener {
        private final UUID playerUUID;
        private final int maxEntities;
        private boolean isRegistered = true;
        private final Player player;
        private final Location startLocation;
        private SchedulerAdapter.TaskHandle checkTaskHandle = null;
        private static final int CHECK_INTERVAL = 20; // Check every second (20 ticks)
        private static final double MAX_DISTANCE = 30.0; // Maximum distance in blocks
        private final Set<String> processedEvents = new HashSet<>();

        public EntityTeleportListener(Player player, int maxEntities) {
            this.player = player;
            this.playerUUID = player.getUniqueId();
            this.maxEntities = maxEntities;
            this.startLocation = player.getLocation().clone();
            
            // Disable distance checking for admins by default
            if (player.hasPermission("allium.tpmob")) {
                this.distanceCheckingEnabled = false;
                Text.sendDebugLog(INFO, "Distance checking disabled for admin: " + player.getName());
            } else {
                // Enable distance checking for non-admins
                this.distanceCheckingEnabled = true;
            }
            
            // Register events
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            
            // Start the distance check task (will be skipped for admins due to distanceCheckingEnabled = false)
            scheduleNextCheck();
        }
        
        /**
         * Checks if the player has moved too far from the starting location
         * and cleans up if necessary
         */
        private boolean distanceCheckingEnabled = false; // Disabled by default for admins
        
        /**
         * Disables the distance checking feature
         * This prevents the "moved too far" message when teleporting
         */
        public void disableDistanceChecking() {
            this.distanceCheckingEnabled = false;
            Text.sendDebugLog(INFO, "Distance checking disabled for player " + player.getName());
        }
        
        private void checkDistance() {
            // Skip distance checking if disabled (which is the default for admins)
            if (!distanceCheckingEnabled) {
                return;
            }
            
            if (player == null || !player.isOnline()) {
                cleanup(false);
                return;
            }
            
            // Check distance from start location - only applies to non-admins now
            if (player.getLocation().distance(startLocation) > MAX_DISTANCE) {
                // Player moved too far, cleanup
                cleanup(true);
            }
        }
        
        /**
         * Cleans up resources and unregisters this listener
         * @param showDistanceMessage Whether to show the distance warning message
         */
        private void cleanup(boolean showDistanceMessage) {
            // Skip cleanup for admins if triggered by distance checking
            if (showDistanceMessage && player != null && player.isOnline() && player.hasPermission("allium.tpmob")) {
                Text.sendDebugLog(INFO, "Skipping distance-based cleanup for admin: " + player.getName());
                return;
            }
            
            // Only cleanup if not already done
            if (isRegistered) {
                // Cancel the check task
                if (checkTaskHandle != null) {
                    checkTaskHandle.cancel();
                    checkTaskHandle = null;
                }
                
                // Unregister events
                HandlerList.unregisterAll(this);
                isRegistered = false;
                
                // Clear any active entity glows
                Map<UUID, org.bukkit.entity.Entity> entityMap = selectedEntities.get(playerUUID);
                if (entityMap != null) {
                    for (org.bukkit.entity.Entity entity : entityMap.values()) {
                        if (entity != null && entity.isValid()) {
                            removePetGlow(entity);
                        }
                    }
                    entityMap.clear();
                    selectedEntities.remove(playerUUID);
                }
                
                // Remove from active listeners
                activeEntityTeleportListeners.remove(playerUUID);
                
                // Show distance message if needed
                if (showDistanceMessage && player != null && player.isOnline()) {
                    // Get the first color code and disabled style
                    String firstColorOfToggle = lang.getFirstColorCode("tp.tpe-toggle");
                    String disabledStyle = lang.get("styles.state.false");
                    
                    // Notify the player
                    player.sendMessage(lang.get("tp.tpe-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                    .replace("{name}", "")
                    .replace("{info}", "§cYou have moved too far from your starting location.")
                    );
                }
            }
        }
        
        /**
         * Unregister this listener from the event system
         */
        @Override
        public void unregisterListener() {
            cleanup(false);
        }

        // Store last processed entity and timestamp to handle double events
        private UUID lastProcessedEntityUUID = null;
        private long lastProcessedTime = 0;
        // Coalesce duplicate rapid events by tracking pending scheduled selections per entity
        private final java.util.Set<UUID> pendingSelections = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        
        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
            // Only handle events for the specific player
            if (!event.getPlayer().getUniqueId().equals(playerUUID)) return;
            
            // Ignore events if listener is no longer registered
            if (!isRegistered) return;
            
            // Get the clicked entity
            org.bukkit.entity.Entity clickedEntity = event.getRightClicked();
            if (clickedEntity == null) return;
            
            // Check if the entity is valid for teleportation (any entity except players)
            if (!isTeleportableEntity(clickedEntity)) return;
            
            // Get the entity's UUID for tracking
            UUID entityUUID = clickedEntity.getUniqueId();
            long currentTime = System.currentTimeMillis();
            
            // CRITICAL FIX: Check if this is a duplicate event for the same entity
            // Minecraft sometimes fires the same event twice for a single click
            if (entityUUID.equals(lastProcessedEntityUUID) && (currentTime - lastProcessedTime < 200)) {
                Text.sendDebugLog(INFO, "Ignoring duplicate event for entity: " + entityUUID);
                event.setCancelled(true);
                return;
            }
            
            // Update last processed entity and time
            lastProcessedEntityUUID = entityUUID;
            lastProcessedTime = currentTime;
            
            // Add to processed events for additional safety
            String eventId = currentTime + "_" + entityUUID;
            processedEvents.add(eventId);
            
            // Cancel the event to prevent other plugins from processing it
            event.setCancelled(true);
            
            // Avoid scheduling duplicate tasks for the same entity within a short window
            if (!pendingSelections.add(entityUUID)) {
                Text.sendDebugLog(INFO, "Ignoring duplicate pending selection for entity: " + entityUUID);
                event.setCancelled(true);
                return;
            }

            // Process the entity selection/deselection with a slight delay to avoid conflicts
            final Player playerRef = event.getPlayer();
            Text.sendDebugLog(INFO, "Scheduling selection (entity event) for entity " + entityUUID + " by player " + event.getPlayer().getName());
            SchedulerAdapter.runAtEntityLater(clickedEntity, () -> {
                Text.sendDebugLog(INFO, "[TPE] Runnable executing (GLOBAL) for entity event: " + entityUUID + " player=" + playerRef.getName());
                try {
                    processEntitySelection(playerRef, clickedEntity, entityUUID);
                } finally {
                    pendingSelections.remove(entityUUID);
                }
            }, 2L);
            // Fallback: if entity-scheduled runnable didn't execute, reschedule on the entity's scheduler (Folia-safe)
            SchedulerAdapter.runLater(() -> {
                if (pendingSelections.remove(entityUUID)) {
                    Text.sendDebugLog(WARN, "[TPE] Fallback runnable (rescheduled at-entity) for entity event: " + entityUUID + " player=" + playerRef.getName());
                    SchedulerAdapter.runAtEntity(clickedEntity, () -> processEntitySelection(playerRef, clickedEntity, entityUUID));
                }
            }, 4L);
        }
        
        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
            if (!event.getPlayer().getUniqueId().equals(playerUUID)) return;
            if (!isRegistered) return;
            org.bukkit.entity.Entity clickedEntity = event.getRightClicked();
            if (clickedEntity == null) return;
            if (!isTeleportableEntity(clickedEntity)) return;
            UUID entityUUID = clickedEntity.getUniqueId();
            long currentTime = System.currentTimeMillis();
            if (entityUUID.equals(lastProcessedEntityUUID) && (currentTime - lastProcessedTime < 200)) {
                Text.sendDebugLog(INFO, "Ignoring duplicate at-entity event for entity: " + entityUUID);
                event.setCancelled(true);
                return;
            }
            lastProcessedEntityUUID = entityUUID;
            lastProcessedTime = currentTime;
            String eventId = currentTime + "_AT_" + entityUUID;
            processedEvents.add(eventId);
            event.setCancelled(true);
            // Avoid scheduling duplicate tasks for the same entity within a short window
            if (!pendingSelections.add(entityUUID)) {
                Text.sendDebugLog(INFO, "Ignoring duplicate pending at-entity selection for entity: " + entityUUID);
                return;
            }
            final Player playerRef = event.getPlayer();
            Text.sendDebugLog(INFO, "Scheduling selection (at-entity event) for entity " + entityUUID + " by player " + event.getPlayer().getName());
            SchedulerAdapter.runAtEntityLater(clickedEntity, () -> {
                Text.sendDebugLog(INFO, "[TPE] Runnable executing for at-entity event: " + entityUUID + " player=" + playerRef.getName());
                try {
                    processEntitySelection(playerRef, clickedEntity, entityUUID);
                } finally {
                    pendingSelections.remove(entityUUID);
                }
            }, 2L);
            // Fallback: ensure execution even if entity-scheduled task is dropped; reschedule at-entity to avoid off-thread access
            SchedulerAdapter.runLater(() -> {
                if (pendingSelections.remove(entityUUID)) {
                    Text.sendDebugLog(WARN, "[TPE] Fallback runnable (rescheduled at-entity) for at-entity event: " + entityUUID + " player=" + playerRef.getName());
                    SchedulerAdapter.runAtEntity(clickedEntity, () -> processEntitySelection(playerRef, clickedEntity, entityUUID));
                }
            }, 4L);
        }
        
        /**
         * Process entity selection/deselection
         * This is run in the next tick to avoid conflicts
         */
        private void processEntitySelection(Player player, org.bukkit.entity.Entity clickedEntity, UUID entityUUID) {
            // Add detailed debug logging
            Text.sendDebugLog(INFO, "Processing entity selection for player: " + player.getName() + ", entity: " + 
                                   clickedEntity.getType().name() + ", UUID: " + entityUUID);
            
            // Get or create the map of selected entities for this player
            Map<UUID, org.bukkit.entity.Entity> entityMap = selectedEntities.computeIfAbsent(
                playerUUID, k -> new java.util.concurrent.ConcurrentHashMap<>()
            );
            
            // Debug log the current selection state
            Text.sendDebugLog(INFO, "Current selection state - Player has " + entityMap.size() + " entities selected");
            for (Map.Entry<UUID, org.bukkit.entity.Entity> entry : entityMap.entrySet()) {
                Text.sendDebugLog(INFO, "  - Selected entity: " + entry.getValue().getType().name() + ", UUID: " + entry.getKey());
            }
            
            // Check if the entity is already selected by UUID
            boolean isAlreadySelected = entityMap.containsKey(entityUUID);
            Text.sendDebugLog(INFO, "Entity is already selected: " + isAlreadySelected);
            
            if (isAlreadySelected) {
                // Deselect the entity
                entityMap.remove(entityUUID);
                
                // Remove glow effect and restore default persistence
                if (clickedEntity != null && clickedEntity.isValid()) {
                    SchedulerAdapter.runAtEntity(clickedEntity, () -> clickedEntity.setGlowing(false));
                    
                    // Restore default persistence behavior for mobs
                    if (clickedEntity instanceof org.bukkit.entity.Mob) {
                        org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) clickedEntity;
                        SchedulerAdapter.runAtEntity(mob, () -> mob.setRemoveWhenFarAway(true)); // Reset to default behavior
                        Text.sendDebugLog(INFO, "Restored default despawn behavior for mob: " + clickedEntity.getType().name());
                    }
                }
                
                // Get the message and first color code
                String actionMessage = lang.get("tp.tpe-action");
                if (actionMessage == null || actionMessage.isEmpty()) {
                    Text.sendDebugLog(WARN, "Lang key 'tp.tpe-action' missing or empty. Using default message template.");
                    actionMessage = "&aEntity &e{mob} &ahas been {state}.";
                }
                String firstColorOfAction = lang.getFirstColorCode("tp.tpe-action");
                String disabledStyle = lang.get("styles.state.false");
                final String msgTemplateDeselect = actionMessage;
                final String mobNameDeselect = getEntityName(clickedEntity);
                
                // Folia-safe: schedule message on the player's entity scheduler
                Text.sendDebugLog(INFO, "Queueing deselection message for player " + player.getName());
                SchedulerAdapter.runAtEntity(player, () -> {
                    String msg = msgTemplateDeselect
                        .replace("{mob}", mobNameDeselect)
                        .replace("{state}", disabledStyle + "deselected" + firstColorOfAction);
                    try {
                        Text.sendDebugLog(INFO, "Sending deselection message to player " + player.getName() + ": " + msg);
                        net.kyori.adventure.text.Component comp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
                        player.sendMessage(comp);
                    } catch (Throwable t) {
                        Text.sendDebugLog(WARN, "Failed to send deselection message via Component, falling back: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        player.sendMessage(net.survivalfun.core.managers.core.Text.colorize(msg));
                    }
                });
            } else {
                // Check max entities limit
                if (entityMap.size() >= maxEntities) {
                    // Folia-safe: send error on the player's entity scheduler
                    SchedulerAdapter.runAtEntity(player, () -> {
                        Text.sendErrorMessage(player, "tp.tppet-maxpets", lang, "{max}", String.valueOf(maxEntities));
                    });
                    return;
                }
                
                // Select the entity by UUID
                entityMap.put(entityUUID, clickedEntity);
                
                // Add glow effect to the entity and make it persistent
                if (clickedEntity != null && clickedEntity.isValid()) {
                    SchedulerAdapter.runAtEntity(clickedEntity, () -> clickedEntity.setGlowing(true));
                    
                    // Make mobs persistent so they don't despawn when chunks unload
                    if (clickedEntity instanceof org.bukkit.entity.Mob) {
                        org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) clickedEntity;
                        SchedulerAdapter.runAtEntity(mob, () -> mob.setRemoveWhenFarAway(false)); // Prevent despawning
                        Text.sendDebugLog(INFO, "Made mob persistent to prevent despawning: " + clickedEntity.getType().name());
                    }
                }
                
                // Get the message and first color code
                String actionMessage = lang.get("tp.tpe-action");
                if (actionMessage == null || actionMessage.isEmpty()) {
                    Text.sendDebugLog(WARN, "Lang key 'tp.tpe-action' missing or empty. Using default message template.");
                    actionMessage = "&aEntity &e{mob} &ahas been {state}.";
                }
                String firstColorOfAction = lang.getFirstColorCode("tp.tpe-action");
                String enabledStyle = lang.get("styles.state.true");
                final String msgTemplateSelect = actionMessage;
                final String mobNameSelect = getEntityName(clickedEntity);
                
                // Folia-safe: schedule message on the player's entity scheduler
                Text.sendDebugLog(INFO, "Queueing selection message for player " + player.getName());
                SchedulerAdapter.runAtEntity(player, () -> {
                    String msg = msgTemplateSelect
                        .replace("{mob}", mobNameSelect)
                        .replace("{state}", enabledStyle + "selected" + firstColorOfAction);
                    try {
                        Text.sendDebugLog(INFO, "Sending selection message to player " + player.getName() + ": " + msg);
                        net.kyori.adventure.text.Component comp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
                        player.sendMessage(comp);
                    } catch (Throwable t) {
                        Text.sendDebugLog(WARN, "Failed to send selection message via Component, falling back: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        player.sendMessage(net.survivalfun.core.managers.core.Text.colorize(msg));
                    }
                });
            }
        }
        
        /**
         * Check if an entity is valid for teleportation (any entity except players)
         *
         * @param entity The entity to check
         * @return true if the entity can be teleported
         */
        private boolean isTeleportableEntity(org.bukkit.entity.Entity entity) {
            // Allow any entity except players
            return entity != null && !(entity instanceof Player);
        }

        /**
         * Get a human-readable name for an entity
         *
         * @param entity The entity to get the name for
         * @return The entity's name or a default name based on entity type
         */
        private String getEntityName(org.bukkit.entity.Entity entity) {
            if (entity == null) return "Unknown";
            
            // Use Component API instead of deprecated getCustomName()
            if (entity.customName() != null) {
                // Serialize the component to plain text to avoid verbose TextComponentImpl dumps
                return PlainTextComponentSerializer.plainText().serialize(entity.customName());
            }
            
            // Return formatted entity type name
            return entity.getType().name().substring(0, 1).toUpperCase() + 
                   entity.getType().name().substring(1).toLowerCase().replace("_", " ");
        }
        
        /**
         * Schedule the next distance check on the player's entity scheduler.
         */
        private void scheduleNextCheck() {
            // Cancel any existing task
            if (checkTaskHandle != null) {
                checkTaskHandle.cancel();
            }
            // Only schedule if distance checking is enabled
            if (!distanceCheckingEnabled) {
                return;
            }
            checkTaskHandle = SchedulerAdapter.runAtEntityLater(player, this::checkDistance, CHECK_INTERVAL);
        }
    }

    private void teleportPets(UUID playerUUID, Player player, List<Entity> pets) {
        if (player == null || !player.isOnline() || pets == null || pets.isEmpty()) {
            Text.sendDebugLog(WARN, "Cannot teleport pets: invalid parameters");
            return;
        }
        
        // Calculate offset positions in a circle around the player
        double radius = 1.8; // slightly tighter than entities
        int numPets = pets.size();
        
        Text.sendDebugLog(INFO, "Teleporting " + numPets + " pets for player " + player.getName());
        
        // Create a copy to avoid concurrent modification
        final List<Entity> petList = new ArrayList<>(pets);
        Location targetLocation = player.getLocation();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (int i = 0; i < petList.size(); i++) {
            Entity pet = petList.get(i);
            if (pet == null) {
                Text.sendDebugLog(WARN, "Skipping null pet during teleport");
                continue;
            }
            if (!pet.isValid()) {
                Text.sendDebugLog(WARN, "Skipping invalid pet during teleport: " + pet.getType().name());
                continue;
            }
            // Only same-world teleports are supported here
            if (!pet.getWorld().equals(targetLocation.getWorld())) {
                Text.sendDebugLog(WARN, "Pet is in a different world, cannot teleport: " + pet.getType().name());
                continue;
            }
            
            double angle = 2 * Math.PI * i / numPets;
            double x = targetLocation.getX() + radius * Math.cos(angle);
            double z = targetLocation.getZ() + radius * Math.sin(angle);
            Location petLocation = new Location(
                targetLocation.getWorld(),
                x,
                targetLocation.getY(),
                z,
                targetLocation.getYaw(),
                targetLocation.getPitch()
            );
            
            // Folia-safe: schedule per-entity teleport
            SchedulerAdapter.runAtEntity(pet, () -> {
                boolean success = pet.teleport(petLocation);
                if (success) {
                    // Remove glowing after successful teleport
                    pet.setGlowing(false);
                    if (pet instanceof org.bukkit.entity.Mob) {
                        ((org.bukkit.entity.Mob) pet).setRemoveWhenFarAway(true);
                    }
                    successCount.incrementAndGet();
                } else {
                    Text.sendDebugLog(WARN, "Failed to teleport pet " + pet.getType().name() +
                                               " to player " + player.getName());
                }
            });
        }
        
        // Post-teleport notify and cleanup selection/listener a couple ticks later
        SchedulerAdapter.runAtEntityLater(player, () -> {
            int count = successCount.get();
            if (count > 0) {
                String message = lang.get("tp.tppet-teleported").replace("{count}", String.valueOf(count));
                if (message.contains("{s|y|ies}")) {
                    message = message.replace("{s|y|ies}", count == 1 ? "y" : "ies");
                } else if (message.contains("{s}")) {
                    // Handle old format for backward compatibility
                    message = message.replace("{s}", count == 1 ? "" : "s");
                }
                
                player.sendMessage(message);
            } else {
                player.sendMessage(lang.get("tp.tppet-none-teleported"));
            }
            
            // Cleanup selection
            List<Entity> current = selectedPets.get(playerUUID);
            if (current != null) {
                current.clear();
                selectedPets.remove(playerUUID);
            }
            // Disable active listener if present
            PetTeleportListener listener = activePetTeleportListeners.get(playerUUID);
            if (listener != null) {
                listener.unregisterListener();
                activePetTeleportListeners.remove(playerUUID);
            }
        }, 2L);
    }

    /**
     * Teleport selected pets to a target location
     *
     * @param player The player whose pets to teleport
     * @param targetLocation The location to teleport pets to
     * @return true if pets were teleported successfully
     */
    public boolean teleportSelectedPets(Player player, Location targetLocation) {
        UUID playerUUID = player.getUniqueId();
        List<org.bukkit.entity.Entity> playerPets = selectedPets.get(playerUUID);
        if (playerPets == null || playerPets.isEmpty()) {
            return false;
        }

        // Teleport each selected pet
        for (org.bukkit.entity.Entity pet : playerPets) {
            SchedulerAdapter.runAtEntity(pet, () -> {
                pet.teleport(targetLocation);
                removePetGlow(pet);
            });
        }

        // Clear selected pets and remove glows
        playerPets.clear();
        selectedPets.remove(playerUUID);

        // Disable pet teleport mode
        PetTeleportListener listener = activePetTeleportListeners.get(playerUUID);
        if (listener != null) {
            listener.unregisterListener();
            activePetTeleportListeners.remove(playerUUID);
        }

        // Get the first color code and disabled style
        String firstColorOfPetToggle = lang.getFirstColorCode("tp.tppet-toggle");
        String disabledStyle = lang.get("styles.state.false");
        
        player.sendMessage(lang.get("tp.tppet-toggle")
                .replace("{state}", disabledStyle + "disabled" + firstColorOfPetToggle)
                .replace("{name}", "")
                .replace("{info}", ""));
        return true;
    }

    /**
     * Auto-disable pet teleport mode after successful teleport
     * This is called by external commands like /spawn to clean up pet teleport state
     *
     * @param playerUUID The UUID of the player whose pet teleport mode should be disabled
     */
    public void autoDisablePetTeleport(UUID playerUUID) {
        handleSuccessfulCompanionTeleport(playerUUID, plugin.getServer().getPlayer(playerUUID));
    }

    public void handleSuccessfulCompanionTeleport(UUID playerUUID, Player directPlayer) {
        if (playerUUID == null) {
            return;
        }

        List<org.bukkit.entity.Entity> playerPets = selectedPets.get(playerUUID);
        if (playerPets != null) {
            for (org.bukkit.entity.Entity pet : playerPets) {
                if (pet != null && pet.isValid()) {
                    SchedulerAdapter.runAtEntity(pet, () -> removePetGlow(pet));
                }
            }
            playerPets.clear();
        }
        selectedPets.remove(playerUUID);

        Map<UUID, org.bukkit.entity.Entity> playerEntities = selectedEntities.get(playerUUID);
        if (playerEntities != null) {
            for (org.bukkit.entity.Entity entity : playerEntities.values()) {
                if (entity != null && entity.isValid()) {
                    SchedulerAdapter.runAtEntity(entity, () -> removePetGlow(entity));
                }
            }
            playerEntities.clear();
        }
        selectedEntities.remove(playerUUID);

        PetTeleportListener petListener = activePetTeleportListeners.remove(playerUUID);
        if (petListener != null) {
            petListener.unregisterListener();
        }

        EntityTeleportListener entityListener = activeEntityTeleportListeners.remove(playerUUID);
        if (entityListener != null) {
            entityListener.unregisterListener();
        }

        Player player = directPlayer != null && directPlayer.isOnline() ? directPlayer : plugin.getServer().getPlayer(playerUUID);
        if (player != null && player.isOnline()) {
            notifyAutoDisable(player, true, true);
        }
    }

    public void notifyAutoDisable(Player player, boolean petsDisabled, boolean entitiesDisabled) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (petsDisabled) {
            String firstColorOfPetToggle = lang.getFirstColorCode("tp.tppet-toggle");
            String disabledStyle = lang.get("styles.state.false");
            player.sendMessage(lang.get("tp.tppet-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfPetToggle)
                    .replace("{name}", "")
                    .replace("{info}", "Auto-disabled after successful teleport"));
        }

        if (entitiesDisabled) {
            String firstColorOfToggle = lang.getFirstColorCode("tp.tpe-toggle");
            String disabledStyle = lang.get("styles.state.false");
            player.sendMessage(lang.get("tp.tpe-toggle")
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                    .replace("{name}", "")
                    .replace("{info}", "Auto-disabled after successful teleport"));
        }
    }

    /**
     * Teleport selected entities to a target location
     *
     * @param player The player whose entities to teleport
     * @param targetLocation The location to teleport entities to
     * @return true if entities were teleported successfully
     */
    public boolean teleportSelectedEntities(Player player, Location targetLocation) {
        if (player == null || !player.isOnline() || targetLocation == null) {
            return false;
        }
        
        UUID playerUUID = player.getUniqueId();
        Map<UUID, org.bukkit.entity.Entity> playerEntities = selectedEntities.get(playerUUID);
        
        if (playerEntities == null || playerEntities.isEmpty()) {
            return false;
        }
        
        // Convert to list for teleportEntities method
        List<Entity> entitiesToTeleport = new ArrayList<>(playerEntities.values());
        
        // Log the entities being teleported
        Text.sendDebugLog(INFO, "Attempting to teleport " + entitiesToTeleport.size() + " entities for player " + player.getName());
        for (Entity entity : entitiesToTeleport) {
            if (entity != null) {
                Text.sendDebugLog(INFO, "Entity to teleport: " + entity.getType().name() + 
                                      " (UUID: " + entity.getUniqueId() + ", Valid: " + entity.isValid() + ")");
            }
        }
        
        // Immediately disable the entity teleport listener to prevent "moved too far" messages
        // when teleporting
        EntityTeleportListener listener = activeEntityTeleportListeners.get(playerUUID);
        if (listener != null) {
            // Just disable the distance checking but keep the listener registered
            // so we can still access the entities
            listener.disableDistanceChecking();
        }
        
        // Use the teleportEntities method to handle the actual teleportation
        // The teleportEntities method will handle removing glowing effects
        teleportEntities(playerUUID, player, entitiesToTeleport);
        
        // We're using instant auto-disable now, so no delay is needed
        
        // Execute cleanup immediately in the next server tick for instant feedback
        SchedulerAdapter.runAtEntityLater(player, () -> {
            // Clear selected entities
            if (playerEntities != null) {
                playerEntities.clear();
                selectedEntities.remove(playerUUID);
            }
            
            // Now fully unregister the listener
            if (listener != null) {
                listener.unregisterListener();
                activeEntityTeleportListeners.remove(playerUUID);
            }
            
            // Get the first color code and disabled style
            String firstColorOfToggle = lang.getFirstColorCode("tp.tpe-toggle");
            String disabledStyle = lang.get("styles.state.false");
            
            if (player.isOnline()) {
                player.sendMessage(lang.get("tp.tpe-toggle")
                        .replace("{state}", disabledStyle + "disabled" + firstColorOfToggle)
                        .replace("{name}", "")
                        .replace("{info}", "Auto-disabled after teleport"));
            }
            
            Text.sendDebugLog(INFO, "Entity teleport mode auto-disabled instantly for player: " + player.getName());
        }, 1L); // Run in the next tick for instant feedback
        
        return true;
    }

    /**
     * Checks if a string can be parsed as a number (supports decimal and negative numbers)
     * @param str The string to check
     * @return true if the string is a valid number
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        int start = 0;
        if (str.startsWith("~")) {
            if (str.length() == 1) return true; // Just "~" is valid
            start = 1;
            // After ~, check if the rest is numeric
            if (str.length() > start && (str.charAt(start) == '+' || str.charAt(start) == '-')) {
                start++;
            }
        } else if (str.startsWith("-")) {
            start = 1;
        }
        boolean hasDecimal = false;
        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '.') {
                if (hasDecimal) return false; // More than one decimal point
                hasDecimal = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a coordinate string, supporting relative (~) and absolute values
     * @param current The current coordinate value (for relative offsets)
     * @param input The input string to parse
     * @return The parsed coordinate
     * @throws NumberFormatException if the input is not a valid coordinate
     */
    private double parseCoordinate(double current, String input) throws NumberFormatException {
        if (input.startsWith("~")) {
            // Relative to current position
            if (input.length() == 1) return current;
            return current + Double.parseDouble(input.substring(1));
        } else {
            // Absolute coordinate
            return Double.parseDouble(input);
        }
    }

    private boolean handleTeleport(CommandSender sender, String[] args, String label) {
        // Check if no arguments are provided (except /back which is valid with no args)
        if (args.length == 0) {
            if (!label.equalsIgnoreCase("back")) {
                // Show usage for /tp command
                String usage = lang.get("command-usage")
                    .replace("{cmd}", "tp")
                    .replace("{args}", "<player> [target]");
                lang.sendMessage(sender, "command-usage", usage);
                return true;
            }
            // /back with no args falls through to back handling below
        }

        // Handle coordinate-based teleport: /tp @s x y z or /tp player x y z
        if (args.length >= 3 && args.length <= 6 && (isNumeric(args[0]) || args[0].startsWith("@") || isNumeric(args[1]))) {
            try {
                Player targetPlayer;
                int coordIndex = 0;
                boolean isSelector = args[0].startsWith("@");

                if (isSelector) {
                    // Handle selector-based teleport: /tp @s x y z
                    if (!sender.hasPermission("allium.admin")) {
                        Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " with selectors");
                        return true;
                    }

                    if (args[0].equals("@s")) {
                        if (!(sender instanceof Player)) {
                            sender.sendMessage(lang.get("not-a-player"));
                            return true;
                        }
                        targetPlayer = (Player) sender;
                        coordIndex = 1;
                    } else {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", args[0]);
                        return true;
                    }
                } else if (args.length >= 4 && !isNumeric(args[0]) && isNumeric(args[1])) {
                    // Format: /tp player x y z [yaw] [pitch] [world] — args[0] is player name
                    if (!sender.hasPermission("allium.tp.others")) {
                        Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "tp others");
                        return true;
                    }
                    targetPlayer = plugin.getServer().getPlayer(args[0]);
                    if (targetPlayer == null) {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                        return true;
                    }
                    coordIndex = 1;
                } else if (sender instanceof Player) {
                    // Format: /tp x y z [yaw] [pitch] [world] — self teleport, use sender's world when world not specified
                    targetPlayer = (Player) sender;
                } else {
                    sender.sendMessage(lang.get("not-a-player"));
                    return true;
                }

                Location currentLoc = targetPlayer.getLocation();
                double x = parseCoordinate(currentLoc.getX(), args[coordIndex]);
                double y = parseCoordinate(currentLoc.getY(), args[coordIndex + 1]);
                double z = parseCoordinate(currentLoc.getZ(), args[coordIndex + 2]);

                float yaw = currentLoc.getYaw();
                float pitch = currentLoc.getPitch();
                int worldArgIndex = coordIndex + 3;

                if (args.length > coordIndex + 3 && isNumeric(args[coordIndex + 3])) {
                    yaw = Float.parseFloat(args[coordIndex + 3]);
                    while (yaw < -180.0F) yaw += 360.0F;
                    while (yaw >= 180.0F) yaw -= 360.0F;
                    worldArgIndex = coordIndex + 4;

                    if (args.length > coordIndex + 4 && isNumeric(args[coordIndex + 4])) {
                        pitch = Float.parseFloat(args[coordIndex + 4]);
                        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
                        worldArgIndex = coordIndex + 5;
                    }
                }

                World world = targetPlayer.getWorld();
                if (args.length > worldArgIndex) {
                    World targetWorld = Bukkit.getWorld(args[worldArgIndex]);
                    if (targetWorld == null) {
                        sender.sendMessage(lang.get("world-not-found").replace("{world}", args[worldArgIndex]));
                        return true;
                    }
                    world = targetWorld;
                }

                Location targetLocation = new Location(
                    world,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
                );
                final double fx = x, fy = y, fz = z; // for lambda capture
                targetPlayer.teleportAsync(targetLocation).thenAccept(success -> {
                    if (success) {
                        String positionStr = lang.get("tp.position")
                                .replace("{x}", String.format("%.1f", fx))
                                .replace("{y}", String.format("%.1f", fy))
                                .replace("{z}", String.format("%.1f", fz));
                        lang.sendMessage(targetPlayer, "tp.success", "{name}", "to", "{target}", positionStr);
                    } else {
                        targetPlayer.sendMessage(Text.colorize("&cTeleport failed."));
                    }
                });
                return true;
            } catch (NumberFormatException e) {
                Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "coordinates");
                return true;
            }
        }

        if (label.equalsIgnoreCase("back")) {
            if (args.length >= 1) {
                if (!sender.hasPermission("allium.back.other")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "back");
                    return true;
                }

                Player targetPlayer = plugin.getServer().getPlayer(args[0]);
                if (targetPlayer == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }

                boolean initiatedByOther = !(sender instanceof Player executorPlayer
                        && executorPlayer.getUniqueId().equals(targetPlayer.getUniqueId()));
                executeBackTeleport(sender, targetPlayer, initiatedByOther);
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("not-a-player"));
                return true;
            }

            executeBackTeleport(player, player, false);
            return true;
        }
        // Case 2: /tp(o) <player1> <player2> or /tp(o) <selector> <selector>
        else {
            boolean bypassToggle = "tpo".equals(label) && sender.hasPermission("allium.tptoggle.bypass");
            
            // Handle single argument: /tp <player> - teleport self to player
            if (args.length == 1) {
                if (!(sender instanceof Player selfPlayer)) {
                    sender.sendMessage(lang.get("not-a-player"));
                    return true;
                }
                
                Player targetPlayer = plugin.getServer().getPlayer(args[0]);
                if (targetPlayer == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }
                
                final Player finalTarget = targetPlayer;
                selfPlayer.teleportAsync(targetPlayer.getLocation()).thenAccept(success -> {
                    if (success) {
                        lang.sendMessage(selfPlayer, "tp.success", "{name}", finalTarget.getName(), "{target}", "");
                    }
                });
                return true;
            }
            
            String permissionBase = "allium.tp";
            String othersPerm = permissionBase + ".others";
            if (!sender.hasPermission(othersPerm)) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " others");
                return true;
            }

            List<Player> sourcePlayers;
            if (args[0].startsWith("@")) {
                if (!sender.hasPermission("allium.admin")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " &cwith selectors.");
                    return true;
                }
                // /tp <target> - teleport self to target
                sourcePlayers = Collections.singletonList((Player) sender);
                
                // Shift args to support both /tp <target> and /tp <player> <target> syntax
                String[] newArgs = new String[2];
                newArgs[0] = args[0]; // The target player
                newArgs[1] = args[0]; // The target player (will be resolved in the next step)
                args = newArgs;
            } else {
                // /tp <player> <target> - teleport player to target
                Player player1 = plugin.getServer().getPlayer(args[0]);
                if (player1 == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }
                sourcePlayers = Collections.singletonList(player1);
            }

            // Check if we have enough arguments
            if (args.length < 2) {
                String usage = lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<player1> <player2>");
                lang.sendMessage(sender, "command-usage", usage);
                return true;
            }

            List<Player> targetPlayers;
            Location targetLocation = null;
            String resolvedTargetName;

            if (args[1].startsWith("@")) {
                if (!sender.hasPermission("allium.admin")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " &cwith selectors.");
                    return true;
                }
                List<Entity> targetEntities = parseEntitySelector(args[1], sender instanceof Player ? (Player) sender : null);
                if (targetEntities == null) {
                    Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "&cselector: " + args[1], "{syntax}", "Possible selectors: @p, @r");
                    return true;
                }
                targetPlayers = targetEntities.stream()
                    .filter(entity -> entity instanceof Player)
                    .map(entity -> (Player) entity)
                    .collect(Collectors.toList());
                if (targetPlayers.isEmpty()) {
                    Text.sendErrorMessage(sender, "no-players-found", lang);
                    return true;
                }
                targetLocation = targetPlayers.get(0).getLocation();
                resolvedTargetName = targetPlayers.get(0).getName();
            } else {
                // Prefer online lookup for target first
                Player online2 = plugin.getServer().getPlayer(args[1]);
                if (online2 != null) {
                    targetPlayers = Collections.singletonList(online2);
                    targetLocation = online2.getLocation();
                    resolvedTargetName = online2.getName();
                } else {
                    OfflinePlayer player2 = plugin.getServer().getOfflinePlayer(args[1]);
                    if (player2 == null || !player2.hasPlayedBefore()) {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                        return true;
                    }
                    targetLocation = database.getPlayerLocation(player2.getUniqueId(), LocationType.TELEPORT);
                    if (targetLocation == null) {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                        return true;
                    }
                    targetPlayers = Collections.emptyList();
                    resolvedTargetName = player2.getName();
                }
            }

            for (Player player1 : sourcePlayers) {
                if (targetPlayers.size() == 1 && player1.getUniqueId().equals(targetPlayers.get(0).getUniqueId())) {
                    Text.sendErrorMessage(sender, "cannot-teleport-to-self", lang);
                    continue;
                }

                if (!bypassToggle && targetPlayers.size() == 1 && teleportToggled.contains(targetPlayers.get(0).getUniqueId())) {
                    Text.sendErrorMessage(sender, "teleport-toggled-other", lang,
                            "{player}", player1.getName(),
                            "{target}", targetPlayers.get(0).getName());
                    continue;
                }
                final Location finalTargetLocation = targetLocation;
                final boolean isOffline = targetPlayers.isEmpty();
                player1.teleportAsync(finalTargetLocation).thenAccept(success -> {
                    if (success) {
                        if (isOffline) {
                            lang.sendMessage(player1, "tp.success", 
                                "{name}", "to " + resolvedTargetName + " (offline)",
                                "{target}", "");
                        } else {
                            lang.sendMessage(player1, "tp.success", 
                                "{name}", "to " + resolvedTargetName,
                                "{target}", "");
                        }
                    } else {
                        Text.sendErrorMessage(player1, "teleport-failed", lang);
                    }
                });
                
                // Send confirmation to the command sender
                String senderMessageName = sourcePlayers.size() > 1 ? 
                    sourcePlayers.size() + " players" : 
                    sourcePlayers.get(0).getName();
                    
                if (isOffline) {
                    lang.sendMessage(sender, "tp.success",
                        "{name}", senderMessageName,
                        "{target}", "to " + resolvedTargetName + " (offline)");
                } else {
                    lang.sendMessage(sender, "tp.success",
                        "{name}", senderMessageName,
                        "{target}", "to " + resolvedTargetName);
                }
            }

            return true;
        }

    }

    private static final long LOCATION_CACHE_TTL = 300000; // 5 minutes
    private static final long DEATH_LOCATION_TTL = 120000; // 2 minutes
    private final Map<UUID, LocationCacheEntry> locationCache = new ConcurrentHashMap<>();

    private static class LocationCacheEntry {
        final Location location;
        final long timestamp;
        
        LocationCacheEntry(Location location) {
            this.location = location;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - timestamp > ttl;
        }
    }

    private CompletableFuture<Location> getBackLocation(Player player) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        CompletableFuture<Location> future = new CompletableFuture<>();
        
        logBackOperation("Starting back location retrieval", playerName, playerUUID, null);

        // Clean up expired cache entries
        cleanExpiredCacheEntries();

        // Check cache first
        LocationCacheEntry cached = locationCache.get(playerUUID);
        if (cached != null && !cached.isExpired(LOCATION_CACHE_TTL)) {
            logBackOperation("Using cached back location", playerName, playerUUID, cached.location);
            future.complete(cached.location.clone());
            return future;
        }

        // Check death location if enabled
        if (player.hasPermission("allium.back.ondeath")) {
            checkDeathLocation(player, playerName, playerUUID)
                .thenCompose(location -> {
                    if (location != null) {
                        return CompletableFuture.completedFuture(location);
                    }
                    // Fall back to last location if no death location found
                    return checkLastLocation(player, playerName, playerUUID);
                })
                .thenCompose(location -> {
                    if (location != null) {
                        return CompletableFuture.completedFuture(location);
                    }
                    // Fall back to database if no in-memory location
                    return checkDatabaseLocation(player, playerName, playerUUID);
                })
                .whenComplete((location, error) -> completeFuture(future, playerName, playerUUID, location, error));
        } else {
            // Skip death location check if no permission
            checkLastLocation(player, playerName, playerUUID)
                .thenCompose(location -> {
                    if (location != null) {
                        return CompletableFuture.completedFuture(location);
                    }
                    return checkDatabaseLocation(player, playerName, playerUUID);
                })
                .whenComplete((location, error) -> completeFuture(future, playerName, playerUUID, location, error));
        }

        return future;
    }

    private CompletableFuture<Location> checkDeathLocation(Player player, String playerName, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Location deathLocation = Death.getDeathLocation(player);
                if (deathLocation != null) {
                    long deathTime = Death.getDeathTime(playerUUID);
                    boolean isRecent = deathTime > 0 && 
                        System.currentTimeMillis() - deathTime <= DEATH_LOCATION_TTL;
                    
                    if (isRecent && deathLocation.getWorld() != null) {
                        logBackOperation("Using recent death location", playerName, playerUUID, deathLocation);
                        Death.clearDeathLocation(player);
                        return deathLocation;
                    }
                }
                return null;
            } catch (Exception e) {
                logBackError("Error checking death location", playerName, playerUUID, e);
                return null;
            }
        });
    }

    private CompletableFuture<Location> checkLastLocation(Player player, String playerName, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            Location lastLoc = lastLocation.get(playerUUID);
            if (lastLoc != null && lastLoc.getWorld() != null) {
                logBackOperation("Using in-memory location", playerName, playerUUID, lastLoc);
                return lastLoc.clone();
            }
            return null;
        });
    }

    private CompletableFuture<Location> checkDatabaseLocation(Player player, String playerName, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Location dbLoc = database.getPlayerLocation(playerUUID, LocationType.TELEPORT);
                if (dbLoc != null && dbLoc.getWorld() != null) {
                    logBackOperation("Using database location", playerName, playerUUID, dbLoc);
                    return dbLoc;
                }
                return null;
            } catch (Exception e) {
                logBackError("Error getting database location", playerName, playerUUID, e);
                return null;
            }
        });
    }

    private void completeFuture(CompletableFuture<Location> future, String playerName, 
                            UUID playerUUID, Location location, Throwable error) {
        if (error != null) {
            logBackError("Error in back location retrieval", playerName, playerUUID, error);
            future.completeExceptionally(error);
        } else if (location != null) {
            // Cache the valid location
            locationCache.put(playerUUID, new LocationCacheEntry(location.clone()));
            future.complete(location);
        } else {
            logBackOperation("No valid back location found", playerName, playerUUID, null);
            future.complete(null);
        }
    }

    private void cleanExpiredCacheEntries() {
        long now = System.currentTimeMillis();
        locationCache.entrySet().removeIf(entry -> 
            entry.getValue().isExpired(LOCATION_CACHE_TTL)
        );
    }

    private void logBackOperation(String operation, String playerName, UUID playerUUID, Location location) {
        if (location != null) {
            Text.sendDebugLog(INFO, String.format(
                "[BACK] [%s] %s (UUID: %s) at %s (%.1f, %.1f, %.1f)",
                operation, 
                playerName, 
                playerUUID.toString(),
                location.getWorld() != null ? location.getWorld().getName() : "null",
                location.getX(), 
                location.getY(), 
                location.getZ()
            ));
        } else {
            Text.sendDebugLog(INFO, String.format(
                "[BACK] [%s] %s (UUID: %s)",
                operation, 
                playerName, 
                playerUUID.toString()
            ));
        }
    }

    private void logBackError(String operation, String playerName, UUID playerUUID, Throwable error) {
        Text.sendDebugLog(ERROR, String.format(
            "[BACK] [ERROR] %s for %s (UUID: %s): %s",
            operation, playerName, playerUUID, error.getMessage()
        ), error);
    }

    private void executeBackTeleport(CommandSender sender, Player targetPlayer, boolean initiatedByOther) {
        // Get back location asynchronously
        getBackLocation(targetPlayer).thenAccept(location -> {
            if (location == null) {
                Text.sendErrorMessage(sender, "no-back-location", lang, "{player}", targetPlayer.getName());
                return;
            }
            
            // Check if this is a death location
            boolean isDeathLocation = Death.getDeathLocation(targetPlayer) != null;
            
            // Save current location for future /back
            saveCurrentLocation(targetPlayer).thenRun(() -> {
                // Execute the teleport
                targetPlayer.teleportAsync(location).thenAccept(success -> {
                    if (success) {
                        // Play sound and send message to target player
                        String messageKey = isDeathLocation ? "tp.back-death-location" : "tp.back";
                        lang.sendMessage(targetPlayer, messageKey);
                        
                        // If initiated by someone else, send a different message to command sender
                        if (initiatedByOther && sender instanceof Player) {
                            String senderMessageKey = isDeathLocation ? "tp.back-death-location-other" : "tp.back-other";
                            String senderMessage = lang.get(senderMessageKey, targetPlayer)
                                    .replace("{name}", targetPlayer.getName());
                            lang.sendMessage(sender, senderMessage);
                        }
                        
                        // Clear death location if this was a death teleport
                        if (isDeathLocation) {
                            Death.clearDeathLocation(targetPlayer);
                        }
                    } else {
                        Text.sendErrorMessage(sender, "teleport-failed", lang);
                    }
                });
            });
        });
    }

    /**
     * Parses a Minecraft entity selector and returns matching entities
     * Supports selectors like @e[type=minecraft:villager,distance=..3], @p[distance=..10], etc.
     *
     * @param selector The selector string (e.g., "@e[type=minecraft:villager,distance=..3]")
     * @param executor The player executing the selector (for relative positioning)
     * @return List of matching entities, or null if selector is invalid
     */
    private List<Entity> parseEntitySelector(String selector, Player executor) {
        if (!selector.startsWith("@")) {
            return null;
        }

        String baseSelector = selector.substring(1); // Remove @
        Map<String, String> parameters = new HashMap<>();

        // Parse parameters in brackets
        int bracketStart = baseSelector.indexOf('[');
        int bracketEnd = baseSelector.indexOf(']');
        String selectorType = baseSelector;
        if (bracketStart != -1 && bracketEnd != -1 && bracketEnd > bracketStart) {
            selectorType = baseSelector.substring(0, bracketStart);
            String paramsStr = baseSelector.substring(bracketStart + 1, bracketEnd);

            // Parse parameters like "type=minecraft:villager,distance=..3"
            String[] paramPairs = paramsStr.split(",");
            for (String paramPair : paramPairs) {
                String[] parts = paramPair.split("=", 2);
                if (parts.length == 2) {
                    parameters.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        Location executorLoc = executor.getLocation();
        World world = executor.getWorld();
        List<Entity> candidates = new ArrayList<>();

        switch (selectorType) {
            case "p":
                // Nearest player
                double minDistance = Double.MAX_VALUE;
                Player nearestPlayer = null;
                for (Player p : world.getPlayers()) {
                    if (!p.equals(executor)) {
                        double distance = p.getLocation().distanceSquared(executorLoc);
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearestPlayer = p;
                        }
                    }
                }
                if (nearestPlayer != null) {
                    candidates.add(nearestPlayer);
                }
                break;

            case "r":
                // Random player
                List<Player> players = new ArrayList<>(world.getPlayers());
                players.remove(executor); // Don't include executor
                if (!players.isEmpty()) {
                    candidates.add(players.get(new Random().nextInt(players.size())));
                }
                break;

            case "a":
                // All players
                for (Player p : world.getPlayers()) {
                    if (!p.equals(executor)) {
                        candidates.add(p);
                    }
                }
                break;

            case "s":
                // Self
                candidates.add(executor);
                break;

            case "e":
                // All entities
                candidates.addAll(world.getEntities());
                break;

            default:
                return null; // Invalid selector type
        }

        // Apply filters
        List<Entity> filtered = new ArrayList<>();
        for (Entity entity : candidates) {
            if (matchesSelectorParameters(entity, parameters, executorLoc)) {
                filtered.add(entity);
            }
        }

        // Apply sorting (distance by default)
        if (parameters.containsKey("distance") || selectorType.equals("p")) {
            filtered.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(executorLoc)));
        }

        // Apply limit if specified
        if (parameters.containsKey("limit")) {
            try {
                int limit = Integer.parseInt(parameters.get("limit"));
                if (filtered.size() > limit) {
                    filtered = filtered.subList(0, limit);
                }
            } catch (NumberFormatException ignored) {
                // Invalid limit, ignore
            }
        }

        return filtered;
    }

    /**
     * Checks if an entity matches the selector parameters
     */
    private boolean matchesSelectorParameters(Entity entity, Map<String, String> parameters, Location executorLoc) {
        for (Map.Entry<String, String> param : parameters.entrySet()) {
            String key = param.getKey();
            String value = param.getValue();

            switch (key) {
                case "type":
                    if (!matchesEntityType(entity, value)) {
                        return false;
                    }
                    break;

                case "distance":
                    if (!matchesDistance(entity.getLocation(), executorLoc, value)) {
                        return false;
                    }
                    break;

                case "dx":
                case "dy":
                case "dz":
                    // Volume selection - not implemented yet
                    break;

                case "x":
                case "y":
                case "z":
                    // Absolute coordinates - not implemented yet
                    break;

                case "x_rotation":
                case "y_rotation":
                    // Rotation - not implemented yet
                    break;

                case "limit":
                    // Handled separately
                    break;

                case "sort":
                    // Sorting - handled separately
                    break;

                default:
                    // Unknown parameter, ignore for now
                    break;
            }
        }
        return true;
    }

    /**
     * Checks if entity type matches the selector value
     */
    private boolean matchesEntityType(Entity entity, String typeValue) {
        // Remove minecraft: prefix if present
        if (typeValue.startsWith("minecraft:")) {
            typeValue = typeValue.substring(10);
        }

        // Handle negation with !
        boolean negated = false;
        if (typeValue.startsWith("!")) {
            negated = true;
            typeValue = typeValue.substring(1);
        }

        // Check entity type
        String entityTypeName = entity.getType().name().toLowerCase();
        boolean matches = entityTypeName.equals(typeValue.toLowerCase()) ||
                         entityTypeName.equals("minecraft:" + typeValue.toLowerCase());

        return negated ? !matches : matches;
    }

    /**
     * Checks if distance matches the selector value
     * Supports formats like "10", "..10", "5..15", "10.."
     */
    private boolean matchesDistance(Location entityLoc, Location executorLoc, String distanceValue) {
        double distance = entityLoc.distance(executorLoc);

        if (distanceValue.startsWith("..")) {
            // Maximum distance (e.g., "..10")
            try {
                double maxDist = Double.parseDouble(distanceValue.substring(2));
                return distance <= maxDist;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (distanceValue.endsWith("..")) {
            // Minimum distance (e.g., "5..")
            try {
                double minDist = Double.parseDouble(distanceValue.substring(0, distanceValue.length() - 2));
                return distance >= minDist;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (distanceValue.contains("..")) {
            // Range (e.g., "5..15")
            String[] parts = distanceValue.split("\\.\\.");
            if (parts.length == 2) {
                try {
                    double minDist = parts[0].isEmpty() ? 0 : Double.parseDouble(parts[0]);
                    double maxDist = parts[1].isEmpty() ? Double.MAX_VALUE : Double.parseDouble(parts[1]);
                    return distance >= minDist && distance <= maxDist;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        } else {
            // Exact distance (e.g., "10")
            try {
                double targetDist = Double.parseDouble(distanceValue);
                return Math.abs(distance - targetDist) < 0.1; // Small tolerance
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return false;
    }


    /**
     * Handles the /tpa command by sending a teleport request
     *
     * @param sender The sender of the request
     * @param args Command arguments: [target]
     * @param isHereRequest Whether this is a /tpahere request
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportRequest(CommandSender sender, String[] args, boolean isHereRequest) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!hasTeleportRequestPermission(player, isHereRequest)) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", isHereRequest ? "tpahere" : "tpa");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", isHereRequest ? "tpahere" : "tpa")
                .replace("{args}", "<player>");
            lang.sendMessage(player, "command-usage", usage);
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "§crequest to teleport to");
            return true;
        }

        // Check if target has teleport requests toggled off
        if (teleportToggled.contains(target.getUniqueId()) && !player.hasPermission("allium.tpo")) {
            player.sendMessage(lang.get("tp.blocked").replace("{name}", target.getName()));
            return true;
        }

        // Get expire time from config (in seconds, default to 120)
        int expireSeconds = config.getInt("teleport.expire");
        if (expireSeconds <= 0) {
            expireSeconds = 120; // Default to 2 minutes if not set or invalid
        }
        // Get delay from config (default to 3 seconds if not set)
        int delay = config.getInt("teleport.delay");
        if (delay <= 0) {
            delay = 3; // Default to 3 seconds if not set or invalid
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Check for existing request to the same player within the expire time
        for (Map.Entry<UUID, Long> entry : teleportRequestTimestamps.entrySet()) {
            // If the request is still valid (not expired)
            if (entry.getValue() > currentTime - (expireSeconds * 1000L)) {
                UUID requester = entry.getKey();
                UUID existingTarget = teleportRequests.get(requester);
                
                // Check if this is a duplicate request to the same player
                if ((requester.equals(player.getUniqueId()) && existingTarget != null && existingTarget.equals(target.getUniqueId())) ||
                    (requester.equals(target.getUniqueId()) && existingTarget != null && existingTarget.equals(player.getUniqueId()))) {
                    lang.sendMessage(target, "tp.already-sent", "{name}", player.getName());
                    return true;
                }
            }
        }

        // Store the request type (normal or here) in the UUID map
        // For a normal /tpa request, player wants to teleport TO target
        // For a /tpahere request, player wants target to teleport TO player
        if (isHereRequest) {
            // For a tpahere request, the requester wants the target to teleport to them
            teleportRequests.put(player.getUniqueId(), target.getUniqueId());
            teleportRequestTimestamps.put(player.getUniqueId(), currentTime);
            teleportHereRequests.add(player.getUniqueId());
        } else {
            // For a tpa request, the requester wants to teleport to the target
            teleportRequests.put(player.getUniqueId(), target.getUniqueId());
            teleportRequestTimestamps.put(player.getUniqueId(), currentTime);
            teleportHereRequests.remove(player.getUniqueId());
        }

        // Send appropriate messages
        if (isHereRequest) {
// Send the sent message with sound using lang.sendMessage
            String sentMessage = lang.get("tp.sent")
                .replace("{name}", target.getName())
                .replace("{cancelCmd}", "/tpcancel");
            lang.sendMessage(player, sentMessage);
                
            // Build the formatted message with placeholders
            String action = isHereRequest ? "that you teleport to them" : "to teleport to you";
            String message = lang.get("tp.format")
                .replace("{name}", player.getName())
                .replace("{action}", action)
                .replace("{time}", String.valueOf(expireSeconds));
            
            // Send the message with sound using lang.sendMessage
            lang.sendMessage(target, message);
        } else {
// Send the sent message with sound using lang.sendMessage
            String sentMessage = lang.get("tp.sent")
                .replace("{name}", target.getName())
                .replace("{cancelCmd}", "/tpcancel");
            lang.sendMessage(player, sentMessage);
                
            // Build the formatted message with placeholders
            String action = isHereRequest ? "that you teleport to them" : "to teleport to you";
            String message = lang.get("tp.format")
                .replace("{name}", player.getName())
                .replace("{action}", action)
                .replace("{time}", String.valueOf(expireSeconds));
            
            // Send the message with sound using lang.sendMessage
            lang.sendMessage(target, message);
        }



        return true;
    }

    /**
     * Handles the /tpaccept command by accepting a teleport request
     *
     * @param sender The sender accepting the request
     * @param args Command arguments: [player] (optional)
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportAccept(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tpaccept");
            return true;
        }

        UUID requesterUUID = null;
        UUID playerUUID = player.getUniqueId();

        // If player specified who to accept
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            UUID requesterId = requester.getUniqueId();

            if (teleportRequests.containsKey(requesterId)) {
                UUID storedTarget = teleportRequests.get(requesterId);
                if (storedTarget.equals(playerUUID)) {
                    requesterUUID = requesterId;
                } else if (storedTarget.equals(requesterId) && teleportRequests.containsKey(playerUUID) && teleportRequests.get(playerUUID).equals(requesterId)) {
                    // Fallback if legacy mapping exists in reverse (should not happen now)
                    requesterUUID = playerUUID;
                }
            }

            if (requesterUUID == null) {
                lang.sendMessage(player, "tp.no-request");
                return true;
            }
        } else {
            // Find any pending requests where this player is the TARGET
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(playerUUID)) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            lang.sendMessage(player, "tp.no-request");
            return true;
        }

        // Check if request is expired
        Long timestamp = teleportRequestTimestamps.get(requesterUUID);
        if (timestamp == null || System.currentTimeMillis() - timestamp > getRequestExpirationTime()) {
            // Notify both sides (requester and target) if online
            lang.sendMessage(player, "tp.expired");
            UUID other = teleportRequests.get(requesterUUID);
            if (other != null) {
                Player otherPlayer = plugin.getServer().getPlayer(other);
                if (otherPlayer != null && otherPlayer.isOnline()) {
                    lang.sendMessage(otherPlayer, "tp.expired");
                }
            }
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Determine who teleports to whom
        UUID destinationUUID = teleportRequests.get(requesterUUID);
        Player requesterPlayer = plugin.getServer().getPlayer(requesterUUID);
        Player targetPlayer = destinationUUID != null ? plugin.getServer().getPlayer(destinationUUID) : null;

        if (requesterPlayer == null || targetPlayer == null) {
            UUID missingUUID = requesterPlayer == null ? requesterUUID : destinationUUID;
            String missingName = missingUUID != null ? plugin.getServer().getOfflinePlayer(missingUUID).getName() : null;
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", missingName != null ? missingName : "Player");
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            teleportHereRequests.remove(requesterUUID);
            return true;
        }

        boolean isHereRequest = teleportHereRequests.contains(requesterUUID);
        Player teleportingPlayer = isHereRequest ? targetPlayer : requesterPlayer;
        Player destinationPlayer = isHereRequest ? requesterPlayer : targetPlayer;


        // Check if player is on cooldown and doesn't have bypass permission
        if (!teleportingPlayer.hasPermission("allium.tpa.nocooldown") && hasCooldown(teleportingPlayer.getUniqueId())) {
            long remaining = getRemainingCooldown(teleportingPlayer.getUniqueId());
            // Use lang.yml cooldown key
            Text.sendErrorMessage(teleportingPlayer, "cooldown", lang, 
                    "{time}", Text.formatTime((int) remaining),
                    "{cmd}", "teleport");
            return true;
        }
        
        // Teleport the player with delay and cooldown handling
        if (teleportingPlayer.hasPermission("allium.tpa.nodelay")) {
            // Store last location before teleporting
            // Teleport the player
            Location destLoc = destinationPlayer.getLocation();
            teleportingPlayer.teleportAsync(destLoc).thenAccept(success -> {
                if (success) {
                    lang.sendMessage(teleportingPlayer, "tp.success", "{name}", "to " + destinationPlayer.getName(), "{target}", "");
                    lang.sendMessage(destinationPlayer, "tp.accept", "{name}", teleportingPlayer.getName());
                    
                    // Set cooldown if player doesn't have cooldown bypass
                    if (!teleportingPlayer.hasPermission("allium.tpa.nocooldown")) {
                        setCooldown(teleportingPlayer.getUniqueId());
                    }
                } else {
                    teleportingPlayer.sendMessage(Text.colorize("&cTeleport failed."));
                }
            });
        } else {
            int delay = config.getInt("teleport.delay"); //Get delay from config, default to 3

            Location originalLocation = teleportingPlayer.getLocation(); // Store the player's original location
            lang.sendMessage(teleportingPlayer, "tp.delay", "{delay}", Text.formatTime(delay));
            lang.sendMessage(destinationPlayer, "tp.accept", "{name}", teleportingPlayer.getName());

            // Create a CancelTeleportation listener
            final java.util.concurrent.atomic.AtomicReference<SchedulerAdapter.TaskHandle> teleportTaskRef = new java.util.concurrent.atomic.AtomicReference<>();
            CancelTeleportation cancelListener = new CancelTeleportation(plugin, teleportingPlayer, originalLocation, 0.99d);

            // Register the cancellation listener
            plugin.getServer().getPluginManager().registerEvents(cancelListener, plugin);

            SchedulerAdapter.TaskHandle scheduled = SchedulerAdapter.runLater(() -> {
                try {
                    if (!teleportingPlayer.isOnline()) {
                        return;
                    }

                    if (!destinationPlayer.isOnline()) {
                        Text.sendErrorMessage(teleportingPlayer, "player-not-found", lang, "{name}", destinationPlayer.getName());
                        return;
                    }

                    if (cancelListener.isCancelled()) {
                        return;
                    }

                    SchedulerAdapter.runAtEntity(destinationPlayer, () -> {
                        Location destLoc2 = destinationPlayer.getLocation().clone();

                        SchedulerAdapter.runAtEntity(teleportingPlayer, () -> {
                            if (!teleportingPlayer.isOnline()) {
                                return;
                            }

                            if (cancelListener.isCancelled()) {
                                return;
                            }

                            org.bukkit.event.HandlerList.unregisterAll(cancelListener);

                            teleportingPlayer.teleportAsync(destLoc2).thenAccept(success -> {
                                SchedulerAdapter.runAtEntity(teleportingPlayer, () -> {
                                    if (success) {
                                        lang.sendMessage(teleportingPlayer, "tp.success", "{name}", "to " + destinationPlayer.getName(), "{target}", "");
                                        if (!teleportingPlayer.hasPermission("allium.tpa.nocooldown")) {
                                            setCooldown(teleportingPlayer.getUniqueId());
                                        }
                                    } else {
                                        teleportingPlayer.sendMessage(Text.colorize("&cTeleport failed."));
                                    }
                                });
                            });
                        });
                    });
                } finally {
                    teleportTaskRef.set(null);
                }
            }, delay * 20L); //delay in ticks
            teleportTaskRef.set(scheduled);
        }



        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    /**
     * Checks if a player is on cooldown
     * @param playerId UUID of the player to check
     * @return true if the player is on cooldown, false otherwise
     */
    private boolean hasCooldown(UUID playerId) {
        if (teleportCooldowns.containsKey(playerId)) {
            long cooldownTime = teleportCooldowns.get(playerId);
            return System.currentTimeMillis() < cooldownTime;
        }
        return false;
    }

    /**
     * Gets the remaining cooldown time in seconds
     * @param playerId UUID of the player to check
     * @return remaining cooldown time in seconds, 0 if no cooldown
     */
    private long getRemainingCooldown(UUID playerId) {
        if (!hasCooldown(playerId)) return 0;
        return (teleportCooldowns.get(playerId) - System.currentTimeMillis()) / 1000 + 1; // +1 to round up
    }

    private boolean hasTeleportRequestPermission(Player player, boolean isHereRequest) {
        return isHereRequest ? player.hasPermission("allium.tpahere") : player.hasPermission("allium.tpa");
    }

    /**
     * Sets a cooldown for a player
     * @param playerId UUID of the player to set cooldown for
     */
    private void setCooldown(UUID playerId) {
        int cooldownSeconds = config.getInt("teleport.cooldown");
        if (cooldownSeconds <= 0) {
            cooldownSeconds = 30; // Default 30 seconds if not set or invalid
        }
        teleportCooldowns.put(playerId, System.currentTimeMillis() + (cooldownSeconds * 1000L));
    }
    


    /**
     * Handles the /tpdeny command by denying a teleport request
     *
     * @param sender The sender denying the request
     * @param args Command arguments: [player] (optional)
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportDeny(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tpdeny");
            return true;
        }

        UUID requesterUUID = null;
        UUID playerUUID = player.getUniqueId();

        // If player specified who to deny
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            // Check if this player has a pending request from the specified player
            if (teleportRequests.containsKey(requester.getUniqueId()) &&
                    teleportRequests.get(requester.getUniqueId()).equals(playerUUID)) {
                requesterUUID = requester.getUniqueId();
            } else if (teleportRequests.containsKey(playerUUID) &&
                    teleportRequests.get(playerUUID).equals(requester.getUniqueId())) {
                requesterUUID = requester.getUniqueId();
            } else {
                lang.sendMessage(player, "tp.no-request");
                return true;
            }
        } else {
            // Find any pending requests for this player
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(playerUUID) || entry.getKey().equals(playerUUID)) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            lang.sendMessage(player, "tp.no-request");
            return true;
        }

        // Check if request is expired
        Long timestamp = teleportRequestTimestamps.get(requesterUUID);
        if (timestamp == null || System.currentTimeMillis() - timestamp > getRequestExpirationTime()) {
            // Notify both sides (requester and target) if online
            lang.sendMessage(player, "tp.expired");
            UUID other = teleportRequests.get(requesterUUID);
            if (other != null) {
                Player otherPlayer = plugin.getServer().getPlayer(other);
                if (otherPlayer != null && otherPlayer.isOnline()) {
                    lang.sendMessage(otherPlayer, "tp.expired");
                }
            }
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Get the requester player
        Player requester = plugin.getServer().getPlayer(requesterUUID);
        String requesterName = requester != null ? requester.getName() : "Player";

        // Inform both players
        lang.sendMessage(player, "tp.deny", "{name}", requesterName);

        if (requester != null) {
            lang.sendMessage(requester, "tp.failure", "{name}", player.getName());
        }

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);
        teleportHereRequests.remove(requesterUUID);

        return true;
    }

    private boolean handleTeleportHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("not-a-player");
            return true;
        }

        if (!player.hasPermission("allium.tphere")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tphere");
            return true;
        }

        if (args.length != 1) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", "tphere")
                .replace("{args}", "<player>");
            lang.sendMessage(player, "command-usage", usage);
            return true;
        }

        if (args[0].startsWith("@")) {
            if (!sender.hasPermission("allium.admin")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", args[0] + " &cwith selectors.");
                return true;
            }

            // Use the new entity selector parser
            List<Entity> entities = parseEntitySelector(args[0], player);
            if (entities == null) {
                Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "&cselector: " + args[0], "{syntax}", "Possible selectors: @p, @r, @a, @e[type=minecraft:villager,distance=..3]");
                return true;
            }

            if (entities.isEmpty()) {
                Text.sendErrorMessage(sender, "no-entities-found", lang);
                return true;
            }

            // Handle @e selector specially for all entities
            if (args[0].startsWith("@e")) {
                // Filter out players for @e selector
                List<Entity> nonPlayerEntities = entities.stream()
                    .filter(entity -> !(entity instanceof Player))
                    .collect(Collectors.toList());

                if (nonPlayerEntities.isEmpty()) {
                    Text.sendErrorMessage(sender, "no-entities-found", lang);
                    return true;
                }

                teleportEntities(player.getUniqueId(), player, nonPlayerEntities);
                return true;
            }

            // For other selectors (@p, @r, @a), handle as players
            List<Player> targets = entities.stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                .collect(Collectors.toList());

            if (targets.isEmpty()) {
                Text.sendErrorMessage(sender, "no-players-found", lang);
                return true;
            }

            for (Player target : targets) {
                Location here = player.getLocation();
                target.teleportAsync(here).thenAccept(success -> {
                    if (success) {
                        lang.sendMessage(target, "tp.success", "{name}", "to", "{target}", player.getName());
                    } else {
                        target.sendMessage(Text.colorize("&cTeleport failed."));
                    }
                });
            }

            lang.sendMessage(player, "tp.success", "{name}", targets.size() > 1 ? targets.size() + " players" : targets.get(0).getName(), "{target}", "to you");
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                String targetName = Bukkit.getOfflinePlayer(args[0]).getName();
                Text.sendErrorMessage(player, "player-not-online", lang, "{name}", targetName);
            } else {
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            }
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "teleport to");
            return true;
        }

        Location here = player.getLocation();
        target.teleportAsync(here).thenAccept(success -> {
            if (success) {
                lang.sendMessage(target, "tp.success", "{name}", "", "{target}", "to " + player.getName());
            lang.sendMessage(player, "tp.success", "{name}", target.getName(), "{target}", "to you");
            } else {
                player.sendMessage(Text.colorize("&cTeleport failed."));
            }
        });
        return true;
    }

    /**
     * Handles the /tppos command by teleporting to coordinates with optional yaw and pitch
     *
     * @param sender The sender of the command
     * @param args Command arguments: [x, y, z] [yaw] [pitch] [world]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportPosition(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tppos")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tppos");
            return true;
        }

        // Check arguments
        if (args.length < 3) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", "tppos")
                .replace("{args}", "<x> <y> <z> [yaw] [pitch] [world]");
            lang.sendMessage(player, "command-usage", usage);
            return true;
        }

        try {
            // Parse coordinates
            double x = parseCoordinate(player.getLocation().getX(), args[0]);
            double y = parseCoordinate(player.getLocation().getY(), args[1]);
            double z = parseCoordinate(player.getLocation().getZ(), args[2]);
            
            // Parse yaw and pitch if provided, otherwise use current values
            float yaw = player.getLocation().getYaw();
            float pitch = player.getLocation().getPitch();
            
            int worldArgIndex = 3; // Default index for world argument if no yaw/pitch
            
            // Check for yaw argument (4th or 5th argument)
            if (args.length > 3 && isNumeric(args[3])) {
                yaw = Float.parseFloat(args[3]);
                // Normalize yaw to -180 to 180
                while (yaw < -180.0F) yaw += 360.0F;
                while (yaw >= 180.0F) yaw -= 360.0F;
                worldArgIndex = 4;
                
                // Check for pitch argument (5th argument)
                if (args.length > 4 && isNumeric(args[4])) {
                    pitch = Float.parseFloat(args[4]);
                    // Clamp pitch between -90 and 90 degrees
                    pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
                    worldArgIndex = 5;
                }
            }
            
            // Get world (default to current world if not specified)
            World world = player.getWorld();
            if (args.length > worldArgIndex) {
                World targetWorld = Bukkit.getWorld(args[worldArgIndex]);
                if (targetWorld == null) {
                    Text.sendErrorMessage(player, "world-not-found", lang, "{world}", args[worldArgIndex]);
                    return true;
                }
                world = targetWorld;
            }
            
            // Create location with specified yaw and pitch
            Location location = new Location(
                world,
                x,
                y,
                z,
                yaw,
                pitch
            );
            final double fx = x, fy = y, fz = z; final float fyaw = yaw, fpitch = pitch;
            player.teleportAsync(location).thenAccept(success -> {
                if (success) {
                    StringBuilder positionInfo = new StringBuilder(lang.get("tp.position")
                        .replace("{x}", String.format("%.2f", fx))
                        .replace("{y}", String.format("%.2f", fy))
                        .replace("{z}", String.format("%.2f", fz)));

                    // Add rotation info if yaw/pitch were specified
                    if (args.length > 3 && isNumeric(args[3])) {
                        positionInfo.append(String.format(" (yaw: %.1f", fyaw));
                        if (args.length > 4 && isNumeric(args[4])) {
                            positionInfo.append(String.format(", pitch: %.1f", fpitch));
                        }
                        positionInfo.append(")");
                    }

                    lang.sendMessage(player, "tp.success",
                        "{name}", "to",
                        "{target}", positionInfo.toString());
                } else {
                    player.sendMessage(Text.colorize("&cTeleport failed."));
                }
            });
        } catch (NumberFormatException e) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", "tppos")
                .replace("{args}", "<x> <y> <z> [yaw] [pitch] [world]");
            lang.sendMessage(sender, "command-usage", usage);
        }

        return true;
    }

    /**
     * Handles the /tptoggle command
     * Allows toggling teleport requests for self or others (with appropriate permission)
     *
     * @param sender The sender of the command
     * @param args Command arguments: [player] (optional)
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportToggle(CommandSender sender, String[] args) {
        // Check if args are provided to toggle someone else's teleport
        if (args.length > 0) {
            // Check permission for toggling others' teleport settings
            if (!sender.hasPermission("allium.tptoggle.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "tptoggle others");
                return true;
            }

            // Get target player
            Player targetPlayer = plugin.getServer().getPlayer(args[0]);
            if (targetPlayer == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            UUID targetUUID = targetPlayer.getUniqueId();
            boolean isSelf = (sender instanceof Player) && ((Player) sender).getUniqueId().equals(targetUUID);

            // Toggle teleport status for target player
            if (teleportToggled.contains(targetUUID)) {
                teleportToggled.remove(targetUUID);

                // Prefix for other player
                String namePrefix = isSelf ? "" : "for ";

                // Notify sender
                String enabledStyle = lang.get("styles.state.true");
                lang.sendMessage(sender, "tp.toggle", 
                        "{state}", enabledStyle + "enabled" + lang.getFirstColorCode("tp.toggle"),
                        "{name}", isSelf ? "" : namePrefix + targetPlayer.getName());
            } else {
                teleportToggled.add(targetUUID);

                // Prefix for other player
                String namePrefix = isSelf ? "" : "for ";

                // Notify sender
                String disabledStyle = lang.get("styles.state.false");
                lang.sendMessage(sender, "tp.toggle", 
                        "{state}", disabledStyle + "disabled" + lang.getFirstColorCode("tp.toggle"),
                        "{name}", isSelf ? "" : namePrefix + targetPlayer.getName());
            }

            return true;
        }

        // If no args, toggle for self
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tptoggle")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tptoggle");
            return true;
        }

        UUID playerUUID = player.getUniqueId();

        // Toggle teleport status for self
        if (teleportToggled.contains(playerUUID)) {
            teleportToggled.remove(playerUUID);
            String enabledStyle = lang.get("styles.state.true");
            lang.sendMessage(player, "tp.toggle", 
                    "{state}", enabledStyle + "enabled" + lang.getFirstColorCode("tp.toggle"),
                    "{name}", "");
        } else {
            teleportToggled.add(playerUUID);
            String disabledStyle = lang.get("styles.state.false");
            lang.sendMessage(player, "tp.toggle", 
                    "{state}", disabledStyle + "disabled" + lang.getFirstColorCode("tp.toggle"),
                    "{name}", "");
        }

        return true;
    }

    /**
     * Handles the /top command by teleporting to the highest block
     *
     * @param sender The sender of the command
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportTop(CommandSender sender) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.top")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "top");
            return true;
        }

        // Check if player is in the Nether
        if (player.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
            if(!player.hasPermission("allium.admin")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "top in the nether.");
                return true;
            }
        }

        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            Text.sendErrorMessage(player, "contact-admin", lang);
            return true;
        }


        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = world.getHighestBlockYAt(x, z);

        Location destination = new Location(
            world,
            x + 0.5,
            y + 1,
            z + 0.5,
            current.getYaw(),
            current.getPitch()
        );
        player.teleportAsync(destination).thenAccept(success -> {
            if (success) {
                lang.sendMessage(player, "tp.success", 
                        "{name}", "to top.", 
                        "{target}", "");
            } else {
                player.sendMessage(Text.colorize("&cTeleport failed."));
            }
        });
        return true;
    }

    /**
     * Handles the /bottom command by teleporting to the lowest safe block
     *
     * @param sender The sender of the command
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportBottom(CommandSender sender) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.bottom")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "bottom");
            return true;
        }

        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            Text.sendErrorMessage(player, "contact-admin", lang);
            return true;
        }

        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = world.getMinHeight(); // Use minimum world height

        // Find the first safe location from bottom up
        while (y < current.getY()) {
            Block block = world.getBlockAt(x, y, z);
            Block blockAbove = world.getBlockAt(x, y + 1, z);
            Block blockAbove2 = world.getBlockAt(x, y + 2, z);

            if (!block.getType().isAir() &&
                    blockAbove.getType().isAir() &&
                    blockAbove2.getType().isAir()) {
                break;
            }
            y++;
        }

        Location destination = new Location(
            world,
            x + 0.5,
            y + 1,
            z + 0.5,
            current.getYaw(),
            current.getPitch()
        );
        player.teleportAsync(destination).thenAccept(success -> {
            if (success) {
                lang.sendMessage(player, "tp.success", 
                        "{name}", "to bottom.", 
                        "{target}", "");
            } else {
                player.sendMessage(Text.colorize("&cTeleport failed."));
            }
        });
        return true;
    }

    /**
     * Handles offline teleport commands (/otp, /offlinetp, /tpoffline)
     * Allows players with core.tp.offline permission to teleport to offline players' last known locations
     *
     * @param sender The sender of the command
     * @param args Command arguments: [player]
     * @return true if the command was processed successfully
     */
    /**
     * Checks if a UUID belongs to a Floodgate (Bedrock) player
     * @param uuid The UUID to check
     * @return true if the UUID belongs to a Floodgate player, false otherwise
     */
    private boolean isFloodgatePlayer(UUID uuid) {
        try {
            // Check if Floodgate is available
            return plugin.getServer().getPluginManager().getPlugin("floodgate") != null &&
                   uuid.version() == 2; // Floodgate UUIDs have version 2
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean handleTeleportOffline(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            handleTeleport(sender, args, "tp");
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tp.offline")) {
            if (!player.hasPermission("allium.tp")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tp");
                return true;
            }
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tp offline");
            return true;
        }

        // Check arguments
        if (args.length < 1) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", "otp")
                .replace("{args}", "<player>");
            lang.sendMessage(player, "command-usage", usage);
            return true;
        }

        String targetPlayerName = args[0];
        UUID targetUUID;
        String resolvedName = targetPlayerName;

        // First, check if the player is online
        Player onlinePlayer = plugin.getServer().getPlayer(targetPlayerName);
        if (onlinePlayer != null) {
            // If player is online, forward to regular teleport handler
            return handleTeleport(player, args, "tp");
        }

        // For offline players, get their profile
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(targetPlayerName);
        
        // First check if the player exists (valid UUID/name)
        if (offlinePlayer.getUniqueId() == null || (offlinePlayer.getName() == null && !isFloodgatePlayer(offlinePlayer.getUniqueId()))) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }
        
        // Then check if the player has ever joined
        if (!offlinePlayer.hasPlayedBefore() && !isFloodgatePlayer(offlinePlayer.getUniqueId())) {
            Text.sendErrorMessage(player, "never-joined", lang, "{name}", offlinePlayer.getName() != null ? offlinePlayer.getName() : targetPlayerName);
            return true;
        }

        // Get the player's UUID and resolved name
        targetUUID = offlinePlayer.getUniqueId();
        if (offlinePlayer.getName() != null) {
            resolvedName = offlinePlayer.getName();
        }

        // Get the player's last logout location from database
        Location targetLocation = database.getPlayerLocation(targetUUID, Database.LocationType.LOGOUT);
        
        if (targetLocation == null) {
            Text.sendErrorMessage(player, "location-not-found", lang, "{name}", resolvedName);
            return true;
        }
        
        final Location finalTargetLocation = targetLocation;
        final String targetName = resolvedName; // capture effectively-final for lambda

        // Teleport the player to the offline player's last known location
        player.teleportAsync(finalTargetLocation).thenAccept(success -> {
            if (success) {
                // Send success message with sound and particles
                lang.sendMessage(player, "tp.success", 
                        "{name}", "", 
                        "{target}", targetName);
                
                // Log the teleport for debugging if enabled
                if (config.getBoolean("debug-mode")) {
                    Text.sendDebugLog(INFO, "Player " + player.getName() + " teleported to offline location of " + targetName + 
                        " at " + finalTargetLocation.getWorld().getName() + " " + (int) finalTargetLocation.getX() + ", " + 
                        (int) finalTargetLocation.getY() + ", " + (int) finalTargetLocation.getZ());
                }
            } else {
                Text.sendErrorMessage(player, "teleport-failed", lang);
            }
        });
            
        // Log the teleport for debugging if enabled
        if (config.getBoolean("debug-mode")) {
            Text.sendDebugLog(INFO, "Player " + player.getName() + " teleported to offline location of " + targetName + 
                " at " + targetLocation.getWorld().getName() + " " + (int) targetLocation.getX() + ", " + 
                (int) targetLocation.getY() + ", " + (int) targetLocation.getZ());
        }
        return true;
    }

    /**
     * Cancels the player's outgoing teleport request(s).
     * If a target player name is provided, cancels only the request involving that target.
     */
    private boolean handleTeleportCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        UUID playerUUID = player.getUniqueId();

        // Optional: cancel request with a specific target only
        UUID targetUUID = null;
        if (args.length >= 1) {
            Player targetPlayer = plugin.getServer().getPlayer(args[0]);
            if (targetPlayer != null) {
                targetUUID = targetPlayer.getUniqueId();
            }
        }

        // Collect keys to remove (map key is the request owner's key from creation time)
        List<UUID> keysToRemove = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
            UUID key = entry.getKey();
            UUID value = entry.getValue();

            boolean involvesPlayer = key.equals(playerUUID) || value.equals(playerUUID);
            if (!involvesPlayer) continue;

            if (targetUUID != null) {
                // Only remove if the other party matches the specified target
                UUID other = key.equals(playerUUID) ? value : key;
                if (!other.equals(targetUUID)) continue;
            }

            keysToRemove.add(key);
        }

        if (keysToRemove.isEmpty()) {
            lang.sendMessage(player, "tp.no-request");
            return true;
        }

        for (UUID key : keysToRemove) {
            UUID other = teleportRequests.get(key);

            // Remove mappings and timestamps
            teleportRequestTimestamps.remove(key);
            teleportRequests.remove(key);
            teleportHereRequests.remove(key);

            // Notify participants
            lang.sendMessage(player, "tp.cancel");
            if (other != null) {
                Player otherPlayer = plugin.getServer().getPlayer(other);
                if (otherPlayer != null && otherPlayer.isOnline()) {
                    lang.sendMessage(otherPlayer, "tp.cancel");
                }
            }
        }

        return true;
    }

    /**
     * Gets the back location for a player asynchronously
     * First checks for death location if player has permission, then falls back to teleport location
     * @param player The player to get back location for
     * @return CompletableFuture that completes with the back location or null if none found
     */
    public boolean isTeleportToggled(UUID playerUUID) {
        if (teleportToggled.contains(playerUUID)) {
            return true;
        }
        // Check database if not in memory
        Boolean state = database.getTeleportToggleState(playerUUID);
        if (state != null && state) {
            teleportToggled.add(playerUUID);
            return true;
        }
        return false;
    }

    /**
     * Sets the teleport toggle state for a player
     * @param playerId Player's UUID
     * @param state True to enable teleport toggling, false to disable
     */
    public void setTeleportToggled(UUID playerId, boolean state) {
        if (state) {
            teleportToggled.add(playerId);
        } else {
            teleportToggled.remove(playerId);
        }
        // Save to database
        database.saveTeleportToggleState(playerId, state);
        Text.sendDebugLog(INFO, "Saved teleport toggle state for UUID " + playerId + ": " + state);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        // Use the alias actually typed by the user so aliases like "/tpa" resolve correctly
        String commandName = alias.toLowerCase();

        // For TPA and similar commands that need player names
        if (args.length == 1) {
            if (teleportRequestAliases.contains(commandName) ||
                    teleportCommandAliases.contains(commandName) ||
                    teleportHereAliases.contains(commandName) ||
                    "tpo".equals(commandName)) {

                // Return online player names that match the current input
                return getOnlinePlayerNames(args[0]);
            }

            // For TPPOS command, suggest the player's current X coordinate
            if (teleportPositionAliases.contains(commandName)) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getX())));
            }
            if (teleportToggleAliases.contains(commandName)) {
                if (sender.hasPermission("allium.tptoggle.others")) {
                    return getOnlinePlayerNames(args[0]);
                }
            }

        }
        // Additional arguments for TPPOS command
        else if (teleportPositionAliases.contains(commandName)) {
            if (args.length == 2) {
                // Suggest Y coordinate for second argument
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getY())));
            }
            else if (args.length == 3) {
                // Suggest Z coordinate for third argument
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getZ())));
            }
        }

        return Collections.emptyList();
    }

    /**
     * Gets a list of online player names that match the beginning of the input string
     *
     * @param input The beginning of the player name to match
     * @return List of matching player names
     */
    private List<String> getOnlinePlayerNames(String input) {
        String lowercaseInput = input.toLowerCase();
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowercaseInput))
                .collect(Collectors.toList());
    }

    private CompletableFuture<Void> saveCurrentLocation(Player player) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Save the current location asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Location currentLoc = player.getLocation();
                database.savePlayerLocation(
                    player.getUniqueId(),
                    LocationType.TELEPORT,
                    currentLoc,
                    System.currentTimeMillis()
                );
                // Update in-memory cache
                lastLocation.put(player.getUniqueId(), currentLoc);
                future.complete(null);
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error saving current location for " + player.getName(), e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Handle player teleport events to teleport selected pets and entities
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Track last location for /back command
        lastLocation.put(playerUUID, event.getFrom());
        
        // Redundantly persist back location to DB to ensure our teleports are saved
        try {
            if (database != null && event.getFrom() != null && event.getFrom().getWorld() != null) {
                database.savePlayerLocation(playerUUID, LocationType.TELEPORT, event.getFrom(), System.currentTimeMillis());
                if (config.getBoolean("debug-mode")) {
                    plugin.getLogger().fine("[TP] Saved TELEPORT back location for " + player.getName() + " at " +
                            event.getFrom().getWorld().getName() + " " +
                            (int) event.getFrom().getX() + ", " + (int) event.getFrom().getY() + ", " + (int) event.getFrom().getZ());
                }
            }
        } catch (Exception ex) {
            if (config.getBoolean("debug-mode")) {
                Text.sendDebugLog(WARN, "[TP] Failed to save TELEPORT back location for " + player.getName() + ": " + ex.getMessage());
            }
        }
        
        // Handle pet teleportation
        List<Entity> playerPets = selectedPets.get(playerUUID);
        if (playerPets != null && !playerPets.isEmpty()) {
            // Schedule teleport after player has arrived on the player's entity scheduler (Folia-safe)
            SchedulerAdapter.runAtEntityLater(player, () -> {
                teleportPets(playerUUID, player, playerPets);
            }, 5L); // 5 tick delay (1/4 second)
        }
        
        // Handle entity teleportation (admin mode)
        Map<UUID, Entity> playerEntities = selectedEntities.get(playerUUID);
        if (playerEntities != null && !playerEntities.isEmpty() && player.hasPermission("allium.tpmob")) {
            // Schedule teleport after player has arrived on the player's entity scheduler (Folia-safe)
            SchedulerAdapter.runAtEntityLater(player, () -> {
                // Convert map values to list for teleportation
                List<Entity> entitiesToTeleport = new ArrayList<>(playerEntities.values());
                Text.sendDebugLog(INFO, "Teleporting " + entitiesToTeleport.size() + " entities for player " + player.getName());
                teleportEntities(playerUUID, player, entitiesToTeleport);
                
                // Cleanup selection and listener after teleport
                Map<UUID, Entity> map = selectedEntities.get(playerUUID);
                if (map != null) {
                    map.clear();
                    selectedEntities.remove(playerUUID);
                }
                EntityTeleportListener listener = activeEntityTeleportListeners.get(playerUUID);
                if (listener != null) {
                    listener.unregisterListener();
                    activeEntityTeleportListeners.remove(playerUUID);
                }
            }, 5L); // 5 tick delay (1/4 second)
        }
    }
    
    /**
     * Inner class to handle player quit events for pet teleport cleanup
     */
    private class PlayerQuitListener implements Listener {
        
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();
            
            // Check if player has an active pet teleport listener
            PetTeleportListener petListener = activePetTeleportListeners.get(playerUUID);
            if (petListener != null) {
                // Cleanup the listener without showing distance message
                petListener.cleanup(false);
            }
            
            // Check if player has an active entity teleport listener
            EntityTeleportListener entityListener = activeEntityTeleportListeners.get(playerUUID);
            if (entityListener != null) {
                // Cleanup the listener without showing distance message
                entityListener.unregisterListener();
            }
            
            // Clear any selected pets for this player
            if (selectedPets.containsKey(playerUUID)) {
                List<Entity> pets = selectedPets.get(playerUUID);
                if (pets != null) {
                    // Remove glow effect from all pets
                    for (Entity pet : pets) {
                        if (pet != null && pet.isValid()) {
                            removePetGlow(pet);
                        }
                    }
                    pets.clear();
                }
                // Remove player from selected pets map
                selectedPets.remove(playerUUID);
            }
            
            // Clear any selected entities for this player
            if (selectedEntities.containsKey(playerUUID)) {
                Map<UUID, Entity> entityMap = selectedEntities.get(playerUUID);
                if (entityMap != null) {
                    // Remove glow effect from all entities
                    for (Entity entity : entityMap.values()) {
                        if (entity != null && entity.isValid()) {
                            entity.setGlowing(false);
                        }
                    }
                    entityMap.clear();
                }
                // Remove player from selected entities map
                selectedEntities.remove(playerUUID);
            }
        }
    }

    public PluginStart getPlugin() {
        return plugin;
    }
    /**
     * Periodically clears expired teleport requests from the in-memory maps.
     * Uses the configured expiration from getRequestExpirationTime().
     */
    private void clearExpiredRequests() {
        try {
            long now = System.currentTimeMillis();
            long expiration = getRequestExpirationTime();

            // Collect requesters whose requests have expired
            List<UUID> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, Long> entry : teleportRequestTimestamps.entrySet()) {
                Long ts = entry.getValue();
                if (ts == null) continue;
                if (now - ts >= expiration) {
                    toRemove.add(entry.getKey());
                }
            }

            if (toRemove.isEmpty()) return;

            // Remove expired entries from both maps
            for (UUID requester : toRemove) {
                teleportRequestTimestamps.remove(requester);
                teleportRequests.remove(requester);
            }
        } catch (Exception ex) {
            // Be resilient; do not let a periodic cleanup task crash
            Text.sendDebugLog(WARN, "Failed to clear expired teleport requests: " + ex.getMessage());
        }
    }
}
