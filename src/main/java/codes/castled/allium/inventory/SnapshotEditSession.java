package codes.castled.allium.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** A window's working copy. Only an explicit MANUAL save adds database history. */
public final class SnapshotEditSession {
    private final InventorySnapshot source;
    private OfflineInventoryData data;

    public SnapshotEditSession(InventorySnapshot source) {
        this.source = source;
        this.data = OfflineInventoryData.fromSnapshot(source, true, true, true, true);
    }

    /** Storage rows first, then the hotbar, then boots through helmet/offhand. */
    public void displayInventory(Inventory panel) {
        for (int slot = 0; slot < 36; slot++) {
            panel.setItem(slot, copy(data.inventory()[storageIndex(slot)]));
        }
        for (int slot = 0; slot < 4; slot++) {
            panel.setItem(36 + slot, copy(data.armor()[slot]));
        }
        panel.setItem(40, copy(data.offhand()));
    }

    public void captureInventory(Inventory panel) {
        ItemStack[] storage = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        for (int slot = 0; slot < 36; slot++) {
            storage[storageIndex(slot)] = panel.getItem(slot);
        }
        for (int slot = 0; slot < 4; slot++) {
            armor[slot] = panel.getItem(36 + slot);
        }
        data = new OfflineInventoryData(storage, armor, panel.getItem(40), data.enderChest(), data.experience());
    }

    public void displayEnderChest(Inventory panel) {
        for (int slot = 0; slot < 27; slot++) {
            panel.setItem(slot, copy(data.enderChest()[slot]));
        }
    }

    public void captureEnderChest(Inventory panel) {
        ItemStack[] enderChest = new ItemStack[27];
        for (int slot = 0; slot < enderChest.length; slot++) {
            enderChest[slot] = panel.getItem(slot);
        }
        data = new OfflineInventoryData(data.inventory(), data.armor(), data.offhand(), enderChest, data.experience());
    }

    public OfflineInventoryData data() {
        return new OfflineInventoryData(data.inventory(), data.armor(), data.offhand(), data.enderChest(), data.experience());
    }

    public InventorySnapshot snapshot() {
        return snapshot(source.getTimestamp(), source.getReason());
    }

    public InventorySnapshot manualSnapshot() {
        return snapshot(Math.max(System.currentTimeMillis(), source.getTimestamp() + 1), "MANUAL");
    }

    private InventorySnapshot snapshot(long timestamp, String reason) {
        ItemStack[] contents = new ItemStack[41];
        System.arraycopy(data.inventory(), 0, contents, 0, 36);
        System.arraycopy(data.armor(), 0, contents, 36, 4);
        contents[40] = data.offhand();
        return new InventorySnapshot(source.getPlayerId(), timestamp, reason,
            InventorySnapshot.toBase64(contents), InventorySnapshot.toBase64(data.armor()),
            InventorySnapshot.toBase64(new ItemStack[]{data.offhand()}),
            InventorySnapshot.toBase64(data.enderChest()), data.experience(),
            source.getLocation().clone(), source.getWorldName());
    }

    private static int storageIndex(int displaySlot) {
        return displaySlot < 27 ? displaySlot + 9 : displaySlot - 27;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
