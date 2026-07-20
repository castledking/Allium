package codes.castled.allium.harvest.event;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a spawner block enters the tracking index. */
public class SpawnerTrackEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location location;
    private final EntityType entityType;

    public SpawnerTrackEvent(Location location, EntityType entityType) {
        this.location = location;
        this.entityType = entityType;
    }

    public Location getLocation() { return location.clone(); }
    public EntityType getEntityType() { return entityType; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
