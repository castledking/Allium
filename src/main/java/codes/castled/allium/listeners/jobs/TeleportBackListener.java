package codes.castled.allium.listeners.jobs;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TeleportBackListener implements Listener {
    private final PluginStart plugin;
    private final TP tpCommand;

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

        // Teleport companions
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
