package codes.castled.allium.tradingcards.xp;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

final class TestFishStack extends ItemStack {
    private final Map<NamespacedKey, Object> values = new HashMap<>();
    TestFishStack(String species, Double weight) {
        if (species != null) values.put(new NamespacedKey("litefish", "id"), species);
        if (weight != null) values.put(new NamespacedKey("litefish", "weight"), weight);
    }
    @Override public Material getType() { return Material.COD; }
    @Override public boolean hasItemMeta() { return true; }
    @Override public ItemMeta getItemMeta() {
        PersistentDataContainer pdc = BridgeTestContext.proxy(PersistentDataContainer.class, (p, m, a) -> {
            if (m.getName().equals("get")) {
                Object value = values.get(a[0]);
                return value != null && ((PersistentDataType<?, ?>) a[1]).getPrimitiveType().isInstance(value)
                    ? value : null;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        return BridgeTestContext.proxy(ItemMeta.class, (p, m, a) -> pdc);
    }
}
