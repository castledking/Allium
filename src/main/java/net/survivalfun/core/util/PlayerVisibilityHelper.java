package net.survivalfun.core.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Helper class for player visibility management.
 * Uses Bukkit's hidePlayer/showPlayer which hides from both locator bar (entity visibility)
 * and tab list. Tab list re-addition is handled separately by TabListManager when players
 * should remain visible in tab.
 */
public class PlayerVisibilityHelper {

    private static Plugin plugin;

    public static void initialize(Plugin pluginInstance) {
        plugin = pluginInstance;
    }

    public static void hidePlayer(Player viewer, Player target) {
        if (plugin == null) return;
        try {
            viewer.hidePlayer(plugin, target);
        } catch (Exception e) {
            plugin.getLogger().warning("Error hiding player " + target.getName() + " from " + viewer.getName() + ": " + e.getMessage());
        }
    }

    public static void showPlayer(Player viewer, Player target) {
        if (plugin == null) return;
        try {
            viewer.showPlayer(plugin, target);
        } catch (Exception e) {
            plugin.getLogger().warning("Error showing player " + target.getName() + " to " + viewer.getName() + ": " + e.getMessage());
        }
    }
}
