package codes.castled.allium.harvest.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Fired after a harvest completed and drops were delivered. */
public class CropHarvestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final String primaryOutcomeId;
    private final Location location;
    private final List<ItemStack> drops;
    private final boolean regrown;

    public CropHarvestEvent(
        Player player, UUID instanceId, String cropId, String pathId,
        String primaryOutcomeId, Location location, List<ItemStack> drops, boolean regrown
    ) {
        this.player = player;
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.primaryOutcomeId = primaryOutcomeId;
        this.location = location;
        this.drops = List.copyOf(drops);
        this.regrown = regrown;
    }

    public Player getPlayer() { return player; }
    public UUID getInstanceId() { return instanceId; }
    public String getCropId() { return cropId; }
    public String getPathId() { return pathId; }
    public String getPrimaryOutcomeId() { return primaryOutcomeId; }
    public Location getLocation() { return location.clone(); }
    public List<ItemStack> getDrops() { return drops; }
    /** True if the crop regressed to a regrowth stage instead of being removed. */
    public boolean isRegrown() { return regrown; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
