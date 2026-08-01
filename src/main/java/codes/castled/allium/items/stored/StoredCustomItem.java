package codes.castled.allium.items.stored;

import java.util.Collections;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;

/**
 * Adapts a {@link StoredItem} to the {@link CustomItem} contract so stored items resolve through
 * the existing registry — which is what makes {@code /give <player> ci:<id>} and {@code /i ci:<id>}
 * work with no changes to the give pipeline.
 */
public final class StoredCustomItem extends CustomItem {

    private final StoredItem stored;

    public StoredCustomItem(Plugin plugin, StoredItem stored) {
        super(plugin, stored.getId());
        this.stored = stored;
    }

    public StoredItem getStored() {
        return stored;
    }

    @Override
    public Material getMaterial() {
        return stored.getMaterial();
    }

    @Override
    public String getDisplayName() {
        ItemStack probe = stored.toItemStack(1);
        ItemMeta meta = probe.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return stored.getId();
    }

    @Override
    public List<String> getLore() {
        ItemStack probe = stored.toItemStack(1);
        ItemMeta meta = probe.getItemMeta();
        List<String> lore = meta == null ? null : meta.getLore();
        return lore == null ? Collections.emptyList() : lore;
    }

    @Override
    public String getTextureType() {
        // The snapshot already carries whatever model or custom-model-data the source item had, so
        // there is nothing for CustomItem's texture handling to re-apply.
        return null;
    }

    /**
     * Rebuilds the item from its snapshot.
     * <p>
     * Overrides the superclass entirely rather than extending it: the base implementation composes an
     * item from name/lore/model fields, which would discard everything else the snapshot holds.
     */
    @Override
    public ItemStack createItemStack(final int amount) {
        ItemStack item = stored.toItemStack(amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Stamps the id so the stack can be resolved back to its definition later — this is how
            // the give path recovers per-item options such as notify-receivers.
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, getId());
            item.setItemMeta(meta);
        }
        return item;
    }
}
