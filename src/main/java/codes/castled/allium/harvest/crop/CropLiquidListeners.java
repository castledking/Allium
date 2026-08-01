package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.util.BlockPositionKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

/**
 * Liquids destroying crops.
 *
 * <p>A crop is a display entity standing in air, not a block, so vanilla has
 * nothing to destroy when a fluid arrives: water and lava flow straight through
 * the cell and the plant keeps standing inside them. That reads as a bug rather
 * than as protection, so the vanilla outcome is reproduced here deliberately —
 * the same way planting and breaking are done without ever tying a crop to a
 * real block.
 *
 * <p>The flow itself is never interfered with (unless a crop is configured to
 * be {@link LiquidResponse#PROTECT}ed): the fluid spreads exactly as it would
 * have, and the crop in its way is destroyed as it arrives. Which side of the
 * vanilla split a fluid falls on — water hands the block's contents back, lava
 * fizzes and consumes them — is configurable per fluid, because water carving
 * through a farm is a routine accident and lava reaching one never is.
 *
 * <p>Three ways a fluid can arrive are covered, all through the same decision:
 * spreading ({@code BlockFromToEvent}), a bucket, and a dispenser. A crop's
 * cell is air, so nothing else can put a fluid there — a placed block is
 * already refused outright by {@code CropListeners}, which has no fluid to let
 * through and so simply cancels. A powder snow bucket is refused here for the
 * same reason: it is a block arriving by bucket, and buckets do not fire
 * {@code BlockPlaceEvent}.
 */
public final class CropLiquidListeners implements Listener {

    private final CropInstanceService instances;
    private final CropHarvestService harvests;
    private final HarvestConfig.Liquids settings;

    public CropLiquidListeners(
        CropInstanceService instances,
        CropHarvestService harvests,
        HarvestConfig.Liquids settings
    ) {
        this.instances = instances;
        this.harvests = harvests;
        this.settings = settings;
    }

    /**
     * A fluid spreading into the next cell.
     *
     * <p>Every face is handled, downward included. Vanilla makes no exception
     * for a fluid falling onto a plant rather than running into it, and a crop
     * that survived being rained on from above but not from the side would be a
     * strange thing to have to explain.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        // BlockFromToEvent is not only fired for fluids — a dragon egg
        // teleporting uses it too — so the source has to be identified rather
        // than assumed.
        LiquidResponse response = responseFor(event.getBlock().getType());
        if (response == null) {
            return;
        }
        apply(event, event.getToBlock(), response);
    }

    /**
     * A bucket emptied into a crop's cell.
     *
     * <p>A crop's cell is always air — that is what makes it plantable — so a
     * bucket aimed at one never waterlogs the clicked block and always fills
     * the cell beside it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        LiquidResponse response = responseForBucket(event.getBucket());
        if (response == null) {
            // Powder snow is the other thing a bucket can hold. It is a block
            // that would land in the cell rather than a fluid that flows
            // through it, so it is refused the way any other block placed into
            // a crop is — buckets do not fire BlockPlaceEvent, so the refusal
            // has to happen here.
            if (event.getBucket() == Material.POWDER_SNOW_BUCKET
                && instances.isOccupied(keyOf(target))) {
                event.setCancelled(true);
            }
            return;
        }
        apply(event, target, response);
    }

    /**
     * A dispenser emptying a bucket into a crop's cell.
     *
     * <p>Only the cell directly in front of the dispenser needs this: the
     * source block it creates there spreads onwards through {@link #onFlow}
     * like any other.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        // Droppers fire this event too, and a dropper holding a water bucket
        // spits the item out rather than placing anything — the crop in front
        // of it is in no danger.
        if (event.getBlock().getType() != Material.DISPENSER) {
            return;
        }
        LiquidResponse response = responseForBucket(event.getItem().getType());
        if (response == null) {
            return;
        }
        if (!(event.getBlock().getBlockData() instanceof Directional directional)) {
            return;
        }
        apply(event, event.getBlock().getRelative(directional.getFacing()), response);
    }

    /**
     * The shared decision, wherever the fluid came from.
     *
     * <p>Any cell of a crop counts, not just its anchor: a multi-block plant
     * with a stream running through one corner of its footprint is as destroyed
     * as one hit in the middle.
     */
    private void apply(Cancellable event, Block target, LiquidResponse response) {
        CropInstance crop = instances.at(keyOf(target)).orElse(null);
        if (crop == null) {
            return;
        }
        if (response.survives()) {
            event.setCancelled(true);
            return;
        }
        harvests.destroyByLiquid(crop, response);
    }

    /** @return the configured response for a fluid block, or null if it is not one */
    private LiquidResponse responseFor(Material type) {
        return switch (type) {
            case WATER -> settings.water();
            case LAVA -> settings.lava();
            default -> null;
        };
    }

    /**
     * @return the configured response for a filled bucket, or null if it holds
     *         no fluid. Every mob bucket counts: emptying one places the water
     *         its occupant arrives in.
     */
    private LiquidResponse responseForBucket(Material bucket) {
        return switch (bucket) {
            case WATER_BUCKET, COD_BUCKET, SALMON_BUCKET, PUFFERFISH_BUCKET,
                 TROPICAL_FISH_BUCKET, AXOLOTL_BUCKET, TADPOLE_BUCKET -> settings.water();
            case LAVA_BUCKET -> settings.lava();
            default -> null;
        };
    }

    private static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
