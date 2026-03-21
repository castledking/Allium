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

import net.survivalfun.core.inventory.InventorySnapshot;

import io.papermc.paper.datacomponent.DataComponentType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.survivalfun.core.managers.core.SecurityAlertManager;
import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.util.SchedulerAdapter;

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
        Text.sendDebugLog(INFO, "[InventoryDebug] CreativeManager initialized with inventory change listener");
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
                Text.sendDebugLog(WARN, "Invalid block name in creative-mode.blacklist.blocks: " + blockName);
            }
        }

        List<String> entities = plugin.getConfig().getStringList("creative-manager.blacklist.entities");
        for (String entityName : entities) {
            try {
                EntityType entityType = EntityType.valueOf(entityName.toUpperCase());
                badEntities.add(entityType);
            } catch (IllegalArgumentException e) {
                Text.sendDebugLog(WARN, "Invalid entity name in creative-mode.blacklist.entities: " + entityName);
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

    private void cachePlayerPermissions(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.hasPermission("allium.gamemode")) {
            cachedCreativePermissions.put(uuid, player.hasPermission("allium.gamemode.creative"));
            cachedSpectatorPermissions.put(uuid, player.hasPermission("allium.gamemode.spectator"));
            if (plugin.getConfig().getBoolean("debug-mode")) {
                Text.sendDebugLog(INFO, "Cached permissions for " + player.getName() +
                        ": creative=" + cachedCreativePermissions.get(uuid) +
                        ", spectator=" + cachedSpectatorPermissions.get(uuid));
            }
        }
    }

    private boolean canPlace(Player player) {
        return player.hasPermission("allium.gamemode.creative.place");
    }
    private boolean canBreak(Player player) {
        return player.hasPermission("allium.gamemode.creative.break");
    }
    private boolean canBypassBlacklist(Player player) {
        return player.hasPermission("allium.gamemode.creative.blacklist");
    }
    private boolean canSpawn(Player player) {
        return player.hasPermission("allium.gamemode.creative.spawn");
    }
    private boolean canInteract(Player player) {
        return player.hasPermission("allium.gamemode.creative.interact");
    }
    private boolean canDrop(Player player) {
        return player.hasPermission("allium.gamemode.creative.drop");
    }
    private boolean canUse(Player player) {
        return player.hasPermission("allium.gamemode.creative.use");
    }
    private boolean canUseContainers(Player player) {
        return player.hasPermission("allium.gamemode.creative.container");
    }
    private boolean canPickupItems(Player player) {
        return player.hasPermission("allium.gamemode.creative.pickup");
    }

    private void createFizzleEffect(Location location) {
        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.RED, 1.0f);
        location.getWorld().spawnParticle(
                Particle.DUST,
                location,
                15,
                0.5,
                0.5,
                0.5,
                0,
                dustOptions
        );
        location.getWorld().playSound(
                location,
                Sound.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.BLOCKS,
                0.5f,
                1.2f
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            if (event.getItem().getType() == Material.POTION) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "drink that");
                createFizzleEffect(player.getLocation());
            }
            if (event.getItem().getType().isEdible()) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "eat that");
                createFizzleEffect(player.getLocation());
            }
        }

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Handle canInteract permissions
        if (!canInteract(player)) {
            // Bed interaction
            if (event.getClickedBlock() != null && event.getClickedBlock().getType().name().endsWith("_BED") && event.getAction().isRightClick()) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "sleep in a bed");
                createFizzleEffect(player.getLocation());
                return;
            }
            // Decorated pot interaction
            if (event.getClickedBlock() != null && event.getClickedBlock().getType().name().endsWith("DECORATED_POT") && event.getAction().isRightClick() && event.getItem() != null && event.getItem().getType() != Material.AIR) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with decorated pots");
                createFizzleEffect(player.getLocation());
                return;
            }
            // Potion drinking
            if (event.getItem() != null && event.getItem().getType() == Material.POTION && event.getAction().isRightClick()) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "drink that");
                createFizzleEffect(player.getLocation());
                return;
            }
            // Firework usage
            if (event.getAction().isRightClick() && event.hasItem() && event.getItem().getType() == Material.FIREWORK_ROCKET) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fireworks");
                createFizzleEffect(player.getLocation());
                return;
            }
            // Flower pot interaction
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.FLOWER_POT) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with flower pots");
                createFizzleEffect(player.getLocation());
                return;
            }
            // General block interaction
            if (event.hasBlock() && isInteractableBlock(event.getClickedBlock().getType())) {
                event.setCancelled(true);
                createFizzleEffect(event.getClickedBlock().getLocation());
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with that");
                return;
            }
        }

        // Handle canSpawn permissions
        if (event.hasItem() && event.getItem() != null && event.getItem().getType().name().endsWith("_SPAWN_EGG")) {
            if (!canSpawn(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "spawn that");
                createFizzleEffect(player.getLocation());
                return;
            }
            SchedulerAdapter.runAtEntity(player, () -> {
                player.getNearbyEntities(3, 3, 3).forEach(entity -> {
                    if (entity.getTicksLived() < 5) {
                        entity.setMetadata("spawned-by-player",
                                new org.bukkit.metadata.FixedMetadataValue(plugin, player));
                    }
                });
            });
        }

        // Handle blacklist for block interactions
        if (event.hasBlock() && badBlocks.contains(event.getClickedBlock().getType()) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getClickedBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.blacklist", plugin.getLangManager(),
                    "{action}", "interacting with " + event.getClickedBlock().getType().name());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            // Item frame rotation
            if (event.getRightClicked() instanceof ItemFrame) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "rotate items in frames");
                createFizzleEffect(player.getLocation());
                return;
            }
            // General living entity interaction
            if (event.getRightClicked() instanceof org.bukkit.entity.LivingEntity) {
                String entityName = event.getRightClicked().getType().name().toLowerCase().replace("_", " ");
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with that " + entityName);
                createFizzleEffect(event.getRightClicked().getLocation());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            // Armor stand interaction
            if (event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with armor stands");
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingInteract(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player) && event.getEntity() instanceof Painting) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "place paintings");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "break that");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "shoot that");
            createFizzleEffect(player.getEyeLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            if (event.getEntityType() == EntityType.TRIDENT) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw tridents");
                createFizzleEffect(player.getLocation());
                return;
            }
            if (event.getEntityType() == EntityType.ENDER_PEARL) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw ender pearls");
                createFizzleEffect(player.getLocation());
            }
            if (event.getEntityType() == EntityType.SNOWBALL) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw snowballs");
                createFizzleEffect(player.getLocation());
            }
            if (event.getEntityType() == EntityType.EGG) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw eggs");
                createFizzleEffect(player.getLocation());
            }
            if (event.getEntityType() == EntityType.SPLASH_POTION)  {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw splash potions");
                createFizzleEffect(player.getLocation());
            }
            if (event.getEntityType() == EntityType.LINGERING_POTION) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw lingering potions");
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getState() == PlayerFishEvent.State.FISHING && !canInteract(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fishing rods");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            String entityName = event.getEntity().getType().name().toLowerCase().replace("_", " ");
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "hurt that " + entityName);
            createFizzleEffect(event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativeInventoryManagement(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Inventory clickedInventory = event.getClickedInventory();
        ItemStack itemOnCursor = player.getItemOnCursor();
        if (event.getSlot() == -999 && clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, 
                    "Allowed item destruction via X slot for " + player.getName() + 
                    ", item: " + itemOnCursor.getType()
                );
            }
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                return;
            }
        }
        if (clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE &&
            (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR)) {
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                return;
            }
        }
        if (!canUse(player)) {
            event.setCancelled(true);
            SchedulerAdapter.runAtEntity(player, () -> {
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
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerEquipArmorCreative(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getSlotType() == InventoryType.SlotType.ARMOR && event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.PLAYER) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "equip armor");
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canDrop(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "drop that");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canUseContainers(player)) return;
        Inventory inventory = event.getInventory();
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title()).toLowerCase();
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));
        Set<String> restrictedTitles = new HashSet<>(Arrays.asList(
                "chest", "large chest", "barrel", "shulker box", "dispenser", "dropper",
                "hopper", "furnace", "blast furnace", "smoker", "brewing stand", "anvil",
                "grindstone", "enchanting table", "loom", "cartography table", "smithing table",
                "stonecutter", "beacon", "crafting table"
        ));
        if (restrictedTypes.contains(inventory.getType()) || restrictedTitles.stream().anyMatch(title::contains)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use this container");
            org.bukkit.block.Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock.getType() != Material.AIR) {
                createFizzleEffect(targetBlock.getLocation());
            } else {
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canUseContainers(player)) return;
        Inventory inventory = event.getClickedInventory();
        if (inventory == null || inventory.equals(player.getInventory())) return;
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));
        if (restrictedTypes.contains(inventory.getType())) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use this container");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canPickupItems(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "pick that up");
            createFizzleEffect(event.getItem().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Material blockType = event.getBlock().getType();
        if (!canPlace(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "place that");
            return;
        }
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "place that");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Material blockType = event.getBlock().getType();
        if (!canBreak(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "break that");
            return;
        }
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "break that");
        }
    }

    private boolean isInteractableBlock(Material material) {
        String name = material.name();
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
        return name.equals("LECTERN") ||
                name.equals("BELL") ||
                name.equals("ITEM_FRAME") ||
                name.equals("ARMOR_STAND") ||
                name.equals("RESPAWN_ANCHOR") ||
                name.equals("LODESTONE") ||
                name.equals("BED") ||
                name.equals("CAKE");
    }

    private void saveCurrentInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        if (player.hasPermission("allium.gamemode.creative.inventory")) {
            return;
        }
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        try {
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
            ItemStack[] survivalInventory = new ItemStack[0];
            ItemStack[] survivalArmor = new ItemStack[0];
            ItemStack survivalOffhand = null;
            ItemStack[] creativeInventory = new ItemStack[0];
            ItemStack[] creativeArmor = new ItemStack[0];
            ItemStack creativeOffhand = null;
            if (inventories != null) {
                survivalInventory = inventories.getSurvivalInventory();
                survivalArmor = inventories.getSurvivalArmor();
                survivalOffhand = inventories.getSurvivalOffhand();
                creativeInventory = inventories.getCreativeInventory();
                creativeArmor = inventories.getCreativeArmor();
                creativeOffhand = inventories.getCreativeOffhand();
            }
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
                    break;
            }
            // Save inventory using InventorySnapshot
            String reason = "gamemode_change_" + gameMode.name().toLowerCase();
            InventorySnapshot snapshot = new InventorySnapshot(player, reason);
            boolean success = database.saveInventorySnapshot(snapshot);
            
            if (success) {
                Text.sendDebugLog(INFO, "Successfully saved inventory snapshot for " + player.getName() + 
                    " (" + player.getUniqueId() + ") with reason: " + reason);
            } else {
                Text.sendDebugLog(ERROR, "Failed to save inventory snapshot for " + player.getName());
            }
            
            // Keep the PlayerInventories object for any other necessary operations
            PlayerInventories updated = new PlayerInventories(
                survivalInventory,
                survivalArmor,
                survivalOffhand,
                creativeInventory,
                creativeArmor,
                creativeOffhand
            );
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to save inventory for " + playerName, e);
        }
    }

    private void loadInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        if (player.hasPermission("allium.gamemode.creative.inventory")) {
            return;
        }
        UUID playerUUID = player.getUniqueId();
        try {
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
            if (inventories == null) {
                return;
            }
            ItemStack[] inventory;
            ItemStack[] armor;
            ItemStack offhand;
            if (gameMode == GameMode.CREATIVE) {
                inventory = inventories.getCreativeInventory();
                armor = inventories.getCreativeArmor();
                offhand = inventories.getCreativeOffhand();
            } else if (gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE) {
                inventory = inventories.getSurvivalInventory();
                armor = inventories.getSurvivalArmor();
                offhand = inventories.getSurvivalOffhand();
            } else {
                return;
            }
            if (inventory != null && inventory.length > 0) {
                player.getInventory().setContents(inventory);
            }
            if (armor != null && armor.length > 0) {
                player.getInventory().setArmorContents(armor);
            }
            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
            player.updateInventory();
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to load inventory for player: " + player.getName(), e);
        }
    }

    private void clearInventory(@NotNull Player player) {
        if (player.hasPermission("allium.gamemode.creative.inventory")) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.updateInventory();
    }

    public GameMode getLastKnownGamemode(UUID playerUUID) {
        return lastKnownGamemodes.getOrDefault(playerUUID, GameMode.SURVIVAL);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        GameMode oldGameMode = player.getGameMode();

        cachePlayerPermissions(player);

        // If a caller explicitly enabled bypass (e.g., temporary GM swap to refresh client state),
        // skip all inventory save/clear/load logic to avoid any item swapping side-effects.
        if (bypassInventoryManagement.contains(player.getUniqueId())) {
            return;
        }

        if (player.hasPermission("allium.gamemode.creative.inventory")) {
            return;
        }

        saveCurrentInventory(player, oldGameMode);
        SchedulerAdapter.runAtEntityLater(player, () -> {
            if (!player.isOnline()) return;
            // Ensure gamemode has updated
            if (player.getGameMode() == newGameMode) {
                clearInventory(player); // Clear before loading to avoid overlap
                loadInventory(player, newGameMode);
            }
        }, 2L); // Reduced delay to 2 ticks
    }

    private final Set<UUID> bypassInventoryManagement = ConcurrentHashMap.newKeySet();

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
            // Skip spectator restoration if player joins in survival - they should stay in survival (vanilla behavior)
            if (lastKnownGamemode == GameMode.SPECTATOR && currentGamemode == GameMode.SURVIVAL) {
                // Update the known gamemode to survival to prevent future conflicts
                lastKnownGamemodes.put(playerId, GameMode.SURVIVAL);
                return;
            }
            
            if ((lastKnownGamemode == GameMode.CREATIVE && player.hasPermission("allium.gamemode.creative")) ||
                    (lastKnownGamemode == GameMode.SPECTATOR && player.hasPermission("allium.gamemode.spectator")) &&
                    player.hasPermission("allium.gamemode")) {
                // Player has permission - restore their previous gamemode
                SchedulerAdapter.runAtEntityLater(player, () -> {
                    if (!player.isOnline()) return;
                    player.setGameMode(lastKnownGamemode);
                    // Skip inventory management for players with creative.inventory permission (vanilla behavior)
                    if (!player.hasPermission("allium.gamemode.creative.inventory")) {
                        clearInventory(player); // Clear to prevent item duplication
                        loadInventory(player, lastKnownGamemode);
                    }
                }, 2L);
            } else if ((lastKnownGamemode == GameMode.CREATIVE && !player.hasPermission("allium.gamemode.creative")) ||
                       (lastKnownGamemode == GameMode.SPECTATOR && !player.hasPermission("allium.gamemode.spectator"))) {
                // Player doesn't have permission for their last known gamemode - fix their inventory
                if (!player.hasPermission("allium.gamemode.creative.inventory")) {
                    SchedulerAdapter.runAtEntityLater(player, () -> {
                        if (!player.isOnline()) return;
                        // Clear their current inventory (which is from the unauthorized gamemode)
                        clearInventory(player);
                        // Load the inventory for their current gamemode (survival)
                        loadInventory(player, currentGamemode);
                        Text.sendDebugLog(INFO, "Fixed inventory for " + player.getName() + 
                                " who was in unauthorized gamemode " + lastKnownGamemode + 
                                ", now in " + currentGamemode);
                    }, 2L);
                }
                // Update the known gamemode to prevent future conflicts
                lastKnownGamemodes.put(playerId, currentGamemode);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cachePlayerPermissions(player);
        lastKnownGamemodes.put(player.getUniqueId(), player.getGameMode());
        auditJoinCreative(player);
        checkAndApplyGameModeReset(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        cachedCreativePermissions.remove(playerId);
        cachedSpectatorPermissions.remove(playerId);
        lastKnownGamemodes.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChangeTrack(PlayerGameModeChangeEvent event) {
        Plugin sourcePlugin = detectSourcePlugin();
        if (sourcePlugin == null || !sourcePlugin.getName().equals("Multiverse-Core")) {
            lastKnownGamemodes.put(event.getPlayer().getUniqueId(), event.getNewGameMode());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeAudit(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("allium.gamemode.creative")) {
            return;
        }
        if (player.getGameMode() == event.getNewGameMode()) {
            return;
        }

        SecurityAlertManager alerts = plugin.getSecurityAlertManager();
        if (alerts != null) {
            alerts.broadcastGamemodeAudit(player, "switched gamemode", player.getGameMode(), event.getNewGameMode());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World toWorld = player.getWorld();
        GameMode currentGamemode = player.getGameMode();
        UUID playerUUID = player.getUniqueId();

        if (player.hasPermission("mv.bypass.gamemode." + toWorld.getName()) ||
                player.hasPermission("mv.bypass.gamemode.*") ||
                player.hasPermission("allium.gamemode.creative") ||
                player.hasPermission("allium.gamemode.spectator") ||
                player.hasPermission("allium.gamemode")) {

            if (player.hasPermission("allium.gamemode.creative.inventory")) {
                return;
            }
            saveCurrentInventory(player, currentGamemode);

            SchedulerAdapter.runAtEntityLater(player, () -> {
                if (!player.isOnline() || !player.getWorld().equals(toWorld)) return;
                if (player.getGameMode() != currentGamemode) {
                    player.setGameMode(currentGamemode);
                }
                clearInventory(player); // Clear to prevent item duplication
                loadInventory(player, currentGamemode);
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGamemodeSwitchRevert(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGamemode = event.getNewGameMode();
        GameMode oldGamemode = player.getGameMode();
        UUID playerUUID = player.getUniqueId();

        long currentTime = System.currentTimeMillis();
        if (recentRestorations.containsKey(playerUUID)) {
            long lastRestoration = recentRestorations.get(playerUUID);
            if (currentTime - lastRestoration < RESTORATION_COOLDOWN) {
                return;
            }
        }

        Plugin sourcePlugin = detectSourcePlugin();
        boolean isMultiverse = sourcePlugin != null && sourcePlugin.getName().equals("Multiverse-Core");

        if (isMultiverse && (
                (oldGamemode == GameMode.CREATIVE && player.hasPermission("allium.gamemode.creative")) ||
                (oldGamemode == GameMode.SPECTATOR && player.hasPermission("allium.gamemode.spectator")) &&
                player.hasPermission("allium.gamemode") ||
                player.hasPermission("mv.bypass.gamemode." + player.getWorld().getName()) ||
                player.hasPermission("mv.bypass.gamemode.*"))) {

            event.setCancelled(true);

            if (player.hasPermission("allium.gamemode.creative.inventory")) {
                return;
            }

            SchedulerAdapter.runAtEntityLater(player, () -> {
                if (!player.isOnline()) return;
                if (player.getGameMode() != oldGamemode) {
                    player.setGameMode(oldGamemode);
                }
                clearInventory(player); // Clear to prevent item duplication
                loadInventory(player, oldGamemode);
                recentRestorations.put(playerUUID, System.currentTimeMillis());
            }, 2L);
        }
    }

    private Plugin detectSourcePlugin() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> pluginPackagePrefixes = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.equals(this.plugin)) continue;
            String mainClass = plugin.getPluginMeta().getMainClass();
            String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));
            pluginPackagePrefixes.add(packagePrefix);
        }
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            for (String prefix : pluginPackagePrefixes) {
                if (className.startsWith(prefix)) {
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        String mainClass = plugin.getPluginMeta().getMainClass();
                        String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));
                        if (className.startsWith(packagePrefix)) {
                            return plugin;
                        }
                    }
                }
            }
            if (className.contains("Multiverse") || className.contains("multiverse")) {
                Plugin mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
                if (mvPlugin != null) {
                    return mvPlugin;
                }
            }
        }
        return null;
    }

    private void auditJoinCreative(Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        if (player.hasPermission("allium.gamemode.creative")) {
            return;
        }

        SecurityAlertManager alerts = plugin.getSecurityAlertManager();
        if (alerts != null) {
            alerts.broadcastGamemodeAudit(player, "joined in creative", null, player.getGameMode());
        }
    }
}
