package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.InventorySnapshot;
import codes.castled.allium.inventory.OfflineInventoryData;
import codes.castled.allium.inventory.SnapshotEditSession;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.ItemBuilder;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static codes.castled.allium.managers.core.Text.DebugSeverity.ERROR;

public class RestoreOptionsGUI extends SnapshotEditorGUI {
    private final InventorySnapshot snapshot;
    private final RestoreGUI previousGUI;
    private final UUID targetId;
    private SnapshotEditSession session;
    private boolean manualSavePending;

    public RestoreOptionsGUI(Player player, InventorySnapshot snapshot, RestoreGUI previousGUI, PluginStart plugin) {
        super(player, "Restore Options - " + snapshot.getReason(), 6, plugin);
        this.snapshot = snapshot;
        this.previousGUI = previousGUI;
        this.targetId = snapshot.getPlayerId();
    }

    @Override
    protected int storageSlots() {
        return 36;
    }

    @Override
    protected boolean isSnapshotSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot <= 40;
    }

    @Override
    public void initialize() {
        // BaseGUI opens us on the viewer's entity thread. Deserialize item
        // metadata there too, and never redraw the source over session edits.
        if (session != null) {
            return;
        }
        try {
            session = new SnapshotEditSession(snapshot);
            session.displayInventory(inventory);
            addControls();
            player.playSound(player.getLocation(), "minecraft:block.chest.open", 0.5f, 1.0f);
        } catch (Exception e) {
            handleError("Error loading snapshot", e);
        }
    }

    private void addControls() {
        Player target = Bukkit.getPlayer(targetId);
        String targetName = resolveTargetName();
        boolean targetOnline = target != null;
        String targetLabel = targetName + (targetOnline ? "" : " (offline)");
        List<String> includes = new ArrayList<>(List.of(
            "&7Main inventory, armor & offhand,",
            "&7experience and ender chest"));
        if (canEditSession()) {
            includes.add("");
            includes.add("&aUses the items currently in this session");
            includes.add("&7History changes only when you save a Manual copy");
        }

        putControl(45, new ItemBuilder(Material.CHEST)
            .name("&aRestore to Self").lore(includes).build(), this::handleRestoreSelf);
        if (canEditSession()) {
            putControl(46, new ItemBuilder(Material.WRITABLE_BOOK)
                .name("&eSave as Manual Snapshot")
                .lore("&7Save this session as a new &fMANUAL &7snapshot",
                    "&7for &f" + targetName,
                    "&7Keeps the original snapshot unchanged",
                    "", "&7Move items between this panel and your inventory",
                    "&7Restore uses your edits even without saving",
                    "&7Close the viewer to discard unsaved panel edits")
                .build(), this::handleSaveManual);
        }
        putControl(47, new ItemBuilder(Material.CHEST_MINECART)
            .name("&bRestore Inventory Only")
            .lore("&7Restore all 36 storage slots from this session",
                "&7Excludes armor and offhand").build(), event -> restoreInventory(player, true, false, false, false));
        putControl(48, new ItemBuilder(Material.IRON_CHESTPLATE)
            .name("&bRestore Armor Only")
            .lore("&7Restore armor and offhand from this session",
                "&7Excludes main inventory").build(), event -> restoreInventory(player, false, true, false, false));
        putControl(49, new ItemBuilder(Material.EXPERIENCE_BOTTLE)
            .name("&eRestore Experience Only")
            .lore("&7Restore the saved experience").build(), event -> restoreInventory(player, false, false, true, false));
        putControl(50, new ItemBuilder(Material.ENDER_PEARL)
            .name("&dTeleport to Location")
            .lore("&7World: &f" + snapshot.getWorldName(),
                "&7X: &f" + (int) snapshot.getLocation().getX(),
                "&7Y: &f" + (int) snapshot.getLocation().getY(),
                "&7Z: &f" + (int) snapshot.getLocation().getZ(),
                "", "&eClick to teleport to this location").build(), this::handleTeleport);
        putControl(51, new ItemBuilder(Material.ENDER_CHEST)
            .name("&bEnder Chest Options")
            .lore("&7View, edit and restore this session's ender chest").build(), this::handleOpenEnderChest);
        if (!targetId.equals(player.getUniqueId())) {
            List<String> targetLore = new ArrayList<>(includes);
            if (!targetOnline) {
                targetLore.add("&7Applies the next time they join");
            }
            putControl(52, new ItemBuilder(Material.PLAYER_HEAD).skullOwner(targetName)
                .name("&aRestore to " + targetLabel).lore(targetLore).build(), this::handleRestoreTarget);
        }
        putControl(53, new ItemBuilder(Material.ARROW)
            .name("&cBack").lore("&7Return to saved history", "&7Discards unsaved panel edits").build(),
            event -> previousGUI.open());
    }

    private InventorySnapshot sessionSnapshot() {
        session.captureInventory(inventory);
        return session.snapshot();
    }

    private void handleSaveManual(InventoryClickEvent event) {
        session.captureInventory(inventory);
        saveManualCopy();
    }

    /** The ender chest editor captures its panel before using this same save action. */
    void saveManualCopy() {
        if (!canEditSession() || manualSavePending) {
            return;
        }
        try {
            InventorySnapshot manual = session.manualSnapshot();
            manualSavePending = true;
            plugin.getInventoryManager().saveSnapshot(manual).whenComplete((success, error) ->
                SchedulerAdapter.runAtEntity(player, () -> {
                    manualSavePending = false;
                    if (error != null || !Boolean.TRUE.equals(success)) {
                        player.sendMessage("§cCould not save the Manual snapshot. Your session edits are still available.");
                        return;
                    }
                    player.sendMessage("§aSaved a new MANUAL snapshot for " + resolveTargetName() + ".");
                }));
        } catch (Exception e) {
            manualSavePending = false;
            handleError("Error saving Manual snapshot", e);
        }
    }

    private void handleRestoreSelf(InventoryClickEvent event) {
        restoreInventory(player, true, true, true, true);
    }

    private void handleRestoreTarget(InventoryClickEvent event) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            queueOfflineRestore(resolveTargetName(), sessionSnapshot());
        } else {
            restoreInventory(target, true, true, true, true);
        }
    }

    private void handleOpenEnderChest(InventoryClickEvent event) {
        session.captureInventory(inventory);
        new EnderChestRestoreGUI(player, snapshot, session, this, plugin).open();
    }

    private void handleTeleport(InventoryClickEvent event) {
        try {
            player.teleport(snapshot.getLocation());
            player.playSound(player.getLocation(), "minecraft:entity.enderman.teleport", 1.0f, 1.0f);
            player.sendMessage("§aYou have been teleported to the saved location!");
        } catch (Exception e) {
            handleError("Failed to teleport", e);
        }
    }

    private void restoreInventory(Player target, boolean restoreInv, boolean restoreArmor,
                                  boolean restoreExp, boolean restoreEnderChest) {
        // Freeze the viewer's panel before moving to another player's thread.
        InventorySnapshot selected = sessionSnapshot();
        SchedulerAdapter.runAtEntity(target, () -> {
            try {
                OfflineInventoryData override = OfflineInventoryData.fromSnapshot(
                    selected, restoreInv, restoreArmor, restoreExp, restoreEnderChest);
                plugin.getOfflineInventoryManager().applyToOnlinePlayer(target, override);
                target.playSound(target.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.0f);
                String restored = restoredParts(restoreInv, restoreArmor, restoreExp, restoreEnderChest);
                target.sendMessage("§aSuccessfully restored " + restored + "!");
                if (!target.getUniqueId().equals(player.getUniqueId())) {
                    SchedulerAdapter.runAtEntity(player, () -> player.sendMessage(
                        "§aSuccessfully restored " + target.getName() + "'s " + restored + "!"));
                }
            } catch (Exception e) {
                handleError("Error restoring inventory", e);
            }
        });
    }

    private void queueOfflineRestore(String targetName, InventorySnapshot selected) {
        plugin.getOfflineInventoryManager().queueOfflineSnapshotRestore(
            targetId, targetName, selected, true, true, true, true
        ).whenComplete((ignored, error) -> SchedulerAdapter.runAtEntity(player, () -> {
            if (error != null) {
                handleError("Error queueing offline restore", new RuntimeException(error));
            } else {
                player.sendMessage("§aQueued " + targetName + "'s edited snapshot restore for their next login.");
            }
        }));
    }

    private static String restoredParts(boolean inventory, boolean armor, boolean exp, boolean enderChest) {
        List<String> parts = new ArrayList<>();
        if (inventory) parts.add("inventory");
        if (armor) parts.add("armor and offhand");
        if (exp) parts.add("experience");
        if (enderChest) parts.add("ender chest");
        return String.join(", ", parts);
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
