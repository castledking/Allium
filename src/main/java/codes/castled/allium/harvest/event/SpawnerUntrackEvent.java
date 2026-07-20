package codes.castled.allium.harvest.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a spawner block leaves the tracking index. */
public class SpawnerUntrackEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location location;

    public SpawnerUntrackEvent(Location location) {
        this.location = location;
    }

    public Location getLocation() { return location.clone(); }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
