package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.BlockRef;
import java.util.Map;
import org.bukkit.block.Block;

/**
 * The renderer used when no block provider is installed. Every block stage
 * then simply stays invisible instead of failing the crop.
 */
public final class NoopCropBlockRenderer implements CropBlockRenderer {

    public static final NoopCropBlockRenderer INSTANCE = new NoopCropBlockRenderer();

    private NoopCropBlockRenderer() {
    }

    @Override
    public boolean isCustomBlock(Block block) {
        return false;
    }

    @Override
    public boolean exists(BlockRef ref) {
        return false;
    }

    @Override
    public boolean matches(Block block, BlockRef ref, Map<String, String> state) {
        return false;
    }

    @Override
    public Map<String, String> stateOf(Block block) {
        return Map.of();
    }

    @Override
    public boolean apply(Block block, BlockRef ref, Map<String, String> state) {
        return false;
    }

    @Override
    public boolean remove(Block block) {
        return false;
    }
}
