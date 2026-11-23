package codes.castled.ores;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Places ores in chunks and runs /pregen jobs.
 *
 * <p>A chunk remembers each ore it got in its persistent data, under the keys legacy Allium used
 * ({@code allium:ore_generated_<ore>}, and {@code allium:allium_ore_generated} from its first
 * version), so chunks legacy already processed never get a second round of veins.
 */
final class OreGeneration implements AutoCloseable {
    private static final NamespacedKey FIRST_VERSION_MARKER = NamespacedKey.fromString("allium:allium_ore_generated");

    private final Plugin plugin;
    private final AddonConnection core;
    private final TaskScheduler scheduler;
    private final OrePlacement placement;
    private volatile Settings settings;
    private volatile boolean closed;
    private volatile Job job;
    private TaskHandle pump;

    private static final class Job {
        final World world;
        final PregenCursor cursor;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicLong chunks = new AtomicLong(), ores = new AtomicLong();
        volatile boolean paused;

        Job(World world, PregenCursor cursor) {
            this.world = world;
            this.cursor = cursor;
        }
    }

    OreGeneration(Plugin plugin, TaskScheduler scheduler, OrePlacement placement, Settings settings) {
        this(plugin, null, scheduler, placement, settings);
    }

    OreGeneration(Plugin plugin, AddonConnection core, OrePlacement placement, Settings settings) {
        this(plugin, core, core.scheduler(), placement, settings);
    }

    private OreGeneration(Plugin plugin, AddonConnection core, TaskScheduler scheduler, OrePlacement placement, Settings settings) {
        this.plugin = plugin;
        this.core = core;
        this.scheduler = scheduler;
        this.placement = placement;
        this.settings = settings;
    }

    AddonConnection core() { return core; }

    void reload(Settings next) {
        stopGeneration();
        settings = next;
    }

    Settings settings() { return settings; }
    OrePlacement placement() { return placement; }
    List<OreDefinition> definitions() { return settings.ores(); }

    boolean automaticIn(World world) {
        Settings current = settings;
        return !closed && current.enabled() && current.automatic() && current.worlds().contains(world.getName());
    }

    /** Whether every configured ore's blocks exist in CraftEngine. */
    boolean ready() {
        return placement != null && !settings.ores().isEmpty() && settings.ores().stream()
                .allMatch(ore -> placement.exists(ore.block()) && placement.exists(ore.deepslateBlock()));
    }

    /** Places this chunk's ores, after vanilla populated it. Server thread only. */
    int generateOresInChunk(Chunk chunk) {
        Settings current = settings;
        if (closed || !current.enabled() || placement == null || !chunk.isLoaded()) return 0;
        var data = chunk.getPersistentDataContainer();
        if (data.has(FIRST_VERSION_MARKER, PersistentDataType.BYTE)) return 0;
        World world = chunk.getWorld();
        boolean mountain = mountainous(chunk);
        int total = 0;
        for (OreDefinition ore : current.ores()) {
            NamespacedKey key = NamespacedKey.fromString("allium:ore_generated_" + ore.name());
            if (data.has(key, PersistentDataType.BYTE)
                    || !placement.exists(ore.block()) || !placement.exists(ore.deepslateBlock())) continue;
            AtomicBoolean failed = new AtomicBoolean();
            total += OreVeins.generate(world.getSeed(), chunk.getX(), chunk.getZ(), ore,
                    ore.heights(world.getMinHeight(), world.getMaxHeight(), mountain), mountain, position -> {
                        var block = chunk.getBlock(position.x(), position.y(), position.z());
                        Material material = block.getType();
                        if (material != Material.STONE && material != Material.DEEPSLATE) return false;
                        boolean placed = placement.place(block, material == Material.DEEPSLATE ? ore.deepslateBlock() : ore.block());
                        if (!placed) failed.set(true);
                        return placed;
                    });
            // A missing pack or a failed placement must not use the chunk up: it is tried again later.
            if (!failed.get()) data.set(key, PersistentDataType.BYTE, (byte) 1);
        }
        return total;
    }

    static boolean isMountainous(String biome) {
        return biome.contains("PEAK") || biome.contains("SLOPE") || biome.contains("MEADOW")
                || biome.contains("GROVE") || biome.contains("WINDSWEPT") || biome.contains("BADLAND")
                || biome.contains("PLATEAU") || biome.equals("STONY_SHORE");
    }

