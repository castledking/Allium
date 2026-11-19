package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Shows the first page of {@code motd.txt}'s index on join to holders of {@code allium.motd}, after
 * {@code motd.join-delay-ticks} so it follows the join messages. Off with
 * {@code motd.show-on-join: false} or when the {@code motd} command family is disabled.
 */
public final class MotdOnJoin implements Listener {

    public static final long MAX_DELAY_TICKS = 200;

    private final PluginStart plugin;
    private final TextFileCommand motd;

    public MotdOnJoin(PluginStart plugin, TextFileCommand motd) {
        this.plugin = plugin;
        this.motd = motd;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("motd.show-on-join", true) || !player.hasPermission("allium.motd")) {
            return;
        }
        if (plugin.getCommandManager() != null && plugin.getCommandManager().getFamilyPolicy().isDisabled("motd")) {
            return;
        }
        long ticks = Math.max(0, Math.min(MAX_DELAY_TICKS, config.getLong("motd.join-delay-ticks", 10)));
        Runnable show = () -> {
            // The same connection only: a rejoin in between gets its own MOTD.
            if (player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player) {
                motd.sendIndex(player);
            }
        };
        if (ticks == 0) {
            show.run();
        } else {
            plugin.getScheduler().runOnServerLater(show, ticks * 50L);
        }
    }
}
