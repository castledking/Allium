package codes.castled.allium.harvest.util;

import java.util.UUID;

/** Deterministic identity of a single block position across restarts. */
public record BlockPositionKey(UUID worldId, int x, int y, int z) {

    public ChunkKey chunkKey() {
        return new ChunkKey(worldId, x >> 4, z >> 4);
    }

    public BlockPositionKey offset(int dx, int dy, int dz) {
        return new BlockPositionKey(worldId, x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return worldId + "[" + x + "," + y + "," + z + "]";
    }
}
