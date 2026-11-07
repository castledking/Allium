package codes.castled.allium.item.library;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code <enchantment>:<level>} tokens ({@code sharpness:5}) through the Bukkit enchantment
 * registry. Enchanted books get stored enchantments. Levels above the vanilla maximum (up to 255)
 * and enchantments that do not normally apply are allowed, with a warning.
 */
public final class EnchantmentAdapter {

    public static final int MAX_LEVEL = 255;

    private EnchantmentAdapter() {
    }

    public static Optional<Enchantment> find(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        return namespaced == null ? Optional.empty() : Optional.ofNullable(Registry.ENCHANTMENT.get(namespaced));
    }

    /** @return a warning, or {@code null} */
    public static String apply(ItemStack stack, ItemMeta meta, Enchantment enchantment, String rawLevel) {
        int level;
        try {
            level = Integer.parseInt(rawLevel);
        } catch (NumberFormatException e) {
            throw new ItemBuildException("enchantment level '" + rawLevel + "' is not a number");
        }
        if (level < 1 || level > MAX_LEVEL) {
            throw new ItemBuildException("enchantment level must be 1-" + MAX_LEVEL);
        }
        if (meta instanceof EnchantmentStorageMeta book) {
            book.addStoredEnchant(enchantment, level, true);
            return null;
        }
        meta.addEnchant(enchantment, level, true);
        String name = enchantment.getKey().getKey();
        if (!enchantment.canEnchantItem(stack)) {
            return name + " does not normally apply to " + stack.getType().name().toLowerCase(Locale.ROOT);
        }
        if (level > enchantment.getMaxLevel()) {
            return name + " " + level + " is above its vanilla maximum of " + enchantment.getMaxLevel();
        }
        return null;
    }
}
