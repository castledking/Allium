package codes.castled.allium.harvest.crop.craftengine;

import codes.castled.allium.harvest.crop.CropInstance;
import codes.castled.allium.harvest.crop.CropInstanceService;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.util.BlockPositionKey;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Keeps CraftEngine's own block behaviour out of the way of crops that are
 * drawn as blocks.
 *
 * <p>Without this, breaking a crop would hand out the block's own drop on top
 * of the crop's break drops, and right-clicking it would run the block's
 * interactions instead of the crop's.</p>
 *
 * <p>Touches CraftEngine classes directly, so it must only be registered when
 * CraftEngine is enabled.</p>
 */
public final class CraftEngineCropListener implements Listener {

    private final CropInstanceService instances;
    private final CropRegistry registry;

    public CraftEngineCropListener(CropInstanceService instances, CropRegistry registry) {
        this.instances = instances;
        this.registry = registry;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (isCropBlock(event.bukkitBlock())) {
            // What the player gets comes from the crop's own break drops.
            event.setDropItems(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockInteract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) {
            // A left click is how a block gets broken, and CraftEngine turns a
            // cancelled interact event into a cancelled Bukkit event — which
            // stops the break before it starts. The crop would be a block
            // that cannot be broken. Only the right click is ours to
            // suppress: that is the one that would place another block or
            // run the block's own interactions.
            return;
        }
        if (isCropBlock(event.bukkitBlock())) {
            event.setCancelled(true);
        }
    }

    /** Whether the block is the one a block-drawn crop stands in. */
    private boolean isCropBlock(Block block) {
        if (block == null) {
            return false;
        }
        BlockPositionKey key = new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        return instances.at(key)
            .filter(crop -> crop.position().equals(key))
            .filter(this::isBlockStage)
            .isPresent();
    }

    private boolean isBlockStage(CropInstance crop) {
        return registry.crop(crop.cropId())
            .flatMap(definition -> definition.path(crop.pathId()))
            .map(path -> path.stage(crop.stage()))
            .map(StageDefinition::isBlockStage)
            .orElse(false);
    }
}
