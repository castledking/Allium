package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/** Editable snapshot cells with a protected toolbar and controlled bulk moves. */
public abstract class SnapshotEditorGUI extends BaseGUI {
    protected static final String EDIT_PERMISSION = "allium.restore.edit";

    protected SnapshotEditorGUI(Player player, String title, int rows, PluginStart plugin) {
        super(player, title, rows, plugin);
    }

    protected abstract int storageSlots();

    protected boolean isSnapshotSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < storageSlots();
    }

    protected final boolean canEditSession() {
        return player.hasPermission(EDIT_PERMISSION);
    }

    @Override
    protected boolean shouldCancelClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        return !canEditSession() || !(isSnapshotSlot(slot) || (slot >= size && slot < size + 36));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (shouldCancelClick(event)) {
            super.handleClick(event);
            return;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            int rawSlot = event.getRawSlot();
            int sourceSlot = event.getSlot();
            Inventory source = event.getClickedInventory();
            SchedulerAdapter.runAtEntity(player, () -> {
                if (player.getOpenInventory().getTopInventory() != inventory || !canEditSession() || source == null) {
                    return;
                }
                // Read the slot when the task runs: a held key or repeated
                // packet cannot transfer the same captured stack twice.
                Inventory destination = rawSlot < size ? player.getInventory() : inventory;
                int destinationSlots = rawSlot < size ? 36 : storageSlots();
                moveStack(source, sourceSlot, destination, destinationSlots);
                player.updateInventory();
            });
        } else if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            SchedulerAdapter.runAtEntity(player, this::collectEditableItems);
        }
        // Ordinary clicks, hotbar swaps and drops use vanilla's transaction.
        // They can touch only content cells or the editor's own inventory.
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        if (!canEditSession() || event.getRawSlots().stream().anyMatch(slot ->
                !isSnapshotSlot(slot) && !(slot >= size && slot < size + 36))) {
            event.setCancelled(true);
        }
    }

    protected final void putControl(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
        inventory.setItem(slot, item);
        if (handler == null) {
            clickHandlers.remove(slot);
        } else {
            clickHandlers.put(slot, handler);
        }
    }

    private void collectEditableItems() {
        if (player.getOpenInventory().getTopInventory() != inventory || !canEditSession()) {
            return;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (empty(cursor)) {
            return;
        }
        cursor = cursor.clone();
        for (int slot = 0; slot < size && cursor.getAmount() < cursor.getMaxStackSize(); slot++) {
            if (isSnapshotSlot(slot)) {
                collect(inventory, slot, cursor);
            }
        }
        for (int slot = 0; slot < 36 && cursor.getAmount() < cursor.getMaxStackSize(); slot++) {
            collect(player.getInventory(), slot, cursor);
        }
        player.setItemOnCursor(cursor);
        player.updateInventory();
    }

    private static void collect(Inventory source, int slot, ItemStack cursor) {
        ItemStack stack = source.getItem(slot);
        if (empty(stack) || !cursor.isSimilar(stack)) {
            return;
        }
        int taken = Math.min(stack.getAmount(), cursor.getMaxStackSize() - cursor.getAmount());
        ItemStack remainder = stack.clone();
        remainder.setAmount(stack.getAmount() - taken);
        source.setItem(slot, remainder.getAmount() == 0 ? null : remainder);
        cursor.setAmount(cursor.getAmount() + taken);
    }

    private static void moveStack(Inventory source, int sourceSlot, Inventory destination, int slots) {
        ItemStack sourceItem = source.getItem(sourceSlot);
        if (empty(sourceItem)) {
            return;
        }
        ItemStack remainder = sourceItem.clone();
        int maxStack = Math.min(remainder.getMaxStackSize(), destination.getMaxStackSize());
        for (int pass = 0; pass < 2 && remainder.getAmount() > 0; pass++) {
            for (int slot = 0; slot < slots && remainder.getAmount() > 0; slot++) {
                ItemStack existing = destination.getItem(slot);
                if (pass == 0 && !empty(existing) && remainder.isSimilar(existing)) {
                    int moved = Math.min(remainder.getAmount(), Math.max(0, maxStack - existing.getAmount()));
                    if (moved > 0) {
                        ItemStack merged = existing.clone();
                        merged.setAmount(existing.getAmount() + moved);
                        destination.setItem(slot, merged);
                        remainder.setAmount(remainder.getAmount() - moved);
                    }
                } else if (pass == 1 && empty(existing)) {
                    int moved = Math.min(remainder.getAmount(), maxStack);
                    ItemStack placed = remainder.clone();
                    placed.setAmount(moved);
                    destination.setItem(slot, placed);
                    remainder.setAmount(remainder.getAmount() - moved);
                }
            }
        }
        source.setItem(sourceSlot, remainder.getAmount() == 0 ? null : remainder);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getAmount() <= 0 || item.isEmpty();
    }
}
