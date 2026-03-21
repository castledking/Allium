package net.survivalfun.core.items.impl;

import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manager for Spawner Type Changer. Hopper intake menu, confirm menu,
 * block vanilla spawn egg on spawner, track changed spawners in changed_spawners.yml.
 */
public class SpawnerChangerManager implements Listener {

    private static final String INTAKE_MENU_TITLE = "Insert Spawn Egg:";
    private static final String CONFIRM_MENU_TITLE = "Confirm Spawner Change:";

    private static final Set<EntityType> RESTRICTED_MOBS = EnumSet.of(
        EntityType.VILLAGER,
        EntityType.ZOMBIE_VILLAGER,
        EntityType.WANDERING_TRADER,
        EntityType.MOOSHROOM,
        EntityType.COW,
        EntityType.SHEEP,
        EntityType.CHICKEN,
        EntityType.SPIDER,
        EntityType.ZOMBIE,
        EntityType.SKELETON,
        EntityType.IRON_GOLEM,
        EntityType.ENDERMAN,
        EntityType.SLIME,
        EntityType.MAGMA_CUBE,
        EntityType.SQUID,
        EntityType.GLOW_SQUID,
        EntityType.PIG,
        EntityType.BLAZE,
        EntityType.CREEPER
    );

    private final Plugin plugin;
    private final CustomItemRegistry registry;
    private final NamespacedKey changedSpawnerKey;

    private final Map<UUID, Location> playerSpawnerSessions = new HashMap<>();
    private final Map<UUID, EntityType> playerConfirmSessions = new HashMap<>();
    private final Map<UUID, ItemStack> playerSpawnEggSessions = new HashMap<>();
    private final Map<UUID, Long> lastSpawnEggMessage = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 10000;

    private final Set<String> changedSpawners = new HashSet<>();
    private final File dataFile;

    public SpawnerChangerManager(Plugin plugin, CustomItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.changedSpawnerKey = new NamespacedKey(plugin, "changed_spawner");
        this.dataFile = new File(plugin.getDataFolder(), "changed_spawners.yml");
        loadChangedSpawners();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnEggUseOnSpawner(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;
        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return;
        if (event.getPlayer().hasPermission("allium.spawner.vanilla")) return;
        event.setCancelled(true);
        sendRateLimitedMessage(event.getPlayer(), lastSpawnEggMessage,
            ChatColor.RED + "You must use a " + hexColor("#FFD700") + "Spawner Type Changer" +
                ChatColor.RESET + ChatColor.RED + " to change a spawner's type.");
    }

    public boolean isHoldingTool(Player player) {
        net.survivalfun.core.items.CustomItem tool = registry.getItem(SpawnerChangerItem.ITEM_ID);
        if (tool == null) return false;
        return tool.isThisItem(player.getInventory().getItemInMainHand()) ||
            tool.isThisItem(player.getInventory().getItemInOffHand());
    }

    private void sendRateLimitedMessage(Player player, Map<UUID, Long> cooldownMap, String message) {
        long now = System.currentTimeMillis();
        Long lastTime = cooldownMap.get(player.getUniqueId());
        if (lastTime == null || (now - lastTime) >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage(message);
            cooldownMap.put(player.getUniqueId(), now);
        }
    }

    private String hexColor(String hex) {
        if (hex == null || hex.length() != 7 || !hex.startsWith("#")) return "";
        StringBuilder result = new StringBuilder("§x");
        for (int i = 1; i < 7; i++) {
            result.append("§").append(hex.charAt(i));
        }
        return result.toString();
    }

    private ItemStack createFiller(Material material, String displayName, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (loreLine != null) {
                meta.setLore(Arrays.asList(ChatColor.GRAY + loreLine));
            }
            meta.addItemFlags(
                org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE,
                org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                org.bukkit.inventory.ItemFlag.HIDE_DESTROYS,
                org.bukkit.inventory.ItemFlag.HIDE_PLACED_ON
            );
        }
        return item;
    }

