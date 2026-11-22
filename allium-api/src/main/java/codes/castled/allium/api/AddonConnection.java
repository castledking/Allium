package codes.castled.allium.api;

import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/** One addon's connection to Allium, from {@link AlliumApi#connect} or {@link AlliumAddon#allium()}. */
public interface AddonConnection {

    Plugin plugin();

    /** Whether a synchronous inventory edit may proceed while Allium deliveries are active. */
    default boolean canEditInventory(Player player) {
        return false;
    }

    /** Checks Allium's protection integration before an addon mutates a world cell. */
    default boolean mayModify(Player player, Block block, String action, @Nullable Event cause) {
        return false;
    }

    /** Item definitions owned by this addon, and Allium's item create/identify. */
    AddonItems items();

    /** Command families declared in this addon's {@code plugin.yml}, run through Allium's command specs. */
    AddonCommands commands();

    /**
     * Allium's scheduler boundary, scoped to this addon: its server-thread tasks belong to the addon,
     * and everything still scheduled is cancelled when the addon disables.
     */
    TaskScheduler scheduler();

    /** Addon-owned powertool actions and tools, released when the addon disables. */
    default AddonPowerTools powerTools() {
        throw new UnsupportedOperationException("Allium's powertool API is unavailable");
    }

    /** Allium's text formatting and delivery. */
    AddonText text();

    /** Allium's economy, for charging and paying players; unavailable when its economy module is off. */
    default AddonEconomy economy() {
        return AddonEconomy.UNAVAILABLE;
    }
}
