package codes.castled.allium.rtp;

import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Loads (and generates) a chunk without stalling the server where the platform allows it: Paper's
 * {@code World#getChunkAtAsync}, else one synchronous load on the next tick (Spigot), so a search
 * costs at most one chunk per tick there.
 */
final class AsyncChunks {

    @Nullable
    private static final Method PAPER_ASYNC = find();

    private AsyncChunks() {
    }

    @Nullable
    private static Method find() {
        try {
            return World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /** Whether chunks load off the server thread here. */
    static boolean asynchronous() {
        return PAPER_ASYNC != null;
    }

    /** Server thread. Completes when the chunk is loaded, on whichever thread loaded it. */
    @SuppressWarnings("unchecked")
    static CompletableFuture<Chunk> load(World world, int cx, int cz, TaskScheduler scheduler) {
        if (world.isChunkLoaded(cx, cz)) {
            return CompletableFuture.completedFuture(world.getChunkAt(cx, cz));
        }
        if (PAPER_ASYNC != null) {
            try {
                return (CompletableFuture<Chunk>) PAPER_ASYNC.invoke(world, cx, cz, true);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                // Fall back to a synchronous load below.
            }
        }
        CompletableFuture<Chunk> loaded = new CompletableFuture<>();
        scheduler.runOnServer(() -> {
            try {
                loaded.complete(world.getChunkAt(cx, cz));
            } catch (RuntimeException failure) {
                loaded.completeExceptionally(failure);
            }
        });
        return loaded;
    }
}
