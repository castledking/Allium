package codes.castled.allium.harvest.item;

import codes.castled.allium.harvest.util.Materials;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Resolves {@code minecraft:}/{@code vanilla:} references to materials. */
public final class VanillaItemResolver implements ItemResolver {

    @Override
    public String namespace() {
        return ItemRef.NS_MINECRAFT;
    }

    private Material materialOf(ItemRef ref) {
        Material material = Material.matchMaterial(ref.id().toUpperCase(Locale.ROOT));
        return Materials.isItemOrUnknown(material) ? material : null;
    }

    @Override
    public boolean exists(ItemRef ref) {
        return materialOf(ref) != null;
    }

    @Override
    public Optional<ItemStack> create(ItemRef ref, int amount) {
        Material material = materialOf(ref);
        if (material == null) {
            return Optional.empty();
        }
        return Optional.of(new ItemStack(material, Math.max(1, amount)));
    }

    @Override
    public Optional<String> idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        // A vanilla identification must not claim custom items from other
        // namespaces; the chain queries custom resolvers first.
        return Optional.of(stack.getType().getKey().getKey());
    }
}
