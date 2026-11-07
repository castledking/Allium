package codes.castled.allium.item.library;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.spec.RawItemData;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * Applies raw vanilla item data, split by version:
 * <ul>
 *   <li>Before 1.20.5, {@code {...}} NBT is parsed by {@code ItemFactory#createItemStack}
 *       ({@code minecraft:id{...}}) and {@code [...]} components are rejected.</li>
 *   <li>From 1.20.5 (including 26.x), {@code [...]} data components are parsed by
 *       {@code ItemFactory#createItemStack} ({@code minecraft:id[...]}) and {@code {...}} NBT is
 *       rejected.</li>
 * </ul>
 * Raw data applies to vanilla items only: on Allium or provider items it could rewrite the
 * identity and signature tags, so it is refused.
 */
public final class ComponentAdapter {

    private final ServerVersion version;
    private final ItemFactory factory;

    public ComponentAdapter(ServerVersion version, ItemFactory factory) {
        this.version = Objects.requireNonNull(version, "version");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** @throws ItemBuildException with a message for the sender when the input is refused or invalid */
    public ItemStack create(ProviderKey key, RawItemData raw, int amount) {
        if (!key.isVanilla()) {
            throw new ItemBuildException("raw item data (" + (raw.kind() == RawItemData.Kind.COMPONENTS ? "[...] components" : "{...} NBT")
                    + ") applies only to vanilla items, not " + key);
        }
        if (raw.kind() == RawItemData.Kind.COMPONENTS && !version.hasDataComponents()) {
            throw new ItemBuildException("data components [...] require Minecraft 1.20.5+; this server is " + version
                    + ", so write {...} NBT instead");
        }
        if (raw.kind() == RawItemData.Kind.NBT && version.hasDataComponents()) {
            throw new ItemBuildException("Minecraft " + version + " uses [...] data components; {...} NBT is no longer accepted");
        }
        ItemStack stack;
        try {
            stack = factory.createItemStack(key + raw.text());
        } catch (IllegalArgumentException e) {
            throw new ItemBuildException("the server could not parse " + key + raw.text() + ": " + e.getMessage());
        }
        if (stack == null || stack.getType().isAir()) {
            throw new ItemBuildException(key + raw.text() + " produced no item");
        }
        stack.setAmount(amount);
        return stack;
    }
}
