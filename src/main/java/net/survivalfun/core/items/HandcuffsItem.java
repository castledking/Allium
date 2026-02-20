package net.survivalfun.core.items;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.survivalfun.core.managers.core.Text;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.datacomponent.DataComponentTypes;   // <-- NEW

import static net.survivalfun.core.managers.core.Text.DebugSeverity.INFO;

import java.util.UUID;

public class HandcuffsItem {

    private static final String HANDCUFFS_KEY = "handcuffs_item";
    private static final NamespacedKey PDC_KEY = new NamespacedKey("allium", HANDCUFFS_KEY);

    /* --------------------------------------------------------------
       Helper – set a custom model with the DataComponent API
       -------------------------------------------------------------- */
    private static ItemStack setItemModel(ItemStack item, String modelName) {
        if (item == null || item.getType() == Material.AIR) return item;

        if (modelName == null || !modelName.contains(":")) {
            Text.sendDebugLog(INFO, "Invalid model name format: " + modelName);
            return item;
        }

        String[] parts = modelName.split(":");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            Text.sendDebugLog(INFO, "Invalid model name format: " + modelName);
            return item;
        }

        try {
            Key modelKey = Key.key(parts[0], parts[1]);
            item.setData(DataComponentTypes.ITEM_MODEL, modelKey);
            return item;
        } catch (Exception e) {
            Text.sendDebugLog(INFO, "Failed to set item model: " + e.getMessage());
            e.printStackTrace();
            return item;
        }
    }

    /* --------------------------------------------------------------
       Creation – now uses DataComponent API for everything
       -------------------------------------------------------------- */
    public static ItemStack createHandcuffs() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);

        // ---- 1. Unbreakable (old meta) ---------------------------------
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        // ---- 2. PDC identifier (still needed for isHandcuffs()) -------
        meta.getPersistentDataContainer().set(PDC_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        // ---- 3. Custom model -------------------------------------------
        item = setItemModel(item, "template:fishing_rod_handcuffs");

        // ---- 4. Optional: make it look like a “halo” (name, slot, etc.) -
        // (you can delete any line you don’t need)
        item.setData(DataComponentTypes.ITEM_NAME,
                Component.text("Handcuffs", NamedTextColor.GOLD));

        // If you ever want the item to be equippable on the head:
        // item.setData(DataComponentTypes.EQUIPPABLE,
        //         Equippable.equippable(EquipmentSlot.HEAD).build());

        // ---- 5. Max stack size = 1 --------------------------------------
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);

        // ---- 6. Add speed reduction attribute -----------------------
        AttributeModifier modifier = new AttributeModifier(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            "handcuffs_speed_reduction",
            -0.5,
            AttributeModifier.Operation.ADD_SCALAR,
            EquipmentSlot.HAND
        );
        
        // Get the item meta and add the attribute modifier
        ItemMeta itemMeta = item.getItemMeta();
        itemMeta.addAttributeModifier(Attribute.MOVEMENT_SPEED, modifier);
        item.setItemMeta(itemMeta);

        return item;
    }

    /* --------------------------------------------------------------
       Identification – still uses PDC (fast & reliable)
       -------------------------------------------------------------- */
    public static boolean isHandcuffs(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(PDC_KEY, PersistentDataType.BYTE);
    }

    /* --------------------------------------------------------------
       Model-update helper – now works on any slot (including off-hand)
       -------------------------------------------------------------- */
    public static void updateHandcuffsModelData(Player player, String modelName) {
        PlayerInventory inv = player.getInventory();
        boolean changed = false;

        // Main inventory
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (isHandcuffs(slot)) {
                ItemStack updated = setItemModel(new ItemStack(slot), modelName);
                if (!slot.isSimilar(updated)) {
                    inv.setItem(i, updated);
                    changed = true;
                }
            }
        }

        // Off-hand
        ItemStack off = inv.getItemInOffHand();
        if (isHandcuffs(off)) {
            ItemStack updated = setItemModel(new ItemStack(off), modelName);
            if (!off.isSimilar(updated)) {
                inv.setItemInOffHand(updated);
                changed = true;
            }
        }

        if (changed) {
            Text.sendDebugLog(INFO, "Updated handcuffs model to " + modelName + " for " + player.getName());
        }
    }
}