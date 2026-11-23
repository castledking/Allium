package codes.castled.allium.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;

/** The Bukkit server thread for {@link AlliumScheduler}; the only class that touches {@code BukkitScheduler}. */
public final class BukkitServerThread implements ServerThread {
    private static final boolean FOLIA = detectFolia();

    private final Plugin plugin;

    public BukkitServerThread(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isFolia() { return FOLIA; }

    public static ServerThread global(Plugin plugin) {
        return FOLIA ? FoliaServerThread.global(plugin) : new BukkitServerThread(plugin);
    }

    public static ServerThread entity(Plugin plugin, Entity entity) {
        return FOLIA ? FoliaServerThread.entity(plugin, entity) : new BukkitServerThread(plugin);
    }

    public static ServerThread region(Plugin plugin, World world, int chunkX, int chunkZ) {
        return FOLIA ? FoliaServerThread.region(plugin, world, chunkX, chunkZ) : new BukkitServerThread(plugin);
    }

    /** Paper/Folia chunk loading for the regional teleport path. An absent chunk can be returned when generation is disabled. */
    public static CompletableFuture<Chunk> loadRegionalChunk(World world, int chunkX, int chunkZ, boolean generate) {
        return FoliaServerThread.loadChunk(world, chunkX, chunkZ, generate);
    }

    /** Native async move, called from the entity owner after destination validation. */
    public static CompletableFuture<Boolean> teleportRegional(Entity entity, Location destination, TeleportCause cause) {
        return FoliaServerThread.teleport(entity, destination, cause);
    }

    /** Defers a reply or other sender-owned action. Console work stays on the global scheduler. */
    public static TaskHandle runFor(Plugin plugin, TaskScheduler scheduler, CommandSender sender,
                                    Runnable task, Runnable retired, long delayMillis) {
        if (isFolia()) {
            if (sender instanceof Entity entity) {
                return scheduler.runOn(entity(plugin, entity), task, retired, delayMillis);
            }
            if (sender instanceof BlockCommandSender blockSender) {
                var block = blockSender.getBlock();
                return scheduler.runOn(region(plugin, block.getWorld(), block.getX() >> 4, block.getZ() >> 4),
                        task, retired, delayMillis);
            }
        }
        return delayMillis > 0 ? scheduler.runOnServerLater(task, delayMillis) : scheduler.runOnServer(task);
    }

    /** Runs an action inline when the caller already owns the recipient. */
    public static void onOwner(Plugin plugin, TaskScheduler scheduler, CommandSender sender, Runnable task) {
        if (!isFolia()) {
            task.run();
            return;
        }
        if (sender instanceof Entity entity) {
            ServerThread owner = entity(plugin, entity);
            if (owner.isServerThread()) task.run();
            else scheduler.runOn(owner, task, () -> {}, 0);
            return;
        }
        if (sender instanceof BlockCommandSender blockSender) {
            var block = blockSender.getBlock();
            ServerThread owner = region(plugin, block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
            if (owner.isServerThread()) task.run();
            else scheduler.runOn(owner, task, () -> {}, 0);
            return;
        }
        if (scheduler.isServerThread()) task.run();
        else scheduler.runOnServer(task);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, Bukkit.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException notFolia) {
            return false;
        }
    }

    @Override
    public boolean isServerThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public TaskHandle schedule(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkit = periodTicks > 0L
                ? plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks)
                : plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle() {
            @Override
            public void cancel() {
                bukkit.cancel();
            }

            @Override
            public boolean isCancelled() {
                return bukkit.isCancelled();
            }
        };
    }
}
