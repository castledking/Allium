package net.survivalfun.core.listeners.items;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.Map;

public final class OraxenSmeltingListener implements Listener {

    private static final NamespacedKey ORAXEN_ID_KEY = new NamespacedKey("oraxen", "id");

    private static final Map<String, String> SMELT_OVERRIDES = Map.of(
            "raw_galena", "galena_ingot",
            "raw_silver", "silver_ingot"
    );

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        final String sourceId = getOraxenId(event.getSource());
        if (sourceId == null) {
            return;
        }

        final String resultId = SMELT_OVERRIDES.get(sourceId);
        if (resultId == null) {
            return;
        }

        final ItemStack result = buildOraxenItem(resultId);
        if (result != null) {
            event.setResult(result);
        }
    }

    private String getOraxenId(final ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return null;
        }

        return item.getItemMeta().getPersistentDataContainer().get(ORAXEN_ID_KEY, PersistentDataType.STRING);
    }

    private ItemStack buildOraxenItem(final String itemId) {
        if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) {
            return null;
        }

        try {
            final Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            final Method getItemById = oraxenItemsClass.getMethod("getItemById", String.class);
            final Object itemBuilder = getItemById.invoke(null, itemId);
            if (itemBuilder == null) {
                return null;
            }

            final Method build = itemBuilder.getClass().getMethod("build");
            final ItemStack built = (ItemStack) build.invoke(itemBuilder);
            if (built == null) {
                return null;
            }

            try {
                final Class<?> itemUpdaterClass = Class.forName("io.th0rgal.oraxen.items.ItemUpdater");
                final Method updateItem = itemUpdaterClass.getMethod("updateItem", ItemStack.class);
                return (ItemStack) updateItem.invoke(null, built);
            } catch (ReflectiveOperationException ignored) {
                return built;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
