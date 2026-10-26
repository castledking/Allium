package codes.castled.allium.managers.world;

import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

/** Deterministic custom ores and bounded asynchronous pre-generation. */
public final class OreGenerationManager implements AutoCloseable {
    private final Plugin plugin;
    private final Supplier<? extends ConfigurationSection> configuration;
    private final Predicate<Chunk> mountainous;
    private final OrePlacement placement;
    private final NamespacedKey legacyMarker;
    private volatile List<OreDefinition> ores = List.of();
    private volatile Set<String> worlds = Set.of();
    private volatile boolean enabled, automatic, closed;
    private int chunksPerTick, delayTicks;
    private boolean pregenOres;
    private volatile Job job;
    private TaskHandle pump;

    private static final class Job {
        final World world;
        final PregenCursor cursor;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicLong chunks = new AtomicLong(), ores = new AtomicLong();
        volatile boolean paused;
        Job(World world, PregenCursor cursor) { this.world = world; this.cursor = cursor; }
    }

    public OreGenerationManager(JavaPlugin plugin, OrePlacement placement) {
        this(plugin, placement, plugin::getConfig, OreGenerationManager::mountainousChunk);
    }

    OreGenerationManager(Plugin plugin, OrePlacement placement, Supplier<? extends ConfigurationSection> configuration,
                         Predicate<Chunk> mountainous) {
        this.plugin = plugin; this.placement = placement;
        this.configuration = configuration;
        this.mountainous = mountainous;
        this.legacyMarker = new NamespacedKey(plugin, "allium_ore_generated");
        reload();
    }

    public void reload() {
        stopGeneration();
        var config = configuration.get();
        enabled = config.getBoolean("ore-generation.enabled", false);
        automatic = config.getBoolean("ore-generation.auto-generate", true);
        worlds = Set.copyOf(config.getStringList("ore-generation.worlds"));
        chunksPerTick = Math.clamp(config.getInt("pregen.chunks-per-tick", 2), 1, 16);
        delayTicks = Math.clamp(config.getInt("pregen.delay-ticks", 1), 1, 200);
        pregenOres = config.getBoolean("pregen.generate-ores", true);
        ConfigurationSection root = config.getConfigurationSection("ore-generation.ores");
        if (root == null) root = config.getConfigurationSection("ore-generation");
        List<OreDefinition> parsed = new ArrayList<>();
        if (root != null) for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            try { parsed.add(OreDefinition.read(name, section)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("[OreGen] " + name + ": " + e.getMessage()); }
        }
        ores = List.copyOf(parsed);
    }

    public boolean automaticIn(World world) {
        return !closed && enabled && automatic && worlds.contains(world.getName());
    }
    public int chunksPerTick() { return chunksPerTick; }
    public int delayTicks() { return delayTicks; }
    public List<OreDefinition> definitions() { return ores; }
    public OrePlacement placement() { return placement; }
    public boolean ready() {
        return placement != null && !ores.isEmpty() && ores.stream()
            .allMatch(ore -> placement.exists(ore.block()) && placement.exists(ore.deepslateBlock()));
    }

    /** Must run on the chunk's owning thread, after vanilla population. */
    public int generateOresInChunk(Chunk chunk) {
        if (closed || !enabled || placement == null || !chunk.isLoaded()) return 0;
        var data = chunk.getPersistentDataContainer();
        if (data.has(legacyMarker, PersistentDataType.BYTE)) return 0;
        World world = chunk.getWorld();
        boolean mountain = mountainous.test(chunk);
        int total = 0;
        for (OreDefinition ore : ores) {
            NamespacedKey key = new NamespacedKey(plugin, "ore_generated_" + ore.name());
            if (data.has(key, PersistentDataType.BYTE)
                || !placement.exists(ore.block()) || !placement.exists(ore.deepslateBlock())) continue;
            AtomicBoolean failed = new AtomicBoolean();
            total += OreVeins.generate(world.getSeed(), chunk.getX(), chunk.getZ(), ore,
                ore.heights(world.getMinHeight(), world.getMaxHeight(), mountain), mountain, position -> {
                    var block = chunk.getBlock(position.x(), position.y(), position.z());
                    Material material = block.getType();
                    if (material != Material.STONE && material != Material.DEEPSLATE) return false;
                    boolean success = placement.place(block, material == Material.DEEPSLATE ? ore.deepslateBlock() : ore.block());
                    if (!success) failed.set(true);
                    return success;
                });
            // Missing packs or a failed placement must never permanently consume a chunk.
            if (!failed.get()) data.set(key, PersistentDataType.BYTE, (byte) 1);
        }
        return total;
    }

