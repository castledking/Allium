package codes.castled.allium.listeners.jobs;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.commands.TP;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.managers.DB.Database.LocationType;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.UUID;

public class TeleportBackListener implements Listener {
    private final PluginStart plugin;
    private final TP tpCommand;

    public TeleportBackListener(PluginStart plugin) {
        this.plugin = plugin;
        // Read the instance straight off the plugin: getCommand("tp").getExecutor() returns the
        // wrapper PluginStart installs to suppress Bukkit's usage fallback, not the TP object.
        this.tpCommand = plugin.getTpInstance();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND
                && cause != PlayerTeleportEvent.TeleportCause.PLUGIN
                && cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }

        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) return;

        UUID playerId = player.getUniqueId();

        // Update in-memory cache
        plugin.getTpInstance().getLastLocationMap().put(playerId, location.clone());

        // Update database
        try {
            Database db = plugin.getDatabase();
            if (db != null) {
                db.savePlayerLocation(playerId, LocationType.TELEPORT, location, System.currentTimeMillis());
            }
        } catch (Exception e) {
            // silently fail
        }

        // Drag selected pets and mobs along. TP#teleportCompanions claims the selection atomically
        // and sends the auto-disable message itself, so a teleport that raises this event more than
        // once still teleports and announces exactly once.
        Location toLocation = event.getTo();
        if (toLocation != null && tpCommand != null && player.isOnline()) {
            Location destination = toLocation.clone();
            SchedulerAdapter.runAtEntity(player, () -> {
                try {
                    tpCommand.teleportCompanions(player, destination);
                } catch (Exception ex) {
                    Text.sendDebugLog(WARN, "[TeleportBackListener] Failed to teleport companions", ex);
                }
            });
        }
    }
}
