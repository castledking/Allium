package net.survivalfun.core.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.survivalfun.core.managers.core.TabListManager;

/**
 * Helper class for player visibility management using Paper API entity-based hiding with fallback.
 * This replaces the old plugin-based hidePlayer/showPlayer methods with
 * entity-based hideEntity/showEntity methods that only affect entity visibility
 * (locator bar) while keeping players visible in the tab list.
 *
 * On Paper servers, uses the Paper API for optimal performance.
 * On non-Paper servers, falls back to Bukkit's hidePlayer/showPlayer methods.
 */
public class PlayerVisibilityHelper {

    private static Plugin plugin; // Reference to the main plugin for Bukkit API calls

    /**
     * Initialize the helper with the plugin reference.
     * This is needed for Bukkit API calls that require a plugin parameter.
     *
     * @param pluginInstance The main plugin instance
     */
    public static void initialize(Plugin pluginInstance) {
        plugin = pluginInstance;
    }

    /**
     * Hide a player entity from another player using entity-based hiding.
     * This only affects entity visibility (locator bar) and should not affect tab list.
     *
     * @param viewer The player who should not see the target player entity
     * @param target The player entity to hide from the viewer
     */
    public static void hidePlayer(Player viewer, Player target) {
        if (plugin == null) {
            plugin.getLogger().warning("PlayerVisibilityHelper not initialized - cannot hide player " + target.getName());
            return;
        }

        try {
            // Use Paper API entity-based hiding (only affects entity visibility)
            viewer.hideEntity(plugin, target);
        } catch (NoSuchMethodError e) {
            // Paper API not available - fall back to Bukkit's hidePlayer method
            try {
                viewer.hidePlayer(plugin, target);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error hiding player " + target.getName() + " from " + viewer.getName() + ": " + ex.getMessage());
            }
        } catch (Exception e) {
            // Catch any other exceptions during entity hiding
            plugin.getLogger().warning("Error hiding player entity " + target.getName() + " from " + viewer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Show a player entity to another player using entity-based showing.
     * This only affects entity visibility (locator bar) and should not affect tab list.
     *
     * @param viewer The player who should see the target player entity
     * @param target The player entity to show to the viewer
     */
    public static void showPlayer(Player viewer, Player target) {
        if (plugin == null) {
            plugin.getLogger().warning("PlayerVisibilityHelper not initialized - cannot show player " + target.getName());
            return;
        }

        try {
            // Use Paper API entity-based showing (only affects entity visibility)
            viewer.showEntity(plugin, target);
        } catch (NoSuchMethodError e) {
            // Paper API not available - fall back to Bukkit's showPlayer method
            try {
                viewer.showPlayer(plugin, target);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error showing player " + target.getName() + " to " + viewer.getName() + ": " + ex.getMessage());
            }
        } catch (Exception e) {
            // Catch any other exceptions during entity showing
            plugin.getLogger().warning("Error showing player entity " + target.getName() + " to " + viewer.getName() + ": " + e.getMessage());
        }
    }
}
