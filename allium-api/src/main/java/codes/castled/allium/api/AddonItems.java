package codes.castled.allium.api;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Item definitions and Allium's item create/identify. */
public interface AddonItems {

    /**
     * Defines {@code allium:<id>} for this addon, with the behavior {@code <addon>:<id>} (the
     * addon's lowercase name). Removed again when the addon disables.
     *
     * @throws IllegalArgumentException if the id is already defined by Allium or another addon
     */
    void define(ItemDefinition definition);

    /**
     * Creates a stack from any item reference Allium understands: {@code allium:<id>},
     * {@code minecraft:<id>} or a bare vanilla name, or a provider reference such as
     * {@code nexo:<id>}.
     */
    ItemResult create(String reference, int amount);

    /** What a stack is; empty for {@code null} or air. */
    Optional<ItemIdentity> identify(@Nullable ItemStack stack);
}
