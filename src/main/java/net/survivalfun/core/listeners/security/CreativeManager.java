package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class CreativeManager implements Listener {

    private final PluginStart plugin;
    private final Database database;
    private final Set<Material> badBlocks;
    private final Set<EntityType> badEntities;
    private final Map<UUID, Long> lastErrorMessageTime;
    private final Map<UUID, GameMode> lastKnownGamemodes = new HashMap<>();
    private BukkitTask gamemodeCheckTask;
    private final Map<UUID, Long> recentRestorations = new HashMap<>();
    private static final long RESTORATION_COOLDOWN = 5000;

    public CreativeManager(@NotNull PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.badBlocks = new HashSet<>();
        this.badEntities = new HashSet<>();
        this.lastErrorMessageTime = new HashMap<>();
        loadBlacklists();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("CreativeModeInventoryManager has been registered");
    }

    private boolean isOnErrorCooldown(Player player){
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        if (lastErrorMessageTime.containsKey(playerUUID)) {
            long lastErrorTime = lastErrorMessageTime.get(playerUUID);
            if (currentTime - lastErrorTime < 10000) {
                return true;
            }
        }
        lastErrorMessageTime.put(playerUUID, currentTime);
        return false;
    }

    private void sendErrorMessageWithCooldown(Player player, String messageKey,
                                              net.survivalfun.core.managers.lang.Lang lang,
                                              Object... replacements) {
        if (!isOnErrorCooldown(player)) {
            Text.sendErrorMessage(player, messageKey, lang, replacements);
        }
    }

    private void loadBlacklists() {
        List<String> blocks = plugin.getConfig().getStringList("creative-manager.blacklist.blocks");
        for (String blockName : blocks) {
            try {
                Material material = Material.valueOf(blockName.toUpperCase());
                badBlocks.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid block name in creative-mode.blacklist.blocks: " + blockName);
            }
        }

        List<String> entities = plugin.getConfig().getStringList("creative-manager.blacklist.entities");
        for (String entityName : entities) {
            try {
                EntityType entityType = EntityType.valueOf(entityName.toUpperCase());
                badEntities.add(entityType);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid entity name in creative-mode.blacklist.entities: " + entityName);
            }
        }
    }

    public void reloadBlacklists(){
        badBlocks.clear();
        badEntities.clear();
        loadBlacklists();
    }

    private final Map<UUID, Boolean> cachedCreativePermissions = new HashMap<>();
    private final Map<UUID, Boolean> cachedSpectatorPermissions = new HashMap<>();

    // Add this method to cache permissions when a player changes gamemode or joins
    private void cachePlayerPermissions(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.hasPermission("core.gamemode")) {
            cachedCreativePermissions.put(uuid, player.hasPermission("core.gamemode.creative"));
            cachedSpectatorPermissions.put(uuid, player.hasPermission("core.gamemode.spectator"));
            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Cached permissions for " + player.getName() +
                        ": creative=" + cachedCreativePermissions.get(uuid) +
                        ", spectator=" + cachedSpectatorPermissions.get(uuid));
            }
        }

    }

    private boolean canPlace(Player player) {
        return player.hasPermission("core.gamemode.creative.place");
    }
    private boolean canBreak(Player player) {
        return player.hasPermission("core.gamemode.creative.break");
    }

    private boolean canBypassBlacklist(Player player) {
        return player.hasPermission("core.gamemode.creative.blacklist");
    }

    private boolean canSpawn(Player player) {
        return player.hasPermission("core.gamemode.creative.spawn");
    }

    private boolean canInteract(Player player) {
        return player.hasPermission("core.gamemode.creative.interact");
    }

    private boolean canDrop(Player player) {
        return player.hasPermission("core.gamemode.creative.drop");
    }

    /**
     * Creates a "fizzle" effect at the given location to indicate a failed action.
     */
    private void createFizzleEffect(Location location) {
        // Create a DustOptions object for red dust particles
        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.RED, 1.0f);

        // Play a redstone dust particle effect at the location
        location.getWorld().spawnParticle(
                Particle.DUST,    // Dust particle
                location,         // At the block's location
                15,               // Number of particles
                0.5,              // Spread in X direction
                0.5,              // Spread in Y direction
                0.5,              // Spread in Z direction
                0,                // Speed (not used for DUST)
                dustOptions       // The required DustOptions parameter
        );

        // Add a small sound for the fizzle effect
        location.getWorld().playSound(
                location,
                Sound.BLOCK_FIRE_EXTINGUISH, // A good "fizzle" sound
                SoundCategory.BLOCKS,
                0.5f,  // Volume (quieter than normal)
                1.2f   // Slightly higher pitch for a "blocked" feel
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if(event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (player.getGameMode() != GameMode.CREATIVE) return;
            if (canInteract(player)) return;
            event.setCancelled(true);
            // Send error message to player
            sendErrorMessageWithCooldown(
                    player,
                    "creative-manager.restrict",
                    plugin.getLangManager(),
                    "{action}", "hurt that " + event.getEntity().getType().name().toLowerCase()
            );
            createFizzleEffect(event.getEntity().getLocation());
        }
    }


    /**
     * Adds spawn source tracking to entities spawned by players.
     * Also prevents spawning if player doesn't have permission in creative mode.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerSpawnEntity(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!event.hasItem()) return;

        ItemStack item = event.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
            // Check if player is in creative mode and doesn't have permission
            if (player.getGameMode() == GameMode.CREATIVE && !canSpawn(player)) {
                // Cancel the event
                event.setCancelled(true);

                // Send error message to player
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "spawn that"
                );

                // Create visual feedback effect
                createFizzleEffect(player.getLocation());

                return;
            }

            // If permission check passed or not in creative mode, add metadata to the spawned entity
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Find recently spawned entities near the player
                player.getNearbyEntities(3, 3, 3).forEach(entity -> {
                    if (entity.getTicksLived() < 5) { // Very recently spawned
                        entity.setMetadata("spawned-by-player",
                                new org.bukkit.metadata.FixedMetadataValue(plugin, player));
                    }
                });
            });
        }
    }

    /**
     * Handle inventory restoration and gamemode resets when a player joins
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        cachePlayerPermissions(player);

        lastKnownGamemodes.put(player.getUniqueId(), player.getGameMode());

        // First check for any pending gamemode resets
        checkAndApplyGameModeReset(player);

        // Then verify the player has permission for their current gamemode
        // This handles cases where server restarts and players retain gamemodes they shouldn't have
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            verifyGameModePermission(player);
        }, 10L);

        // Skip inventory management if player has bypass permission
        if (player.hasPermission("core.gamemode.creative.inventory")) {
            return;
        }

        // Load the appropriate inventory based on their current game mode
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            GameMode gameMode = player.getGameMode();
            loadInventory(player, gameMode == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.SURVIVAL);
        }, 20L);
    }

    // Then modify your onPlayerQuit method to use the cached permissions
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final GameMode currentGameMode = player.getGameMode();
        final UUID playerUUID = player.getUniqueId();
        final String playerName = player.getName();

        // Capture inventory contents immediately to avoid issues with player object becoming invalid
        final ItemStack[] inventoryContents = player.getInventory().getContents().clone();
        final ItemStack[] armorContents = player.getInventory().getArmorContents().clone();
        final ItemStack offhandItem = player.getInventory().getItemInOffHand().clone();

        // Schedule the inventory saving for the next tick to ensure all inventory changes are processed
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Use cached permissions instead of checking directly
            boolean hasGeneralPermission = player.hasPermission("core.gamemode");
            boolean hasCreativePermission = currentGameMode == GameMode.CREATIVE &&
                    cachedCreativePermissions.getOrDefault(playerUUID, false);
            boolean hasSpectatorPermission = currentGameMode == GameMode.SPECTATOR &&
                    cachedSpectatorPermissions.getOrDefault(playerUUID, false);
            boolean shouldKeepGamemode = hasGeneralPermission || hasCreativePermission || hasSpectatorPermission;

            plugin.getLogger().info("Should keep gamemode: " + shouldKeepGamemode +
                    " (General: " + hasGeneralPermission +
                    ", Creative: " + hasCreativePermission +
                    ", Spectator: " + hasSpectatorPermission + ")");

            if (shouldKeepGamemode) {
                // Player has permission to keep their gamemode, so we'll save it
                saveInventoryDirectly(playerUUID, playerName, currentGameMode,
                        inventoryContents, armorContents, offhandItem);

                plugin.getLogger().info("Player " + playerName + " has permission to keep their gamemode.");
            } else if (currentGameMode == GameMode.CREATIVE || currentGameMode == GameMode.SPECTATOR) {
                // Save their current inventory before changing gamemode
                saveInventoryDirectly(playerUUID, playerName, currentGameMode,
                        inventoryContents, armorContents, offhandItem);

                // Store the player's UUID and intended gamemode in the database for reset on next login
                storeGameModeReset(playerUUID);

                // Log the gamemode reset for audit purposes
                plugin.getLogger().info("Player " + playerName + " logged out with " +
                        currentGameMode.name() + " gamemode without permission to keep it. " +
                        "Their gamemode will be reset to SURVIVAL on next login.");
            }

            // Clean up cached permissions
            cachedCreativePermissions.remove(playerUUID);
            cachedSpectatorPermissions.remove(playerUUID);
            lastKnownGamemodes.remove(playerUUID);
            recentRestorations.remove(playerUUID);
        }, 1L);
    }

    // Add this to track gamemode changes initiated by your plugin
    public void setPlayerGamemode(Player player, GameMode gameMode) {
        player.setGameMode(gameMode);
        lastKnownGamemodes.put(player.getUniqueId(), gameMode);
    }

    // Add this to your onGameModeChange event handler
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChangeTrack(PlayerGameModeChangeEvent event) {
        // Only track changes that aren't from Multiverse
        Plugin sourcePlugin = detectSourcePlugin();
        if (sourcePlugin == null || !sourcePlugin.getName().equals("Multiverse-Core")) {
            lastKnownGamemodes.put(event.getPlayer().getUniqueId(), event.getNewGameMode());
        }
    }

    // Modify the onGamemodeSwitchRevert method to track restorations
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGamemodeSwitchRevert(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGamemode = event.getNewGameMode();
        GameMode oldGamemode = player.getGameMode();
        UUID playerUUID = player.getUniqueId();

        // Check if this player had a recent restoration - if so, skip
        long currentTime = System.currentTimeMillis();
        if (recentRestorations.containsKey(playerUUID)) {
            long lastRestoration = recentRestorations.get(playerUUID);
            if (currentTime - lastRestoration < RESTORATION_COOLDOWN) {
                // Skip this event, we've recently handled a restoration for this player
                return;
            }
        }

        // Only check for Multiverse-initiated changes
        Plugin sourcePlugin = detectSourcePlugin();
        if (sourcePlugin != null && sourcePlugin.getName().equals("Multiverse-Core")) {
            // Check if player has permission to keep their gamemode
            if ((oldGamemode == GameMode.CREATIVE && player.hasPermission("core.gamemode.creative")) ||
                    (oldGamemode == GameMode.SPECTATOR && player.hasPermission("core.gamemode.spectator")) ||
                    player.hasPermission("core.gamemode")) {

                // Log that we're going to revert this change
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Detected Multiverse changing " + player.getName() +
                            "'s gamemode from " + oldGamemode + " to " + newGamemode +
                            ". Will revert this change.");

                }

                // Schedule a task to revert the gamemode change
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(oldGamemode);

                    // Record this restoration
                    recentRestorations.put(playerUUID, System.currentTimeMillis());

                    if(plugin.getConfig().getBoolean("debug-mode")) {

                        plugin.getLogger().info("Reverted " + player.getName() +
                                "'s gamemode back to " + oldGamemode +
                                " after Multiverse changed it to " + newGamemode);
                    }
                }, 2L);
            }
        }
    }

    // Modify the periodic check task to respect the cooldown
    private void startGamemodeCheckTask() {
        // Cancel any existing task
        if (gamemodeCheckTask != null) {
            gamemodeCheckTask.cancel();
        }

        // Start a new task that runs every 5 seconds
        gamemodeCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long currentTime = System.currentTimeMillis();

            // Clean up old restoration records first
            Iterator<Map.Entry<UUID, Long>> iterator = recentRestorations.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Long> entry = iterator.next();
                if (currentTime - entry.getValue() > RESTORATION_COOLDOWN) {
                    iterator.remove();
                }
            }

            // Now check each player
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerUUID = player.getUniqueId();

                // Skip players who had a recent restoration
                if (recentRestorations.containsKey(playerUUID)) {
                    continue; // Skip this player, they had a recent restoration
                }

                // Check if player should be in creative mode
                if (player.hasPermission("core.gamemode.creative") &&
                        player.getGameMode() != GameMode.CREATIVE) {

                    // Get the last known gamemode for this player
                    GameMode lastKnownGamemode = getLastKnownGamemode(playerUUID);

                    if (lastKnownGamemode == GameMode.CREATIVE) {
                        // Record this restoration BEFORE we do it
                        recentRestorations.put(playerUUID, currentTime);

                        player.setGameMode(GameMode.CREATIVE);

                        if(plugin.getConfig().getBoolean("debug-mode")) {

                            plugin.getLogger().info("Restored " + player.getName() +
                                    "'s gamemode to CREATIVE during periodic check");
                        }

                        // Send a message to the player
                        player.sendMessage(ChatColor.GREEN + "Your gamemode has been restored to creative mode.");
                    }
                }

                // Similar check for spectator mode
                if (player.hasPermission("core.gamemode.spectator") &&
                        player.getGameMode() != GameMode.SPECTATOR) {

                    GameMode lastKnownGamemode = getLastKnownGamemode(playerUUID);

                    if (lastKnownGamemode == GameMode.SPECTATOR) {
                        // Record this restoration BEFORE we do it
                        recentRestorations.put(playerUUID, currentTime);

                        player.setGameMode(GameMode.SPECTATOR);
                        if(plugin.getConfig().getBoolean("debug-mode")) {

                            plugin.getLogger().info("Restored " + player.getName() +
                                    "'s gamemode to SPECTATOR during periodic check");
                        }

                        // Send a message to the player
                        player.sendMessage(ChatColor.GREEN + "Your gamemode has been restored to spectator mode.");
                    }
                }
            }
        }, 100L, 100L); // Check every 5 seconds (100 ticks)
    }

    // Add this method to detect the source plugin (as you already have)
    private Plugin detectSourcePlugin() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Create a list of plugin names to check against class names in the stack trace
        List<String> pluginPackagePrefixes = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            // Skip our own plugin
            if (plugin.equals(this.plugin)) continue;

            // Add the plugin's package prefix to our list
            String mainClass = plugin.getDescription().getMain();
            String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));
            pluginPackagePrefixes.add(packagePrefix);
        }

        // Examine the stack trace to find which plugin might have triggered this
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();

            // Check if this class belongs to a plugin
            for (String prefix : pluginPackagePrefixes) {
                if (className.startsWith(prefix)) {
                    // Found a match, get the plugin instance
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        String mainClass = plugin.getDescription().getMain();
                        String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));

                        if (className.startsWith(packagePrefix)) {
                            return plugin;
                        }
                    }
                }
            }

            // Special case for Multiverse (which is likely the culprit)
            if (className.contains("Multiverse") || className.contains("multiverse")) {
                Plugin mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
                if (mvPlugin != null) {
                    return mvPlugin;
                }
            }
        }

        return null;
    }

    // New method to save inventory directly without using Player object
    private void saveInventoryDirectly(UUID playerUUID, String playerName, GameMode gameMode,
                                       ItemStack[] inventory, ItemStack[] armor, ItemStack offhand) {
        // Get existing inventories from database
        Database.PlayerInventories inventories = database.getPlayerInventories(playerUUID);

        // Initialize inventory arrays
        ItemStack[] survivalInventory = null;
        ItemStack[] survivalArmor = null;
        ItemStack survivalOffhand = null;
        ItemStack[] creativeInventory = null;
        ItemStack[] creativeArmor = null;
        ItemStack creativeOffhand = null;

        // If we have existing inventories, load them
        if (inventories != null) {
            survivalInventory = inventories.getSurvivalInventory();
            survivalArmor = inventories.getSurvivalArmor();
            survivalOffhand = inventories.getSurvivalOffhand();
            creativeInventory = inventories.getCreativeInventory();
            creativeArmor = inventories.getCreativeArmor();
            creativeOffhand = inventories.getCreativeOffhand();
        }

        // Update the appropriate inventory based on game mode
        if (gameMode == GameMode.CREATIVE) {
            creativeInventory = inventory;
            creativeArmor = armor;
            creativeOffhand = offhand;
        } else {
            survivalInventory = inventory;
            survivalArmor = armor;
            survivalOffhand = offhand;
        }

        // Save to database
        database.savePlayerInventories(
                playerUUID, playerName,
                survivalInventory, survivalArmor, survivalOffhand,
                creativeInventory, creativeArmor, creativeOffhand
        );
    }

    /**
     * Stores a gamemode reset request in the database for a player
     */
    private void storeGameModeReset(UUID playerUUID) {
        // Using H2's MERGE INTO syntax which is the equivalent of "INSERT OR REPLACE" in SQLite
        database.executeUpdate(
                "MERGE INTO gamemode_resets KEY(player_uuid) VALUES (?, ?, CURRENT_TIMESTAMP)",
                playerUUID.toString(), GameMode.SURVIVAL.name());
    }

    /**
     * Checks and applies any pending gamemode resets when a player joins
     * This method should be called from your onPlayerJoin event handler
     */
    private void checkAndApplyGameModeReset(Player player) {
        UUID playerUUID = player.getUniqueId();

        // Query the database for any pending resets
        // This is a simplified example - adapt to your actual database implementation
        String resetGameMode = database.queryString(
                "SELECT reset_gamemode FROM gamemode_resets WHERE player_uuid = ?",
                playerUUID.toString());

        if (resetGameMode != null) {
            // Apply the reset
            try {
                GameMode resetTo = GameMode.valueOf(resetGameMode);

                // Schedule the gamemode change for after the player fully joins
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(resetTo);
                    player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                            .replace("{mode}", resetTo.toString()));

                    // Clear the reset request from the database
                    database.executeUpdate("DELETE FROM gamemode_resets WHERE player_uuid = ?",
                            playerUUID.toString());
                }, 5L);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid gamemode reset value for player " +
                        player.getName() + ": " + resetGameMode);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Check if player is in creative mode
        if (player.getGameMode() == GameMode.CREATIVE) {
            // Check if player has permission to drop items in creative mode
            if (!canDrop(player)) {
                // Cancel the drop event
                event.setCancelled(true);

                // Send error message to player
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "drop that"
                );

                // Create visual feedback effect
                createFizzleEffect(player.getLocation());
            }
        }
    }

    /**
     * Checks if a player has permission to use containers in creative mode
     */
    private boolean canUseContainers(Player player) {
        return player.hasPermission("core.gamemode.creative.container");
    }

    /**
     * Checks if a player has permission to pick up items in creative mode
     */
    private boolean canPickupItems(Player player) {
        return player.hasPermission("core.gamemode.creative.pickup");
    }

    /**
     * Handles inventory open events to restrict creative mode players
     * from opening containers without permission
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // Only apply to creative mode players
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Skip if player has permission
        if (canUseContainers(player)) return;

        Inventory inventory = event.getInventory();
        String title = event.getView().getTitle().toLowerCase();

        // List of restricted inventory types
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));

        // List of restricted container titles
        Set<String> restrictedTitles = new HashSet<>(Arrays.asList(
                "chest", "large chest", "barrel", "shulker box", "dispenser", "dropper",
                "hopper", "furnace", "blast furnace", "smoker", "brewing stand", "anvil",
                "grindstone", "enchanting table", "loom", "cartography table", "smithing table",
                "stonecutter", "beacon", "crafting table"
        ));

        // Check if this is a restricted inventory type
        if (restrictedTypes.contains(inventory.getType())) {
            // Cancel the event
            event.setCancelled(true);

            // Send error message
            sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                    plugin.getLangManager(), "{action}", "use this container");

            // Create visual feedback
            org.bukkit.block.Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock.getType() != Material.AIR) {
                createFizzleEffect(targetBlock.getLocation());
            } else {
                // Fallback to player's location if no valid target block
                createFizzleEffect(player.getLocation());
            }
            return;
        }

        // Check if the title contains any restricted container names
        for (String restrictedTitle : restrictedTitles) {
            if (title.contains(restrictedTitle)) {
                // Cancel the event
                event.setCancelled(true);

                // Send error message
                sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                        plugin.getLangManager(), "{action}", "use this container");

                // Create visual feedback
                org.bukkit.block.Block targetBlock = player.getTargetBlock(null, 5);
                if (targetBlock.getType() != Material.AIR) {
                    createFizzleEffect(targetBlock.getLocation());
                } else {
                    // Fallback to player's location if no valid target block
                    createFizzleEffect(player.getLocation());
                }

                break;
            }
        }
    }

    /**
     * Handles inventory click events to prevent creative mode players
     * from interacting with restricted containers
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        // Only apply to creative mode players
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Skip if player has permission
        if (canUseContainers(player)) return;

        Inventory inventory = event.getClickedInventory();

        // Skip if the clicked inventory is null or player inventory
        if (inventory == null || inventory.equals(player.getInventory())) return;

        // List of restricted inventory types
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));

        // Check if this is a restricted inventory type
        if (restrictedTypes.contains(inventory.getType())) {
            // Cancel the event
            event.setCancelled(true);

            // Send error message
            sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                    plugin.getLangManager(), "{action}", "use this container");
        }
    }

    /**
     * Handles item pickup events to prevent creative mode players
     * from picking up items without permission
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();

        // Only apply to creative mode players
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Skip if player has permission
        if (canPickupItems(player)) return;

        // Cancel the event
        event.setCancelled(true);

        // Send error message
        sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                plugin.getLangManager(), "{action}", "pick that up");

        // Create visual feedback
        createFizzleEffect(event.getItem().getLocation());
    }


    /**
     * Handles block placement restrictions in creative mode.
     * Only restricted by permissions, not by config booleans.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        Material blockType = event.getBlock().getType();

        // Check general placement permission first
        if (!canPlace(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "place that");
            return;
        }

        // If they have general placement permission but the block is blacklisted,
        // check for blacklist bypass permission
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "place that");
        }
    }

    /**
     * Handles block breaking restrictions in creative mode.
     * Only restricted by permissions, not by config booleans.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        Material blockType = event.getBlock().getType();

        // Check general breaking permission first
        if (!canBreak(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "break that");
            return;
        }

        // If they have general breaking permission but the block is blacklisted,
        // check for blacklist bypass permission
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "break that");
        }
    }

    /**
     * Handles interaction with blocks like containers and redstone components in creative mode.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        Material blockType = event.getClickedBlock().getType();

        // First check if player has general interaction permission
        if (!canInteract(player)) {
            // Cancel interaction with any interactable block
            if (isInteractableBlock(blockType)) {
                event.setCancelled(true);
                createFizzleEffect(event.getClickedBlock().getLocation());
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                        "{action}", "interact with that");
                return;
            }
        }

        // Even if they have interaction permission, check blacklist
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getClickedBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.blacklist", plugin.getLangManager(),
                    "{action}", "interacting with " + blockType.name());
        }
    }

    /**
     * Determines if a block is an interactable storage or redstone component.
     */
    private boolean isInteractableBlock(Material material) {
        String name = material.name();

        // Storage containers
        if (name.contains("CHEST") ||
                name.contains("BARREL") ||
                name.contains("SHULKER") ||
                name.equals("DISPENSER") ||
                name.equals("DROPPER") ||
                name.equals("HOPPER") ||
                name.equals("FURNACE") ||
                name.equals("BLAST_FURNACE") ||
                name.equals("SMOKER") ||
                name.equals("BREWING_STAND") ||
                name.equals("ENDER_CHEST") ||
                name.contains("ANVIL") ||
                name.equals("BEACON") ||
                name.equals("LOOM") ||
                name.equals("STONECUTTER") ||
                name.equals("GRINDSTONE") ||
                name.equals("CARTOGRAPHY_TABLE") ||
                name.equals("ENCHANTING_TABLE") ||
                name.equals("SMITHING_TABLE")) {
            return true;
        }

        // Redstone components
        if (name.contains("BUTTON") ||
                name.contains("LEVER") ||
                name.contains("PRESSURE_PLATE") ||
                name.contains("TRAPDOOR") ||
                name.contains("DOOR") ||
                name.equals("REPEATER") ||
                name.equals("COMPARATOR") ||
                name.equals("DAYLIGHT_DETECTOR") ||
                name.equals("OBSERVER") ||
                name.equals("TRIPWIRE") ||
                name.equals("TARGET") ||
                name.equals("TRAPPED_CHEST") ||
                name.equals("NOTE_BLOCK") ||
                name.equals("JUKEBOX") ||
                name.equals("COMMAND_BLOCK") ||
                name.equals("CHAIN_COMMAND_BLOCK") ||
                name.equals("REPEATING_COMMAND_BLOCK") ||
                name.equals("STRUCTURE_BLOCK")) {
            return true;
        }

        // Additional interactive blocks
        return name.equals("LECTERN") ||
                name.equals("BELL") ||
                name.equals("ITEM_FRAME") ||
                name.equals("ARMOR_STAND") ||
                name.equals("RESPAWN_ANCHOR") ||
                name.equals("LODESTONE") ||
                name.equals("BED") ||
                name.equals("CAKE");
    }





    /**
     * Handle inventory separation when a player changes game mode
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        GameMode oldGameMode = player.getGameMode();

        cachePlayerPermissions(player);

        // Skip if player has bypass permission
        if (player.hasPermission("core.gamemode.creative.inventory")) {
            return;
        }

        // Handle switching to creative mode
        if (newGameMode == GameMode.CREATIVE && oldGameMode != GameMode.CREATIVE) {
            // Save survival inventory
            saveCurrentInventory(player, oldGameMode);

            // Clear inventory and load creative inventory
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                clearInventory(player);
                loadInventory(player, GameMode.CREATIVE);
            }, 1L);
        }

        // Handle switching from creative to any other mode
        else if (oldGameMode == GameMode.CREATIVE && newGameMode != GameMode.CREATIVE) {
            // Save creative inventory
            saveCurrentInventory(player, oldGameMode);

            // Restore survival inventory
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                clearInventory(player);
                loadInventory(player, GameMode.SURVIVAL);
            }, 1L);
        }
    }



    /**
     * Verifies that a player has permission for their current gamemode
     * If not, resets them to survival mode
     */
    private void verifyGameModePermission(Player player) {
        GameMode currentMode = player.getGameMode();

        // Skip check for survival mode (default) and for players with bypass permission
        if (currentMode == GameMode.SURVIVAL ||
                player.hasPermission("core.gamemode.creative.bypass.*")) {
            return;
        }

        // Check if player has permission for their current gamemode
        String permissionSuffix = currentMode.name().toLowerCase();
        if (!player.hasPermission("core.gamemode." + permissionSuffix)) {
            // Player doesn't have permission for this gamemode, reset to survival
            player.setGameMode(GameMode.SURVIVAL);

            // Notify the player
            player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                    .replace("{gamemode}", GameMode.SURVIVAL.toString()));

            // Log the reset
            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Reset " + player.getName() + "'s gamemode from " +
                        currentMode.name() + " to SURVIVAL (no permission)");
            }
        }
    }

    /**
     * Save a player's current inventory based on their game mode
     */
    private void saveCurrentInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        // Get existing inventories from database
        Database.PlayerInventories inventories = database.getPlayerInventories(playerUUID);

        // Initialize inventory arrays
        ItemStack[] survivalInventory = null;
        ItemStack[] survivalArmor = null;
        ItemStack survivalOffhand = null;
        ItemStack[] creativeInventory = null;
        ItemStack[] creativeArmor = null;
        ItemStack creativeOffhand = null;

        // If we have existing inventories, load them
        if (inventories != null) {
            survivalInventory = inventories.getSurvivalInventory();
            survivalArmor = inventories.getSurvivalArmor();
            survivalOffhand = inventories.getSurvivalOffhand();
            creativeInventory = inventories.getCreativeInventory();
            creativeArmor = inventories.getCreativeArmor();
            creativeOffhand = inventories.getCreativeOffhand();
        }

        // Update the appropriate inventory based on game mode
        if (gameMode == GameMode.CREATIVE) {
            creativeInventory = player.getInventory().getContents();
            creativeArmor = player.getInventory().getArmorContents();
            creativeOffhand = player.getInventory().getItemInOffHand();
        } else {
            survivalInventory = player.getInventory().getContents();
            survivalArmor = player.getInventory().getArmorContents();
            survivalOffhand = player.getInventory().getItemInOffHand();
        }

        // Save to database
        database.savePlayerInventories(
                playerUUID, playerName,
                survivalInventory, survivalArmor, survivalOffhand,
                creativeInventory, creativeArmor, creativeOffhand
        );
    }

    /**
     * Load a player's inventory based on the specified game mode
     */
    private void loadInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();

        // Get inventories from database
        Database.PlayerInventories inventories = database.getPlayerInventories(playerUUID);

        if (inventories == null) {
            // No saved inventories, just clear their inventory
            clearInventory(player);
            return;
        }

        // Clear current inventory first
        clearInventory(player);

        // Load the appropriate inventory based on game mode
        ItemStack[] inventory;
        ItemStack[] armor;
        ItemStack offhand;
        if (gameMode == GameMode.CREATIVE) {
            inventory = inventories.getCreativeInventory();
            armor = inventories.getCreativeArmor();
            offhand = inventories.getCreativeOffhand();

        } else {
            inventory = inventories.getSurvivalInventory();
            armor = inventories.getSurvivalArmor();
            offhand = inventories.getSurvivalOffhand();

        }
        if (inventory != null) {
            player.getInventory().setContents(inventory);
        }
        if (armor != null) {
            player.getInventory().setArmorContents(armor);
        }
        if (offhand != null) {
            player.getInventory().setItemInOffHand(offhand);
        }

        // Update the player's inventory view
        player.updateInventory();
    }

    /**
     * Clear a player's inventory completely
     */
    private void clearInventory(@NotNull Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.updateInventory();
    }

    public GameMode getLastKnownGamemode(UUID playerUUID) {
        return lastKnownGamemodes.getOrDefault(playerUUID, GameMode.SURVIVAL);
    }
    public void cleanup() {
        if (gamemodeCheckTask != null) {
            gamemodeCheckTask.cancel();
            gamemodeCheckTask = null;
        }
    }
}
