package codes.castled.allium.tool.felltree;

import org.jetbrains.annotations.Nullable;

/**
 * Read-only access to the blocks around a tree. The plugin answers from the world without ever
 * loading a chunk; tests use maps.
 */
public interface BlockView {

    /** A leaf block's natural state: {@code persistent} is true for leaves a player placed. */
    record Leaf(boolean persistent, int distance) {
    }

    /**
     * The block's vanilla ID without the namespace (e.g. {@code oak_log}), {@code air} for air,
     * or {@code null} when its chunk is not loaded.
     */
    @Nullable
    String material(int x, int y, int z);

    /** The leaf state when the block is leaves, else {@code null}. */
    @Nullable
    Leaf leaf(int x, int y, int z);

    int minY();

    int maxY();
}
