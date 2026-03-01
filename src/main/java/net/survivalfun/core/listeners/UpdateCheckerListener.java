package net.survivalfun.core.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Notifies players with allium.admin when an update is available (on join).
 */
public class UpdateCheckerListener implements Listener {

    private static volatile String updateMessage = null;

    public static void setUpdateAvailable(String message) {
        updateMessage = message;
    }

    public static void clearUpdateMessage() {
        updateMessage = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (updateMessage != null && player.hasPermission("allium.admin")) {
            player.sendMessage(Component.text(updateMessage).color(NamedTextColor.GOLD));
        }
    }
}
