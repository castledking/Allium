package codes.castled.allium.harvest.integration;

import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolver;
import io.th0rgal.oraxen.api.OraxenItems;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves {@code oraxen:} references through Oraxen's public API. Only
 * instantiated when the Oraxen plugin is present.
 */
public final class OraxenItemResolver implements ItemResolver {

    @Override
    public String namespace() {
        return ItemRef.NS_ORAXEN;
    }

    @Override
    public boolean exists(ItemRef ref) {
        return OraxenItems.exists(ref.id());
    }

    @Override
    public Optional<ItemStack> create(ItemRef ref, int amount) {
        io.th0rgal.oraxen.items.ItemBuilder builder = OraxenItems.getItemById(ref.id());
        if (builder == null) {
            return Optional.empty();
        }
        ItemStack stack = builder.build();
        stack.setAmount(Math.max(1, amount));
        return Optional.of(stack);
    }

    @Override
    public Optional<String> idOf(ItemStack stack) {
        return Optional.ofNullable(OraxenItems.getIdByItem(stack));
    }
}