    public void openIntakeMenu(Player player, Location spawnerLocation) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, INTAKE_MENU_TITLE);
        ItemStack filler = createFiller(Material.GRAY_STAINED_GLASS_PANE,
            ChatColor.DARK_GRAY + "Insert Spawn Egg",
            "Place a spawn egg in any slot");
        inv.setItem(0, filler);
        inv.setItem(1, filler);
        inv.setItem(3, filler);
        inv.setItem(4, filler);
        player.openInventory(inv);
        playerSpawnerSessions.put(player.getUniqueId(), spawnerLocation);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChangedSpawnerRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;
        if (!isSpawnerChanged(block.getLocation())) return;
        if (isHoldingTool(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChangedSpawnerSpawn(SpawnerSpawnEvent event) {
        Block block = event.getSpawner().getBlock();
        if (!isSpawnerChanged(block.getLocation())) return;

        event.getEntity().setCustomName(" ");
        event.getEntity().setCustomNameVisible(false);
        SchedulerAdapter.runLater(() -> {
            if (!event.getEntity().isValid()) return;
            event.getEntity().setCustomName(null);
            event.getEntity().setCustomNameVisible(false);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        if (title.equals(INTAKE_MENU_TITLE)) {
            handleIntakeMenuClick(event, player);
        } else if (title.equals(CONFIRM_MENU_TITLE)) {
            handleConfirmMenuClick(event, player);
        }
    }

    private boolean isIntakeFiller(ItemStack item) {
        return item != null && item.getType() == Material.GRAY_STAINED_GLASS_PANE
            && item.hasItemMeta() && item.getItemMeta() != null
            && item.getItemMeta().hasDisplayName()
            && item.getItemMeta().getDisplayName().contains("Insert Spawn Egg");
    }

    private void handleIntakeMenuClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        if (slot >= 0 && slot <= 4) {
            ItemStack current = event.getCurrentItem();
            if (current != null && isIntakeFiller(current)) {
                ItemStack cursor = event.getCursor();
                boolean placingSpawnEgg = cursor != null && cursor.getType() != Material.AIR
                    && cursor.getType().name().endsWith("_SPAWN_EGG");
                if (!placingSpawnEgg) {
                    event.setCancelled(true);
                }
            }
        }
        SchedulerAdapter.runLater(() -> {
            Inventory inv = event.getInventory();
            ItemStack spawnEgg = null;
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
                    spawnEgg = item;
                    break;
                }
            }
            if (spawnEgg != null) {
                EntityType entityType = getEntityTypeFromSpawnEgg(spawnEgg);
                if (entityType != null) {
                    if (!canUseEntityType(player, entityType)) {
                        player.sendMessage(ChatColor.RED + "You don't have permission to change spawners to " +
                            ChatColor.YELLOW + formatEntityName(entityType) + ChatColor.RED + "!");
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(spawnEgg.clone());
                        for (ItemStack drop : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                        inv.clear();
                        player.closeInventory();
                        cleanupSession(player);
                        return;
                    }
                    playerSpawnEggSessions.put(player.getUniqueId(), spawnEgg.clone());
                    inv.clear();
                    openConfirmMenu(player, spawnEgg.clone(), entityType);
                }
            }
        }, 1L);
    }

    private boolean canUseEntityType(Player player, EntityType entityType) {
        if (!RESTRICTED_MOBS.contains(entityType)) return true;
        return player.hasPermission("allium.spawnerchanger.any")
            || player.hasPermission(getRestrictedPermission(entityType));
    }

    private String getRestrictedPermission(EntityType entityType) {
        return "allium.spawnerchanger.use." + entityType.name().toLowerCase();
    }

    private void openConfirmMenu(Player player, ItemStack spawnEgg, EntityType entityType) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, CONFIRM_MENU_TITLE);
        ItemStack filler = createFiller(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        ItemStack denyButton = new ItemStack(Material.RED_CONCRETE);
        ItemMeta denyMeta = denyButton.getItemMeta();
        if (denyMeta != null) {
            denyMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✖ Cancel");
            denyMeta.setLore(Arrays.asList(ChatColor.GRAY + "Click to cancel"));
            denyButton.setItemMeta(denyMeta);
        }
        inv.setItem(0, denyButton);
        inv.setItem(1, filler);
        ItemStack displayEgg = spawnEgg.clone();
        ItemMeta eggMeta = displayEgg.getItemMeta();
        if (eggMeta != null) {
            eggMeta.setDisplayName(ChatColor.AQUA + formatEntityName(entityType) + " Spawn Egg");
            displayEgg.setItemMeta(eggMeta);
        }
        inv.setItem(2, displayEgg);
        inv.setItem(3, filler);
        ItemStack acceptButton = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta acceptMeta = acceptButton.getItemMeta();
        if (acceptMeta != null) {
            acceptMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ Confirm");
            acceptMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Click to change spawner to",
                ChatColor.YELLOW + formatEntityName(entityType)
            ));
            acceptButton.setItemMeta(acceptMeta);
        }
        inv.setItem(4, acceptButton);
        playerConfirmSessions.put(player.getUniqueId(), entityType);
        player.openInventory(inv);
    }

    private void handleConfirmMenuClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == 0) {
            ItemStack spawnEgg = playerSpawnEggSessions.get(player.getUniqueId());
            if (spawnEgg != null) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(spawnEgg);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Spawner change cancelled.");
            cleanupSession(player);
        } else if (slot == 4) {
            Location spawnerLoc = playerSpawnerSessions.get(player.getUniqueId());
            EntityType entityType = playerConfirmSessions.get(player.getUniqueId());
            if (spawnerLoc != null && entityType != null) {
                Block block = spawnerLoc.getBlock();
                if (block.getType() == Material.SPAWNER) {
                    setChangedSpawnerType(block, entityType);
                    markSpawnerAsChanged(spawnerLoc);
                    consumeToolFromPlayer(player);
                    playerSpawnEggSessions.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Success! " +
                        ChatColor.GRAY + "Spawner changed to " + ChatColor.YELLOW + formatEntityName(entityType) +
                        ChatColor.GRAY + "!");
                    player.sendMessage(ChatColor.GRAY + "This spawner can now be picked up without Silk Touch.");
                } else {
                    player.sendMessage(ChatColor.RED + "The spawner is no longer there!");
                }
            }
            player.closeInventory();
            cleanupSession(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnerBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) return;
        Location loc = block.getLocation();
        if (!isSpawnerChanged(loc)) return;
        detachSpawnerMeta(block);
        CreatureSpawner spawner = (CreatureSpawner) block.getState();
        EntityType entityType = spawner.getSpawnedType();
        event.setCancelled(true);
        block.setType(Material.AIR);
        ItemStack spawnerItem = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawnerItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + formatEntityName(entityType) + " Spawner");
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Entity: " + ChatColor.WHITE + formatEntityName(entityType),
                ChatColor.GRAY + "Changed with Spawner Type Changer"
            ));
            meta.getPersistentDataContainer().set(changedSpawnerKey, PersistentDataType.STRING, entityType.name());
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            spawnerItem.setItemMeta(meta);
        }
        loc.getWorld().dropItemNaturally(loc, spawnerItem);
        unmarkSpawnerAsChanged(loc);
        event.getPlayer().sendMessage(ChatColor.GREEN + "Spawner picked up!");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) return;
        ItemStack item = event.getItemInHand();
        if (!item.hasItemMeta()) return;
        String entityName = item.getItemMeta().getPersistentDataContainer()
            .get(changedSpawnerKey, PersistentDataType.STRING);
        if (entityName == null) return;
        try {
            EntityType entityType = EntityType.valueOf(entityName);
            setChangedSpawnerType(event.getBlock(), entityType);
            markSpawnerAsChanged(event.getBlock().getLocation());
            event.getPlayer().sendMessage(ChatColor.GREEN + "Placed " + formatEntityName(entityType) + " Spawner!");
        } catch (IllegalArgumentException ignored) {}
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        if (title.equals(INTAKE_MENU_TITLE)) {
            if (!playerSpawnEggSessions.containsKey(player.getUniqueId())) {
                returnItemsToPlayer(player, event.getInventory());
                cleanupSession(player);
            }
        } else if (title.equals(CONFIRM_MENU_TITLE)) {
            ItemStack spawnEgg = playerSpawnEggSessions.get(player.getUniqueId());
            if (spawnEgg != null) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(spawnEgg);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            cleanupSession(player);
        }
    }

    private void returnItemsToPlayer(Player player, Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (item.getType() == Material.GRAY_STAINED_GLASS_PANE && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName() && meta.getDisplayName().contains("Insert Spawn Egg")) {
                    continue;
                }
            }
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private void cleanupSession(Player player) {
        playerSpawnerSessions.remove(player.getUniqueId());
        playerConfirmSessions.remove(player.getUniqueId());
        playerSpawnEggSessions.remove(player.getUniqueId());
    }

    private void setChangedSpawnerType(Block block, EntityType entityType) {
        if (block.getType() != Material.SPAWNER) return;

        CreatureSpawner spawner = (CreatureSpawner) block.getState();
        spawner.setSpawnedType(entityType);
        clearSpawnerMetaTags(spawner.getPersistentDataContainer());
        spawner.update();
        detachSpawnerMeta(block);
    }

    private void detachSpawnerMeta(Block block) {
        Plugin spawnerMeta = Bukkit.getPluginManager().getPlugin("SpawnerMeta");
        if (spawnerMeta == null || !spawnerMeta.isEnabled()) return;

        try {
            Class<?> generatorRegistry = Class.forName("mc.rellox.spawnermeta.spawner.generator.GeneratorRegistry");
            generatorRegistry.getMethod("remove", Block.class).invoke(null, block);
        } catch (Exception ignored) {}

        try {
            Class<?> locationRegistry = Class.forName("mc.rellox.spawnermeta.configuration.location.LocationRegistry");
            locationRegistry.getMethod("remove", Block.class).invoke(null, block);
        } catch (Exception ignored) {}

        if (block.getType() != Material.SPAWNER) return;
        CreatureSpawner spawner = (CreatureSpawner) block.getState();
        if (clearSpawnerMetaTags(spawner.getPersistentDataContainer())) {
            spawner.update();
        }
    }

    private boolean clearSpawnerMetaTags(PersistentDataContainer container) {
        boolean changed = false;
        for (NamespacedKey key : new HashSet<>(container.getKeys())) {
            if ("spawnermeta".equalsIgnoreCase(key.getNamespace())) {
                container.remove(key);
                changed = true;
            }
        }
        return changed;
    }

    private void consumeToolFromPlayer(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        net.survivalfun.core.items.CustomItem tool = registry.getItem(SpawnerChangerItem.ITEM_ID);
        if (tool != null) {
            if (tool.isThisItem(mainHand)) {
                mainHand.setAmount(mainHand.getAmount() - 1);
            } else if (tool.isThisItem(offHand)) {
                offHand.setAmount(offHand.getAmount() - 1);
            }
        }
    }

    private EntityType getEntityTypeFromSpawnEgg(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return null;
        String materialName = item.getType().name();
        String entityName = materialName.replace("_SPAWN_EGG", "");
        try {
            return EntityType.valueOf(entityName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String formatEntityName(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == ' ') {
                result.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String locationToKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void markSpawnerAsChanged(Location loc) {
        changedSpawners.add(locationToKey(loc));
        saveChangedSpawners();
    }

    private void unmarkSpawnerAsChanged(Location loc) {
        changedSpawners.remove(locationToKey(loc));
        saveChangedSpawners();
    }

    private boolean isSpawnerChanged(Location loc) {
        return changedSpawners.contains(locationToKey(loc));
    }

    private void loadChangedSpawners() {
        if (!dataFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        changedSpawners.addAll(config.getStringList("spawners"));
    }

    private void saveChangedSpawners() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("spawners", new ArrayList<>(changedSpawners));
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save changed spawners: " + e.getMessage());
        }
    }

    public void openIntakeMenuFromBlock(Player player, Location spawnerLocation) {
        openIntakeMenu(player, spawnerLocation);
    }

    /** Rate-limited message when player right-clicks air with the tool. */
    public void sendClickMessage(Player player) {
        sendRateLimitedMessage(player, lastSpawnEggMessage,
            ChatColor.YELLOW + "Right-click on a spawner to change its type!");
    }
}
