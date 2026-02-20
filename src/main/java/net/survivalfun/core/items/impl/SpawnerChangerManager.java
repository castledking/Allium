package net.survivalfun.core.items.impl;

import net.survivalfun.core.managers.core.Text;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class SpawnerChangerManager implements Listener {

    private static final String CHANGED_SPAWNERS_FILE = "changed_spawners.yml";
    private static final Map<UUID, String> intakeData = new HashMap<>();
    private static final Map<UUID, String> confirmData = new HashMap<>();

    private static final Set<EntityType> RESTRICTED_TYPES = EnumSet.of(
        EntityType.VILLAGER, EntityType.WANDERING_TRADER
    );

    private final Plugin plugin;
    private final File changedSpawnersFile;
    private YamlConfiguration changedSpawnersConfig;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 10000;

    public SpawnerChangerManager(Plugin plugin) {
        this.plugin = plugin;
        this.changedSpawnersFile = new File(plugin.getDataFolder(), CHANGED_SPAWNERS_FILE);
        loadChangedSpawners();
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadChangedSpawners() {
        if (!changedSpawnersFile.exists()) {
            changedSpawnersConfig = new YamlConfiguration();
            return;
        }

        try {
            changedSpawnersConfig = YamlConfiguration.loadConfiguration(changedSpawnersFile);
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to load changed spawners config", e);
            changedSpawnersConfig = new YamlConfiguration();
        }
    }

    private void saveChangedSpawners() {
        try {
            changedSpawnersConfig.save(changedSpawnersFile);
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to save changed spawners config", e);
        }
    }

    public void openIntakeMenu(Player player, Location spawnerLocation) {
        Inventory intake = Bukkit.createInventory(null, 9, "§d§lSpawner Type Changer");

        ItemStack infoItem = new ItemStack(Material.PAPER);
        ItemMeta meta = infoItem.getItemMeta();
        meta.setDisplayName("§e§lPlace Spawn Egg");
        meta.setLore(Arrays.asList(
            "§7Place a spawn egg in this slot",
            "§7to change the spawner type",
            "",
            "§cWarning: Single use item!"
        ));
        infoItem.setItemMeta(meta);
        intake.setItem(4, infoItem);

        intakeData.put(player.getUniqueId(), spawnerLocation.toString());

        player.openInventory(intake);
    }

    public void openConfirmMenu(Player player, Location spawnerLocation, EntityType newType) {
        Inventory confirm = Bukkit.createInventory(null, 9, "§a§lConfirm Change");

        ItemStack confirmItem = new ItemStack(Material.GREEN_STAINED_GLASS);
        ItemMeta confirmMeta = confirmItem.getItemMeta();
        confirmMeta.setDisplayName("§a§lConfirm");
        confirmItem.setItemMeta(confirmMeta);
        confirm.setItem(0, confirmItem);

        ItemStack cancelItem = new ItemStack(Material.RED_STAINED_GLASS);
        ItemMeta cancelMeta = cancelItem.getItemMeta();
        cancelMeta.setDisplayName("§c§lCancel");
        cancelItem.setItemMeta(cancelMeta);
        confirm.setItem(1, cancelItem);

        ItemStack infoItem = new ItemStack(Material.SPAWNER);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName("§e§lChange to: " + formatEntityName(newType));
        infoItem.setItemMeta(infoMeta);
        confirm.setItem(4, infoItem);

        confirmData.put(player.getUniqueId(), spawnerLocation.toString() + ";" + newType.name());

        player.openInventory(confirm);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = event.getView().getTitle();
        
        if (title.equals("§d§lSpawner Type Changer")) {
            event.setCancelled(true);
            handleIntakeClick(player, event);
        } else if (title.equals("§a§lConfirm Change")) {
            event.setCancelled(true);
            handleConfirmClick(player, event);
        }
    }

    private void handleIntakeClick(Player player, InventoryClickEvent event) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        if (event.getSlot() != 4) {
            return;
        }

        EntityType eggType = getEggType(clickedItem);
        if (eggType == null) {
            player.sendMessage(ChatColor.RED + "That's not a valid spawn egg!");
            return;
        }

        if (clickedItem.getAmount() > 1) {
            clickedItem.setAmount(clickedItem.getAmount() - 1);
        } else {
            event.getInventory().setItem(4, null);
        }

        String locString = intakeData.get(player.getUniqueId());
        if (locString == null) {
            return;
        }

        Location spawnerLocation = locationFromString(locString);
        if (spawnerLocation == null) {
            return;
        }

        Block spawnerBlock = spawnerLocation.getBlock();
        if (spawnerBlock.getType() != Material.SPAWNER) {
            player.sendMessage(ChatColor.RED + "The spawner is no longer there!");
            player.closeInventory();
            intakeData.remove(player.getUniqueId());
            return;
        }

        if (!player.hasPermission("allium.spawnerchanger.any") && RESTRICTED_TYPES.contains(eggType)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to create " + formatEntityName(eggType) + " spawners!");
            player.closeInventory();
            intakeData.remove(player.getUniqueId());
            return;
        }

        player.closeInventory();
        intakeData.remove(player.getUniqueId());
        openConfirmMenu(player, spawnerLocation, eggType);
    }

    private void handleConfirmClick(Player player, InventoryClickEvent event) {
        if (event.getSlot() == 1) {
            player.sendMessage(ChatColor.YELLOW + "Spawner type change cancelled.");
            player.closeInventory();
            confirmData.remove(player.getUniqueId());
            return;
        }

        if (event.getSlot() != 0) {
            return;
        }

        String confirmMeta = confirmData.get(player.getUniqueId());
        if (confirmMeta == null) {
            return;
        }

        String[] parts = confirmMeta.split(";");
        if (parts.length != 7) {
            return;
        }

        String locPart = "";
        for (int i = 0; i < parts.length - 1; i++) {
            locPart += (i > 0 ? ";" : "") + parts[i];
        }
        
        Location spawnerLocation = locationFromString(locPart);
        EntityType newType;
        
        try {
            newType = EntityType.valueOf(parts[parts.length - 1]);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (spawnerLocation == null) {
            return;
        }

        Block spawnerBlock = spawnerLocation.getBlock();
        if (spawnerBlock.getType() != Material.SPAWNER) {
            player.sendMessage(ChatColor.RED + "The spawner is no longer there!");
            player.closeInventory();
            confirmData.remove(player.getUniqueId());
            return;
        }

        if (spawnerBlock.getState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(newType);
            spawner.update();
        }

        String worldKey = spawnerLocation.getWorld().getName() + ":" + 
            spawnerLocation.getBlockX() + "," + spawnerLocation.getBlockY() + "," + spawnerLocation.getBlockZ();
        changedSpawnersConfig.set(worldKey, newType.name());
        saveChangedSpawners();

        player.sendMessage(ChatColor.GREEN + "Spawner type changed to " + ChatColor.YELLOW + formatEntityName(newType) + ChatColor.GREEN + "!");
        player.closeInventory();
        confirmData.remove(player.getUniqueId());

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item != null && item.getType() != Material.AIR) {
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private EntityType getEggType(ItemStack item) {
        if (item == null) return null;

        Material type = item.getType();
        
        if (type.name().endsWith("_SPAWN_EGG")) {
            String eggName = type.name().replace("_SPAWN_EGG", "");
            try {
                return EntityType.valueOf(eggName);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.SpawnEggMeta eggMeta) {
            return eggMeta.getSpawnedType();
        }

        return null;
    }

    private Location locationFromString(String str) {
        try {
            String[] parts = str.split(";");
            if (parts.length < 6) return null;

            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;

            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);

            return new Location(world, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatEntityName(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        StringBuilder formatted = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : name.toCharArray()) {
            if (c == ' ') {
                capitalizeNext = true;
                formatted.append(' ');
            } else if (capitalizeNext) {
                formatted.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                formatted.append(c);
            }
        }

        return formatted.toString();
    }

    public boolean isChangedSpawner(Location location) {
        if (location == null || location.getWorld() == null) return false;
        
        String worldKey = location.getWorld().getName() + ":" + 
            location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        
        return changedSpawnersConfig.contains(worldKey);
    }

    public EntityType getChangedSpawnerType(Location location) {
        if (location == null || location.getWorld() == null) return null;
        
        String worldKey = location.getWorld().getName() + ":" + 
            location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        
        String typeName = changedSpawnersConfig.getString(worldKey);
        if (typeName == null) return null;
        
        try {
            return EntityType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void sendClickMessage(Player player) {
        long now = System.currentTimeMillis();
        Long lastTime = cooldowns.get(player.getUniqueId());
        
        if (lastTime == null || (now - lastTime) >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage(ChatColor.YELLOW + "Right-click on a spawner to change its type!");
            cooldowns.put(player.getUniqueId(), now);
        }
    }

    public void openIntakeMenuFromBlock(Player player, Location spawnerLocation) {
        openIntakeMenu(player, spawnerLocation);
    }
}
