package codes.castled.allium.managers.world;

import codes.castled.allium.harvest.crop.def.BlockRef;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** Keeps optional CraftEngine classes out of generation and command code. */
public interface OrePlacement {
    boolean exists(BlockRef ref);
    boolean place(Block block, BlockRef ref);
    String idOf(Block block);
    ItemStack itemOf(String id);
}
