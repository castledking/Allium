package codes.castled.ores;

import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Queues freshly populated chunks of the configured worlds and gives them their ores a few at a time. */
final class FreshChunks implements Listener, AutoCloseable {
    private record Pending(UUID world, int x, int z) {}

    private final OreGeneration generation;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final Set<Pending> queued = ConcurrentHashMap.newKeySet();
    private final TaskHandle task;
    private volatile boolean closed;

    FreshChunks(OreGeneration generation, TaskScheduler scheduler) {
        this.generation = generation;
        long period = generation.settings().delayTicks() * TaskScheduler.MILLIS_PER_TICK;
        task = scheduler.repeatOnServer(() -> {
            drain();
            return !closed;
        }, TaskScheduler.MILLIS_PER_TICK, period);
    }

    @EventHandler
    public void onPopulate(ChunkPopulateEvent event) {
        var chunk = event.getChunk();
        if (closed || !generation.automaticIn(chunk.getWorld())) return;
        Pending pending = new Pending(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(pending)) queue.offer(pending);
    }

    private void drain() {
        for (int i = 0; i < generation.settings().chunksPerTick(); i++) {
            Pending pending = queue.poll();
            if (pending == null) return;
            queued.remove(pending);
            var world = Bukkit.getWorld(pending.world());
            if (world == null || !generation.automaticIn(world) || !world.isChunkLoaded(pending.x(), pending.z())) continue;
            generation.generateOresInChunk(world.getChunkAt(pending.x(), pending.z()));
        }
    }

    @Override
    public void close() {
        closed = true;
        task.cancel();
        queue.clear();
        queued.clear();
        HandlerList.unregisterAll(this);
    }
}
