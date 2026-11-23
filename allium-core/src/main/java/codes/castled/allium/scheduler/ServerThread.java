package codes.castled.allium.scheduler;

/**
 * An execution target on the platform, such as the global scheduler, an entity's scheduler, or
 * the scheduler for a world chunk. Traditional servers use the main thread for each target.
 */
public interface ServerThread {

    boolean isServerThread();

    /**
     * Runs {@code task} on the server thread after {@code delayTicks} ticks (0 = the next tick),
     * then every {@code periodTicks} ticks when {@code periodTicks > 0}.
     */
    TaskHandle schedule(Runnable task, long delayTicks, long periodTicks);

    /** The retirement callback can run without ownership and must only release pending application state. */
    default TaskHandle schedule(Runnable task, Runnable retired, long delayTicks, long periodTicks) {
        return schedule(task, delayTicks, periodTicks);
    }
}
