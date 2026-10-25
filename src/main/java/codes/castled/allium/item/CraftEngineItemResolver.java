package codes.castled.allium.item;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves references of one CraftEngine pack namespace, e.g. {@code default}.
 *
 * <p>CraftEngine ids are namespaced by the pack that defines them, not by the
 * plugin, so every loaded pack namespace gets its own resolver. This class
 * touches CraftEngine classes directly, so it must only be instantiated once
 * CraftEngine has loaded its packs (see {@code CraftEngineItemsGate}).
 */
public final class CraftEngineItemResolver implements ItemResolver {

    private final String namespace;

    public CraftEngineItemResolver(String namespace) {
        this.namespace = namespace;
    }

    /**
     * The namespaces CraftEngine currently knows about, taken from the loaded
     * items and blocks.
     */
    public static Set<String> loadedNamespaces() {
        Set<String> namespaces = new LinkedHashSet<>();
        collectNamespaces(CraftEngineItems.loadedItems().keySet(), namespaces);
        collectNamespaces(CraftEngineBlocks.loadedBlocks().keySet(), namespaces);
        namespaces.remove(ItemRef.NS_MINECRAFT);
        return namespaces;
    }

    private static void collectNamespaces(Iterable<Key> keys, Set<String> into) {
        for (Key key : keys) {
            into.add(key.namespace());
        }
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public boolean exists(ItemRef ref) {
        return CraftEngineItems.byId(Key.of(namespace, ref.id())) != null;
    }

    @Override
    public Optional<ItemStack> create(ItemRef ref, int amount) {
        BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(namespace, ref.id()));
        if (definition == null) {
            return Optional.empty();
        }
        ItemStack stack = definition.buildBukkitItem();
        stack.setAmount(Math.max(1, amount));
        return Optional.of(stack);
    }

    @Override
    public Optional<String> idOf(ItemStack stack) {
        Key key = CraftEngineItems.getCustomItemId(stack);
        if (key == null || !namespace.equals(key.namespace())) {
            return Optional.empty();
        }
        return Optional.of(key.value());
    }
}
