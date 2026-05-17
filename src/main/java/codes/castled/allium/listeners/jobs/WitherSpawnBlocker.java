package codes.castled.allium.listeners.jobs;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.logging.Level;

public class WitherSpawnBlocker implements Listener {

    private final PluginStart plugin;
    private volatile boolean witherSpawnBlocked;

    public WitherSpawnBlocker(PluginStart plugin) {
        this.plugin = plugin;
        this.witherSpawnBlocked = plugin.getDatabase() != null && plugin.getDatabase().isWitherSpawnDisabled();
    }

    public boolean isWitherSpawnBlocked() {
        return witherSpawnBlocked;
    }

    public void setWitherSpawnBlocked(boolean blocked, CommandSender sender) {
        if (this.witherSpawnBlocked == blocked) {
            String state = blocked ? "&cWither spawning is already disabled." : "&aWither spawning is already enabled.";
            sender.sendMessage(Text.colorize(state));
            return;
        }

        this.witherSpawnBlocked = blocked;
        if (plugin.getDatabase() != null) {
            try {
                plugin.getDatabase().setWitherSpawnDisabled(blocked);
            } catch (Exception ex) {
                Text.sendDebugLog(WARN, "Failed to persist wither toggle state", ex);
            }
        }

        String message = blocked
                ? "&cWither spawning has been disabled."
                : "&aWither spawning has been enabled.";
        sender.sendMessage(Text.colorize(message));

        Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission("allium.withertoggle.notify") && !player.equals(sender))
                .forEach(player -> player.sendMessage(Text.colorize("&7[Allium] " + message)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWitherSummon(CreatureSpawnEvent event) {
        if (!witherSpawnBlocked) {
            return;
        }

        if (!(event.getEntity() instanceof Wither)) {
            return;
        }

        SpawnReason reason = event.getSpawnReason();
        if (reason != SpawnReason.BUILD_WITHER && reason != SpawnReason.NETHER_PORTAL) {
            return;
        }

        event.setCancelled(true);
    }
}
