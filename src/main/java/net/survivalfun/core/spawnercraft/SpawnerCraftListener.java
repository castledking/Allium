package net.survivalfun.core.spawnercraft;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.CreatureSpawner;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.logging.Level;

/**
 * Listener for spawner crafting: block core/head placement, show guide, handle craft result.
 */
public class SpawnerCraftListener implements Listener {

    private final PluginStart plugin;
    private final SpawnerCoreManager coreManager;
    private final NamespacedKey simulatedSpawnerKey;
    private final NamespacedKey spawnerEntityKey;

    public SpawnerCraftListener(PluginStart plugin, SpawnerCoreManager coreManager) {
        this.plugin = plugin;
        this.coreManager = coreManager;
        this.simulatedSpawnerKey = new NamespacedKey(plugin, "simulated_spawner");
        this.spawnerEntityKey = new NamespacedKey(plugin, "spawner_entity");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (coreManager.isCore(item)) {
            event.setCancelled(true);
            EntityType entityType = coreManager.getCoreEntityType(item);
            if (entityType != null) SpawnerCraftGuideGui.open(event.getPlayer(), entityType);
            return;
        }
        if (MobHeadRegistry.isMobHeadForPlacement(item)) {
            EntityType headType = MobHeadRegistry.getEntityType(item);
            if (headType != null && MobHeadDropListener.handlesMobType(headType)) {
                event.setCancelled(true);
                SpawnerCraftGuideGui.open(event.getPlayer(), headType);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClickAir(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        if (coreManager.isCore(item)) {
            EntityType entityType = coreManager.getCoreEntityType(item);
            if (entityType != null) {
                event.setCancelled(true);
                SpawnerCraftGuideGui.open(event.getPlayer(), entityType);
            }
            return;
        }
        if (MobHeadRegistry.isMobHeadForPlacement(item)) {
            EntityType headType = MobHeadRegistry.getEntityType(item);
            if (headType != null && MobHeadDropListener.handlesMobType(headType)) {
                event.setCancelled(true);
                SpawnerCraftGuideGui.open(event.getPlayer(), headType);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(SpawnerCraftGuideGui.getGuiTitle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();
        EntityType entityType = getValidSpawnerCraft(matrix);
        if (entityType != null && event.getView().getPlayer() instanceof Player) {
            Player player = (Player) event.getView().getPlayer();
            ItemStack simulatedResult = createSimulatedSpawner(entityType, player);
            inventory.setResult(simulatedResult);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof CraftingInventory)) return;
        if (event.getSlotType() != org.bukkit.event.inventory.InventoryType.SlotType.RESULT) return;
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !isSimulatedSpawner(clickedItem)) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        CraftingInventory craftingInv = (CraftingInventory) event.getInventory();
        if (!player.hasPermission("allium.spawnercraft.craft")) {
            event.setCancelled(true);
            player.sendMessage("§cYou don't have permission to craft spawners.");
            return;
        }
        EntityType entityType = getSimulatedSpawnerEntity(clickedItem);
        if (entityType == null) return;
        event.setCancelled(true);
        ItemStack[] matrix = craftingInv.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            if (matrix[i] != null) {
                matrix[i].setAmount(matrix[i].getAmount() - 1);
            }
        }
        craftingInv.setMatrix(matrix);
        craftingInv.setResult(null);

        final String entityDisplayName = SpawnerCoreManager.formatEntityName(entityType);
        SchedulerAdapter.runLater(() -> {
            boolean success = giveCraftedSpawner(player, entityType);
            if (success) {
                player.sendMessage("§a§lSpawner Crafted! §7You received a " + entityDisplayName + " Spawner.");
            } else {
                player.sendMessage("§c§lCraft Failed! §7Could not give spawner. Please contact an administrator.");
            }
        }, 1L);
    }

    private boolean giveCraftedSpawner(Player player, EntityType entityType) {
        CraftProtocol protocol = getCraftProtocol();
        return switch (protocol) {
            case VANILLA -> giveVanillaSpawner(player, entityType);
            case SPAWNER_META -> dispatchConsoleGive("sm give " + entityType.name().toLowerCase(Locale.ENGLISH) + " 1 " + player.getName(), player, protocol);
            case UPGRADABLE_SPAWNERS -> dispatchConsoleGive("upgradeablespawners admin give " + player.getName() + " " + entityType.name(), player, protocol);
        };
    }

    private boolean giveVanillaSpawner(Player player, EntityType entityType) {
        ItemStack spawner = new ItemStack(Material.SPAWNER, 1);
        if (!(spawner.getItemMeta() instanceof BlockStateMeta meta)) {
            return false;
        }

        if (!(meta.getBlockState() instanceof CreatureSpawner creatureSpawner)) {
            return false;
        }

        creatureSpawner.setSpawnedType(entityType);
        meta.setBlockState(creatureSpawner);
        spawner.setItemMeta(meta);

        player.getInventory().addItem(spawner).values().forEach(overflow ->
            player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        return true;
    }

    private boolean dispatchConsoleGive(String command, Player player, CraftProtocol protocol) {
        boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!success) {
            plugin.getLogger().log(Level.WARNING,
                "Failed to execute " + protocol.configValue + " spawner give command for " + player.getName() + ": " + command);
        }
        return success;
    }

    private CraftProtocol getCraftProtocol() {
        String raw = plugin.getConfig().getString("spawnercraft.protocol", CraftProtocol.VANILLA.configValue);
        return CraftProtocol.fromConfig(raw);
    }

    private ItemStack createSimulatedSpawner(EntityType entityType, Player player) {
        ItemStack spawner = new ItemStack(Material.SPAWNER, 1);
        ItemMeta meta = spawner.getItemMeta();
        if (meta == null) return spawner;
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        meta.setDisplayName("§e§lSpawner: " + entityName);
        List<String> lore = new ArrayList<>();
        lore.add(" §8▪ §7Owner: §b" + player.getName());
        lore.add(" §8▪ §7Entity: §b" + entityName);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(simulatedSpawnerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(spawnerEntityKey, PersistentDataType.STRING, entityType.name());
        spawner.setItemMeta(meta);
        return spawner;
    }

    private boolean isSimulatedSpawner(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(simulatedSpawnerKey, PersistentDataType.BYTE);
    }

    private EntityType getSimulatedSpawnerEntity(ItemStack item) {
        if (!isSimulatedSpawner(item)) return null;
        String entityName = item.getItemMeta().getPersistentDataContainer().get(spawnerEntityKey, PersistentDataType.STRING);
        if (entityName == null) return null;
        try {
            return EntityType.valueOf(entityName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private EntityType getValidSpawnerCraft(ItemStack[] matrix) {
        if (matrix == null || matrix.length != 9) return null;
        ItemStack center = matrix[4];
        if (!coreManager.isCore(center)) return null;
        EntityType templateType = coreManager.getCoreEntityType(center);
        if (templateType == null) return null;
        int[] surroundingSlots = {0, 1, 2, 3, 5, 6, 7, 8};
        for (int slot : surroundingSlots) {
            ItemStack item = matrix[slot];
            if (item == null || !MobHeadRegistry.isAnyHeadType(item)) return null;
            EntityType headType = MobHeadRegistry.getEntityType(item);
            if (headType == null || headType != templateType) return null;
        }
        return templateType;
    }

    private enum CraftProtocol {
        UPGRADABLE_SPAWNERS("UpgradableSpawners"),
        SPAWNER_META("SpawnerMeta"),
        VANILLA("Vanilla");

        private final String configValue;

        CraftProtocol(String configValue) {
            this.configValue = configValue;
        }

        private static CraftProtocol fromConfig(String raw) {
            if (raw == null) {
                return VANILLA;
            }

            String normalized = raw.trim().toLowerCase(Locale.ENGLISH).replace("-", "").replace("_", "");
            return switch (normalized) {
                case "upgradeablespawners", "upgradablespawners" -> UPGRADABLE_SPAWNERS;
                case "spawnermeta" -> SPAWNER_META;
                case "vanilla" -> VANILLA;
                default -> VANILLA;
            };
        }
    }
}
