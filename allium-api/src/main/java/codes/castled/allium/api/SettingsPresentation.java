package codes.castled.allium.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Optional Bukkit service for graphical entry points owned by Allium commands. */
@FunctionalInterface
public interface SettingsPresentation {
    enum Screen { HOMES, WARPS, SELL, POWERTOOLS }

    /** Called on the player's owning thread. Returns true when the provider opened a panel. */
    boolean open(Player player);

    default boolean open(Player player, Screen screen) {
        return false;
    }

    /** The input consumer is called on the player's owning thread. */
    default boolean edit(Player player, String editor, ItemStack preview, List<String> initial,
                         Consumer<List<String>> submit) {
        return false;
    }
}
