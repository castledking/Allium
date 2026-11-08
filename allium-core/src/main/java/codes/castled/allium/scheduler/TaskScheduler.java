package codes.castled.allium.scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/**
 * Where Allium's work runs. Every slice takes this instead of reaching for the platform scheduler,
 * raw threads or ad-hoc executors, so thread ownership is explicit and shutdown is complete.
 *
 * <ul>
 *   <li>Server-thread work: entity, world, inventory and Bukkit API calls.</li>
 *   <li>{@link #runAsyncLater}: short, non-blocking follow-ups off the server thread (retries,
 *       timeouts) on one shared timer thread. Never block in them.</li>
 *   <li>{@link #boundedPool}: blocking work (database, network) on a named pool with a bounded
 *       queue that rejects instead of growing.</li>
 * </ul>
 *
 * <p>Delays are in milliseconds; server-thread delays round up to whole ticks (50 ms). Once the
 * scheduler is closed, new work is not scheduled and {@link TaskHandle#NONE} is returned.
 */
public interface TaskScheduler {

    long MILLIS_PER_TICK = 50L;

    boolean isServerThread();

    /** Runs {@code task} on the server thread on the next tick. */
    TaskHandle runOnServer(Runnable task);

    TaskHandle runOnServerLater(Runnable task, long delayMillis);

    /** Runs {@code step} on the server thread every {@code periodMillis} until it returns false or is cancelled. */
    TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis);

    /** Runs a short task off the server thread after {@code delayMillis}. */
    TaskHandle runAsyncLater(Runnable task, long delayMillis);

    /**
     * A named pool for blocking work, owned by this scheduler: {@code threads} daemon threads and a
     * queue of {@code queueCapacity}; a full queue throws {@code RejectedExecutionException}. The
     * owner should shut it down (and may drain it) itself; {@link AlliumScheduler#close} stops any
     * pool still running.
     */
    ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity);

    /** {@link #runOnServer} as an {@link Executor}. */
    default Executor serverExecutor() {
        return this::runOnServer;
    }
}