    static boolean isMountainous(String biome) {
        return biome.contains("PEAK") || biome.contains("SLOPE") || biome.contains("MEADOW")
            || biome.contains("GROVE") || biome.contains("WINDSWEPT") || biome.contains("BADLAND")
            || biome.contains("PLATEAU") || biome.equals("STONY_SHORE");
    }

    private static boolean mountainousChunk(Chunk chunk) {
        World world = chunk.getWorld();
        return isMountainous(chunk.getBlock(8, Math.clamp(64, world.getMinHeight(), world.getMaxHeight() - 1), 8)
            .getBiome().getKey().getKey().toUpperCase(Locale.ROOT));
    }

    public synchronized CompletableFuture<Void> startGeneration(World world, int centerX, int centerZ, int radius) {
        if (closed || isGenerating()) return CompletableFuture.failedFuture(new IllegalStateException("Generation is already running or shutting down"));
        PregenCursor cursor = new PregenCursor(centerX, centerZ, radius);
        if (pregenOres && (!enabled || !ready())) return CompletableFuture.failedFuture(
            new IllegalStateException("Ore generation is disabled or configured CraftEngine blocks are missing"));
        Job next = new Job(world, cursor);
        job = next;
        pump = SchedulerAdapter.runRepeatingGlobal(plugin, () -> dispatch(next), 1, delayTicks);
        return next.future;
    }

    private void dispatch(Job current) {
        if (closed || job != current || current.future.isDone() || current.paused) return;
        for (int i = 0; i < chunksPerTick && !current.future.isDone() && job == current
            && current.inFlight.get() < chunksPerTick && current.cursor.hasNext(); i++) {
            var position = current.cursor.next();
            current.inFlight.incrementAndGet();
            try {
                current.world.getChunkAtAsync(position.x(), position.z(), true).whenComplete((chunk, error) -> {
                    if (error != null) { finishChunk(current, 0, error); return; }
                    if (closed || job != current || current.future.isDone()) { finishChunk(current, 0, null); return; }
                    Location location = new Location(current.world, position.x() * 16 + 8, current.world.getMinHeight(), position.z() * 16 + 8);
                    SchedulerAdapter.runAtLocation(plugin, location, () -> {
                        if (closed || job != current || current.future.isDone()) { finishChunk(current, 0, null); return; }
                        try {
                            chunk.addPluginChunkTicket(plugin);
                            int placed = pregenOres ? generateOresInChunk(chunk) : 0;
                            finishChunk(current, placed, null);
                        } catch (Throwable t) { finishChunk(current, 0, t); }
                        finally { chunk.removePluginChunkTicket(plugin); }
                    });
                });
            } catch (Throwable t) { finishChunk(current, 0, t); }
        }
        if (!current.cursor.hasNext() && current.inFlight.get() == 0) complete(current, null);
    }

    private void finishChunk(Job current, int placed, Throwable error) {
        try {
            if (closed || job != current || current.future.isDone()) return;
            if (error != null) { complete(current, error); return; }
            current.chunks.incrementAndGet();
            current.ores.addAndGet(placed);
        } finally { current.inFlight.decrementAndGet(); }
        // Completion is checked by the global pump after ALL region work finishes.
    }
    private synchronized void complete(Job current, Throwable error) {
        if (job != current || current.future.isDone()) return;
        if (pump != null) { pump.cancel(); pump = null; }
        if (error == null) current.future.complete(null); else current.future.completeExceptionally(error);
    }
    public synchronized void stopGeneration() {
        if (pump != null) { pump.cancel(); pump = null; }
        if (job != null && !job.future.isDone()) job.future.completeExceptionally(new CancellationException("Generation stopped"));
    }
    public void pauseGeneration() { if (isGenerating()) job.paused = true; }
    public void resumeGeneration() { if (isGenerating()) job.paused = false; }
    public boolean isGenerating() { return job != null && !job.future.isDone(); }
    public boolean isPaused() { return isGenerating() && job.paused; }
    public long getGeneratedChunks() { return job == null ? 0 : job.chunks.get(); }
    public long getGeneratedOres() { return job == null ? 0 : job.ores.get(); }
    public long getTotalChunks() { return job == null ? 0 : job.cursor.total(); }
    public String getWorldName() { return job == null ? "-" : job.world.getName(); }
    @Override public void close() { closed = true; stopGeneration(); }
}
