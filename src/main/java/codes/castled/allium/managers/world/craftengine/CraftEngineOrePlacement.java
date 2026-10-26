package codes.castled.allium.managers.world.craftengine;

import codes.castled.allium.harvest.crop.craftengine.CraftEngineBlockService;
import codes.castled.allium.harvest.crop.def.BlockRef;
import codes.castled.allium.managers.world.OrePlacement;
import java.util.Map;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

public final class CraftEngineOrePlacement implements OrePlacement {
    private final CraftEngineBlockService blocks;
    public CraftEngineOrePlacement(Logger logger) { this.blocks = new CraftEngineBlockService(logger); }
    @Override public boolean exists(BlockRef ref) { return blocks.exists(ref); }
    @Override public boolean place(Block block, BlockRef ref) { return blocks.apply(block, ref, Map.of()); }
    @Override public String idOf(Block block) {
        var state = CraftEngineBlocks.getCustomBlockState(block);
        return state == null ? null : state.customBlockState().ownerId().asString();
    }
    @Override public ItemStack itemOf(String id) {
        var item = CraftEngineItems.byId(Key.of(id));
        return item == null ? null : item.buildBukkitItem();
    }
}
