package net.survivalfun.core.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;

public class Item {

    private static final List<Material> giveableItems = new ArrayList<>();

    public static void initialize() {
        // Iterate through all Material enum values
        for (Material material : Material.values()) {
            try {
                // Attempt to create an ItemStack
                new ItemStack(material, 1);
                // If successful, add to the cache
                giveableItems.add(material);
            } catch (IllegalArgumentException e) {
                // Ignore, this material is not giveable
            }
        }
    }

    public static List<Material> getGiveableItems() {
        return giveableItems;
    }


public static boolean isGiveable(Material material) {
        return giveableItems.contains(material);
    }
}