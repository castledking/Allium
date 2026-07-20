package codes.castled.allium.harvest.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a crop instance has been created and scheduled. */
public class CropPlantEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final Location location;

    public CropPlantEvent(Player player, UUID instanceId, String cropId, String pathId, Location location) {
        this.player = player;
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.location = location;
    }

    public Player getPlayer() { return player; }
    public UUID getInstanceId() { return instanceId; }
    public String getCropId() { return cropId; }
    public String getPathId() { return pathId; }
    public Location getLocation() { return location.clone(); }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