    private static boolean mountainous(Chunk chunk) {
        World world = chunk.getWorld();
        // Biome is an enum on older servers and a registry interface on newer ones; both are Keyed.
        Object biome = chunk.getBlock(8, Math.max(world.getMinHeight(), Math.min(64, world.getMaxHeight() - 1)), 8).getBiome();
        String name = biome instanceof Keyed keyed ? keyed.getKey().getKey() : String.valueOf(biome);
        return isMountainous(name.toUpperCase(Locale.ROOT));
    }

    synchronized CompletableFuture<Void> startGeneration(World world, int centerX, int centerZ, int radius) {
        if (closed || isGenerating()) return CompletableFuture.failedFuture(new IllegalStateException("A pre-generation is already running."));
        PregenCursor cursor = new PregenCursor(centerX, centerZ, radius);
        Settings current = settings;
        if (current.pregenOres() && (!current.enabled() || !ready())) return CompletableFuture.failedFuture(
                new IllegalStateException("Ore generation is off, or the configured CraftEngine blocks are missing."));
        Job next = new Job(world, cursor);
        job = next;
        long period = current.delayTicks() * TaskScheduler.MILLIS_PER_TICK;
        pump = scheduler.repeatOnServer(() -> {
            dispatch(next);
            return !next.future.isDone();
        }, TaskScheduler.MILLIS_PER_TICK, period);
        return next.future;
    }

    private void dispatch(Job current) {
        Settings now = settings;
        if (closed || job != current || current.future.isDone() || current.paused) return;
        for (int i = 0; i < now.chunksPerTick() && !current.future.isDone() && job == current
                && current.inFlight.get() < now.chunksPerTick() && current.cursor.hasNext(); i++) {
            var position = current.cursor.next();
            current.inFlight.incrementAndGet();
            Chunks.load(current.world, position.x(), position.z()).whenComplete((chunk, error) -> {
                if (error != null) {
                    Runnable fail = () -> finishChunk(current, 0, error);
                    if (core != null && core.isFolia()) fail.run();
                    else if (scheduler.isServerThread()) fail.run();
                    else scheduler.runOnServer(fail);
                    return;
                }
                Runnable step = () -> {
                    if (closed || job != current || current.future.isDone()) { finishChunk(current, 0, null); return; }
                    try {
                        chunk.addPluginChunkTicket(plugin);
                        finishChunk(current, now.pregenOres() ? generateOresInChunk(chunk) : 0, null);
                    } catch (Throwable failed) {
                        finishChunk(current, 0, failed);
                    } finally {
                        chunk.removePluginChunkTicket(plugin);
                    }
                };
                if (core != null && core.isFolia()) core.onRegion(current.world, position.x(), position.z(), step);
                else if (scheduler.isServerThread()) step.run();
                else scheduler.runOnServer(step);
            });
        }
        if (!current.cursor.hasNext() && current.inFlight.get() == 0) complete(current, null);
    }

    private void finishChunk(Job current, int placed, Throwable error) {
        try {
            if (closed || job != current || current.future.isDone()) return;
            if (error != null) {
                complete(current, error);
                return;
            }
            current.chunks.incrementAndGet();
            current.ores.addAndGet(placed);
        } finally {
            current.inFlight.decrementAndGet();
        }
    }

    private synchronized void complete(Job current, Throwable error) {
        if (job != current || current.future.isDone()) return;
        if (pump != null) {
            pump.cancel();
            pump = null;
        }
        if (error == null) current.future.complete(null);
        else current.future.completeExceptionally(error);
    }

    synchronized void stopGeneration() {
        if (pump != null) {
            pump.cancel();
            pump = null;
        }
        if (job != null && !job.future.isDone()) job.future.completeExceptionally(new CancellationException("Generation stopped"));
    }

    void pauseGeneration() { if (isGenerating()) job.paused = true; }
    void resumeGeneration() { if (isGenerating()) job.paused = false; }
    boolean isGenerating() { return job != null && !job.future.isDone(); }
    boolean isPaused() { return isGenerating() && job.paused; }
    long generatedChunks() { return job == null ? 0 : job.chunks.get(); }
    long generatedOres() { return job == null ? 0 : job.ores.get(); }
    long totalChunks() { return job == null ? 0 : job.cursor.total(); }
    String worldName() { return job == null ? "-" : job.world.getName(); }

    @Override
    public void close() {
        closed = true;
        stopGeneration();
    }
}
