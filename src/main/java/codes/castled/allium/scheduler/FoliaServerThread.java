package codes.castled.allium.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Schedules against the public Folia interfaces while retaining the Spigot compile target. */
final class FoliaServerThread implements ServerThread {
    private static final Class<?> GLOBAL = api("GlobalRegionScheduler");
    private static final Class<?> ENTITY = api("EntityScheduler");
    private static final Class<?> REGION = api("RegionScheduler");
    private static final Class<?> TASK = api("ScheduledTask");
    private static final Class<?>[] NO_PARAMETERS = {};

    private final Class<?> schedulerApi;
    private final Supplier<Object> scheduler;
    private final BooleanSupplier ownsThread;
    private final Class<?>[] argumentTypes;
    private final Object[] arguments;

    private FoliaServerThread(Class<?> schedulerApi, Supplier<Object> scheduler, BooleanSupplier ownsThread,
                              Class<?>[] argumentTypes, Object... arguments) {
        this.schedulerApi = schedulerApi;
        this.scheduler = scheduler;
        this.ownsThread = ownsThread;
        this.argumentTypes = argumentTypes;
        this.arguments = arguments;
    }

    static ServerThread global(Plugin plugin) {
        return new FoliaServerThread(GLOBAL, () -> invoke(Bukkit.class, null, "getGlobalRegionScheduler", NO_PARAMETERS),
                () -> (boolean) invoke(Bukkit.class, null, "isGlobalTickThread", NO_PARAMETERS),
                new Class<?>[]{Plugin.class, Consumer.class}, plugin, null);
    }

    static ServerThread entity(Plugin plugin, Entity entity) {
        return new FoliaServerThread(ENTITY, () -> invoke(Entity.class, entity, "getScheduler", NO_PARAMETERS),
                () -> (boolean) invoke(Bukkit.class, null, "isOwnedByCurrentRegion", new Class<?>[]{Entity.class}, entity),
                new Class<?>[]{Plugin.class, Consumer.class, Runnable.class}, plugin, null, null);
    }

    static ServerThread region(Plugin plugin, World world, int chunkX, int chunkZ) {
        return new FoliaServerThread(REGION, () -> invoke(Bukkit.class, null, "getRegionScheduler", NO_PARAMETERS),
                () -> (boolean) invoke(Bukkit.class, null, "isOwnedByCurrentRegion",
                        new Class<?>[]{World.class, int.class, int.class}, world, chunkX, chunkZ),
                new Class<?>[]{Plugin.class, World.class, int.class, int.class, Consumer.class}, plugin, world, chunkX, chunkZ, null);
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ, boolean generate) {
        return (CompletableFuture<Chunk>) invoke(World.class, world, "getChunkAtAsync",
                new Class<?>[]{int.class, int.class, boolean.class}, chunkX, chunkZ, generate);
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<Boolean> teleport(Entity entity, Location destination, TeleportCause cause) {
        return (CompletableFuture<Boolean>) invoke(Entity.class, entity, "teleportAsync",
                new Class<?>[]{Location.class, TeleportCause.class}, destination, cause);
    }

    @Override public boolean isServerThread() { return ownsThread.getAsBoolean(); }

    @Override public TaskHandle schedule(Runnable task, long delayTicks, long periodTicks) {
        return schedule(task, () -> {}, delayTicks, periodTicks);
    }

    @Override public TaskHandle schedule(Runnable task, Runnable retired, long delayTicks, long periodTicks) {
        int base = arguments.length;
        int timing = periodTicks > 0 ? 2 : delayTicks > 0 ? 1 : 0;
        Class<?>[] types = Arrays.copyOf(argumentTypes, base + timing);
        Object[] args = Arrays.copyOf(arguments, base + timing);
        Consumer<Object> run = ignored -> task.run();
        if (schedulerApi == ENTITY) {
            args[base - 2] = run;
            args[base - 1] = retired;
        } else {
            args[base - 1] = run;
        }
        if (timing > 0) {
            types[base] = long.class;
            args[base] = Math.max(1L, delayTicks);
        }
        if (timing == 2) {
            types[base + 1] = long.class;
            args[base + 1] = periodTicks;
        }
        String method = switch (timing) {
            case 2 -> "runAtFixedRate";
            case 1 -> "runDelayed";
            default -> "run";
        };
        Object handle = invoke(schedulerApi, scheduler.get(), method, types, args);
        if (handle == null) {
            retired.run();
            return TaskHandle.NONE;
        }
        return new TaskHandle() {
            @Override public void cancel() { invoke(TASK, handle, "cancel", NO_PARAMETERS); }
            @Override public boolean isCancelled() { return (boolean) invoke(TASK, handle, "isCancelled", NO_PARAMETERS); }
        };
    }

    private static Class<?> api(String name) {
        try { return Class.forName("io.papermc.paper.threadedregions.scheduler." + name, false, Bukkit.class.getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("Missing Folia interface " + name, failure); }
    }

    private static Object invoke(Class<?> api, Object receiver, String method, Class<?>[] types, Object... args) {
        try { return api.getMethod(method, types).invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Folia call failed: " + method, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Folia interface: " + api.getSimpleName() + "." + method, failure);
        }
    }
}
