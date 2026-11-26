package codes.castled.allium.api;

import org.bukkit.inventory.ItemStack;

/** Bukkit service for resolving stable Allium products in external shops. IDs omit the allium namespace. */
public interface TradeItemCatalog {
    boolean contains(String id);
    ItemStack create(String id);
    String identify(ItemStack item);
}
