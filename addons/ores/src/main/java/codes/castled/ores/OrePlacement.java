package codes.castled.ores;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** The block plugin ores are placed through. Keeps CraftEngine's classes out of everything else. */
interface OrePlacement {
    boolean exists(BlockRef block);

    boolean place(Block target, BlockRef block);

    /** The custom block's id at {@code block}, or null for a vanilla block. */
    String idOf(Block block);

    /** The item for a custom block id, for Jade's icon; null when there is none. */
    ItemStack itemOf(String id);
}
