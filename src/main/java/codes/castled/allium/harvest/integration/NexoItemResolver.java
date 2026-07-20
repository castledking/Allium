package codes.castled.allium.harvest.integration;

import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolver;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves {@code nexo:} references through Nexo's public API. This class
 * touches Nexo classes directly, so it must only be instantiated after the
 * Nexo plugin has been confirmed present (see {@code HarvestModule}). Items
 * are produced via {@link NexoItems#itemFromId}, which carries whatever model
 * mechanism the pack uses (CustomModelData or modern item-model components).
 */
public final class NexoItemResolver implements ItemResolver {

    @Override
    public String namespace() {
        return ItemRef.NS_NEXO;
    }

    @Override
    public boolean exists(ItemRef ref) {
        return NexoItems.exists(ref.id());
    }

    @Override
    public Optional<ItemStack> create(ItemRef ref, int amount) {
        ItemBuilder builder = NexoItems.itemFromId(ref.id());
        if (builder == null) {
            return Optional.empty();
        }
        ItemStack stack = builder.build();
        stack.setAmount(Math.max(1, amount));
        return Optional.of(stack);
    }

    @Override
    public Optional<String> idOf(ItemStack stack) {
        return Optional.ofNullable(NexoItems.idFromItem(stack));
    }
}
