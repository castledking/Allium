package codes.castled.allium.harvest.event;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a spawner's visual overlay is created, updated to a different
 * model, or removed.
 */
public class SpawnerModelUpdateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Action { CREATE, UPDATE, REMOVE }

    private final Location location;
    private final EntityType entityType;
    private final Action action;

    public SpawnerModelUpdateEvent(Location location, EntityType entityType, Action action) {
        this.location = location;
        this.entityType = entityType;
        this.action = action;
    }

    public Location getLocation() { return location.clone(); }
    /** Null for {@link Action#REMOVE} when the block is already gone. */
    public EntityType getEntityType() { return entityType; }
    public Action getAction() { return action; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
