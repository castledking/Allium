package codes.castled.allium.harvest.util;

import java.util.UUID;

/** Identity of a chunk, used to index crops and spawners per chunk. */
public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {

    public static ChunkKey ofBlock(UUID worldId, int blockX, int blockZ) {
        return new ChunkKey(worldId, blockX >> 4, blockZ >> 4);
    }
}
