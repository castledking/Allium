package codes.castled.allium.harvest.event;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when the single weighted roll has chosen a growth path for a freshly
 * planted crop, before the instance is persisted. Listeners may override the
 * selection with another valid path id; the final value is stored permanently
 * on the instance.
 */
public class CropPathSelectEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String cropId;
    private final Location location;
    private final Set<String> validPaths;
    private String pathId;

    public CropPathSelectEvent(String cropId, Location location, String pathId, Set<String> validPaths) {
        this.cropId = cropId;
        this.location = location;
        this.pathId = pathId;
        this.validPaths = validPaths;
    }

    public String getCropId() { return cropId; }
    public Location getLocation() { return location.clone(); }
    public String getPathId() { return pathId; }
    public Set<String> getValidPaths() { return validPaths; }

    /** @throws IllegalArgumentException if the path does not exist on this crop */
    public void setPathId(String pathId) {
        if (!validPaths.contains(pathId)) {
            throw new IllegalArgumentException("Unknown path '" + pathId + "' for crop " + cropId);
        }
        this.pathId = pathId;
    }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
