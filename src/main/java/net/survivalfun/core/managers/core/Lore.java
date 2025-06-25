package net.survivalfun.core.managers.core;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;
import java.util.ArrayList;


public class Lore {

    public static List<String> getLore(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new ArrayList<>(); // Return an empty list
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) { // Extra safety check, though unlikely
            return new ArrayList<>();
        }

        if (!meta.hasLore()) {
            return new ArrayList<>(); // No lore, return an empty list
        }

        List<String> lore = meta.getLore(); // No longer deprecated because of the checks above

        if (lore == null) {  // Final check, though extremely unlikely
            return new ArrayList<>();
        }

        return lore;
    }

    public static void setLore(ItemStack item, List<String> lore) {
        if (item == null) {
            return; // Or throw an exception, depending on your needs
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return; // Or throw an exception
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }
}
