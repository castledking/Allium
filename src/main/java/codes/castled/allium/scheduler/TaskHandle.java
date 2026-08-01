package codes.castled.allium.scheduler;

import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight abstraction over a scheduled task that supports both Bukkit and Folia.
 */
public final class TaskHandle {

    /** Shared across handles; resolving it is version-dependent and identical for every task. */
    private static volatile Method SCHEDULED_TASK_CANCEL;

    private final @Nullable BukkitTask bukkitTask;
    private final @Nullable Object foliaTask; // io.papermc.paper.threadedregions.scheduler.ScheduledTask
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    TaskHandle(BukkitTask bukkitTask) {
        this.bukkitTask = bukkitTask;
        this.foliaTask = null;
    }

    TaskHandle(Object foliaTask) {
        this.bukkitTask = null;
        this.foliaTask = foliaTask;
    }

    /**
     * Cancel the task if possible.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                if (bukkitTask != null) {
                    bukkitTask.cancel();
                } else if (foliaTask != null) {
                    scheduledTaskCancel(foliaTask).invoke(foliaTask);
                }
            } catch (Throwable t) {
                // The task is still running, so let a later call try again rather than latching
                // "cancelled" on and silently leaving a repeating task looping forever.
                cancelled.set(false);
                codes.castled.allium.managers.core.Text.sendDebugLog(
                    codes.castled.allium.managers.core.Text.DebugSeverity.WARN,
                    "[TaskHandle] cancel() failed: " + t);
            }
        }
    }

    /**
     * Resolves {@code cancel()} on the public {@code ScheduledTask} interface.
     *
     * <p>Paper hands back a package-private implementation ({@code FoliaGlobalRegionScheduler$
     * GlobalScheduledTask}), so resolving the method off {@code task.getClass()} yields one we are
     * not allowed to invoke - it fails with "cannot access a member ... with modifiers public" and
     * the task keeps running. Going through the interface gives an invokable handle.
     */
    private static Method scheduledTaskCancel(Object task) throws NoSuchMethodException {
        Method cached = SCHEDULED_TASK_CANCEL;
        if (cached != null) {
            return cached;
        }

        Method resolved = null;
        try {
            resolved = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask")
                    .getMethod("cancel");
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Older Paper, or a different task type; fall through to the interface walk below.
        }

        if (resolved == null) {
            for (Class<?> type : task.getClass().getInterfaces()) {
                try {
                    resolved = type.getMethod("cancel");
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Keep looking.
                }
            }
        }

        if (resolved == null) {
            throw new NoSuchMethodException("no accessible cancel() on " + task.getClass().getName());
        }

        SCHEDULED_TASK_CANCEL = resolved;
        return resolved;
    }

    /**
     * Returns true if we believe the task is still scheduled (best-effort).
     */
    public boolean isScheduled() {
        if (cancelled.get()) return false;
        try {
            if (bukkitTask != null) {
                return !bukkitTask.isCancelled();
            } else if (foliaTask != null) {
                // No public isCancelled on ScheduledTask; assume scheduled until cancel() called
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Returns true if this task has been cancelled.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
