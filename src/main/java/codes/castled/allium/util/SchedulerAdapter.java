package codes.castled.allium.util;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Thin shim that delegates to the Folia-safe scheduler in
 * {@code codes.castled.allium.scheduler.SchedulerAdapter}.
 */
public final class SchedulerAdapter {

    private SchedulerAdapter() {}

    @Deprecated
    public interface TaskHandle {
        void cancel();
    }

    private static Plugin plugin() {
        return codes.castled.allium.PluginStart.getInstance();
    }

    private static TaskHandle wrap(codes.castled.allium.scheduler.TaskHandle h) {
        return h == null ? () -> {} : () -> h.cancel();
    }

    @Deprecated
    public static void init(Plugin pl) { }

    @Deprecated
    public static boolean isFolia() {
        return codes.castled.allium.scheduler.SchedulerAdapter.isFolia();
    }

    // Overloads that accept a Plugin arg (ignored — we use PluginStart.getInstance())
    // These allow mechanical replacement of Bukkit.getScheduler().xxx(plugin, task, ...)
    @Deprecated
    public static TaskHandle runTask(Plugin plugin, Runnable task) {
        return run(task);
    }

    @Deprecated
    public static TaskHandle runTaskLater(Plugin plugin, Runnable task, long delay) {
        return runLater(task, delay);
    }

    @Deprecated
    public static TaskHandle runDelayed(Plugin plugin, Runnable task, long delay) {
        return runLater(task, delay);
    }

    @Deprecated
    public static TaskHandle runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return runTimer(task, delay, period);
    }

    @Deprecated
    public static TaskHandle runTaskAsynchronously(Plugin plugin, Runnable task) {
        return runAsync(task);
    }

    @Deprecated
    public static TaskHandle runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return runLaterAsync(task, delay);
    }

    @Deprecated
    public static TaskHandle runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return runTimer(task, delay, period);
    }

    // Original plugin-less API
    @Deprecated
    public static TaskHandle run(Runnable task) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runGlobal(plugin(), task));
    }

    @Deprecated
    public static TaskHandle runLater(Runnable task, long delay) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runLaterGlobal(plugin(), task, delay));
    }

    @Deprecated
    public static TaskHandle runDelayed(Runnable task, long delay) {
        return runLater(task, delay);
    }

    @Deprecated
    public static TaskHandle runTimer(Runnable task, long delay, long period) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runRepeatingGlobal(plugin(), task, delay, period));
    }

    @Deprecated
    public static TaskHandle runAsync(Runnable task) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runAsyncNow(plugin(), task));
    }

    @Deprecated
    public static TaskHandle runLaterAsync(Runnable task, long delay) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runAsyncLater(plugin(), task, delay));
    }

    // Entity scheduling - plugin-less
    @Deprecated
    public static TaskHandle runAtEntity(Entity entity, Runnable task) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runEntity(plugin(), entity, task, null));
    }

    @Deprecated
    public static TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        return wrap(codes.castled.allium.scheduler.SchedulerAdapter.runLaterEntity(plugin(), entity, task, delayTicks));
    }

    // Entity scheduling - with Plugin arg (for mechanical replacement)
    @Deprecated
    public static TaskHandle runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        return runAtEntity(entity, task);
    }

    @Deprecated
    public static TaskHandle runAtEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        return runAtEntityLater(entity, task, delayTicks);
    }
}
