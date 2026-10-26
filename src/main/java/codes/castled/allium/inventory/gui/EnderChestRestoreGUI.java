package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.InventorySnapshot;
import codes.castled.allium.inventory.SnapshotEditSession;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.ItemBuilder;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

import static codes.castled.allium.managers.core.Text.DebugSeverity.ERROR;

public class EnderChestRestoreGUI extends SnapshotEditorGUI {
    private final SnapshotEditSession session;
    private final RestoreOptionsGUI parent;
    private final UUID targetId;
    private boolean initialized;

    public EnderChestRestoreGUI(Player player, InventorySnapshot snapshot, SnapshotEditSession session,
                               RestoreOptionsGUI parent, PluginStart plugin) {
        super(player, "Ender Chest Restore - " + snapshot.getReason(), 4, plugin);
        this.session = session;
        this.parent = parent;
        this.targetId = snapshot.getPlayerId();
    }

    @Override
    protected int storageSlots() {
        return 27;
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }
        session.displayEnderChest(inventory);
        addControls();
        initialized = true;
        player.playSound(player.getLocation(), "minecraft:block.ender_chest.open", 0.6f, 1.0f);
    }

    private void addControls() {
        Player target = Bukkit.getPlayer(targetId);
        String targetName = resolveTargetName();
        String targetLabel = targetName + (target == null ? " (offline)" : "");
        if (canEditSession()) {
            putControl(28, new ItemBuilder(Material.WRITABLE_BOOK)
                .name("&eSave as Manual Snapshot")
                .lore("&7Save the inventory and ender chest edits",
                    "&7as a new &fMANUAL &7snapshot for &f" + targetName,
                    "&7Keeps the original snapshot unchanged").build(), event -> {
                        session.captureEnderChest(inventory);
                        parent.saveManualCopy();
                    });
        }
        putControl(30, new ItemBuilder(Material.ENDER_EYE)
            .name("&aRestore Ender Chest to Self")
            .lore("&7Restore the ender chest items currently",
                "&7in this session to your ender chest").build(), event -> restoreEnderChest(player));
        if (!targetId.equals(player.getUniqueId())) {
            putControl(32, new ItemBuilder(Material.PLAYER_HEAD).skullOwner(targetName)
                .name("&aRestore Ender Chest to " + targetLabel)
                .lore(target == null ? "&7Applies this session's items on login" : "&7Applies this session's items now")
                .build(), this::handleRestoreTarget);
        }
        putControl(35, new ItemBuilder(Material.ARROW)
            .name("&cBack").lore("&7Return to restore options", "&7Keeps edits in this session").build(), event -> {
                session.captureEnderChest(inventory);
                parent.open();
            });
    }

    private void handleRestoreTarget(InventoryClickEvent event) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            restoreEnderChest(target);
            return;
        }
        session.captureEnderChest(inventory);
        InventorySnapshot selected = session.snapshot();
        String targetName = resolveTargetName();
        plugin.getOfflineInventoryManager().queueOfflineSnapshotRestore(
            targetId, targetName, selected, false, false, false, true
        ).whenComplete((ignored, error) -> SchedulerAdapter.runAtEntity(player, () -> {
            if (error != null) {
                handleError("Error queueing ender chest restore", new RuntimeException(error));
            } else {
                player.sendMessage("§aQueued " + targetName + "'s edited ender chest restore for their next login.");
            }
        }));
    }

    private void restoreEnderChest(Player target) {
        session.captureEnderChest(inventory);
        ItemStack[] selected = session.data().enderChest();
        SchedulerAdapter.runAtEntity(target, () -> {
            try {
                target.getEnderChest().setContents(selected);
                target.updateInventory();
                target.playSound(target.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.1f);
                target.sendMessage("§aYour ender chest has been restored from this session!");
                if (!target.getUniqueId().equals(player.getUniqueId())) {
                    SchedulerAdapter.runAtEntity(player, () -> player.sendMessage(
                        "§aSuccessfully restored " + target.getName() + "'s ender chest!"));
                }
            } catch (Exception e) {
                handleError("Error restoring ender chest", e);
            }
        });
    }

    private String resolveTargetName() {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            return target.getName();
        }
        String name = Bukkit.getOfflinePlayer(targetId).getName();
        return name == null ? "Unknown" : name;
    }

    private void handleError(String message, Exception e) {
        Text.sendDebugLog(ERROR, message, e);
        if (player.isOnline()) {
            player.sendMessage("§c" + message + ". Please check the server log.");
        }
    }
}
