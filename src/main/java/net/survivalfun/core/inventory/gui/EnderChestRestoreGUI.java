package net.survivalfun.core.inventory.gui;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventorySnapshot;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.util.ItemBuilder;
import net.survivalfun.core.util.SchedulerAdapter;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Arrays;
import java.util.UUID;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.ERROR;

public class EnderChestRestoreGUI extends BaseGUI {
    private final InventorySnapshot snapshot;
    private final RestoreOptionsGUI parent;
    private final UUID targetId;

    public EnderChestRestoreGUI(Player player, InventorySnapshot snapshot, RestoreOptionsGUI parent, PluginStart plugin) {
        super(player, "Ender Chest Restore - " + snapshot.getReason(), 4, plugin);
        this.snapshot = snapshot;
        this.parent = parent;
        this.targetId = snapshot.getPlayerId();
    }

    @Override
    public void initialize() {
        SchedulerAdapter.runAtEntity(player, () -> {
            try {
                displayEnderChestContents(snapshot.getEnderChestContents());
                addControls();
                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.0f);
            } catch (Exception e) {
                handleError("Error initializing ender chest GUI", e, player);
            }
        });
    }

    private void displayEnderChestContents(ItemStack[] contents) {
        for (int i = 0; i < Math.min(contents.length, 27); i++) {
            setItem(i, contents[i], null);
        }
    }

    private void addControls() {
        Player target = Bukkit.getPlayer(targetId);
        String targetName;
        if (target != null) {
            targetName = target.getName();
        } else {
            targetName = Bukkit.getOfflinePlayer(targetId).getName();
            if (targetName == null) {
                targetName = "Unknown";
            }
        }

        ItemStack restoreSelfBtn = new ItemBuilder(Material.ENDER_EYE)
            .name("&aRestore Ender Chest to Self")
            .lore(
                "&7Restores the saved ender chest",
                "&7contents to your ender chest"
            )
            .build();

        ItemStack restoreTargetBtn = new ItemBuilder(Material.PLAYER_HEAD)
            .skullOwner(targetName)
            .name("&aRestore Ender Chest to " + targetName)
            .lore(
                "&7Restores the saved ender chest",
                "&7contents to " + targetName
            )
            .build();

        ItemStack backBtn = new ItemBuilder(Material.ARROW)
            .name("&cBack")
            .lore("&7Return to restore options")
            .build();

        setItem(30, restoreSelfBtn, this::handleRestoreSelf);

        if (target != null && !target.getUniqueId().equals(player.getUniqueId())) {
            setItem(32, restoreTargetBtn, this::handleRestoreTarget);
        }

        setItem(35, backBtn, event -> parent.open());
    }

    private void handleRestoreSelf(InventoryClickEvent event) {
        Player clicker = (Player) event.getWhoClicked();
        clicker.sendMessage("§aRestoring saved ender chest contents...");
        restoreEnderChest(clicker);
    }

    private void handleRestoreTarget(InventoryClickEvent event) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            player.sendMessage("§cTarget player is no longer online.");
            return;
        }
        player.sendMessage("§aRestoring saved ender chest contents to " + target.getName() + "...");
        restoreEnderChest(target);
    }

    private void restoreEnderChest(Player target) {
        SchedulerAdapter.runAtEntity(target, () -> {
            try {
                ItemStack[] saved = snapshot.getEnderChestContents();
                target.getEnderChest().setContents(Arrays.copyOf(saved, target.getEnderChest().getSize()));
                target.updateInventory();
                target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.1f);
                target.sendMessage("§aYour ender chest has been restored from the snapshot!");
                if (!target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage("§aSuccessfully restored " + target.getName() + "'s ender chest!");
                }
            } catch (Exception e) {
                handleError("Error restoring ender chest", e, target);
            }
        });
    }

    private void handleError(String message, Exception e, Player notifyTarget) {
        Text.sendDebugLog(ERROR, message + ": " + e.getMessage());
        if (notifyTarget != null && notifyTarget.isOnline()) {
            notifyTarget.sendMessage("§cAn error occurred: " + e.getMessage());
        }
    }
}
