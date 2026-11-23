package codes.castled.ores;

import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Queues freshly populated chunks of configured worlds and gives them their ores a few at a time.
 *
 * <p>Unfinished chunks carry a persisted {@code allium:ore_generation_pending} marker in their
 * chunk PDC so transient unloads, unready providers, and server restarts resume generation on
 * subsequent {@link ChunkLoadEvent} or once the block provider becomes ready.
 */
final class FreshChunks implements Listener, AutoCloseable {
    record Pending(UUID world, int x, int z) {}

    private final OreGeneration generation;
    private final Function<UUID, World> worldLookup;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final Set<Pending> queued = ConcurrentHashMap.newKeySet();
    private final Set<Pending> deferredForProvider = ConcurrentHashMap.newKeySet();
    private final TaskHandle task;
    private volatile boolean closed;

    FreshChunks(OreGeneration generation, TaskScheduler scheduler) {
        this(generation, scheduler, Bukkit::getWorld);
    }

    FreshChunks(OreGeneration generation, TaskScheduler scheduler, Function<UUID, World> worldLookup) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.worldLookup = Objects.requireNonNull(worldLookup, "worldLookup");
        long period = generation.settings().delayTicks() * TaskScheduler.MILLIS_PER_TICK;
        task = scheduler.repeatOnServer(() -> {
            drain();
            return !closed;
        }, TaskScheduler.MILLIS_PER_TICK, period);
    }

    /** Re-enqueues any already-loaded chunks that still carry the persisted pending marker. */
    void scanLoadedWorlds(Iterable<World> worlds) {
        var core = generation.core();
        boolean folia = core != null && core.isFolia();
        for (World world : worlds) {
            if (closed || !generation.automaticIn(world)) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                int cx = chunk.getX(), cz = chunk.getZ();
                if (folia) {
                    core.runForRegion(world, cx, cz, () -> {
                        if (closed || !world.isChunkLoaded(cx, cz)) return;
                        Chunk loaded = world.getChunkAt(cx, cz);
                        if (generation.isPending(loaded)) enqueue(world.getUID(), cx, cz);
                    }, 0L);
                } else if (generation.isPending(chunk)) {
                    enqueue(world.getUID(), cx, cz);
                }
            }
        }
    }

    @EventHandler
    public void onPopulate(ChunkPopulateEvent event) {
        Chunk chunk = event.getChunk();
        if (closed || !generation.automaticIn(chunk.getWorld())) return;
        generation.markPending(chunk);
        enqueue(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (closed || !generation.automaticIn(chunk.getWorld())) return;
        if (generation.isPending(chunk)) {
            enqueue(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private void enqueue(UUID worldId, int x, int z) {
        Pending pending = new Pending(worldId, x, z);
        deferredForProvider.remove(pending);
        if (queued.add(pending)) queue.offer(pending);
    }

    int queuedCount() { return queued.size(); }
    int deferredCount() { return deferredForProvider.size(); }

    void drain() {
        if (closed) return;
        if (!deferredForProvider.isEmpty() && generation.ready()) {
            var readyBatch = Set.copyOf(deferredForProvider);
            for (Pending pending : readyBatch) {
                if (deferredForProvider.remove(pending) && queued.add(pending)) {
                    queue.offer(pending);
                }
            }
        }
        var core = generation.core();
        boolean folia = core != null && core.isFolia();
        for (int i = 0; i < generation.settings().chunksPerTick(); i++) {
            Pending pending = queue.poll();
            if (pending == null) return;
            queued.remove(pending);
            World world = worldLookup.apply(pending.world());
            if (world == null || !generation.automaticIn(world)) continue;
            if (folia) {
                core.runForRegion(world, pending.x(), pending.z(), () -> {
                    if (closed || !generation.automaticIn(world) || !world.isChunkLoaded(pending.x(), pending.z())) return;
                    handleResult(pending, generation.processChunk(world.getChunkAt(pending.x(), pending.z())));
                }, 0L);
            } else if (world.isChunkLoaded(pending.x(), pending.z())) {
                handleResult(pending, generation.processChunk(world.getChunkAt(pending.x(), pending.z())));
            }
            // If unloaded right now, its persisted PENDING_MARKER stays in the chunk PDC and
            // onChunkLoad will re-enqueue it when the chunk loads again.
        }
    }

    private void handleResult(Pending pending, OreGeneration.ChunkResult result) {
        if (result.status() == OreGeneration.ChunkStatus.DEFERRED_PROVIDER_UNREADY
                || result.status() == OreGeneration.ChunkStatus.DEFERRED_FAILED) {
            deferredForProvider.add(pending);
        }
    }

    @Override
    public void close() {
        closed = true;
        task.cancel();
        queue.clear();
        queued.clear();
        deferredForProvider.clear();
        HandlerList.unregisterAll(this);
    }
}
