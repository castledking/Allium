package net.survivalfun.core.managers.core;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Manager for armor-related logic (equipping, slot checks).
 * Used by Give command for -e/equip and armor slot handling.
 */
public class Armor {

    /**
     * Checks if the given material is an armor item (helmet, chestplate, leggings, boots, elytra).
     */
    public static boolean isArmor(Material material) {
        if (material == null) return false;

        String name = material.name();
        return name.endsWith("_HELMET") ||
                name.endsWith("_CHESTPLATE") ||
                name.endsWith("_LEGGINGS") ||
                name.endsWith("_BOOTS") ||
                material == Material.TURTLE_HELMET ||
                material == Material.ELYTRA;
    }

    /**
     * Checks if an armor slot is empty (null, AIR, or empty ItemStack).
     */
    public static boolean isArmorSlotEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.isEmpty();
    }

    /**
     * Attempts to equip an armor item in the appropriate slot.
     *
     * @param inventory The player's inventory
     * @param armorItem The armor item to equip
     * @return true if the armor was equipped, false otherwise
     */
    public static boolean tryEquipArmor(PlayerInventory inventory, ItemStack armorItem) {
        if (armorItem == null) return false;

        Material material = armorItem.getType();

        if (material.name().endsWith("_HELMET") || material.name().equals("TURTLE_HELMET")) {
            if (isArmorSlotEmpty(inventory.getHelmet())) {
                inventory.setHelmet(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_CHESTPLATE") || material.name().equals("ELYTRA")) {
            if (isArmorSlotEmpty(inventory.getChestplate())) {
                inventory.setChestplate(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_LEGGINGS")) {
            if (isArmorSlotEmpty(inventory.getLeggings())) {
                inventory.setLeggings(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_BOOTS")) {
            if (isArmorSlotEmpty(inventory.getBoots())) {
                inventory.setBoots(armorItem);
                return true;
            }
        }

        return false;
    }
}
