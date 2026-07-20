package codes.castled.allium.harvest.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a crop is about to advance a stage. Cancelling postpones the
 * advance by one full stage duration.
 */
public class CropGrowEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final Location location;
    private final int fromStage;
    private final int toStage;
    private boolean cancelled;

    public CropGrowEvent(UUID instanceId, String cropId, String pathId, Location location, int fromStage, int toStage) {
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.location = location;
        this.fromStage = fromStage;
        this.toStage = toStage;
    }

    public UUID getInstanceId() { return instanceId; }
    public String getCropId() { return cropId; }
    public String getPathId() { return pathId; }
    public Location getLocation() { return location.clone(); }
    public int getFromStage() { return fromStage; }
    public int getToStage() { return toStage; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
