package codes.castled.allium.scheduler;

/**
 * The platform's server (main) thread, as {@link AlliumScheduler} needs it. The Bukkit
 * implementation wraps {@code BukkitScheduler}; tests use a manual tick loop.
 */
public interface ServerThread {

    boolean isServerThread();

    /**
     * Runs {@code task} on the server thread after {@code delayTicks} ticks (0 = the next tick),
     * then every {@code periodTicks} ticks when {@code periodTicks > 0}.
     */
    TaskHandle schedule(Runnable task, long delayTicks, long periodTicks);
}
