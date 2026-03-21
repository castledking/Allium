package net.survivalfun.core.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

public record OfflineInventoryData(
    ItemStack[] inventory,
    ItemStack[] armor,
    ItemStack offhand,
    ItemStack[] enderChest,
    Integer experience
) {
    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    private static final int ENDER_CHEST_SIZE = 27;

    public OfflineInventoryData {
        inventory = cloneArray(inventory);
        armor = cloneArray(armor);
        offhand = cloneItem(offhand);
        enderChest = cloneArray(enderChest);
    }

    public static OfflineInventoryData fromPlayer(Player player) {
        return new OfflineInventoryData(
            resize(player.getInventory().getStorageContents(), STORAGE_SIZE),
            resize(player.getInventory().getArmorContents(), ARMOR_SIZE),
            cloneItem(player.getInventory().getItemInOffHand()),
            resize(player.getEnderChest().getContents(), ENDER_CHEST_SIZE),
            player.getTotalExperience()
        );
    }

    public static OfflineInventoryData fromSnapshot(InventorySnapshot snapshot,
                                                    boolean restoreInventory,
                                                    boolean restoreArmor,
                                                    boolean restoreExperience,
                                                    boolean restoreEnderChest) {
        ItemStack[] snapshotArmor = snapshot.getArmorContents();
        ItemStack snapshotOffhand = snapshot.getOffhandItem();
        if (snapshotOffhand == null && snapshotArmor.length > ARMOR_SIZE) {
            snapshotOffhand = snapshotArmor[ARMOR_SIZE];
        }

        return new OfflineInventoryData(
            restoreInventory ? extractStorage(snapshot.getInventoryContents()) : null,
            restoreArmor ? resize(snapshotArmor, ARMOR_SIZE) : null,
            restoreArmor ? snapshotOffhand : null,
            restoreEnderChest ? resize(snapshot.getEnderChestContents(), ENDER_CHEST_SIZE) : null,
            restoreExperience ? snapshot.getExperience() : null
        );
    }

    public OfflineInventoryData merge(OfflineInventoryData override) {
        if (override == null) {
            return this;
        }
        return new OfflineInventoryData(
            override.inventory != null ? override.inventory : inventory,
            override.armor != null ? override.armor : armor,
            override.armor != null ? override.offhand : (override.offhand != null ? override.offhand : offhand),
            override.enderChest != null ? override.enderChest : enderChest,
            override.experience != null ? override.experience : experience
        );
    }

    public static ItemStack[] extractStorage(ItemStack[] contents) {
        return resize(contents, STORAGE_SIZE);
    }

    public static ItemStack[] resize(ItemStack[] items, int size) {
        ItemStack[] resized = Arrays.copyOf(items == null ? new ItemStack[0] : items, size);
        for (int i = 0; i < resized.length; i++) {
            resized[i] = cloneItem(resized[i]);
        }
        return resized;
    }

    private static ItemStack[] cloneArray(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        ItemStack[] clone = Arrays.copyOf(items, items.length);
        for (int i = 0; i < clone.length; i++) {
            clone[i] = cloneItem(clone[i]);
        }
        return clone;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
