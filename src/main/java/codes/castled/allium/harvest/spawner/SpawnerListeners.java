package codes.castled.allium.harvest.spawner;

import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.scheduler.SchedulerAdapter;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeps the spawner tracking index in sync with world changes. All paths
 * funnel into the idempotent {@code refresh}/{@code untrack} of the tracking
 * service.
 */
public final class SpawnerListeners implements Listener {

    private final Plugin plugin;
    private final SpawnerTrackingService tracking;

    public SpawnerListeners(Plugin plugin, SpawnerTrackingService tracking) {
        this.plugin = plugin;
        this.tracking = tracking;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.SPAWNER) {
            // Delay one tick so the spawned type set by placement logic
            // (e.g. spawner items with stored types) is visible.
            SchedulerAdapter.runAtLocationLater(plugin, event.getBlockPlaced().getLocation(),
                () -> tracking.refresh(event.getBlockPlaced()), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.SPAWNER) {
            tracking.untrack(event.getBlock().getWorld(), keyOf(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.SPAWNER) {
                tracking.untrack(block.getWorld(), keyOf(block));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.SPAWNER) {
                tracking.untrack(block.getWorld(), keyOf(block));
            }
        }
    }

    /**
     * Interacting with a spawner (spawn eggs, spawner-changer items) may
     * change its type; re-converge shortly after.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.SPAWNER) {
            SchedulerAdapter.runAtLocationLater(plugin, block.getLocation(),
                () -> tracking.refresh(block), 2L);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        tracking.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        tracking.onChunkUnload(event.getChunk());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        tracking.onWorldUnload(event.getWorld().getUID());
    }

    private static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
