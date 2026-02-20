package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.TP;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.util.SchedulerAdapter;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TeleportBackListener implements Listener {
    private final PluginStart plugin;
    private final TP tpCommand;
    private long lastSave = 0;
    private static final long SAVE_COOLDOWN = TimeUnit.SECONDS.toMillis(5); // 5 second cooldown

    public TeleportBackListener(PluginStart plugin) {
        this.plugin = plugin;
        TP resolvedTp = null;
        try {
            if (plugin.getCommand("tp") != null && plugin.getCommand("tp").getExecutor() instanceof TP tp) {
                resolvedTp = tp;
            }
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "[TeleportBackListener] Failed to resolve TP command instance", ex);
        }
        this.tpCommand = resolvedTp;
        Text.sendDebugLog(INFO, "[TeleportBackListener] Debug mode enabled");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) return;

        // Skip if the player is just looking around or the location is very close
        if (event.getTo() != null && from.getWorld().equals(event.getTo().getWorld()) &&
            from.distanceSquared(event.getTo()) < 1.0) {
            return;
        }

        // Save the back location
        Location clonedLocation = event.getFrom().clone();
        // Skip certain teleport causes that shouldn't update /back
        switch (event.getCause()) {
            case COMMAND:
            case PLUGIN:
            case UNKNOWN:
                // These are the causes we want to track for /back (command and plugin teleports)
                break;
            case ENDER_PEARL:
            default:
                // Skip item-based teleports and other causes that players shouldn't /back from
                return;
        }

        // Rate limiting check
        long now = System.currentTimeMillis();
        if (now - lastSave < SAVE_COOLDOWN) {
            return;
        }
        lastSave = now;

        UUID playerId = player.getUniqueId();
        // Update in-memory cache
        plugin.getTpInstance().getLastLocationMap().put(playerId, clonedLocation);

        // Update database directly without scheduling a task
        try {
            Database db = plugin.getDatabase();
            if (db != null) {
                long ts = System.currentTimeMillis();
                db.savePlayerLocation(playerId, LocationType.TELEPORT, clonedLocation, ts);
                
                Text.sendDebugLog(INFO, String.format(
                    "[TeleportBack] Saved location for %s at %s (%.1f, %.1f, %.1f)",
                    player.getName(),
                    clonedLocation.getWorld().getName(),
                    clonedLocation.getX(),
                    clonedLocation.getY(),
                    clonedLocation.getZ()
                ));
            }
        } catch (Exception e) {
        }

        Location toLocation = event.getTo();
        if (toLocation != null && tpCommand != null) {
            teleportCompanionsAndCleanup(player, toLocation);
        }
    }

    private void teleportCompanionsAndCleanup(Player player, Location destination) {
        if (!player.isOnline() || destination == null) {
            return;
        }

        UUID playerUUID = player.getUniqueId();

        SchedulerAdapter.runAtEntity(player, () -> {
            try {
                List<Entity> pets = getSelectedPets(playerUUID);
                Map<UUID, Entity> selectedEntities = getSelectedEntities(playerUUID);

                boolean petsTeleported = teleportEntitiesList(pets, destination, "pets", player);
                boolean entitiesTeleported = teleportEntitiesCollection(selectedEntities != null ? selectedEntities.values() : Collections.emptyList(), destination, "entities", player);

                if (petsTeleported || entitiesTeleported) {
                    tpCommand.handleSuccessfulCompanionTeleport(playerUUID, player);
                    sendAutoDisableMessage(player, petsTeleported, entitiesTeleported);
                }
            } catch (Exception ex) {
                Text.sendDebugLog(WARN, "[TeleportBackListener] Failed to teleport companions", ex);
            }
        });
    }

    private List<Entity> getSelectedPets(UUID playerUUID) {
        try {
            Field field = TP.class.getDeclaredField("selectedPets");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, List<Entity>> pets = (Map<UUID, List<Entity>>) field.get(tpCommand);
            if (pets == null) {
                return Collections.emptyList();
            }
            return pets.getOrDefault(playerUUID, Collections.emptyList());
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "[TeleportBackListener] Unable to read selected pets map", ex);
            return Collections.emptyList();
        }
    }

    private Map<UUID, Entity> getSelectedEntities(UUID playerUUID) {
        try {
            Field field = TP.class.getDeclaredField("selectedEntities");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Map<UUID, Entity>> entities = (Map<UUID, Map<UUID, Entity>>) field.get(tpCommand);
            if (entities == null) {
                return null;
            }
            return entities.get(playerUUID);
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "[TeleportBackListener] Unable to read selected entities map", ex);
            return null;
        }
    }

    private boolean teleportEntitiesList(List<Entity> entities, Location destination, String label, Player owner) {
        if (entities == null || entities.isEmpty()) {
            return false;
        }

        List<Entity> valid = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity != null && entity.isValid()) {
                valid.add(entity);
            }
        }

        if (valid.isEmpty()) {
            return false;
        }

        Text.sendDebugLog(INFO, String.format("[TeleportBackListener] Teleporting %d %s for %s", valid.size(), label, owner.getName()));
        for (Entity entity : valid) {
            SchedulerAdapter.runAtEntity(entity, () -> entity.teleport(destination));
        }
        return true;
    }

    private boolean teleportEntitiesCollection(Collection<Entity> entities, Location destination, String label, Player owner) {
        if (entities == null || entities.isEmpty()) {
            return false;
        }

        List<Entity> valid = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity != null && entity.isValid()) {
                valid.add(entity);
            }
        }

        if (valid.isEmpty()) {
            return false;
        }

        Text.sendDebugLog(INFO, String.format("[TeleportBackListener] Teleporting %d %s for %s", valid.size(), label, owner.getName()));
        for (Entity entity : valid) {
            SchedulerAdapter.runAtEntity(entity, () -> entity.teleport(destination));
        }
        return true;
    }

    private void sendAutoDisableMessage(Player player, boolean petsTeleported, boolean entitiesTeleported) {
        tpCommand.notifyAutoDisable(player, petsTeleported, entitiesTeleported);
    }
}
