package codes.castled.ores;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/** Loads a chunk without blocking the server on Paper, and directly where only Spigot's API exists. */
final class Chunks {
    private static final Method ASYNC = find();

    private Chunks() {}

    private static Method find() {
        try {
            return World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (NoSuchMethodException spigot) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<Chunk> load(World world, int x, int z) {
        if (ASYNC != null) {
            try {
                return (CompletableFuture<Chunk>) ASYNC.invoke(world, x, z, true);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }
        try {
            return CompletableFuture.completedFuture(world.getChunkAt(x, z));
        } catch (RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }
}
