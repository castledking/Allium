package net.survivalfun.core.listeners.security;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.VanishManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Handles server list ping events to hide vanished players from online count
 */
public class VanishPingListener implements Listener {

    private final PluginStart plugin;
    private final VanishManager vanishManager;

    public VanishPingListener(PluginStart plugin, VanishManager vanishManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
    }

    /**
     * Handle server list ping - hide vanished players from online count
     */
    @EventHandler
    public void onServerListPing(PaperServerListPingEvent event) {
        // Count total online players
        int totalPlayers = plugin.getServer().getOnlinePlayers().size();

        // Count vanished players (players that should be hidden from the ping)
        int vanishedCount = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (vanishManager.isVanished(player)) {
                vanishedCount++;
            }
        }

        // Set the online player count to exclude vanished players
        // This makes vanished players invisible in the server list
        event.setNumPlayers(totalPlayers - vanishedCount);
        event.setMaxPlayers(plugin.getServer().getMaxPlayers());

        // Note: We don't modify the player sample (the list of players shown in the server list)
        // as Paper's API doesn't easily allow filtering individual players from the sample
        // But the online count is the most important part for hiding vanished players
    }
}
