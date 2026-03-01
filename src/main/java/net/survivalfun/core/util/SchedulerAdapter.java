package net.survivalfun.core.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Entity;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public final class SchedulerAdapter {
    public interface TaskHandle {
        void cancel();
    }

    private static Plugin plugin;
    private static volatile boolean foliaAvailable;
    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    // Cached reflection bits for Folia GlobalRegionScheduler
    private static Method getGlobalRegionScheduler; // Server#getGlobalRegionScheduler
    private static Method grsRun;                   // GlobalRegionScheduler#run(Plugin, Consumer<ScheduledTask>)
    private static Method grsRunDelayed;            // GlobalRegionScheduler#runDelayed(Plugin, Consumer<ScheduledTask>, long)
    private static Method grsRunAtFixedRate;        // GlobalRegionScheduler#runAtFixedRate(Plugin, Consumer<ScheduledTask>, long, long)
    private static Method scheduledTaskCancel;      // ScheduledTask#cancel()

    // Cached reflection bits for Folia EntityScheduler
    private static Method entityGetScheduler;       // Entity#getScheduler()

    private static final Map<Class<?>, SchedulerMethods> entitySchedulerCache = new ConcurrentHashMap<>();

    private SchedulerAdapter() {}

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null) {
            return null;
        }
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static SchedulerMethods resolveEntitySchedulerMethods(Object scheduler) {
        if (scheduler == null) {
            return null;
        }
        Class<?> schedulerClass = scheduler.getClass();
        return entitySchedulerCache.computeIfAbsent(schedulerClass, cls -> {
            SchedulerMethods methods = new SchedulerMethods();

            Method runnableRun = findMethod(cls, "run", Plugin.class, Runnable.class);
            Method consumerRun = findMethod(cls, "run", Plugin.class, java.util.function.Consumer.class);
            Method entityConsumerRun = findMethod(cls, "run", Plugin.class, Entity.class, java.util.function.Consumer.class);

            if (runnableRun != null) {
                methods.runMethod = runnableRun;
                methods.runUsesRunnable = true;
            } else if (consumerRun != null) {
                methods.runMethod = consumerRun;
            } else if (entityConsumerRun != null) {
                methods.runMethod = entityConsumerRun;
                methods.runRequiresEntity = true;
            }

            Method runnableDelayed = findMethod(cls, "runDelayed", Plugin.class, Runnable.class, long.class);
            Method consumerDelayed = findMethod(cls, "runDelayed", Plugin.class, java.util.function.Consumer.class, long.class);
            Method entityConsumerDelayed = findMethod(cls, "runDelayed", Plugin.class, Entity.class, java.util.function.Consumer.class, long.class);

            if (runnableDelayed != null) {
                methods.runDelayedMethod = runnableDelayed;
                methods.runDelayedUsesRunnable = true;
            } else if (consumerDelayed != null) {
                methods.runDelayedMethod = consumerDelayed;
            } else if (entityConsumerDelayed != null) {
                methods.runDelayedMethod = entityConsumerDelayed;
                methods.runDelayedRequiresEntity = true;
            }

            return methods;
        });
    }

    private static final class SchedulerMethods {
        Method runMethod;
        Method runDelayedMethod;
        boolean runRequiresEntity;
        boolean runDelayedRequiresEntity;
        boolean runUsesRunnable;
        boolean runDelayedUsesRunnable;

        boolean hasRunMethod() {
            return runMethod != null;
        }

        boolean hasRunDelayedMethod() {
            return runDelayedMethod != null;
        }
    }

    public static void init(Plugin pl) {
        plugin = Objects.requireNonNull(pl, "plugin");
        detectFolia();
    }

    private static void detectFolia() {
        try {
            // Check for Folia-specific threading model by looking for RegionizedServer class
            // This is more reliable than checking server names since forks might have different names
            boolean hasRegionizedServer = false;
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                hasRegionizedServer = true;
            } catch (ClassNotFoundException ignored) {
                // Not a regionized server (Folia/Canvas/etc)
            }

            // Also check server name/version as fallback (Canvas, Folia, etc. use regionized scheduler)
            String serverName = Bukkit.getName();
            String version = Bukkit.getVersion();
            String combined = ((serverName != null ? serverName : "") + " " + (version != null ? version : "")).toLowerCase();
            boolean isRegionizedByName = combined.contains("folia") || combined.contains("canvas");

            if (hasRegionizedServer || isRegionizedByName) {
                // Try to resolve Server#getGlobalRegionScheduler at runtime
                getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
                Object grs = getGlobalRegionScheduler.invoke(Bukkit.getServer());
                Class<?> grsClass = grs.getClass();
                // resolve methods
                // run(Plugin, Consumer<ScheduledTask>)
                grsRun = grsClass.getMethod("run", Plugin.class, java.util.function.Consumer.class);
                // runDelayed(Plugin, Consumer<ScheduledTask>, long)
                grsRunDelayed = grsClass.getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class, long.class);
                // runAtFixedRate(Plugin, Consumer<ScheduledTask>, long, long)
                grsRunAtFixedRate = grsClass.getMethod("runAtFixedRate", Plugin.class, java.util.function.Consumer.class, long.class, long.class);
                // ScheduledTask#cancel
                Class<?> scheduledTaskClass = grsRun.getReturnType();
                scheduledTaskCancel = scheduledTaskClass.getMethod("cancel");

                // Resolve Entity#getScheduler method now; resolve scheduler methods lazily
                entityGetScheduler = Entity.class.getMethod("getScheduler");

                foliaAvailable = true;
            } else {
                foliaAvailable = false; // Not Folia or Folia-like server
            }
        } catch (Throwable t) {
            foliaAvailable = false; // Fall back to Bukkit
        }
    }

    public static boolean isFolia() {
        if (plugin == null) {
            return false;
        }
        return foliaAvailable;
    }

    public static TaskHandle run(Runnable task) {
        ensureInit();
        if (foliaAvailable) {
            return foliaRun(task);
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, task);
        return () -> bt.cancel();
    }

    public static TaskHandle runLater(Runnable task, long delayTicks) {
        ensureInit();
        if (foliaAvailable) {
            return foliaRunDelayed(task, delayTicks);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return () -> bt.cancel();
    }

    public static TaskHandle runDelayed(Runnable task, long delayTicks) {
        return runLater(task, delayTicks);
    }

    public static TaskHandle runTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        ensureInit();
        if (foliaAvailable) {
            return foliaRunAtFixedRate(task, initialDelayTicks, periodTicks);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        return () -> bt.cancel();
    }

    public static TaskHandle runAsync(Runnable task) {
        ensureInit();
        if (foliaAvailable) {
            // Use ExecutorService for async operations on Folia
            java.util.concurrent.Future<?> future = asyncExecutor.submit(task);
            return () -> future.cancel(true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        return () -> bt.cancel();
    }

    public static TaskHandle runLaterAsync(Runnable task, long delayTicks) {
        ensureInit();
        if (foliaAvailable) {
            // Use ExecutorService with scheduled delay for Folia
            long delayMs = delayTicks * 50; // Convert ticks to milliseconds
            java.util.concurrent.ScheduledExecutorService scheduledExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ScheduledFuture<?> future = scheduledExecutor.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return () -> {
                future.cancel(true);
                scheduledExecutor.shutdown();
            };
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        return () -> bt.cancel();
    }

    private static boolean isDebugScheduler() {
        PluginStart p = PluginStart.getInstance();
        return p != null && p.getConfig().getBoolean("debug-scheduler", false);
    }

    // Folia-safe: schedule at the specific entity's scheduler
    public static TaskHandle runAtEntity(Entity entity, Runnable task) {
        ensureInit();
        String entityName = entity != null ? entity.getName() : "null";
        String taskInfo = task != null ? task.getClass().getSimpleName() : "null";
        
        if (isDebugScheduler()) {
            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Scheduling task " + taskInfo + " for entity: " + entityName);
        }
        
        if (foliaAvailable) {
            try {
                Object es = entityGetScheduler.invoke(entity);
                if (es != null) {
                    SchedulerMethods methods = resolveEntitySchedulerMethods(es);
                    if (methods != null && methods.hasRunMethod()) {
                        if (methods.runRequiresEntity && entity == null) {
                            throw new IllegalArgumentException("EntityScheduler requires a non-null entity instance");
                        }

                        Runnable runnableWrapper = () -> {
                            if (isDebugScheduler()) {
                                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Starting execution of task " + taskInfo + " for " + entityName);
                            }
                            long startTime = System.currentTimeMillis();
                            try {
                                if (task != null) {
                                    task.run();
                                    if (isDebugScheduler()) {
                                        long duration = System.currentTimeMillis() - startTime;
                                        Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Completed task " + taskInfo + " for " + entityName + " in " + duration + "ms");
                                    }
                                }
                            } catch (Throwable t) {
                                Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntity] Error in task " + taskInfo + " for " + entityName + ": " + t.getMessage());
                                t.printStackTrace();
                            }
                        };

                        if (isDebugScheduler()) {
                            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Using Folia entity scheduler for " + entityName);
                        }
                        Object scheduledEntityTask;
                        if (methods.runUsesRunnable) {
                            scheduledEntityTask = methods.runMethod.invoke(es, plugin, runnableWrapper);
                        } else {
                            java.util.function.Consumer<Object> consumer = st -> runnableWrapper.run();
                            scheduledEntityTask = methods.runRequiresEntity
                                    ? methods.runMethod.invoke(es, plugin, entity, consumer)
                                    : methods.runMethod.invoke(es, plugin, consumer);
                        }
                        return () -> {
                            if (isDebugScheduler()) {
                                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Cancelling task " + taskInfo + " for " + entityName);
                            }
                            invokeCancelSafe(scheduledEntityTask);
                        };
                    }
                }
            } catch (Throwable t) {
                Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntity] Error scheduling task for " + entityName + ": " + t.getMessage());
                t.printStackTrace();
            }
        }

        Runnable wrappedTask = () -> {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Starting fallback task execution for " + entityName);
            }
            long startTime = System.currentTimeMillis();
            try {
                if (task != null) {
                    task.run();
                    if (isDebugScheduler()) {
                        long duration = System.currentTimeMillis() - startTime;
                        Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Completed fallback task for " + entityName + " in " + duration + "ms");
                    }
                }
            } catch (Throwable t) {
                Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntity] Error in fallback task for " + entityName + ": " + t.getMessage());
                t.printStackTrace();
            }
        };

        if (foliaAvailable && entity != null && entity.getLocation() != null) {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Falling back to region scheduler for " + entityName);
            }
            Object regionTask = Bukkit.getRegionScheduler().run(plugin, entity.getLocation(), schTask -> wrappedTask.run());
            return () -> {
                if (isDebugScheduler()) {
                    Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Cancelling region fallback task for " + entityName);
                }
                invokeCancelSafe(regionTask);
            };
        }

        if (foliaAvailable) {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Falling back to Folia global scheduler for " + entityName);
            }
            TaskHandle handle = foliaRun(wrappedTask);
            return () -> {
                if (isDebugScheduler()) {
                    Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Cancelling Folia fallback task for " + entityName);
                }
                handle.cancel();
            };
        }

        if (isDebugScheduler()) {
            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Falling back to Bukkit scheduler for " + entityName);
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, wrappedTask);
        return () -> {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntity] Cancelling Bukkit task for " + entityName);
            }
            bt.cancel();
        };
    }

    public static TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        ensureInit();
        String entityName = entity != null ? entity.getName() : "null";
        String taskInfo = task != null ? task.getClass().getSimpleName() : "null";
        
        if (isDebugScheduler()) {
            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Scheduling delayed task " + taskInfo + " for entity: " + 
                entityName + " with delay: " + delayTicks + " ticks");
        }
        
        if (foliaAvailable) {
            try {
                Object es = entityGetScheduler.invoke(entity);
                SchedulerMethods methods = resolveEntitySchedulerMethods(es);
                if (methods != null && methods.hasRunDelayedMethod()) {
                    if (methods.runDelayedRequiresEntity && entity == null) {
                        throw new IllegalArgumentException("EntityScheduler requires a non-null entity instance for delayed execution");
                    }

                    Runnable runnableWrapper = () -> {
                        if (isDebugScheduler()) {
                            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Starting execution of delayed task " + taskInfo + " for " + entityName);
                        }
                        long startTime = System.currentTimeMillis();
                        try {
                            if (task != null) {
                                task.run();
                                if (isDebugScheduler()) {
                                    long duration = System.currentTimeMillis() - startTime;
                                    Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Completed delayed task " + taskInfo + " for " + 
                                        entityName + " in " + duration + "ms");
                                }
                            }
                        } catch (Throwable t) {
                            Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntityLater] Error in delayed task " + taskInfo + 
                                " for " + entityName + ": " + t.getMessage());
                            t.printStackTrace();
                        }
                    };

                    if (isDebugScheduler()) {
                        Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Using Folia entity scheduler with delay for " + entityName);
                    }
                    try {
                        Object scheduledEntityTask;
                        if (methods.runDelayedUsesRunnable) {
                            scheduledEntityTask = methods.runDelayedMethod.invoke(es, plugin, runnableWrapper, delayTicks);
                        } else {
                            java.util.function.Consumer<Object> consumer = st -> runnableWrapper.run();
                            scheduledEntityTask = methods.runDelayedRequiresEntity
                                    ? methods.runDelayedMethod.invoke(es, plugin, entity, consumer, delayTicks)
                                    : methods.runDelayedMethod.invoke(es, plugin, consumer, delayTicks);
                        }
                        final Object scheduledTaskFinal = scheduledEntityTask;
                        return () -> {
                            if (isDebugScheduler()) {
                                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Cancelling delayed task " + taskInfo + " for " + entityName);
                            }
                            invokeCancelSafe(scheduledTaskFinal);
                        };
                    } catch (Throwable reflectionError) {
                        Text.sendDebugLog(WARN, "[SCHEDULER] [runAtEntityLater] Failed invoking entity scheduler delayed method, will fall back", reflectionError);
                    }
                } else {
                    Text.sendDebugLog(WARN, "[SCHEDULER] [runAtEntityLater] No compatible entity scheduler delayed methods for " + entityName);
                }
            } catch (Throwable t) {
                Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntityLater] Error scheduling delayed task for " + entityName + ": " + t.getMessage());
                t.printStackTrace();
            }
        }

        Runnable wrappedTask = () -> {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Starting fallback delayed task execution for " + entityName);
            }
            long startTime = System.currentTimeMillis();
            try {
                if (task != null) {
                    task.run();
                    if (isDebugScheduler()) {
                        long duration = System.currentTimeMillis() - startTime;
                        Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Completed fallback delayed task for " + entityName + " in " + duration + "ms");
                    }
                }
            } catch (Throwable t) {
                Text.sendDebugLog(ERROR, "[SCHEDULER] [runAtEntityLater] Error in fallback delayed task for " + entityName + ": " + t.getMessage());
                t.printStackTrace();
            }
        };

        if (foliaAvailable && entity != null && entity.getLocation() != null) {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Falling back to region scheduler with delay for " + entityName);
            }
            Object regionTask = Bukkit.getRegionScheduler().runDelayed(plugin, entity.getLocation(), schTask -> wrappedTask.run(), delayTicks);
            return () -> {
                if (isDebugScheduler()) {
                    Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Cancelling region fallback delayed task for " + entityName);
                }
                invokeCancelSafe(regionTask);
            };
        }

        if (foliaAvailable) {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Falling back to Folia global scheduler for " + entityName);
            }
            TaskHandle handle = foliaRunDelayed(wrappedTask, delayTicks);
            return () -> {
                if (isDebugScheduler()) {
                    Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Cancelling Folia fallback delayed task for " + entityName);
                }
                handle.cancel();
            };
        }

        if (isDebugScheduler()) {
            Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Falling back to Bukkit scheduler for " + entityName);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, wrappedTask, delayTicks);
        return () -> {
            if (isDebugScheduler()) {
                Text.sendDebugLog(INFO, "[SCHEDULER] [runAtEntityLater] Cancelling Bukkit delayed task for " + entityName);
            }
            bt.cancel();
        };
    }

    private static TaskHandle foliaRun(Runnable task) {
        try {
            Object grs = getGlobalRegionScheduler.invoke(Bukkit.getServer());
            Object scheduledTask = grsRun.invoke(grs, plugin, (java.util.function.Consumer<Object>) st -> task.run());
            return () -> invokeCancelSafe(scheduledTask);
        } catch (IllegalAccessException | InvocationTargetException e) {
            // Fallback to Bukkit main thread run
            BukkitTask bt = Bukkit.getScheduler().runTask(plugin, task);
            return () -> bt.cancel();
        }
    }

    private static TaskHandle foliaRunDelayed(Runnable task, long delay) {
        try {
            Object grs = getGlobalRegionScheduler.invoke(Bukkit.getServer());
            Object scheduledTask = grsRunDelayed.invoke(grs, plugin, (java.util.function.Consumer<Object>) st -> task.run(), delay);
            return () -> invokeCancelSafe(scheduledTask);
        } catch (IllegalAccessException | InvocationTargetException e) {
            BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            return () -> bt.cancel();
        }
    }

    private static TaskHandle foliaRunAtFixedRate(Runnable task, long initialDelay, long period) {
        // Prefer direct API (works reliably on Folia/Canvas); reflection can fail on some forks
        try {
            var scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (ScheduledTask st) -> task.run(), initialDelay, period);
            return () -> scheduledTask.cancel();
        } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
            // Paper/Folia API not available at runtime (e.g. Spigot)
        }
        try {
            Object grs = getGlobalRegionScheduler.invoke(Bukkit.getServer());
            Object scheduledTask = grsRunAtFixedRate.invoke(grs, plugin, (java.util.function.Consumer<Object>) st -> task.run(), initialDelay, period);
            return () -> invokeCancelSafe(scheduledTask);
        } catch (IllegalAccessException | InvocationTargetException e) {
            // On Folia, Bukkit.getScheduler().runTaskTimer throws UnsupportedOperationException - do not use it
            if (foliaAvailable) {
                // Fallback: use ScheduledExecutorService and schedule work on global region each tick
                long initialDelayMs = initialDelay * 50;
                long periodMs = period * 50;
                java.util.concurrent.ScheduledExecutorService exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                java.util.concurrent.ScheduledFuture<?> future = exec.scheduleAtFixedRate(() -> {
                    try {
                        Object grs = getGlobalRegionScheduler.invoke(Bukkit.getServer());
                        grsRun.invoke(grs, plugin, (java.util.function.Consumer<Object>) st -> task.run());
                    } catch (Throwable t) {
                        Text.sendDebugLog(ERROR, "[SCHEDULER] foliaRunAtFixedRate fallback error: " + t.getMessage());
                    }
                }, initialDelayMs, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                return () -> {
                    future.cancel(true);
                    exec.shutdown();
                };
            }
            BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period);
            return () -> bt.cancel();
        }
    }

    private static void invokeCancelSafe(Object scheduledTask) {
        if (scheduledTask == null) return;
        try {
            scheduledTaskCancel.invoke(scheduledTask);
        } catch (Throwable ignored) {}
    }

    private static void ensureInit() {
        if (plugin == null) {
            String errorMsg = "SchedulerAdapter not initialized. Call SchedulerAdapter.init(plugin) in onEnable().";
            if (plugin != null) {
                Text.sendDebugLog(ERROR, "[SCHEDULER] " + errorMsg);
            } else {
                System.err.println("[SCHEDULER] [ERROR] " + errorMsg);
            }
            throw new IllegalStateException(errorMsg);
        }
    }
}
