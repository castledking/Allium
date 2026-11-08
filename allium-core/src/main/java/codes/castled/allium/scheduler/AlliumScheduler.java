package codes.castled.allium.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Allium's one {@link TaskScheduler}: server-thread work through the platform's
 * {@link ServerThread}, delayed async work on a single owned timer thread, and owned bounded pools.
 * A task that throws is reported and never stops the timer or a repeating task's scheduler.
 * {@link #close} cancels everything still scheduled and stops every pool.
 */
public final class AlliumScheduler implements TaskScheduler {

    private final ServerThread server;
    private final Consumer<String> errors;
    private final ScheduledThreadPoolExecutor timer;
    private final List<Pool> pools = new CopyOnWriteArrayList<>();
    private final Set<TaskHandle> serverTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private record Pool(String name, ThreadPoolExecutor executor) {
    }

    /** @param errors receives one line per task that threw */
    public AlliumScheduler(ServerThread server, Consumer<String> errors) {
        this.server = Objects.requireNonNull(server, "server");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Allium-Timer");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.setRemoveOnCancelPolicy(true);
        this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public boolean isServerThread() {
        return server.isServerThread();
    }

    @Override
    public TaskHandle runOnServer(Runnable task) {
        return onServer(guard("server task", task), 0L, 0L);
    }

    @Override
    public TaskHandle runOnServerLater(Runnable task, long delayMillis) {
        return onServer(guard("delayed server task", task), ticks(delayMillis), 0L);
    }

    @Override
    public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) {
        Objects.requireNonNull(step, "step");
        if (periodMillis <= 0L) {
            throw new IllegalArgumentException("period must be positive");
        }
        TaskHandle[] handle = new TaskHandle[1];
        Runnable body = () -> {
            boolean again;
            try {
                again = step.getAsBoolean();
            } catch (Throwable e) {
                report("repeating server task", e);
                again = true;
            }
            if (!again && handle[0] != null) {
                handle[0].cancel();
            }
        };
        handle[0] = onServer(body, ticks(delayMillis), Math.max(1L, ticks(periodMillis)));
        return handle[0];
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delayMillis) {
        if (closed) {
            return TaskHandle.NONE;
        }
        try {
            ScheduledFuture<?> future = timer.schedule(guard("async task", task), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
            return new TaskHandle() {
                @Override
                public void cancel() {
                    future.cancel(false);
                }

                @Override
                public boolean isCancelled() {
                    return future.isCancelled();
                }
            };
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return TaskHandle.NONE;
        }
    }

    @Override
    public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
        if (closed) {
            throw new IllegalStateException("the scheduler is closed");
        }
        ThreadPoolExecutor executor = BoundedPools.create(name, threads, queueCapacity);
        // A module reload replaces its pools; forget the ones its owner already shut down.
        pools.removeIf(p -> p.executor().isTerminated());
        pools.add(new Pool(name, executor));
        return executor;
    }

    private TaskHandle onServer(Runnable task, long delayTicks, long periodTicks) {
        if (closed) {
            return TaskHandle.NONE;
        }
        TaskHandle[] tracked = new TaskHandle[1];
        Runnable body = periodTicks > 0L ? task : () -> {
            if (tracked[0] != null) {
                serverTasks.remove(tracked[0]);
            }
            task.run();
        };
        TaskHandle platform = server.schedule(body, delayTicks, periodTicks);
        TaskHandle handle = new TaskHandle() {
            @Override
            public void cancel() {
                serverTasks.remove(this);
                platform.cancel();
            }

            @Override
            public boolean isCancelled() {
                return platform.isCancelled();
            }
        };
        tracked[0] = handle;
        serverTasks.add(handle);
        return handle;
    }

    /** Server-thread tasks still scheduled (one-shot tasks not yet run, and repeating tasks). */
    public int scheduledServerTasks() {
        return serverTasks.size();
    }

    /**
     * Cancels every scheduled task, stops the timer and shuts down every pool, waiting up to
     * {@code timeoutMillis} for running pool work.
     *
     * @return the names of pools whose work had not finished; they were interrupted
     */
    public List<String> close(long timeoutMillis) {
        closed = true;
        new ArrayList<>(serverTasks).forEach(TaskHandle::cancel);
        serverTasks.clear();
        timer.shutdownNow();
        pools.forEach(p -> p.executor().shutdown());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        List<String> unfinished = new ArrayList<>();
        for (Pool pool : pools) {
            try {
                if (!pool.executor().awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    unfinished.add(pool.name());
                    pool.executor().shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unfinished.add(pool.name());
                pool.executor().shutdownNow();
            }
        }
        return unfinished;
    }

    public boolean isClosed() {
        return closed;
    }

    private Runnable guard(String kind, Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try {
                task.run();
            } catch (Throwable e) {
                report(kind, e);
            }
        };
    }

    private void report(String kind, Throwable e) {
        errors.accept("An Allium " + kind + " failed: " + e);
    }

    /** Milliseconds to whole ticks, rounding up; 0 stays 0 (the next tick). */
    static long ticks(long millis) {
        return millis <= 0L ? 0L : (millis + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK;
    }
}
