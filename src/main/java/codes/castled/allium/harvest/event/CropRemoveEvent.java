package codes.castled.allium.harvest.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a crop instance is removed (broken, harvested out, or purged). */
public class CropRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Reason {
        /** Destroyed by a player. */
        BROKEN,
        HARVESTED,
        ADMIN,
        DEFINITION_REMOVED,
        OBSTRUCTED,
        /**
         * A piston pushed or pulled something through the crop's cell. A ripe
         * plant pays its produce, exactly as breaking it by hand would; an
         * unfinished one pays {@code break-drops.immature}. Neither runs the
         * table's commands — there is no player here for a reward to belong to.
         */
        PISTON,
        /**
         * Water or lava reached the crop's cell. Whether anything dropped
         * depends on which fluid it was and how {@code liquids:} is configured,
         * so this reason alone does not tell you the crop paid out.
         */
        LIQUID
    }

    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final Location location;
    private final Reason reason;

    public CropRemoveEvent(UUID instanceId, String cropId, String pathId, Location location, Reason reason) {
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.location = location;
        this.reason = reason;
    }

    public UUID getInstanceId() { return instanceId; }
    public String getCropId() { return cropId; }
    public String getPathId() { return pathId; }
    public Location getLocation() { return location.clone(); }
    public Reason getReason() { return reason; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
