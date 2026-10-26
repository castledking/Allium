package codes.castled.allium.listeners.world;

import codes.castled.allium.managers.world.OreGenerationManager;
import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.Plugin;

/** Only queues freshly populated chunks in explicitly configured worlds. */
public final class OreGenerationListener implements Listener, AutoCloseable {
    private record Pending(UUID world, int x, int z) { }
    private final Plugin plugin;
    private final OreGenerationManager manager;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final Set<Pending> queued = ConcurrentHashMap.newKeySet();
    private final TaskHandle task;
    private volatile boolean closed;

    public OreGenerationListener(Plugin plugin, OreGenerationManager manager) {
        this.plugin = plugin; this.manager = manager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = SchedulerAdapter.runRepeatingGlobal(plugin, this::drain, 1, manager.delayTicks());
    }
    @EventHandler public void onPopulate(ChunkPopulateEvent event) {
        var chunk = event.getChunk();
        if (closed || !manager.automaticIn(chunk.getWorld())) return;
        Pending pending = new Pending(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(pending)) queue.offer(pending);
    }
    private void drain() {
        for (int i = 0; i < manager.chunksPerTick(); i++) {
            Pending pending = queue.poll();
            if (pending == null) return;
            queued.remove(pending);
            var world = Bukkit.getWorld(pending.world());
            if (world == null || !manager.automaticIn(world)) continue;
            SchedulerAdapter.runAtLocation(plugin, new Location(world, pending.x() * 16 + 8, world.getMinHeight(), pending.z() * 16 + 8), () -> {
                if (!closed && world.isChunkLoaded(pending.x(), pending.z()))
                    manager.generateOresInChunk(world.getChunkAt(pending.x(), pending.z()));
            });
        }
    }
    public int getPendingChunks() { return queued.size(); }
    @Override public void close() { closed = true; task.cancel(); queue.clear(); queued.clear(); HandlerList.unregisterAll(this); }
}
