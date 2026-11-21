package codes.castled.allium.explosion;

import org.bukkit.block.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A block an explosion took and how to put it back.
 *
 * @param data     the block data, as {@code BlockData#getAsString}
 * @param attached whether it rests on or hangs from others (torches, plants, rails, signs), so it
 *                 comes back after the solid blocks
 * @param live     the full snapshot while the server runs; null once read back from the journal
 * @param tile     the block entity data the journal keeps, or null
 */
public record CapturedBlock(int x, int y, int z, String material, String data, boolean attached,
                            @Nullable BlockState live, @Nullable TileSnapshot tile) implements RegenOrder.Placed {

    public boolean hasItems() {
        return tile != null && tile.hasItems();
    }
}
