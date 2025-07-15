package net.survivalfun.core.commands.teleportation;

// No special imports needed for distance checking

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import net.survivalfun.core.listeners.jobs.PlayerDeathListener;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    // Maps for teleport requests
    private final Map<UUID, UUID> teleportRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportRequestTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, List<org.bukkit.entity.Entity>> selectedPets = new ConcurrentHashMap<>();
    private final Map<UUID, PetTeleportListener> activePetTeleportListeners = new ConcurrentHashMap<>();
    private final Map<UUID, EntityTeleportListener> activeEntityTeleportListeners = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, org.bukkit.entity.Entity>> selectedEntities = new ConcurrentHashMap<>();
    private final Set<UUID> teleportToggled = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();

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
        return System.currentTimeMillis() + (expireSeconds * 1000L);
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

        
        // Register player quit listener to handle tppet cleanup when players log out
        plugin.getServer().getPluginManager().registerEvents(new PlayerQuitListener(), plugin);

        // Schedule task to clear expired teleport requests
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::clearExpiredRequests, 1200L, 1200L);
    }



    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String usedCommand = label.toLowerCase();

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
     * Teleport selected entities to the player's location
     * @param playerUUID The UUID of the player
     * @param player The player
     * @param entities The entities to teleport
     */
    /**
     * Temporarily disables distance checking for entity teleportation
     * This prevents the "You have moved too far" message when teleporting entities
     * while flying or moving quickly as an admin
     */
    private void disableDistanceChecking() {
        // For admins using /tpe, we're just going to teleport entities instantly
        // This effectively bypasses any distance checking that might occur
        plugin.getLogger().info("Distance checking disabled for entity teleportation");
        
        // We don't need a listener since we're teleporting entities instantly
        // This avoids the "moved too far" message that can happen with delayed teleports
    }
    
    private void teleportEntities(UUID playerUUID, Player player, List<Entity> entities) {
        if (player == null || !player.isOnline() || entities == null || entities.isEmpty()) {
            plugin.getLogger().warning("Cannot teleport entities: invalid parameters");
            return;
        }
        
        // Disable distance checking to prevent "moved too far" messages
        disableDistanceChecking();
        
        // Calculate offset positions in a circle around the player
        double radius = 2.0;
        int numEntities = entities.size();
        
        plugin.getLogger().info("Teleporting " + numEntities + " entities INSTANTLY for player " + player.getName() + " (distance checking disabled)");
        
        // Create a copy of the entities list to avoid concurrent modification
        final List<Entity> entityList = new ArrayList<>(entities);
            
            // Get current player location for teleporting
            Location targetLocation = player.getLocation();
            int successCount = 0;
            
            // Teleport each entity to a position around the player
            for (int i = 0; i < entityList.size(); i++) {
                Entity entity = entityList.get(i);
                
                // Enhanced debug logging
                if (entity == null) {
                    plugin.getLogger().warning("Skipping null entity during teleport");
                    continue;
                } 
                
                if (!entity.isValid()) {
                    plugin.getLogger().warning("Skipping invalid entity during teleport: " + 
                                             entity.getType().name() + " (UUID: " + entity.getUniqueId() + ")");
                    continue;
                }
                
                // Log entity state before teleport
                plugin.getLogger().info("Entity pre-teleport state: Type=" + entity.getType().name() + 
                                      ", UUID=" + entity.getUniqueId() + 
                                      ", World=" + entity.getWorld().getName() + 
                                      ", Location=" + entity.getLocation() + 
                                      ", isDead=" + entity.isDead() + 
                                      ", isGlowing=" + entity.isGlowing());
                
                // Check if entity is in the same world as the player
                if (!entity.getWorld().equals(targetLocation.getWorld())) {
                    plugin.getLogger().warning("Entity is in a different world, cannot teleport: " + 
                                              entity.getType().name() + " (" + entity.getWorld().getName() + 
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
                
                // Teleport the entity
                boolean success = entity.teleport(entityLocation);
                
                if (success) {
                    // Remove glowing effect after successful teleport
                    entity.setGlowing(false);
                    successCount++;
                    plugin.getLogger().info("Successfully teleported entity " + entity.getType().name() + 
                                           " to player " + player.getName());
                } else {
                    plugin.getLogger().warning("Failed to teleport entity " + entity.getType().name() + 
                                               " to player " + player.getName());
                }
            }
            
            // Notify the player
            if (successCount > 0) {
                // Get the base message
                String message = lang.get("tp.tppet-teleported")
                        .replace("{count}", String.valueOf(successCount));
                
                // Fix the pluralization manually
                if (message.contains("{s|y|ies}")) {
                    if (successCount == 1) {
                        message = message.replace("{s|y|ies}", "y"); // singular: "entity"
                    } else {
                        message = message.replace("{s|y|ies}", "ies"); // plural: "entities"
                    }
                } else if (message.contains("{s}")) {
                    // Handle old format for backward compatibility
                    message = message.replace("{s}", successCount == 1 ? "" : "s");
                }
                
                player.sendMessage(message);
            } else {
                player.sendMessage(lang.get("tp.tppet-none-teleported"));
            }
    }

    private void teleportPets(UUID targetUUID, Player finalTargetPlayer, List<Entity> playerPets) {
        if (playerPets != null && !playerPets.isEmpty()) {
            // Get teleport delay from config (default to 3 seconds if not set)
            int delay = config.getInt("teleport.delay") > 0 ? config.getInt("teleport.delay") : 3;
            Location originalLocation = finalTargetPlayer.getLocation();

            if (finalTargetPlayer.hasPermission("allium.tpa.nodelay")) {
                // Teleport immediately if player has nodelay permission
                for (Entity pet : playerPets) {
                    pet.teleport(finalTargetPlayer.getLocation());
                    removePetGlow(pet);
                }
                playerPets.clear();
                selectedPets.remove(targetUUID);
            } else {
                // Schedule delayed teleportation
                finalTargetPlayer.sendMessage(lang.get("tp.delay").replace("{delay}", String.valueOf(delay)));
                
                // Create a move listener to cancel teleport if player moves
                Listener moveListener = new Listener() {
                    @EventHandler
                    public void onPlayerMove(PlayerMoveEvent event) {
                        if (event.getPlayer().getUniqueId().equals(targetUUID)) {
                            // Get distance between current location and original location
                            double distance = event.getTo().distance(originalLocation);
                            
                            // Cancel only if moved more than 1 block away
                            if (distance > 1.0) {
                                finalTargetPlayer.sendMessage(lang.get("tp.moved"));
                                event.getHandlers().unregister(this);
                            }
                        }
                    }
                };

                // Register the move listener
                plugin.getServer().getPluginManager().registerEvents(moveListener, plugin);

                // Store a final reference to playerPets for use in the runnable
                final List<Entity> finalPlayerPets = new ArrayList<>(playerPets);
                
                // Schedule the teleportation after the delay
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    // Check if player is still within 1 block of original location
                    if (finalTargetPlayer.getLocation().distance(originalLocation) <= 1.0) {
                        for (Entity pet : finalPlayerPets) {
                            pet.teleport(finalTargetPlayer.getLocation());
                            removePetGlow(pet);
                        }
                        selectedPets.remove(targetUUID);
                    }
                    // Unregister move listener
                    PlayerMoveEvent.getHandlerList().unregister(moveListener);
                }, delay * 20L); // delay in ticks
                
                // Clear the original list
                playerPets.clear();
            }
        }
    }

    private void removePetGlow(org.bukkit.entity.Entity entity) {
        if (entity != null) {
            entity.setGlowing(false);
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
        private int checkTaskId = -1;
        private static final int CHECK_INTERVAL = 20; // Check every second (20 ticks)
        private static final double MAX_DISTANCE = 30.0; // Maximum distance in blocks

        public PetTeleportListener(Player player, int maxPets) {
            this.player = player;
            this.playerUUID = player.getUniqueId();
            this.maxPets = maxPets;
            this.startLocation = player.getLocation();
            
            // Start the distance check task
            this.checkTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
                @Override
                public void run() {
                    checkDistance();
                }
            }, CHECK_INTERVAL, CHECK_INTERVAL);
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
                if (checkTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(checkTaskId);
                    checkTaskId = -1;
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
                clickedEntity.setGlowing(true);
                
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
                entity.setGlowing(false);
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
        private int checkTaskId = -1;
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
                plugin.getLogger().info("Distance checking disabled for admin: " + player.getName());
            }
            
            // Register events
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            
            // Start the distance check task (will be skipped for admins due to distanceCheckingEnabled = false)
            this.checkTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
                @Override
                public void run() {
                    checkDistance();
                }
            }, CHECK_INTERVAL, CHECK_INTERVAL);
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
            plugin.getLogger().info("Distance checking disabled for player " + player.getName());
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
            
            // Admin override - never disable for admins
            if (player.hasPermission("allium.tpmob")) {
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
                plugin.getLogger().info("Skipping distance-based cleanup for admin: " + player.getName());
                return;
            }
            
            // Only cleanup if not already done
            if (isRegistered) {
                // Cancel the check task
                if (checkTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(checkTaskId);
                    checkTaskId = -1;
                }
                
                // Unregister events
                HandlerList.unregisterAll(this);
                isRegistered = false;
                
                // Clear any active entity glows
                Map<UUID, org.bukkit.entity.Entity> entityMap = selectedEntities.get(playerUUID);
                if (entityMap != null) {
                    for (org.bukkit.entity.Entity entity : entityMap.values()) {
                        if (entity != null && entity.isValid()) {
                            entity.setGlowing(false);
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
            if (entityUUID.equals(lastProcessedEntityUUID) && (currentTime - lastProcessedTime < 1000)) {
                plugin.getLogger().info("Ignoring duplicate event for entity: " + entityUUID);
                event.setCancelled(true);
                return;
            }
            
            // Update last processed entity and time
            lastProcessedEntityUUID = entityUUID;
            lastProcessedTime = currentTime;
            
            // Add to processed events for additional safety
            String eventId = currentTime + "_" + entityUUID;
            processedEvents.add(eventId);
            
            // Clean up old processed events
            Iterator<String> iterator = processedEvents.iterator();
            while (iterator.hasNext()) {
                String id = iterator.next();
                try {
                    long timestamp = Long.parseLong(id.substring(0, id.indexOf("_")));
                    if (currentTime - timestamp > 2000) { // Older than 2 seconds
                        iterator.remove();
                    }
                } catch (Exception e) {
                    iterator.remove();
                }
            }
            
            // Cancel the event to prevent other plugins from processing it
            event.setCancelled(true);
            
            // Process the entity selection/deselection in the next tick to avoid conflicts
            Bukkit.getScheduler().runTask(plugin, () -> {
                processEntitySelection(event.getPlayer(), clickedEntity, entityUUID);
            });
        }
        
        /**
         * Process entity selection/deselection
         * This is run in the next tick to avoid conflicts
         */
        private void processEntitySelection(Player player, org.bukkit.entity.Entity clickedEntity, UUID entityUUID) {
            // Add detailed debug logging
            plugin.getLogger().info("Processing entity selection for player: " + player.getName() + ", entity: " + 
                                   clickedEntity.getType().name() + ", UUID: " + entityUUID);
            
            // Get or create the map of selected entities for this player
            Map<UUID, org.bukkit.entity.Entity> entityMap = selectedEntities.computeIfAbsent(
                playerUUID, k -> new HashMap<>()
            );
            
            // Debug log the current selection state
            plugin.getLogger().info("Current selection state - Player has " + entityMap.size() + " entities selected");
            for (Map.Entry<UUID, org.bukkit.entity.Entity> entry : entityMap.entrySet()) {
                plugin.getLogger().info("  - Selected entity: " + entry.getValue().getType().name() + ", UUID: " + entry.getKey());
            }
            
            // Check if the entity is already selected by UUID
            boolean isAlreadySelected = entityMap.containsKey(entityUUID);
            plugin.getLogger().info("Entity is already selected: " + isAlreadySelected);
            
            if (isAlreadySelected) {
                // Deselect the entity
                entityMap.remove(entityUUID);
                
                // Remove glow effect and restore default persistence
                if (clickedEntity != null && clickedEntity.isValid()) {
                    clickedEntity.setGlowing(false);
                    
                    // Restore default persistence behavior for mobs
                    if (clickedEntity instanceof org.bukkit.entity.Mob) {
                        org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) clickedEntity;
                        mob.setRemoveWhenFarAway(true); // Reset to default behavior
                        plugin.getLogger().info("Restored default despawn behavior for mob: " + clickedEntity.getType().name());
                    }
                }
                
                // Get the message and first color code
                String actionMessage = lang.get("tp.tpe-action");
                String firstColorOfAction = lang.getFirstColorCode("tp.tpe-action");
                String disabledStyle = lang.get("styles.state.false");
                
                player.sendMessage(actionMessage
                .replace("{mob}", getEntityName(clickedEntity))
                .replace("{state}", disabledStyle + "deselected" + firstColorOfAction));
            } else {
                // Check max entities limit
                if (entityMap.size() >= maxEntities) {
                    Text.sendErrorMessage(player, "tp.tppet-maxpets", lang, "{max}", String.valueOf(maxEntities));
                    return;
                }
                
                // Select the entity by UUID
                entityMap.put(entityUUID, clickedEntity);
                
                // Add glow effect to the entity and make it persistent
                if (clickedEntity != null && clickedEntity.isValid()) {
                    clickedEntity.setGlowing(true);
                    
                    // Make mobs persistent so they don't despawn when chunks unload
                    if (clickedEntity instanceof org.bukkit.entity.Mob) {
                        org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) clickedEntity;
                        mob.setRemoveWhenFarAway(false); // Prevent despawning
                        plugin.getLogger().info("Made mob persistent to prevent despawning: " + clickedEntity.getType().name());
                    }
                }
                
                // Get the message and first color code
                String actionMessage = lang.get("tp.tpe-action");
                String firstColorOfAction = lang.getFirstColorCode("tp.tpe-action");
                String enabledStyle = lang.get("styles.state.true");
                
                player.sendMessage(actionMessage
                .replace("{mob}", getEntityName(clickedEntity))
                .replace("{state}", enabledStyle + "selected" + firstColorOfAction));
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
                return entity.customName().toString();
            }
            
            // Return formatted entity type name
            return entity.getType().name().substring(0, 1).toUpperCase() + 
                   entity.getType().name().substring(1).toLowerCase().replace("_", " ");
        }
    }

    /**
     * Teleports selected pets to a target location
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
            pet.teleport(targetLocation);
            removePetGlow(pet);
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
     * Teleports selected entities to a target location
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
        plugin.getLogger().info("Attempting to teleport " + entitiesToTeleport.size() + " entities for player " + player.getName());
        for (Entity entity : entitiesToTeleport) {
            if (entity != null) {
                plugin.getLogger().info("Entity to teleport: " + entity.getType().name() + 
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
        Bukkit.getScheduler().runTask(plugin, () -> {
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
            
            plugin.getLogger().info("Entity teleport mode auto-disabled instantly for player: " + player.getName());
        }); // Run in the next tick for instant feedback
        
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

    /**
     * Handles the /tp and /tpo commands with unified logic
     *
     * @param sender The sender of the command
     * @param args Command arguments: [x y z] or [player] or [player1] [player2] or [player] [x] [y] [z]
     * @param label The command label used ("/tp" or "/tpo")
     * @return true if the command was processed successfully
     */
    private boolean handleTeleport(CommandSender sender, String[] args, String label) {
        // Check for selected pets to teleport along with the player
        List<org.bukkit.entity.Entity> playerPets = null;
        boolean hasPets = false;
        
        // Ensure sender is a player to get UUID
        if (sender instanceof Player player) {
            playerPets = selectedPets.get(player.getUniqueId());
            hasPets = playerPets != null && !playerPets.isEmpty();
        }
        
        // Check for coordinate-based teleport: /tp x y z [yaw] [pitch] or /tp player x y z [yaw] [pitch]
        if ((args.length >= 3 && args.length <= 5) && 
            (isNumeric(args[0]) || (args.length >= 4 && isNumeric(args[1])))) {
            try {
                Player targetPlayer;
                int coordIndex = 0;
                
                // Determine if we have a player target or if it's the sender
                if (args.length == 4) {
                    // Format: /tp player x y z
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
                    // Format: /tp x y z (teleport self)
                    targetPlayer = (Player) sender;
                } else {
                    sender.sendMessage(lang.get("not-a-player"));
                    return true;
                }
                
                // Get current location for relative coordinates
                Location currentLoc = targetPlayer.getLocation();
                
                // Parse coordinates
                double x = parseCoordinate(currentLoc.getX(), args[coordIndex]);
                double y = parseCoordinate(currentLoc.getY(), args[coordIndex + 1]);
                double z = parseCoordinate(currentLoc.getZ(), args[coordIndex + 2]);
                
                // Parse yaw and pitch if provided, otherwise use current values
                float yaw = currentLoc.getYaw();
                float pitch = currentLoc.getPitch();
                
                int worldArgIndex = 3; // Default index for world argument if no yaw/pitch
                
                // Check for yaw argument (4th or 5th argument)
                if (args.length > 3 && isNumeric(args[coordIndex + 3])) {
                    yaw = Float.parseFloat(args[coordIndex + 3]);
                    // Normalize yaw to -180 to 180
                    while (yaw < -180.0F) yaw += 360.0F;
                    while (yaw >= 180.0F) yaw -= 360.0F;
                    worldArgIndex = 4;
                    
                    // Check for pitch argument (5th argument)
                    if (args.length > 4 && isNumeric(args[coordIndex + 4])) {
                        pitch = Float.parseFloat(args[coordIndex + 4]);
                        // Clamp pitch between -90 and 90 degrees
                        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
                        worldArgIndex = 5;
                    }
                }
                
                // Get world (default to current world if not specified)
                World world = targetPlayer.getWorld();
                if (args.length > worldArgIndex) {
                    World targetWorld = Bukkit.getWorld(args[worldArgIndex]);
                    if (targetWorld == null) {
                        sender.sendMessage(lang.get("world-not-found").replace("{world}", args[worldArgIndex]));
                        return true;
                    }
                    world = targetWorld;
                }
                
                // Create target location with specified yaw/pitch
                Location targetLocation = new Location(
                    world,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
                );
                
                // Store last location before teleporting
                saveLastLocation(targetPlayer.getUniqueId(), targetPlayer.getLocation());
                
                // Teleport player to target location
                targetPlayer.teleport(targetLocation);
                
                // Send appropriate messages
                if (targetPlayer.equals(sender)) {
                    targetPlayer.sendMessage(lang.get("tp.success")
                    .replace(" {name}", "")
                    .replace("{target}", "to ") + lang.get("tp.position")
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{z}", String.valueOf(z)));

                    // Check if player has pets selected via /tppet
                    if (hasPets(targetPlayer)) {
                        teleportSelectedPets(targetPlayer, targetLocation);
                    } else {
                        teleportPets(targetPlayer.getUniqueId(), targetPlayer, playerPets);
                    }
                } else {
                    targetPlayer.sendMessage(lang.get("tp.success")
                    .replace("{name}", "")
                    .replace("{target}", "to ") + lang.get("tp.position")
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{z}", String.valueOf(z)));
                    sender.sendMessage(lang.get("tp.success")
                    .replace("{name}", targetPlayer.getName())
                    .replace("{target}", "to ") + lang.get("tp.position")
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{z}", String.valueOf(z)));
                    // Teleport pets for sender if sender is a player
                    if (sender instanceof Player senderPlayer) {
                        List<Entity> senderPets = selectedPets.get(senderPlayer.getUniqueId());
                        teleportPets(senderPlayer.getUniqueId(), senderPlayer, senderPets);
                    }
                }
                
                return true;
                
            } catch (NumberFormatException e) {
                // If coordinates can't be parsed, fall through to player teleport logic
                Text.sendErrorMessage(sender, "invalid-coordinates", lang);
                return true;
            }
        }
        // Check if this is a /back command
        if (label.equalsIgnoreCase("back")) {
            // Check if sender is a player
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("not-a-player"));
                return true;
            }

            // Check permission
            if (!player.hasPermission("allium.back")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "back");
                return true;
            }

            UUID playerUUID = player.getUniqueId();
            Location targetLocation = null;
            boolean isDeathLocation = false;

            // Check for death location first if player has the permission
            if (player.hasPermission("allium.back.ondeath")) {
                // Get death location from database via PlayerDeathListener
                Location deathLocation = PlayerDeathListener.getDeathLocation(player);
                if (deathLocation != null) {
                    // Check if death location is not too old (2 minutes = 120,000 milliseconds)
                    long deathTime = PlayerDeathListener.getDeathTime(playerUUID);
                    if (deathTime > 0 && System.currentTimeMillis() - deathTime <= 120000) {
                        targetLocation = deathLocation;
                        isDeathLocation = true;
                        PlayerDeathListener.clearDeathLocation(player); // Clear after use
                    }
                }
            }

            // If no death location or no permission, use regular last location from database
            if (targetLocation == null) {
                // Get the last teleport location from database
                Location teleportLocation = database.getPlayerLocation(playerUUID, LocationType.TELEPORT);
                if (teleportLocation == null) {
                    // Try in-memory cache as fallback (for backward compatibility)
                    teleportLocation = lastLocation.get(playerUUID);
                    if (teleportLocation == null) {
                        Text.sendErrorMessage(player, "tp.no-previous-location", lang);
                        return true;
                    }
                }
                targetLocation = teleportLocation;
            }

            // Store current location before teleporting
            saveLastLocation(player.getUniqueId(), player.getLocation());
            
            // Teleport player to target location
            player.teleport(targetLocation);
            
            // Send appropriate message
            if (isDeathLocation) {
                player.sendMessage(lang.get("tp.back-death-location"));
            } else {
                player.sendMessage(lang.get("tp.back"));
            }

            // Teleport selected pets if any
            if (hasPets(player)) {
                teleportSelectedPets(player, targetLocation);
            }
            
            // Teleport selected entities if any (admin mode)
            if (hasSelectedEntities(player)) {
                teleportSelectedEntities(player, targetLocation);
            }

            return true;
        }

        // Show usage if no arguments provided
        if (args.length == 0) {
            String cmdName = label.equalsIgnoreCase("tpo") ? "tpo" : "tp";
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", cmdName)
                    .replace("{args}", "<x> <y> <z> [yaw] [pitch] | <player> | <player1> <player2> | <player> <x> <y> <z> [yaw] [pitch]"));
            return true;
        }

        // Determine if this is a teleport override command
        boolean bypassToggle = label.equalsIgnoreCase("tpo");
        String cmdName = bypassToggle ? "tpo" : "tp";
        String permissionBase = bypassToggle ? "core.tp.override" : "core.tp";

        // Check permission
        if (!sender.hasPermission(permissionBase)) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", cmdName);
            return true;
        }

        // Check arguments
        if (args.length < 1 || args.length > 3) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", cmdName)
                    .replace("{args}", "<player1> [yaw] [pitch] | <player1> <player2>"));
            return true;
        }

        // Case 1: /tp(o) <player> - Teleport sender to target
        if (args.length == 1) {
            // Check if sender is a player
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("not-a-player"));
                return true;
            }

            // Get the target player
            OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[0]);
            if (target == null || !target.hasPlayedBefore()) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            // If player is online, use their current location
            if (target.isOnline()) {
                Player onlineTarget = (Player) target;
                if (onlineTarget.getUniqueId().equals(player.getUniqueId())) {
                    Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "teleport to");
                    return true;
                }

                // Check if target has teleports toggled off
                if (!bypassToggle && teleportToggled.contains(onlineTarget.getUniqueId())) {
                    Text.sendErrorMessage(player, "tp.blocked", lang, "{name}", onlineTarget.getName());
                    return true;
                }

                // Store last location before teleporting
                saveLastLocation(player.getUniqueId(), player.getLocation());
                
                // Get target's location and apply current yaw/pitch if specified in args
                Location targetLoc = onlineTarget.getLocation();
                if (args.length >= 2 && isNumeric(args[1])) {
                    // If yaw is specified, apply it
                    float yaw = Float.parseFloat(args[1]);
                    // Normalize yaw to -180 to 180
                    while (yaw < -180.0F) yaw += 360.0F;
                    while (yaw >= 180.0F) yaw -= 360.0F;
                    targetLoc.setYaw(yaw);
                    
                    // If pitch is also specified, apply it
                    if (args.length >= 3 && isNumeric(args[2])) {
                        float pitch = Float.parseFloat(args[2]);
                        // Clamp pitch between -90 and 90 degrees
                        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
                        targetLoc.setPitch(pitch);
                    }
                }
                
                // Teleport player to target with specified yaw/pitch
                player.teleport(targetLoc);
                player.sendMessage(lang.get("tp.success").replace("{name}", "to " + onlineTarget.getName())
                        .replace("{target}", ""));
                
                // Teleport selected pets if any
                if (hasPets) {
                    teleportSelectedPets(player, targetLoc);
                }
                return true;
            }

            // If player is offline, check if we have their last location
            Location lastKnownLocation = getLastLocation(target.getUniqueId());
            if (lastKnownLocation == null) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            // Check if trying to teleport to self
            if (target.getUniqueId().equals(player.getUniqueId())) {
                Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "teleport to");
                return true;
            }

            // Store last location before teleporting
            saveLastLocation(player.getUniqueId(), player.getLocation());
            
            // Teleport player to last known location
            player.teleport(lastKnownLocation);
            player.sendMessage(lang.get("tp.success").replace("{name}", "to " + target.getName())
                    .replace("{target}", " (offline)"));
            
            // Check if player has pets selected via /tppet
            if (hasPets(player)) {
                teleportSelectedPets(player, lastKnownLocation);
            } else {
                // Use the old method as fallback
                teleportPets(player.getUniqueId(), player, selectedPets.get(player.getUniqueId()));
            }

        }
        // Case 2: /tp(o) <player1> <player2> - Teleport player1 to player2
        else {
            // Check for additional permission
            String othersPerm = permissionBase + ".others";
            if (!sender.hasPermission(othersPerm)) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", cmdName + " others");
                return true;
            }

            // Get the first player (to be teleported)
            Player player1 = plugin.getServer().getPlayer(args[0]);
            if (player1 == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            // Get the second player (destination)
            OfflinePlayer player2 = plugin.getServer().getOfflinePlayer(args[1]);
            if (player2 == null || !player2.hasPlayedBefore()) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                return true;
            }

            // If destination player is online
            if (player2.isOnline()) {
                Player onlinePlayer2 = (Player) player2;
                if (player1.getUniqueId().equals(onlinePlayer2.getUniqueId())) {
                    Text.sendErrorMessage(sender, "cannot-teleport-to-self", lang);
                    return true;
                }

                // Check if destination player has teleports toggled off
                if (!bypassToggle && teleportToggled.contains(onlinePlayer2.getUniqueId())) {
                    Text.sendErrorMessage(sender, "teleport-toggled-other", lang,
                            "{player}", player1.getName(),
                            "{target}", onlinePlayer2.getName());
                    return true;
                }

                // Store last location of player1 before teleporting
                saveLastLocation(player1.getUniqueId(), player1.getLocation());
                
                // Teleport player1 to player2
                player1.teleport(onlinePlayer2.getLocation());
                player1.sendMessage(lang.get("tp.success").replace("{name}", "to " + onlinePlayer2.getName())
                        .replace("{target}", ""));
                
                // Teleport selected pets if any
                if (hasPets) {
                    teleportSelectedPets(player1, onlinePlayer2.getLocation());
                }
                return true;
            }

            // If destination player is offline
            Location lastKnownLocation = getLastLocation(player2.getUniqueId());
            if (lastKnownLocation == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                return true;
            }

            // Store last location of player1 before teleporting
            saveLastLocation(player1.getUniqueId(), player1.getLocation());
            
            // Teleport player1 to last known location
            player1.teleport(lastKnownLocation);
            player1.sendMessage(lang.get("tp.success").replace("{name}", "to " + player2.getName())
                    .replace("{target}", " (offline)"));
            // Teleport player1's pets to last known location
            teleportPets(player1.getUniqueId(), player1, selectedPets.get(player1.getUniqueId()));

            // Prevent teleporting to self
            if (player1.getUniqueId().equals(player2.getUniqueId())) {
                Text.sendErrorMessage(sender, "cannot-teleport-to-self", lang);
                return true;
            }
            // Check if destination player has teleports toggled off
            if (!bypassToggle && teleportToggled.contains(player2.getUniqueId())) {
                Text.sendErrorMessage(sender, "teleport-toggled-other", lang,
                        "{player}", player1.getName(),
                        "{target}", player2.getName());
                return true;
            }
            // Store last location before teleporting
            saveLastLocation(player1.getUniqueId(), player1.getLocation());
            // Teleport player1 to player2
            player1.teleport(player2.getLocation());
            
            // Check if player1 has pets selected via /tppet
            if (hasPets(player1)) {
                teleportSelectedPets(player1, player2.getLocation());
            } else {
                // Use the old method as fallback
                teleportPets(player1.getUniqueId(), player1, selectedPets.get(player1.getUniqueId()));
            }
            // Notify the sender
            sender.sendMessage(lang.get("tp.success")
                    .replace("{name}", player1.getName())
                    .replace("{target}", player2.getName()));
            // Notify player1 if they're not the sender
            if (!(sender instanceof Player) || !player1.equals(sender)) {
                player1.sendMessage(lang.get("tp.success")
                        .replace("{name}", "")
                        .replace("{target}", "to " + player2.getName()));
            }

        }

        return true;
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
        if (!player.hasPermission("allium.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", isHereRequest ? "tpahere" : "tpa");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}",
                    isHereRequest ? "tpahere" : "tpa")
                    .replace("{args}", "<player>"));
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
                    player.sendMessage(lang.get("tp.already-sent").replace("{name}", target.getName()));
                    return true;
                }
            }
        }

        // Store the request type (normal or here) in the UUID map
        // For a normal /tpa request, player wants to teleport TO target
        // For a /tpahere request, player wants target to teleport TO player
        if (isHereRequest) {
            // For tpahere, we store target -> player (target will teleport to player)
            teleportRequests.put(target.getUniqueId(), player.getUniqueId());
            teleportRequestTimestamps.put(target.getUniqueId(), currentTime);
        } else {
            // For tpa, we store player -> target (player will teleport to target)
            teleportRequests.put(player.getUniqueId(), target.getUniqueId());
            teleportRequestTimestamps.put(player.getUniqueId(), currentTime);
        }

        // Send appropriate messages
        if (isHereRequest) {
            String sentMsg = lang.get("tp.sent")
                .replace("{name}", target.getName())
                .replace("{cancelCmd}", "/tpcancel");
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(sentMsg)
                .clickEvent(ClickEvent.runCommand("/tpcancel"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to cancel the teleport request"))));
                
            String formatMsg = lang.get("tp.format").replace("{action}", "that you teleport to them.").replace("{time}", String.valueOf(expireSeconds));
            TextComponent acceptCmd = Component.text("/tpaccept")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/tpaccept"))
                    .hoverEvent(HoverEvent.showText(Component.text("&aAccept request")));
            TextComponent denyCmd = Component.text("/tpdeny")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/tpdeny"))
                    .hoverEvent(HoverEvent.showText(Component.text("&cDeny request")));
            TextComponent baseMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(formatMsg
                    .replace("{name}", player.getName()));
            Component finalMsg = replaceComponentPlaceholders(baseMsg, "{acceptCmd}", acceptCmd,
                    "{denyCmd}", denyCmd, "", Component.empty());
            target.sendMessage(finalMsg);
        } else {
            String sentMsg = lang.get("tp.sent")
                .replace("{name}", target.getName())
                .replace("{cancelCmd}", "/tpcancel");
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(sentMsg)
                .clickEvent(ClickEvent.runCommand("/tpcancel"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to cancel the teleport request"))));
                
            String formatMsg = lang.get("tp.format").replace("{action}", "to teleport to you.").replace("{time}", String.valueOf(expireSeconds));
            TextComponent acceptCmd = Component.text("/tpaccept")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/tpaccept"))
                    .hoverEvent(HoverEvent.showText(Component.text("&aAccept request")));
            TextComponent denyCmd = Component.text("/tpdeny")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/tpdeny"))
                    .hoverEvent(HoverEvent.showText(Component.text("&cDeny request")));
            TextComponent baseMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(formatMsg
                    .replace("{name}", player.getName()));
            Component finalMsg = replaceComponentPlaceholders(baseMsg, "{acceptCmd}", acceptCmd,
                    "{denyCmd}", denyCmd, "", Component.empty());
            target.sendMessage(finalMsg);
        }



        return true;
    }

    private Component replaceComponentPlaceholders(Component original, String placeholder1, Component replacement1,
                                                   String placeholder2, Component replacement2,
                                                   String placeholder3, Component replacement3) {
        if (!(original instanceof TextComponent textComponent)) {
            return original; // Can't process non-text components
        }

        String content = textComponent.content();

        // Create new component parts
        List<Component> parts = new ArrayList<>();

        // Process text to replace placeholders
        if (content.contains(placeholder1) || content.contains(placeholder2) || content.contains(placeholder3)) {
            // Split by first placeholder
            String[] parts1 = content.split(Pattern.quote(placeholder1), 2);

            if (parts1.length > 1) {
                parts.add(Component.text(parts1[0]).style(textComponent.style()));
                parts.add(replacement1);

                // Check for second placeholder in remaining text
                if (parts1[1].contains(placeholder2)) {
                    String[] parts2 = parts1[1].split(Pattern.quote(placeholder2), 2);
                    parts.add(Component.text(parts2[0]).style(textComponent.style()));
                    parts.add(replacement2);

                    // Check for third placeholder in remaining text
                    if (parts2.length > 1 && parts2[1].contains(placeholder3)) {
                        String[] parts3 = parts2[1].split(Pattern.quote(placeholder3), 2);
                        parts.add(Component.text(parts3[0]).style(textComponent.style()));
                        parts.add(replacement3);
                        if (parts3.length > 1) {
                            parts.add(Component.text(parts3[1]).style(textComponent.style()));
                        }
                    } else if (parts2.length > 1) {
                        parts.add(Component.text(parts2[1]).style(textComponent.style()));
                    }
                } else if (parts1[1].contains(placeholder3)) {
                    // Contains third but not second placeholder
                    String[] parts3 = parts1[1].split(Pattern.quote(placeholder3), 2);
                    parts.add(Component.text(parts3[0]).style(textComponent.style()));
                    parts.add(replacement3);
                    if (parts3.length > 1) {
                        parts.add(Component.text(parts3[1]).style(textComponent.style()));
                    }
                } else {
                    parts.add(Component.text(parts1[1]).style(textComponent.style()));
                }
            } else {
                // Doesn't contain first placeholder
                if (content.contains(placeholder2)) {
                    String[] parts2 = content.split(Pattern.quote(placeholder2), 2);
                    parts.add(Component.text(parts2[0]).style(textComponent.style()));
                    parts.add(replacement2);

                    // Check for third placeholder
                    if (parts2.length > 1 && parts2[1].contains(placeholder3)) {
                        String[] parts3 = parts2[1].split(Pattern.quote(placeholder3), 2);
                        parts.add(Component.text(parts3[0]).style(textComponent.style()));
                        parts.add(replacement3);
                        if (parts3.length > 1) {
                            parts.add(Component.text(parts3[1]).style(textComponent.style()));
                        }
                    } else if (parts2.length > 1) {
                        parts.add(Component.text(parts2[1]).style(textComponent.style()));
                    }
                } else if (content.contains(placeholder3)) {
                    // Only contains third placeholder
                    String[] parts3 = content.split(Pattern.quote(placeholder3), 2);
                    parts.add(Component.text(parts3[0]).style(textComponent.style()));
                    parts.add(replacement3);
                    if (parts3.length > 1) {
                        parts.add(Component.text(parts3[1]).style(textComponent.style()));
                    }
                }
            }
        } else {
            // No placeholders found, return original
            return original;
        }

        // Process children components too
        for (Component child : textComponent.children()) {
            parts.add(replaceComponentPlaceholders(child, placeholder1, replacement1,
                    placeholder2, replacement2, placeholder3, replacement3));
        }

        // Build final component
        return Component.empty().children(parts).style(textComponent.style());
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
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
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
                player.sendMessage(lang.get("tp.no-request"));
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
            player.sendMessage(lang.get("tp.no-request"));
            return true;
        }

        // Check if request is expired
        Long timestamp = teleportRequestTimestamps.get(requesterUUID);
        if (timestamp == null || System.currentTimeMillis() - timestamp > getRequestExpirationTime()) {
            player.sendMessage(lang.get("tp.expired"));
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Determine who teleports to whom
        UUID destinationUUID = teleportRequests.get(requesterUUID);
        Player teleportingPlayer = plugin.getServer().getPlayer(requesterUUID);
        Player destinationPlayer = plugin.getServer().getPlayer(destinationUUID);

        if (teleportingPlayer == null || destinationPlayer == null) {
            // Determine which player is null and use their name or UUID
            if (teleportingPlayer == null) {
                // The requester is offline
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}",
                        plugin.getServer().getOfflinePlayer(requesterUUID).getName());
            } else {
                // The destination player is offline
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}",
                        plugin.getServer().getOfflinePlayer(destinationUUID).getName());
            }
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }


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
            saveLastLocation(teleportingPlayer.getUniqueId(), teleportingPlayer.getLocation());

            // Teleport the player
            teleportingPlayer.teleport(destinationPlayer.getLocation());
            teleportingPlayer.sendMessage(lang.get("tp.success")
                    .replace("{name}", "to " + destinationPlayer.getName())
                    .replace("{target}", ""));
            destinationPlayer.sendMessage(lang.get("tp.accept").replace("{name}", teleportingPlayer.getName()));
            
            // Set cooldown if player doesn't have cooldown bypass
            if (!teleportingPlayer.hasPermission("allium.tpa.nocooldown")) {
                setCooldown(teleportingPlayer.getUniqueId());
            }
        } else {
            int delay = config.getInt("teleport.delay"); //Get delay from config, default to 3

            Location originalLocation = teleportingPlayer.getLocation(); // Store the player's original location
            teleportingPlayer.sendMessage(lang.get("tp.delay").replace("{delay}", String.valueOf(delay)));

            // Create a PlayerMoveListener
            Listener moveListener = getMoveListener(requesterUUID, teleportingPlayer, originalLocation);

            // Register the move listener
            plugin.getServer().getPluginManager().registerEvents(moveListener, plugin);

            // Schedule the teleportation after the delay
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Check if player has moved.
                if (teleportingPlayer.getLocation().getBlock().getLocation().equals(originalLocation.getBlock().getLocation())) {
                    // Store last location before teleporting
                    saveLastLocation(teleportingPlayer.getUniqueId(), teleportingPlayer.getLocation());
                    teleportingPlayer.teleport(destinationPlayer.getLocation());
                    teleportingPlayer.sendMessage(lang.get("tp.success")
                            .replace("{name}", "to " + destinationPlayer.getName())
                            .replace("{target}", ""));
                    destinationPlayer.sendMessage(lang.get("tp.accept").replace("{name}", teleportingPlayer.getName()));
                    
                    // Set cooldown if player doesn't have cooldown bypass
                    if (!teleportingPlayer.hasPermission("allium.tpa.nocooldown")) {
                        setCooldown(teleportingPlayer.getUniqueId());
                    }
                } else {
                    Text.sendErrorMessage(player, "tp.moved", lang);
                }
                //Unregister move listener
                PlayerMoveEvent.getHandlerList().unregister(moveListener);

            }, delay * 20L); //delay in ticks
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
    
    private @NotNull Listener getMoveListener(UUID requesterUUID, Player teleportingPlayer, Location originalLocation) {
        UUID finalRequesterUUID = requesterUUID;
        Listener moveListener = new Listener() {
            @EventHandler
            public void onPlayerMove(PlayerMoveEvent event) {
                if (event.getPlayer().getUniqueId().equals(teleportingPlayer.getUniqueId())) {
                    if (!event.getTo().getBlock().getLocation().equals(originalLocation.getBlock().getLocation())) {
                        teleportingPlayer.sendMessage(lang.get("tp.cancelled"));
                        teleportRequests.remove(finalRequesterUUID);
                        teleportRequestTimestamps.remove(finalRequesterUUID);
                        Bukkit.getScheduler().cancelTasks(plugin);
                        org.bukkit.plugin.PluginManager pluginManager = Bukkit.getPluginManager();
                        pluginManager.disablePlugin(plugin);
                        pluginManager.enablePlugin(plugin);
                        event.getHandlers().unregister(this); // Unregister the listener
                    }
                }
            }
        };
        return moveListener;
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
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
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
                player.sendMessage(lang.get("tp.no-request"));
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
            player.sendMessage(lang.get("tp.no-request"));
            return true;
        }

        // Get the requester player
        Player requester = plugin.getServer().getPlayer(requesterUUID);
        String requesterName = requester != null ? requester.getName() : "Player";

        // Inform both players
        player.sendMessage(lang.get("tp.deny").replace("{name}", requesterName));

        if (requester != null) {
            requester.sendMessage(lang.get("tp.failure").replace("{name}", player.getName()));
        }

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    /**
     * Handles the /tphere command by teleporting a player to you
     *
     * @param sender The sender of the command
     * @param args Command arguments: [player]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportHere(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("allium.tphere")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tphere");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "tphere")
                    .replace("{args}", "[player]"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "teleport to");
            return true;
        }

        // Store last location before teleporting
        saveLastLocation(target.getUniqueId(), target.getLocation());

        // Teleport target to player
        target.teleport(player.getLocation());
        target.sendMessage(lang.get("tp.success")
        .replace(" {name}", "")
        .replace("{target}", "")
        );
        player.sendMessage(lang.get("tp.success")
        .replace("{name}", target.getName())
        .replace("{target}", "")
        );

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
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "tppos")
                    .replace("{args}", "<x> <y> <z> [yaw] [pitch] [world]"));
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
                    sender.sendMessage(lang.get("world-not-found").replace("{world}", args[worldArgIndex]));
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
            
            // Store last location before teleporting
            saveLastLocation(player.getUniqueId(), player.getLocation());
            
            // Teleport player
            player.teleport(location);
            
            // Build success message with position and rotation info
            String message = lang.get("tp.success")
                    .replace(" {name}", "")
                    .replace("{target}", "to ") + lang.get("tp.position")
                    .replace("{x}", String.format("%.2f", x))
                    .replace("{y}", String.format("%.2f", y))
                    .replace("{z}", String.format("%.2f", z));
            
            // Add rotation info if yaw/pitch were specified
            if (args.length > 3 && isNumeric(args[3])) {
                message += String.format(" (yaw: %.1f", yaw);
                if (args.length > 4 && isNumeric(args[4])) {
                    message += String.format(", pitch: %.1f", pitch);
                }
                message += ")";
            }
            
            player.sendMessage(message);
            
        } catch (NumberFormatException e) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "tppos")
                    .replace("{args}", "<x> <y> <z> [yaw] [pitch] [world]"));
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
                sender.sendMessage(lang.get("tp.toggle")
                        .replace("{state}", enabledStyle + "enabled" + lang.getFirstColorCode("tp.toggle"))
                        .replace("{name}", isSelf ? "" : namePrefix + targetPlayer.getName()));
            } else {
                teleportToggled.add(targetUUID);

                // Prefix for other player
                String namePrefix = isSelf ? "" : "for ";

                // Notify sender
                String disabledStyle = lang.get("styles.state.false");
                sender.sendMessage(lang.get("tp.toggle")
                        .replace("{state}", disabledStyle + "disabled" + lang.getFirstColorCode("tp.toggle"))
                        .replace("{name}", isSelf ? "" : namePrefix + targetPlayer.getName()));
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
            sender.sendMessage(lang.get("tp.toggle")
                    .replace("{state}", enabledStyle + "enabled" + lang.getFirstColorCode("tp.toggle"))
                    .replace("{name}", ""));
        } else {
            teleportToggled.add(playerUUID);
            String disabledStyle = lang.get("styles.state.false");
            sender.sendMessage(lang.get("tp.toggle")
                    .replace("{state}", disabledStyle + "disabled" + lang.getFirstColorCode("tp.toggle"))
                    .replace("{name}", ""));
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

        // Store last location before teleporting
        saveLastLocation(player.getUniqueId(), player.getLocation());

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
        player.teleport(destination);
        player.sendMessage(lang.get("tp.success")
        .replace("{name}", "to top.")
        .replace("{target}", ""));

        // Teleport selected pets if any
        List<org.bukkit.entity.Entity> playerPets = selectedPets.get(player.getUniqueId());
        if (playerPets != null && !playerPets.isEmpty()) {
            for (org.bukkit.entity.Entity pet : playerPets) {
                pet.teleport(destination);
                removePetGlow(pet);
            }
            playerPets.clear();
            selectedPets.remove(player.getUniqueId());
        }

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

        // Store last location before teleporting
        saveLastLocation(player.getUniqueId(), player.getLocation());

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
        player.teleport(destination);
        player.sendMessage(lang.get("tp.success")
        .replace("{name}", "to bottom.")
        .replace("{target}", "")
        );

        // Teleport selected pets if any
        List<org.bukkit.entity.Entity> playerPets = selectedPets.get(player.getUniqueId());
        if (playerPets != null && !playerPets.isEmpty()) {
            for (org.bukkit.entity.Entity pet : playerPets) {
                pet.teleport(destination);
                removePetGlow(pet);
            }
            playerPets.clear();
            selectedPets.remove(player.getUniqueId());
        }

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
            player.sendMessage(lang.get("command-usage")
                .replace("{cmd}", "otp")
                .replace("{args}", "<player>"));
            return true;
        }

        String targetPlayerName = args[0];

        // Get the target player's UUID
        UUID targetUUID = null;
        try {
            // Try to get UUID from online player first
            Player onlinePlayer = plugin.getServer().getPlayer(targetPlayerName);
            if (onlinePlayer != null) {
                handleTeleport(player, args, "tp");
                return true;
            }

            // Get UUID from offline player
            OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(targetPlayerName);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                targetUUID = offlinePlayer.getUniqueId();
            }
        } catch (Exception e) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }

        if (targetUUID == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }

        // Get the player's last logout location from database
        Location targetLocation = database.getPlayerLocation(targetUUID, Database.LocationType.LOGOUT);
        
        if (targetLocation == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }

        // Save current location before teleporting
        saveLastLocation(player.getUniqueId(), player.getLocation());

        // Teleport the player
        try {
            player.teleport(targetLocation);
            player.sendMessage(lang.get("tp.success")
                .replace("{name}", "to")
                .replace("{target}", lang.get("tp.position")
                .replace("{x}", String.valueOf((int) targetLocation.getX()))
                .replace("{y}", String.valueOf((int) targetLocation.getY()))
                .replace("{z}", String.valueOf((int) targetLocation.getZ()))));
            
            // Log the teleport for debugging if enabled
            if (config.getBoolean("debug-mode")) {
                plugin.getLogger().info("Player " + player.getName() + " teleported to offline location of " + targetPlayerName + 
                    " at " + targetLocation.getWorld().getName() + " " + (int) targetLocation.getX() + ", " + 
                    (int) targetLocation.getY() + ", " + (int) targetLocation.getZ());
            }
        } catch (Exception e) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            if (config.getBoolean("debug-mode")) {
                plugin.getLogger().warning("Failed to teleport " + player.getName() + " to offline location of " + targetPlayerName + ": " + e.getMessage());
            }
        }

        return true;
    }

    /**
     * Clear expired teleport requests
     */
    private void clearExpiredRequests() {
        long currentTime = System.currentTimeMillis();
        Set<UUID> expiredRequests = new HashSet<>();

        for (Map.Entry<UUID, Long> entry : teleportRequestTimestamps.entrySet()) {
            if (currentTime > entry.getValue()) {
                expiredRequests.add(entry.getKey());
            }
        }


        for (UUID uuid : expiredRequests) {
            teleportRequests.remove(uuid);
            teleportRequestTimestamps.remove(uuid);
        }
    }

    /**
     * Gets the last location a player was at before teleporting
     *
     * @param playerUUID The UUID of the player
     * @return The last location, or null if not available
     */
    public Location getLastLocation(UUID playerUUID) {
        // First try to get from database
        Location dbLocation = database.getPlayerLocation(playerUUID, LocationType.TELEPORT);
        if (dbLocation != null) {
            return dbLocation;
        }
        
        // Fall back to in-memory cache for backward compatibility
        return lastLocation.get(playerUUID);
    }
    
    /**
     * Saves a player's last location before teleporting
     *
     * @param playerUUID The UUID of the player
     * @param location The location to save
     */
    private void saveLastLocation(UUID playerUUID, Location location) {
        // Save to database with current timestamp
        database.savePlayerLocation(playerUUID, LocationType.TELEPORT, location, System.currentTimeMillis());
        
        // Also update in-memory cache for backward compatibility
        lastLocation.put(playerUUID, location);
    }

    /**
     * Checks if a player has teleport requests toggled off
     *
     * @param playerUUID The UUID of the player
     * @return true if teleport requests are toggled off
     */
    public boolean isTeleportToggled(UUID playerUUID) {
        return teleportToggled.contains(playerUUID);
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
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        String commandName = command.getName().toLowerCase();

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
    
    /**
     * Handle player teleport events to teleport selected pets and entities
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Track last location for /back command
        lastLocation.put(playerUUID, event.getFrom());
        
        // Handle pet teleportation
        List<Entity> playerPets = selectedPets.get(playerUUID);
        if (playerPets != null && !playerPets.isEmpty()) {
            // Schedule teleport after player has arrived
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                teleportPets(playerUUID, player, playerPets);
            }, 5L); // 5 tick delay (1/4 second)
        }
        
        // Handle entity teleportation (admin mode)
        Map<UUID, Entity> playerEntities = selectedEntities.get(playerUUID);
        if (playerEntities != null && !playerEntities.isEmpty() && player.hasPermission("allium.tpmob")) {
            // Schedule teleport after player has arrived
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Convert map values to list for teleportation
                List<Entity> entitiesToTeleport = new ArrayList<>(playerEntities.values());
                plugin.getLogger().info("Teleporting " + entitiesToTeleport.size() + " entities for player " + player.getName());
                teleportEntities(playerUUID, player, entitiesToTeleport);
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
}
