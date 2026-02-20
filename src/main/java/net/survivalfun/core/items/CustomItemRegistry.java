package net.survivalfun.core.items;

import net.survivalfun.core.managers.config.CustomItemsConfig;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

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

    public void register(final CustomItem item) {
        if (items.containsKey(item.getId())) {
            throw new IllegalArgumentException("Custom item with ID '" + item.getId() + "' is already registered");
        }
        items.put(item.getId(), item);
        plugin.getLogger().info("Registered custom item: " + item.getId());
    }

    public CustomItem getItem(final String id) {
        return items.get(id.toLowerCase());
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
        return items.containsKey(id.toLowerCase());
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
