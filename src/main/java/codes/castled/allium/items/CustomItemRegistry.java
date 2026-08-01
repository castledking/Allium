package codes.castled.allium.items;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import codes.castled.allium.managers.config.CustomItemsConfig;

import java.util.Collection;
import java.util.*;

public class CustomItemRegistry {

    private static CustomItemRegistry instance;
    private final Plugin plugin;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();

    public CustomItemRegistry(final Plugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static CustomItemRegistry getInstance() {
        return instance;
    }

    /**
     * Registers an item, replacing any existing registration under the same id.
     * <p>
     * Ids are keyed lowercase to match {@link #getItem(String)}; registering under the raw id left
     * any id containing a capital permanently unreachable. Replacing rather than throwing is what
     * makes a reload — which re-runs {@link #loadFromConfig()} — survivable.
     */
    public void register(final CustomItem item) {
        final String id = item.getId().toLowerCase();
        final boolean replaced = items.put(id, item) != null;
        plugin.getLogger().info((replaced ? "Re-registered" : "Registered") + " custom item: " + id);
    }

    /** Drops every registration. Call before reloading definitions from disk. */
    public void unregisterAll() {
        items.clear();
    }

    public boolean unregister(final String id) {
        return id != null && items.remove(id.toLowerCase()) != null;
    }

    public CustomItem getItem(final String id) {
        return id == null ? null : items.get(id.toLowerCase());
    }

    public CustomItem getItem(final ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }

        for (final CustomItem item : items.values()) {
            if (item.isThisItem(itemStack)) {
                return item;
            }
        }
        return null;
    }

    public boolean isCustomItem(final ItemStack itemStack) {
        return getItem(itemStack) != null;
    }

    public Collection<CustomItem> getAllItems() {
        return items.values();
    }

    public Set<String> getAllItemIds() {
        return items.keySet();
    }

    public boolean hasItem(final String id) {
        return id != null && items.containsKey(id.toLowerCase());
    }

    public int getItemCount() {
        return items.size();
    }

    public void loadFromConfig() {
        if (!CustomItemsConfig.isLoaded()) {
            CustomItemsConfig.initialize((JavaPlugin) plugin);
        }

        Set<String> configIds = CustomItemsConfig.getItemIds();
        for (String id : configIds) {
            CustomItemsConfig.MaterialDefinition def = CustomItemsConfig.getItemDefinition(id);
            if (def != null) {
                CustomItem item = CustomItem.fromConfigDefinition(plugin, def);
                register(item);
            }
        }
        
        plugin.getLogger().info("Loaded " + items.size() + " custom items from config");
    }
}
