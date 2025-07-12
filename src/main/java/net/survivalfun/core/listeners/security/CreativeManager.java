package net.survivalfun.core.listeners.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.survivalfun.core.managers.core.Text;

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

    private boolean canUse(Player player) {
        return player.hasPermission("core.gamemode.creative.use");
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

    // --- START OF NEW INTERACTION LISTENERS ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractBed(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getClickedBlock().getType().name().endsWith("_BED") && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "sleep in a bed");
        createFizzleEffect(player.getLocation());
    }

    // prevent adding items to decorated pots
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractDecoratedPot(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getClickedBlock().getType() == Material.DECORATED_POT && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with decorated pots");
        createFizzleEffect(player.getLocation());
    }

    // Potion drinking
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDrinkPotion(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getItem().getType() == Material.POTION && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "drink that");
        createFizzleEffect(player.getLocation());
    }

    // Arrow shooting (bow, crossbow)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "shoot that");
        createFizzleEffect(player.getEyeLocation()); // Fizzle from player's view
    }

    // Firework use
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerUseFirework(PlayerInteractEvent event) { // Re-uses PlayerInteractEvent
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Check for right-click action with a firework
        if (event.getAction().isRightClick() && event.hasItem() && event.getItem().getType() == Material.FIREWORK_ROCKET) {
            if (canInteract(player)) return;

            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fireworks");
            createFizzleEffect(player.getLocation());
        }
    }

    // Prevent breaking hanging entities (item frames, paintings)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        if (!canInteract(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "break that");
            createFizzleEffect(player.getLocation());
        }
    }

    // Prevent rotating items in item frames in creative without 'canInteract' permission
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerRotateItemFrame(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        if (event.getRightClicked() instanceof ItemFrame) { // Check if it's an ItemFrame
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "rotate items in frames"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    // Prevent placing paintings in creative without 'canInteract' permission
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPaintingPlaceCreative(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        if (event.getEntity() instanceof Painting) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "place paintings"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    // Prevent interacting with armor stands in creative without 'canInteract' permission
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandInteractCreative(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        if (event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "interact with armor stands"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    // Prevent taking flowers from flower pots in creative without 'canInteract' permission
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlowerPotInteractCreative(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        // Covers both placing into and taking from flower pots
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.FLOWER_POT) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "interact with flower pots"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    public void onPlayerCastFishingRod(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Check if the player is attempting to cast the rod (bobber thrown)
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (canInteract(player)) return;

            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fishing rods");
            createFizzleEffect(player.getLocation()); // Or event.getHook().getLocation() if preferred
        }
    }


    // Splash potions and lingering potions
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw potions");
        createFizzleEffect(player.getLocation());
    }

    // Trident/Ender pearl throw
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;

        if (event.getEntityType() == EntityType.TRIDENT) {
            if (canInteract(player)) return;
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw tridents");
            createFizzleEffect(player.getLocation());
        }
        if (event.getEntityType() == EntityType.ENDER_PEARL) {
            if (canInteract(player)) return;
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw ender pearls");
            createFizzleEffect(player.getLocation());
        }
    }

    // Prevent managing inventory in creative without 'canUse' permission, with exceptions for destroying items
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativeInventoryManagement(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        // --- Item Destruction Logic ---
        // This must happen BEFORE the canUse permission check.
        Inventory clickedInventory = event.getClickedInventory();
        ItemStack itemOnCursor = player.getItemOnCursor(); // Use player's cursor item

        // Case 1: Clicking the "destroy item" slot (slot -999) in the creative inventory GUI with an item on cursor.
        // The destroy slot is part of the CREATIVE inventory type.
        if (event.getSlot() == -999 && clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().info(
                    "Allowed item destruction via X slot for " + player.getName() + 
                    ", item: " + itemOnCursor.getType()
                );
            }
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                // Bukkit.getLogger().info("[CreativeManager] Allowing item destruction via X slot (slot -999).");
                return; // Allow this action, do not proceed to permission checks
            }
        }

        // Case 2: Clicking an empty slot within the *main creative item panel* (not player inventory part)
        // with an item on cursor. This is how players "drop" items into the void in the creative GUI.
        // The clicked slot must be part of the CREATIVE inventory type and be empty.
        if (clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE &&
            (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR)) {
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                // Bukkit.getLogger().info("[CreativeManager] Allowing item destruction via empty creative panel slot.");
                return; // Allow this action, do not proceed to permission checks
            }
        }
        // --- End of Item Destruction Logic ---

        // If the player does not have the 'canUse' permission, block other inventory interactions.
        if (!canUse(player)) {
            event.setCancelled(true); // Cancel the event first

            // Basic anti-duplication: clear cursor and update inventory on the next tick.
            // This attempts to revert client-side assumptions about the click.
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                player.setItemOnCursor(null);
                player.updateInventory();
            });

            sendErrorMessageWithCooldown(
                    player,
                    "creative-manager.restrict",
                    plugin.getLangManager(),
                    "{action}", "manage your inventory"
            );
            createFizzleEffect(player.getLocation());
            return; // Important to return after handling a restricted action
        }
    }

    // Equip armor in creative via inventory click
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerEquipArmorCreative(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;

        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.PLAYER) {
                if (canInteract(player)) return;

                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "equip armor");
                createFizzleEffect(player.getLocation());
            }
        }
    }

    // Prevent right-clicking any mob in creative without permission
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntityCreative(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;

        // Check if the right-clicked entity is a LivingEntity (covers most mobs)
        if (event.getRightClicked() instanceof org.bukkit.entity.LivingEntity) {
            event.setCancelled(true);
            String entityName = event.getRightClicked().getType().name().toLowerCase().replace("_", " ");
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with that " + entityName);
            createFizzleEffect(event.getRightClicked().getLocation());
        }
    }
    

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if(event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (player.getGameMode() != GameMode.CREATIVE) return;
            if (canInteract(player)) return;
            String entityName = event.getEntity().getType().name().toLowerCase().replace("_", " ");
            event.setCancelled(true);
            // Send error message to player
            sendErrorMessageWithCooldown(
                    player,
                    "creative-manager.restrict",
                    plugin.getLangManager(),
                    "{action}", "hurt that " + entityName
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

    // --- END OF NEW INTERACTION LISTENERS ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        cachePlayerPermissions(player);
        lastKnownGamemodes.put(playerId, player.getGameMode());

        // First check for any pending gamemode resets
        checkAndApplyGameModeReset(player);

        GameMode gameMode = player.getGameMode();

        // Skip inventory management for players with permission in survival/adventure
        if (player.hasPermission("core.gamemode.creative.inventory") && gameMode != GameMode.CREATIVE) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory management for " + player.getName() + " in " + gameMode);
            }
            return;
        }

        // Load inventory only for creative mode or if explicitly needed
        if (gameMode == GameMode.CREATIVE) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Loading creative inventory for " + player.getName());
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                loadInventory(player, GameMode.CREATIVE);
            }, 20L);
        } else {
            // For survival mode, only load inventory if it exists in the database
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Checking survival inventory for " + player.getName());
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                PlayerInventories inventories = database.loadPlayerInventories(playerId);
                if (inventories != null && inventories.getSurvivalInventory() != null && inventories.getSurvivalInventory().length > 0) {
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Loading survival inventory for " + player.getName());
                    }
                    loadInventory(player, GameMode.SURVIVAL);
                } else {
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] No survival inventory found for " + player.getName() + ", keeping current inventory");
                    }
                }
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        // Clear any cached permissions
        cachedCreativePermissions.remove(playerId);
        cachedSpectatorPermissions.remove(playerId);
        lastKnownGamemodes.remove(playerId);

        // Skip inventory management for survival players with permission
        if (player.hasPermission("core.gamemode.creative.inventory") && player.getGameMode() != GameMode.CREATIVE) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory save for " + player.getName());
            }
            return;
        }

        // Save inventory for players
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Saving inventory for " + player.getName() + " in " + player.getGameMode());
        }
        saveCurrentInventory(player, player.getGameMode());
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

    // Add this method to detect the source plugin (as you already have)
    private Plugin detectSourcePlugin() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Create a list of plugin names to check against class names in the stack trace
        List<String> pluginPackagePrefixes = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            // Skip our own plugin
            if (plugin.equals(this.plugin)) continue;

            // Add the plugin's package prefix to our list
            String mainClass = plugin.getPluginMeta().getMainClass();
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
                        String mainClass = plugin.getPluginMeta().getMainClass();
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

                // Create visual feedback
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
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title()).toLowerCase();

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
     * Saves inventories of creative players without core.gamemode.creative.inventory permission
     */
    public void saveAllInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE && 
                !player.hasPermission("core.gamemode.creative.inventory")) {
                
                // Save inventory to database for creative mode
                saveCurrentInventory(player, GameMode.CREATIVE);
            }
        }
    }

    /**
     * Save a player's current inventory based on their game mode
     */
    private void saveCurrentInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        
        try {
            // Get existing inventories from database
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
            
            // Initialize with empty arrays instead of null
            ItemStack[] survivalInventory = new ItemStack[0];
            ItemStack[] survivalArmor = new ItemStack[0];
            ItemStack survivalOffhand = null;
            ItemStack[] creativeInventory = new ItemStack[0];
            ItemStack[] creativeArmor = new ItemStack[0];
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

            // Update the inventory for the current game mode
            PlayerInventory inv = player.getInventory();
            switch (gameMode) {
                case SURVIVAL:
                case ADVENTURE:
                    survivalInventory = inv.getStorageContents();
                    survivalArmor = inv.getArmorContents();
                    survivalOffhand = inv.getItemInOffHand();
                    break;
                case CREATIVE:
                    creativeInventory = inv.getStorageContents();
                    creativeArmor = inv.getArmorContents();
                    creativeOffhand = inv.getItemInOffHand();
                    break;
                case SPECTATOR:
                    // Don't save spectator inventories
                    break;
            }

            // Save updated inventories
            PlayerInventories updated = new PlayerInventories(
                survivalInventory,
                survivalArmor,
                survivalOffhand,
                creativeInventory,
                creativeArmor,
                creativeOffhand
            );
            
            database.savePlayerInventories(playerUUID, updated);
            plugin.getLogger().info("Saved " + gameMode.name() + " inventory for " + playerName);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save inventory for " + playerName, e);
        }
    }

    private void loadInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
    
        try {
            // Get inventories from database
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
    
            if (inventories == null) {
                // No saved inventories, keep current inventory
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("[InventoryDebug] No saved inventory found for " + player.getName() + " in " + gameMode);
                }
                return;
            }
    
            // Load the appropriate inventory based on game mode
            ItemStack[] inventory;
            ItemStack[] armor;
            ItemStack offhand;
    
            if (gameMode == GameMode.CREATIVE) {
                inventory = inventories.getCreativeInventory();
                armor = inventories.getCreativeArmor();
                offhand = inventories.getCreativeOffhand();
            } else if (gameMode == GameMode.SURVIVAL) {
                inventory = inventories.getSurvivalInventory();
                armor = inventories.getSurvivalArmor();
                offhand = inventories.getSurvivalOffhand();
            } else {
                // Don't load spectator inventories
                return;
            }
    
            // Only clear and load if we have valid inventory data
            if (inventory != null && inventory.length > 0) {
                clearInventory(player); // Clear inventory only if we have data to load
                player.getInventory().setContents(inventory);
            }
            if (armor != null && armor.length > 0) {
                player.getInventory().setArmorContents(armor);
            }
            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
    
            // Update the player's inventory view
            player.updateInventory();
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("[InventoryDebug] Loaded " + gameMode + " inventory for " + player.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load inventory for player: " + player.getName(), e);
            // Don't clear inventory if loading failed
        }
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
    /**
     * Handle inventory separation when a player changes game mode
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        GameMode oldGameMode = player.getGameMode();

        cachePlayerPermissions(player);

        // Only skip inventory management for survival players with permission
        if (player.hasPermission("core.gamemode.creative.inventory") && player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        // Handle switching to creative mode from survival/adventure/spectator
        if (newGameMode == GameMode.CREATIVE && oldGameMode != GameMode.CREATIVE) {
            // Save current inventory (survival/adventure/spectator)
            saveCurrentInventory(player, oldGameMode);

            // Clear inventory and load creative inventory on the next tick
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.getGameMode() != GameMode.CREATIVE) return;
                
                clearInventory(player);
                loadInventory(player, GameMode.CREATIVE);
            }, 1L);
        }
        // Handle switching from creative to non-creative
        else if (oldGameMode == GameMode.CREATIVE && newGameMode != GameMode.CREATIVE) {
            if (!player.hasPermission("core.gamemode.creative.inventory")) {
                clearInventory(player);
                loadInventory(player, newGameMode);
            }
        }
        // Handle switching between non-creative modes (e.g., survival to adventure)
        else if (oldGameMode != GameMode.CREATIVE && newGameMode != GameMode.CREATIVE) {
            // Save current inventory with old game mode
            saveCurrentInventory(player, oldGameMode);
            
            // No need to clear inventory, just load the appropriate inventory
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!player.hasPermission("core.gamemode.creative.inventory")) {
                    loadInventory(player, newGameMode);
                }
            }, 1L);
        }
    }

    // Map to track players with temporarily disabled inventory management
    private final Set<UUID> bypassInventoryManagement = ConcurrentHashMap.newKeySet();
    
    /**
     * Temporarily bypass inventory management for a player
     * @param player The player to bypass inventory management for
     * @param bypass Whether to bypass inventory management
     */
    public void setBypassInventoryManagement(Player player, boolean bypass) {
        if (bypass) {
            bypassInventoryManagement.add(player.getUniqueId());
        } else {
            bypassInventoryManagement.remove(player.getUniqueId());
        }
    }
    
    public void cleanup() {
        if (gamemodeCheckTask != null) {
            gamemodeCheckTask.cancel();
            gamemodeCheckTask = null;
        }
    }

    private void checkAndApplyGameModeReset(Player player) {
        UUID playerId = player.getUniqueId();
        GameMode lastKnownGamemode = getLastKnownGamemode(playerId);
        GameMode currentGamemode = player.getGameMode();

        if (lastKnownGamemode != currentGamemode) {
            // Check if player has permission to keep their gamemode
            if ((lastKnownGamemode == GameMode.CREATIVE && player.hasPermission("core.gamemode.creative")) ||
                    (lastKnownGamemode == GameMode.SPECTATOR && player.hasPermission("core.gamemode.spectator")) ||
                    player.hasPermission("core.gamemode")) {

                // Log that we're going to revert this change
                if(plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Detected gamemode change for " + player.getName() +
                            " from " + currentGamemode + " to " + lastKnownGamemode +
                            ". Will revert this change.");

                }

                // Schedule a task to revert the gamemode change
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(lastKnownGamemode);

                    if(plugin.getConfig().getBoolean("debug-mode")) {

                        plugin.getLogger().info("Reverted " + player.getName() +
                                "'s gamemode back to " + lastKnownGamemode +
                                " after it was changed to " + currentGamemode);
                    }
                }, 2L);
            }
        }
    }
}