package codes.castled.allium.harvest.util;

import org.bukkit.Material;

/**
 * Material predicates that stay usable outside a running server.
 *
 * <p>{@link Material#isBlock()} and {@link Material#isItem()} resolve through
 * Paper's registry, which only exists once a server has bootstrapped. Config
 * validation runs in unit tests too, so those checks are treated as "cannot
 * verify" rather than fatal when the registry is absent. Genuine typos are
 * still caught: {@link Material#matchMaterial(String)} is a plain enum lookup
 * and returns {@code null} for unknown names in every environment.
 */
public final class Materials {

    private Materials() {
    }

    /** {@code true} when the material is a placeable block, or blockness cannot be determined. */
    public static boolean isBlockOrUnknown(Material material) {
        if (material == null) {
            return false;
        }
        try {
            return material.isBlock();
        } catch (Throwable registryUnavailable) {
            return true;
        }
    }

    /** {@code true} when the material is obtainable as an item, or itemness cannot be determined. */
    public static boolean isItemOrUnknown(Material material) {
        if (material == null) {
            return false;
        }
        try {
            return material.isItem();
        } catch (Throwable registryUnavailable) {
            return true;
        }
    }
}
