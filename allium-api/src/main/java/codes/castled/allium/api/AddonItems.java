package codes.castled.allium.api;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Item definitions and Allium's item create/identify. */
public interface AddonItems {

    /**
     * Defines {@code allium:<id>} for this addon, with the behavior {@code <addon>:<id>} (the
     * addon's lowercase name). Removed again when the addon disables.
     *
     * @throws IllegalArgumentException if the id is already defined by Allium or another addon
     */
    void define(ItemDefinition definition);

    /** Defines a stackable item whose identity always requires a server signature. */
    default void defineStackableSigned(ItemDefinition definition) {
        throw new UnsupportedOperationException("Stackable signing is unavailable");
    }

    /** Registers the accepted names and initializer for {@code allium:<id>;<variant>} in /give and /i. */
    default void defineVariants(String id, Collection<String> names, BiConsumer<ItemStack, String> initialize) {
        throw new UnsupportedOperationException("Item variants are unavailable");
    }

    /** Adds presentation metadata to newly created copies of an item owned by this addon. */
    default void decorate(String id, Consumer<ItemStack> decoration) {
        throw new UnsupportedOperationException("Item decoration is unavailable");
    }

    /** Registers a namespaced native recipe. Exact custom ingredients keep their signed identity. */
    default void registerRecipe(Recipe recipe) {
        throw new UnsupportedOperationException("Addon recipes are unavailable");
    }

    /** Makes an owned item sellable through Allium Worth at this unit price. */
    default void setWorth(String id, BigDecimal price) {
        throw new UnsupportedOperationException("Addon prices are unavailable");
    }

    /**
     * Creates a stack from any item reference Allium understands: {@code allium:<id>},
     * {@code minecraft:<id>} or a bare vanilla name, or a provider reference such as
     * {@code nexo:<id>}.
     */
    ItemResult create(String reference, int amount);

    /** Updates an item owned by this addon after identity and signature verification. */
    default boolean updateState(ItemStack stack, Map<String, String> changes) {
        return false;
    }

    /** What a stack is; empty for {@code null} or air. */
    Optional<ItemIdentity> identify(@Nullable ItemStack stack);
}
