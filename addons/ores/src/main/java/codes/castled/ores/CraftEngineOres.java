package codes.castled.ores;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/** Places ores as CraftEngine blocks. Only created while CraftEngine is enabled. */
final class CraftEngineOres implements OrePlacement {
    private final Logger logger;

    CraftEngineOres(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean exists(BlockRef block) {
        try {
            return CraftEngineBlocks.byId(Key.of(block.namespace(), block.id())) != null;
        } catch (Throwable unavailable) {
            // Before CraftEngine has loaded its packs, blocks do not exist yet.
            return false;
        }
    }

    @Override
    public boolean place(Block target, BlockRef block) {
        try {
            return CraftEngineBlocks.place(target.getLocation(), Key.of(block.namespace(), block.id()), new CompoundTag(), false, false);
        } catch (Throwable failed) {
            logger.warning("CraftEngine block " + block + " could not be placed: " + failed);
            return false;
        }
    }

    @Override
    public String idOf(Block block) {
        try {
            var state = CraftEngineBlocks.getCustomBlockState(block);
            return state == null ? null : state.customBlockState().ownerId().asString();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    @Override
    public ItemStack itemOf(String id) {
        try {
            var item = CraftEngineItems.byId(Key.of(id));
            return item == null ? null : item.buildBukkitItem();
        } catch (Throwable unavailable) {
            return null;
        }
    }
}
