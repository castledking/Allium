package codes.castled.allium.tool.felltree;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * {@link BlockView} backed by a Bukkit {@link World}.
 * Only blocks in loaded chunks are read; unloaded chunks return {@code null} without loading them.
 */
public final class BukkitBlockView implements BlockView {

    private final World world;

    public BukkitBlockView(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public @Nullable String material(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        if (y < minY() || y > maxY()) {
            return "air";
        }
        Block block = world.getBlockAt(x, y, z);
        return block.getType().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public @Nullable Leaf leaf(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        if (y < minY() || y > maxY()) {
            return null;
        }
        Block block = world.getBlockAt(x, y, z);
        BlockData data = block.getBlockData();
        if (data instanceof Leaves leaves) {
            return new Leaf(leaves.isPersistent(), leaves.getDistance());
        }
        return null;
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }
}
