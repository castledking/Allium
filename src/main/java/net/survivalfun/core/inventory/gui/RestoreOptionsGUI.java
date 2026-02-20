package net.survivalfun.core.inventory.gui;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventorySnapshot;
import net.survivalfun.core.util.ItemBuilder;
import net.survivalfun.core.util.SchedulerAdapter;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class RestoreOptionsGUI extends BaseGUI {
    private final InventorySnapshot snapshot;
    private final RestoreGUI previousGUI;
    private final UUID targetId;

    public RestoreOptionsGUI(Player player, InventorySnapshot snapshot, RestoreGUI previousGUI, PluginStart plugin) {
        super(player, "Restore Options - " + snapshot.getReason(), 6, plugin);
        this.snapshot = snapshot;
        this.previousGUI = previousGUI;
        this.targetId = snapshot.getPlayerId();
    }

    @Override
    public void initialize() {
        // Load data asynchronously
        SchedulerAdapter.runAsync(() -> {
            try {
                // Get the snapshot data
                ItemStack[] contents = snapshot.getInventoryContents();
                ItemStack[] armor = snapshot.getArmorContents();
                
                // Get target player info
                String targetName = "Unknown";
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) {
                    targetName = target.getName();
                } else {
                    // Try to get offline player name
                    targetName = Bukkit.getOfflinePlayer(targetId).getName();
                }
                
                // Create restore buttons
                ItemStack restoreSelfBtn = new ItemBuilder(Material.CHEST)
                    .name("&aRestore to Self")
                    .lore(
                        "&7Click to restore this inventory to yourself",
                        "",
                        "&eIncludes:",
                        "&7- Main inventory",
                        "&7- Armor & offhand",
                        "&7- Experience",
                        "&7- Ender chest"
                    )
                    .build();

                ItemStack restoreTargetBtn = new ItemBuilder(Material.PLAYER_HEAD)
                    .skullOwner(targetName)
                    .name("&aRestore to " + targetName)
                    .lore(
                        "&7Click to restore to " + targetName,
                        "",
                        "&eIncludes:",
                        "&7- Main inventory",
                        "&7- Armor & offhand",
                        "&7- Experience",
                        "&7- Ender chest"
                    )
                    .build();

                ItemStack restoreInvBtn = new ItemBuilder(Material.CHEST_MINECART)
                    .name("&bRestore Inventory Only")
                    .lore(
                        "&7Click to restore only the main inventory",
                        "&7(Excludes armor and offhand)"
                    )
                    .build();

                ItemStack restoreArmorBtn = new ItemBuilder(Material.IRON_CHESTPLATE)
                    .name("&bRestore Armor Only")
                    .lore(
                        "&7Click to restore only armor and offhand",
                        "&7(Excludes main inventory)"
                    )
                    .build();

                ItemStack restoreExpBtn = new ItemBuilder(Material.EXPERIENCE_BOTTLE)
                    .name("&eRestore Experience Only")
                    .lore(
                        "&7Click to restore only experience",
                        "&7(Excludes inventory and armor)"
                    )
                    .build();

                ItemStack enderChestBtn = new ItemBuilder(Material.ENDER_CHEST)
                    .name("&bEnder Chest Options")
                    .lore(
                        "&7Click to view and restore the saved",
                        "&7ender chest contents"
                    )
                    .build();

                ItemStack teleportBtn = new ItemBuilder(Material.ENDER_PEARL)
                    .name("&dTeleport to Location")
                    .lore(
                        "&7World: &f" + snapshot.getWorldName(),
                        "&7X: &f" + (int) snapshot.getLocation().getX(),
                        "&7Y: &f" + (int) snapshot.getLocation().getY(),
                        "&7Z: &f" + (int) snapshot.getLocation().getZ(),
                        "",
                        "&eClick to teleport to this location"
                    )
                    .build();

                ItemStack backBtn = new ItemBuilder(Material.ARROW)
                    .name("&cBack")
                    .lore("&7Click to go back")
                    .build();

                // Update on main thread
                SchedulerAdapter.runAtEntity(player, () -> {
                    try {
                        // Display the inventory
                        displayInventory(contents, armor);
                        
                        // Add action buttons
                        setItem(45, restoreSelfBtn, this::handleRestoreSelf);
                        setItem(47, restoreInvBtn, this::handleRestoreInventory);
                        setItem(48, restoreArmorBtn, this::handleRestoreArmor);
                        setItem(49, restoreExpBtn, this::handleRestoreExp);
                        setItem(50, teleportBtn, this::handleTeleport);
                        setItem(51, enderChestBtn, this::handleOpenEnderChest);
                        
                        // Only show restore to target if target is not the viewer
                        if (target != null && !target.getUniqueId().equals(player.getUniqueId())) {
                            setItem(46, restoreTargetBtn, this::handleRestoreTarget);
                        }
                        
                        setItem(53, backBtn, e -> previousGUI.open());
                        
                        // Play open sound
                        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
                    } catch (Exception e) {
                        handleError("Error initializing GUI", e);
                    }
                });
                
            } catch (Exception e) {
                handleError("Error loading snapshot data", e);
            }
        });
    }

    private void displayInventory(ItemStack[] contents, ItemStack[] armor) {
        if (contents == null || armor == null) {
            handleError("Inventory data is null", new NullPointerException("Contents or armor is null"), player);
            return;
        }
        try {
            // Display main inventory (3 rows = 27 slots)
            for (int i = 0; i < Math.min(contents.length, 27); i++) {
                setItem(i, contents[i], null);
            }

            // Display armor and offhand
            if (armor.length > 3) setItem(39, armor[3], null); // Helmet
            if (armor.length > 2) setItem(38, armor[2], null); // Chestplate
            if (armor.length > 1) setItem(37, armor[1], null); // Leggings
            if (armor.length > 0) setItem(36, armor[0], null); // Boots
            if (armor.length > 4) setItem(40, armor[4], null); // Offhand
        } catch (Exception e) {
            handleError("Error displaying inventory", e);
        }
    }

    private void handleRestoreSelf(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        player.sendMessage("§aRestoring your full inventory...");
        restoreInventory(player, true, true, true, true);
    }

    private void handleRestoreTarget(InventoryClickEvent event) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            player.sendMessage("§aRestoring " + target.getName() + "'s inventory...");
            restoreInventory(target, true, true, true, true);
        } else {
            player.sendMessage("§cPlayer is no longer online.");
        }
    }

    private void handleRestoreInventory(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        player.sendMessage("§aRestoring your main inventory...");
        restoreInventory(player, true, false, false, false);
    }

    private void handleRestoreArmor(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        player.sendMessage("§aRestoring your armor and offhand...");
        restoreInventory(player, false, true, false, false);
    }

    private void handleRestoreExp(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        player.sendMessage("§aRestoring your experience...");
        restoreInventory(player, false, false, true, false);
    }

    private void handleOpenEnderChest(InventoryClickEvent event) {
        new EnderChestRestoreGUI(player, snapshot, this, plugin).open();
    }

    private void handleTeleport(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        SchedulerAdapter.runAtEntity(player, () -> {
            try {
                player.teleport(snapshot.getLocation());
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                player.sendMessage("§aYou have been teleported to the saved location!");
            } catch (Exception e) {
                handleError("Failed to teleport", e, player);
            }
        });
    }

    private void restoreInventory(Player target, boolean restoreInv, boolean restoreArmor, boolean restoreExp, boolean restoreEnderChest) {
        SchedulerAdapter.runAtEntity(player, () -> {
            try {
                if (restoreInv) {
                    target.getInventory().setContents(snapshot.getInventoryContents());
                }
                if (restoreArmor) {
                    target.getInventory().setArmorContents(Arrays.copyOf(snapshot.getArmorContents(), 4));
                    if (snapshot.getArmorContents().length > 4) {
                        target.getInventory().setItemInOffHand(snapshot.getArmorContents()[4]);
                    }
                    // Restore offhand item if available
                    if (snapshot.getOffhandItem() != null) {
                        target.getInventory().setItemInOffHand(snapshot.getOffhandItem());
                    }
                }
                if (restoreExp) {
                    // Set total experience and calculate proper level and exp bar
                    target.setTotalExperience(snapshot.getExperience());

                    // Calculate and set the correct level and experience bar
                    int totalExp = snapshot.getExperience();
                    int level = calculateLevelFromExperience(totalExp);
                    float expProgress = calculateExpProgress(totalExp, level);

                    target.setLevel(level);
                    target.setExp(expProgress);
                }

                if (restoreEnderChest) {
                    ItemStack[] savedEnderChest = snapshot.getEnderChestContents();
                    target.getEnderChest().setContents(Arrays.copyOf(savedEnderChest, target.getEnderChest().getSize()));
                }

                target.updateInventory();
                target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);

                String message = "§aSuccessfully restored ";
                List<String> parts = new java.util.ArrayList<>();
                if (restoreInv) parts.add("inventory");
                if (restoreArmor) parts.add("armor");
                if (restoreExp) parts.add("experience");
                if (restoreEnderChest) parts.add("ender chest");

                message += String.join(", ", parts) + "!";
                target.sendMessage(message);

                // If restoring someone else's inventory, notify both players
                if (!target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage("§aSuccessfully restored " + target.getName() + "'s " +
                        String.join(", ", parts) + "!");
                }

            } catch (Exception e) {
                handleError("Error restoring inventory", e, target);
            }
        });
    }

    private void handleError(String message, Exception e) {
        handleError(message, e, player);
    }

    private void handleError(String message, Exception e, Player target) {
        Text.sendDebugLog(ERROR, message + ": " + e.getMessage());
        e.printStackTrace();
        if (target != null && target.isOnline()) {
            target.sendMessage("§cAn error occurred: " + e.getMessage());
        }
    }

    private int calculateLevelFromExperience(int totalExp) {
        int level = 0;
        int expForNextLevel = 0;

        while (expForNextLevel <= totalExp) {
            expForNextLevel += getExpForLevel(level);
            level++;
        }

        return Math.max(0, level - 1);
    }

    private float calculateExpProgress(int totalExp, int level) {
        int expForPreviousLevel = 0;
        for (int i = 0; i < level; i++) {
            expForPreviousLevel += getExpForLevel(i);
        }

        int expForCurrentLevel = getExpForLevel(level);
        int expInCurrentLevel = totalExp - expForPreviousLevel;

        return Math.min(1.0f, Math.max(0.0f, (float) expInCurrentLevel / expForCurrentLevel));
    }

    private int getExpForLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }
}