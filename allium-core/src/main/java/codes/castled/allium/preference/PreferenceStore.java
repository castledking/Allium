package codes.castled.allium.preference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
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
 * instead of blocking the caller.
 */
public final class PreferenceStore implements AutoCloseable {

    public static final class StoreBusyException extends RuntimeException {
        StoreBusyException(String message) {
            super(message);
        }
    }

    private final PreferenceRepository repository;
    private final List<ThreadPoolExecutor> lanes = new ArrayList<>();
    private final Consumer<UUID> beforeLoad;

    /** @param beforeLoad runs on the lane before each load (test-only fault hooks; no-op in production) */
    public PreferenceStore(PreferenceRepository repository, int laneCount, int queueCapacity, Consumer<UUID> beforeLoad) {
        this.repository = repository;
        this.beforeLoad = beforeLoad;
        for (int i = 0; i < Math.max(1, laneCount); i++) {
            int lane = i;
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), runnable -> {
                        Thread thread = new Thread(runnable, "Allium-Preferences-" + lane);
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            lanes.add(executor);
        }
    }

    public CompletableFuture<Optional<StoredPreferences>> load(UUID uuid) {
        return submit(uuid, () -> {
            beforeLoad.accept(uuid);
            return repository.load(uuid);
        });
    }

    public CompletableFuture<StoredPreferences> apply(UUID uuid, PreferenceChange change) {
        return submit(uuid, () -> repository.apply(uuid, change));
    }

    /** Queued and running work across lanes. */
    public int pending() {
        return lanes.stream().mapToInt(e -> e.getQueue().size() + e.getActiveCount()).sum();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> submit(UUID uuid, Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            lanes.get(Math.floorMod(uuid.hashCode(), lanes.size())).execute(() -> {
                try {
                    future.complete(work.run());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new StoreBusyException("preference storage queue is full or closed"));
        }
        return future;
    }

    /**
     * Stops accepting work and waits for queued work (writes included) to finish.
     *
     * @return whether everything finished within the timeout
     */
    public boolean close(long timeoutMillis) {
        lanes.forEach(ThreadPoolExecutor::shutdown);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean done = true;
        for (ThreadPoolExecutor lane : lanes) {
            try {
                done &= lane.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return done;
    }

    @Override
    public void close() {
        close(5_000L);
    }
}
