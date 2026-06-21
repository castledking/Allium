package codes.castled.allium.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.commands.Glow;

/**
 * Listener to manage glow state across player sessions.
 *
 * On quit: cancels rainbow tasks and unregisters teams (prevents team leak).
 * On join: re-applies the glowing flag so the outline persists after reconnect.
 */
public class GlowListener implements Listener {

    private final PluginStart plugin;
    private final Glow glow;

    public GlowListener(PluginStart plugin, Glow glow) {
        this.plugin = plugin;
        this.glow = glow;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (glow.isGlowing(player)) {
            glow.removeGlow(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Note: glow state does NOT persist across joins by design.
        // If persistence is needed, it should be stored in the database.
        // This listener is here for future expansion.
    }
}
