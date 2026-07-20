package codes.castled.allium.harvest.item;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Resolves item references of one namespace into concrete item stacks. */
public interface ItemResolver {

    /** The namespace this resolver handles, e.g. {@code nexo}. */
    String namespace();

    /** Whether the referenced item exists right now. */
    boolean exists(ItemRef ref);

    /**
     * Creates a fresh stack for the reference.
     *
     * @return empty if the item does not exist
     */
    Optional<ItemStack> create(ItemRef ref, int amount);

    /**
     * Identifies a held stack, returning the id within this resolver's
     * namespace if the stack belongs to it.
     */
    Optional<String> idOf(ItemStack stack);
}
