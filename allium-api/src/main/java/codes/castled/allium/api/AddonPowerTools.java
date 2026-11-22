package codes.castled.allium.api;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Registers addon-owned actions and built-in powertools on the server thread. */
public interface AddonPowerTools {
    /** Whether the Allium tools module is active. */
    boolean available();

    /** Registers an action as {@code <addon>:<name>}. Its return value indicates whether it did work. */
    void registerAction(String name, Action action);

    /** Registers an ordinary powertool definition with its own built-in item. */
    void registerTool(ConfigurationSection definition);

    /** Runs Allium's block protection checks for an addon action. */
    boolean mayModify(Player player, Block block, String action, @Nullable Event cause);

    @FunctionalInterface
    interface Action {
        boolean run(Context context, @Nullable String value, Map<String, Object> parameters);
    }

    record Context(Player player, String toolId, ItemStack item, String hand, String trigger,
                   @Nullable Block block, @Nullable Entity entity, @Nullable Event cause) {}
}
