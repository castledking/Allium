package codes.castled.allium.harvest.item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Dispatches item references to the resolver for their namespace. Custom
 * resolvers (Nexo, Oraxen) are queried before the vanilla resolver when
 * identifying held stacks so a custom item is never mistaken for its base
 * material.
 */
public final class ItemResolverChain {

    private final Map<String, ItemResolver> resolvers = new LinkedHashMap<>();
    private final VanillaItemResolver vanilla = new VanillaItemResolver();

    public void register(ItemResolver resolver) {
        resolvers.put(resolver.namespace(), resolver);
    }

    public boolean hasNamespace(String namespace) {
        return ItemRef.NS_MINECRAFT.equals(namespace) || resolvers.containsKey(namespace);
    }

    private ItemResolver resolverFor(ItemRef ref) {
        if (ref.isVanilla()) {
            return vanilla;
        }
        return resolvers.get(ref.namespace());
    }

    public boolean exists(ItemRef ref) {
        ItemResolver resolver = resolverFor(ref);
        return resolver != null && resolver.exists(ref);
    }

    public Optional<ItemStack> create(ItemRef ref, int amount) {
        ItemResolver resolver = resolverFor(ref);
        return resolver == null ? Optional.empty() : resolver.create(ref, amount);
    }

    /**
     * Identifies a stack as a fully qualified reference, preferring custom
     * namespaces over the vanilla material.
     */
    public Optional<ItemRef> identify(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        for (ItemResolver resolver : resolvers.values()) {
            Optional<String> id = resolver.idOf(stack);
            if (id.isPresent()) {
                return Optional.of(new ItemRef(resolver.namespace(), id.get()));
            }
        }
        return vanilla.idOf(stack).map(id -> new ItemRef(ItemRef.NS_MINECRAFT, id));
    }

    /** True if the held stack matches the given reference. */
    public boolean matches(ItemRef ref, ItemStack stack) {
        return identify(stack).map(ref::equals).orElse(false);
    }
}
