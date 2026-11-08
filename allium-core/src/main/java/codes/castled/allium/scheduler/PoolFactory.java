package codes.castled.allium.scheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** Creates a bounded pool; {@link TaskScheduler#boundedPool} in the plugin, {@link BoundedPools#create} in tests. */
@FunctionalInterface
public interface PoolFactory {

    PoolFactory STANDALONE = BoundedPools::create;

    ThreadPoolExecutor create(String name, int threads, int queueCapacity);
}
