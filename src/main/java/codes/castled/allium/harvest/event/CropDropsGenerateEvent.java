package codes.castled.allium.harvest.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after all harvest drops have been generated in memory and before they
 * are delivered. The drop list is mutable.
 */
public class CropDropsGenerateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final String primaryOutcomeId;
    private final Location location;
    private final List<ItemStack> drops;

    public CropDropsGenerateEvent(
        Player player, UUID instanceId, String cropId, String pathId,
        String primaryOutcomeId, Location location, List<ItemStack> drops
    ) {
        this.player = player;
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.primaryOutcomeId = primaryOutcomeId;
        this.location = location;
        this.drops = drops;
    }

    public Player getPlayer() { return player; }
    public UUID getInstanceId() { return instanceId; }
    public String getCropId() { return cropId; }
    public String getPathId() { return pathId; }
    /** Id of the mutually exclusive primary outcome that won, or null. */
    public String getPrimaryOutcomeId() { return primaryOutcomeId; }
    public Location getLocation() { return location.clone(); }
    /** Mutable list of drops about to be delivered. */
    public List<ItemStack> getDrops() { return drops; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
