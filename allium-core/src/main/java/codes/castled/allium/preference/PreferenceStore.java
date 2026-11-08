package codes.castled.allium.preference;

import codes.castled.allium.scheduler.PoolFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bounded asynchronous access to a {@link PreferenceRepository}.
 *
 * <p>Work runs on a fixed number of lanes, each a single thread with a bounded queue, chosen by
 * UUID: every load and write for one player runs in submission order (SQLite uses one lane, which
 * also serializes its writes). A full queue fails the future with {@link StoreBusyException}
 * instead of blocking the caller. {@link #close(long)} drains queued work for a bounded time and
 * reports exactly which writes it could not finish.
 */
public final class PreferenceStore implements AutoCloseable {

    /** The queue is full, or storage already shut down: the work was never queued. */
    public static final class StoreBusyException extends RuntimeException {
        StoreBusyException(String message) {
            super(message);
        }
    }

    /** Queued work dropped by a shutdown that ran out of time; {@link CloseReport} lists it. */
    public static final class DroppedAtShutdownException extends RuntimeException {
        DroppedAtShutdownException() {
            super("not run: preference storage shut down before reaching it");
        }
    }

    /**
     * The outcome of {@link #close(long)}.
     *
     * @param lostWrites       queued writes that never ran, as {@code <uuid> {field=value, ...}}
     * @param inProgressWrites writes running at the timeout; each may or may not have been saved
     * @param droppedLoads     queued loads that never ran
     */
    public record CloseReport(boolean completed, List<String> lostWrites, List<String> inProgressWrites, int droppedLoads) {
    }

    private final class Task implements Runnable {
        final Lane lane;
        final UUID uuid;
        @Nullable
        final PreferenceChange change;
        final Runnable body;
        final CompletableFuture<?> future;

        Task(Lane lane, UUID uuid, @Nullable PreferenceChange change, CompletableFuture<?> future, Runnable body) {
            this.lane = lane;
            this.uuid = uuid;
            this.change = change;
            this.future = future;
            this.body = body;
        }

        @Override
        public void run() {
            lane.running = this;
            try {
                body.run();
            } finally {
                lane.running = null;
            }
        }

        String describe() {
            return uuid + " " + change.describe();
        }
    }

    private static final class Lane {
        final ThreadPoolExecutor executor;
        volatile Task running;

        Lane(ThreadPoolExecutor executor) {
            this.executor = executor;
        }
    }

    private final PreferenceRepository repository;
    private final List<Lane> lanes = new ArrayList<>();
    private final Consumer<UUID> beforeLoad;

    /** @param beforeLoad runs on the lane before each load (test-only fault hooks; no-op in production) */
    public PreferenceStore(PreferenceRepository repository, int laneCount, int queueCapacity, Consumer<UUID> beforeLoad) {
        this(repository, laneCount, queueCapacity, beforeLoad, PoolFactory.STANDALONE);
    }

    /** @param pools creates each lane (one thread, bounded queue); the plugin passes its scheduler's */
    public PreferenceStore(PreferenceRepository repository, int laneCount, int queueCapacity, Consumer<UUID> beforeLoad,
                           PoolFactory pools) {
        this.repository = repository;
        this.beforeLoad = beforeLoad;
        for (int i = 0; i < Math.max(1, laneCount); i++) {
            lanes.add(new Lane(pools.create("Allium-Preferences-" + i, 1, Math.max(1, queueCapacity))));
        }
    }

    public CompletableFuture<Optional<StoredPreferences>> load(UUID uuid) {
        return submit(uuid, null, () -> {
            beforeLoad.accept(uuid);
            return repository.load(uuid);
        });
    }

    public CompletableFuture<StoredPreferences> apply(UUID uuid, PreferenceChange change) {
        return submit(uuid, change, () -> repository.apply(uuid, change));
    }

    /** Queued and running work across lanes. */
    public int pending() {
        return lanes.stream().mapToInt(l -> l.executor.getQueue().size() + l.executor.getActiveCount()).sum();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> submit(UUID uuid, @Nullable PreferenceChange change, Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Lane lane = lanes.get(Math.floorMod(uuid.hashCode(), lanes.size()));
        try {
            lane.executor.execute(new Task(lane, uuid, change, future, () -> {
                try {
                    future.complete(work.run());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            }));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new StoreBusyException(lane.executor.isShutdown()
                    ? "preference storage is shut down" : "preference storage queue is full"));
        }
        return future;
    }

    /**
     * Stops accepting work and waits up to {@code timeoutMillis} for queued work (writes included)
     * to finish. On timeout, queued work is dropped (its futures fail with
     * {@link DroppedAtShutdownException}) and running work is interrupted; the report names every
     * write that was not finished.
     */
    public CloseReport close(long timeoutMillis) {
        lanes.forEach(l -> l.executor.shutdown());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        boolean done = true;
        for (Lane lane : lanes) {
            try {
                done &= lane.executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = false;
                break;
            }
        }
        if (done) {
            return new CloseReport(true, List.of(), List.of(), 0);
        }
        List<String> lost = new ArrayList<>();
        List<String> inProgress = new ArrayList<>();
        int droppedLoads = 0;
        for (Lane lane : lanes) {
            // Before interrupting: a write still running now may or may not reach the database.
            Task running = lane.running;
            if (running != null && running.change != null && !running.future.isDone()) {
                inProgress.add(running.describe());
            }
            for (Runnable queued : lane.executor.shutdownNow()) {
                Task task = (Task) queued;
                if (task.change != null) {
                    lost.add(task.describe());
                } else {
                    droppedLoads++;
                }
                task.future.completeExceptionally(new DroppedAtShutdownException());
            }
        }
        return new CloseReport(false, List.copyOf(lost), List.copyOf(inProgress), droppedLoads);
    }

    @Override
    public void close() {
        close(5_000L);
    }
}
