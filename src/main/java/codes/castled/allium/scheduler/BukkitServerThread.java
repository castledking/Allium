package codes.castled.allium.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** The Bukkit server thread for {@link AlliumScheduler}; the only class that touches {@code BukkitScheduler}. */
public final class BukkitServerThread implements ServerThread {

    private final Plugin plugin;

    public BukkitServerThread(Plugin plugin) {
        this.plugin = plugin;
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
