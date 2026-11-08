package codes.castled.allium.messaging;

import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/**
 * Whether a player is vanished. The default reads the {@code vanished} player metadata that
 * common vanish plugins (SuperVanish, PremiumVanish, Essentials and others) set; Allium's own
 * vanish plugs in later.
 */
@FunctionalInterface
public interface VanishSource {

    VanishSource METADATA = player -> {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    };

    boolean isVanished(Player player);

    /** Whether {@code target} is hidden from {@code viewer} (before any see-vanished permission). */
    default boolean hiddenFrom(Player viewer, Player target) {
        return !viewer.canSee(target) || isVanished(target);
    }
}
