package codes.castled.allium.item;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Contract for an item appearance provider (e.g. {@code minecraft}, {@code allium},
 * {@code nexo}, {@code oraxen}, {@code craftengine}).
 */
public interface ItemProviderAdapter {

    /**
     * Canonical lowercase provider namespace (e.g. {@code "minecraft"}, {@code "allium"}, {@code "nexo"}).
     */
    String providerName();

    /**
     * Current readiness status of this provider.
     */
    ProviderStatus status();

    /**
     * Creates a base {@link ItemStack} for the provider-local {@code id} when {@link #status()} is {@link ProviderStatus#READY}.
     *
     * @param id provider-local item ID (for CraftEngine, retains {@code <namespace>:<id>})
     */
    ResolutionResult createItem(String id);

    /**
     * Inspects an {@link ItemStack} using the provider's own item-ID API to determine
     * whether it belongs to this provider, returning the provider-local ID if recognized.
     */
    Optional<String> identifyItemId(ItemStack stack);

    /**
     * Returns true if {@link #identifyItemId(ItemStack)} is backed by the external provider's
     * authoritative item-ID API when {@link #status()} is {@link ProviderStatus#READY}, so that
     * an {@link ItemStack} claiming this provider in PDC without a matching native provider ID
     * is flagged as a provider/PDC disagreement.
     */
    default boolean hasAuthoritativeNativeIdCheck() {
        return false;
    }
}
