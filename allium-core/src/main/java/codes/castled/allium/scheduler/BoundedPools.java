package codes.castled.allium.scheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Named daemon pools with bounded queues; the one place Allium creates threads for blocking work. */
public final class BoundedPools {

    private BoundedPools() {
    }

    /** {@code threads} daemon threads named {@code <name>-<n>}, a queue of {@code queueCapacity}, rejecting when full. */
    public static ThreadPoolExecutor create(String name, int threads, int queueCapacity) {
        if (name == null || name.isBlank() || threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("invalid pool " + name + " (" + threads + " threads, queue " + queueCapacity + ")");
        }
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, name + "-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(threads > 1);
        return pool;
    }
}
