package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.BlockRef;
import java.util.Map;
import org.bukkit.block.Block;

/**
 * Places, updates and removes the real blocks a crop stage is drawn with.
 *
 * <p>The interface exists so that the rest of the harvest module never has to
 * know which plugin provides the blocks. The one implementation that talks to
 * a block provider lives in its own package and is only ever loaded when that
 * provider is installed; without a provider every crop simply has to be drawn
 * with item models.
 */
public interface CropBlockRenderer {

    /** Whether the given block is one this renderer put into the world. */
    boolean isCustomBlock(Block block);

    /** Whether the referenced block exists at all. */
    boolean exists(BlockRef ref);

    /**
     * Whether the given block already is the requested block in the requested
     * state.
     */
    boolean matches(Block block, BlockRef ref, Map<String, String> state);

    /**
     * The state properties the block actually has, as the provider reports
     * them.
     *
     * <p>Used for diagnostics: a crop that looks wrong is usually a crop whose
     * block is not in the state the stage asked for, and this says which.</p>
     */
    Map<String, String> stateOf(Block block);

    /**
     * Places the requested block, or updates the one already standing there.
     *
     * @return whether the world now shows the requested block
     */
    boolean apply(Block block, BlockRef ref, Map<String, String> state);

    /**
     * Removes a block this renderer placed, without dropping anything.
     *
     * @return whether the block was removed
     */
    boolean remove(Block block);
}
