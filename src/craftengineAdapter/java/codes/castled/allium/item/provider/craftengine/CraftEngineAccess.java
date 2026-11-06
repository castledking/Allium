package codes.castled.allium.item.provider.craftengine;

import org.bukkit.inventory.ItemStack;

/** Small test seam around the native API, kept entirely in the isolated adapter source set. */
interface CraftEngineAccess {
    record Lifecycle(boolean disabled, boolean reloading, boolean initializing, boolean fullyLoaded) {}

    Lifecycle lifecycle();
    int loadedItemCount();
    ItemStack build(String id);
    String nativeId(ItemStack stack);
}
