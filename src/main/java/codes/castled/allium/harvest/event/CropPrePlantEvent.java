package codes.castled.allium.harvest.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired before a crop is planted, before the seed is consumed or a path is
 * selected. Cancelling stops the plant entirely.
 */
public class CropPrePlantEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String cropId;
    private final Location location;
    private boolean cancelled;

    public CropPrePlantEvent(Player player, String cropId, Location location) {
        this.player = player;
        this.cropId = cropId;
        this.location = location;
    }

    public Player getPlayer() { return player; }
    public String getCropId() { return cropId; }
    public Location getLocation() { return location.clone(); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
