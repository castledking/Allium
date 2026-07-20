package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.CropClock;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import codes.castled.allium.scheduler.SchedulerAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Chunk-indexed in-memory registry of loaded crop instances. Crops in
 * unloaded chunks live only in the database; a chunk load asynchronously
 * queries its crops, applies offline catch-up, and reconciles visuals — never
 * forcing other chunks to load. A chunk unload persists and evicts.
 */
public final class CropInstanceService {

    private final Plugin plugin;
    private final Logger logger;
    private final CropRegistry registry;
    private final CropStorage storage;
    private final CropVisualService visuals;

    /** Crops by anchor chunk; values keyed by instance id. */
    private final Map<ChunkKey, Map<UUID, CropInstance>> byChunk = new ConcurrentHashMap<>();
    /** Every occupied cell (anchor + footprint) of loaded crops. */
    private final Map<BlockPositionKey, CropInstance> byCell = new ConcurrentHashMap<>();
    /** Guards double-processing when load events race. */
    private final Map<ChunkKey, Boolean> loadingOrLoaded = new ConcurrentHashMap<>();

    public CropInstanceService(Plugin plugin, CropRegistry registry, CropStorage storage, CropVisualService visuals) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.registry = registry;
        this.storage = storage;
        this.visuals = visuals;
    }

    // ==================== lookup ====================

    public Optional<CropInstance> at(BlockPositionKey cell) {
        return Optional.ofNullable(byCell.get(cell));
    }

    public boolean isOccupied(BlockPositionKey cell) {
        return byCell.containsKey(cell);
    }

    /**
     * Finds a loaded crop by its instance id.
     *
     * <p>Used when resolving a clicked hitbox back to its crop: the entity
     * carries the instance id, not a position, and a multi-cell crop has
     * several positions but only one id.
     */
    public Optional<CropInstance> byInstanceId(UUID instanceId) {
        if (instanceId == null) {
            return Optional.empty();
        }
        for (CropInstance crop : byCell.values()) {
            if (crop.instanceId().equals(instanceId)) {
                return Optional.of(crop);
            }
        }
        return Optional.empty();
    }

    public int loadedCount() {
        return byCell.values().stream().collect(java.util.stream.Collectors.toSet()).size();
    }

    public Map<UUID, Integer> countsByWorld() {
        Map<UUID, Integer> counts = new ConcurrentHashMap<>();
        byChunk.forEach((chunk, crops) ->
            counts.merge(chunk.worldId(), crops.size(), Integer::sum));
        return counts;
    }

    /** Iterates due crops across loaded chunks, up to {@code limit}. */
    public List<CropInstance> collectDue(long now, int limit) {
        List<CropInstance> due = new ArrayList<>();
        outer:
        for (Map<UUID, CropInstance> crops : byChunk.values()) {
            for (CropInstance crop : crops.values()) {
                if (crop.isDue(now)) {
                    due.add(crop);
                    if (due.size() >= limit) break outer;
                }
            }
        }
        return due;
    }

    public void forEachLoaded(Consumer<CropInstance> consumer) {
        byChunk.values().forEach(crops -> crops.values().forEach(consumer));
    }

    // ==================== mutation ====================

    /** Registers a crop in the loaded indexes (region thread of the crop). */
    public void register(CropInstance crop) {
        byChunk.computeIfAbsent(crop.position().chunkKey(), k -> new ConcurrentHashMap<>())
            .put(crop.instanceId(), crop);
        for (BlockPositionKey cell : crop.cells()) {
            byCell.put(cell, crop);
        }
    }

    /** Removes a crop from the loaded indexes only (not the database). */
    public void unregister(CropInstance crop) {
        Map<UUID, CropInstance> crops = byChunk.get(crop.position().chunkKey());
        if (crops != null) {
            crops.remove(crop.instanceId());
        }
        for (BlockPositionKey cell : crop.cells()) {
            byCell.remove(cell, crop);
        }
    }

    /** Re-indexes a crop's cells after a footprint change. */
    public void reindexCells(CropInstance crop, List<BlockPositionKey> newCells) {
        for (BlockPositionKey cell : crop.cells()) {
            byCell.remove(cell, crop);
        }
        crop.setCells(newCells);
        for (BlockPositionKey cell : crop.cells()) {
            byCell.put(cell, crop);
        }
    }

    // ==================== chunk lifecycle ====================

    public void onChunkLoad(Chunk chunk) {
        World world = chunk.getWorld();
        ChunkKey key = new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
        if (loadingOrLoaded.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        storage.loadChunk(key).whenComplete((crops, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[AlliumHarvest] Failed loading crops for chunk " + key, error);
                loadingOrLoaded.remove(key);
                return;
            }
            if (crops.isEmpty()) {
                return;
            }
            Location anchor = new Location(world, (chunk.getX() << 4) + 8, world.getMinHeight(), (chunk.getZ() << 4) + 8);
            SchedulerAdapter.runAtLocation(plugin, anchor, () -> applyLoadedCrops(world, key, crops));
        });
    }

    private void applyLoadedCrops(World world, ChunkKey key, List<CropInstance> crops) {
        if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
            loadingOrLoaded.remove(key);
            return;
        }
        long now = System.currentTimeMillis();
        Map<UUID, CropInstance> inChunk = new ConcurrentHashMap<>();
        for (CropInstance crop : crops) {
            Optional<CropDefinition> definition = registry.crop(crop.cropId());
            if (definition.isEmpty()) {
                logger.warning("[AlliumHarvest] Crop definition '" + crop.cropId()
                    + "' no longer exists; removing stored crop at " + crop.position());
                storage.delete(crop.instanceId());
                continue;
            }
            Optional<CropPathDefinition> path = definition.get().path(crop.pathId());
            if (path.isEmpty()) {
                logger.warning("[AlliumHarvest] Path '" + crop.pathId() + "' of crop '"
                    + crop.cropId() + "' no longer exists; removing stored crop at " + crop.position());
                storage.delete(crop.instanceId());
                continue;
            }
            applyCatchUp(crop, definition.get(), path.get(), now);
            register(crop);
            inChunk.put(crop.instanceId(), crop);
            storage.saveLater(crop);
        }
        Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
        visuals.reconcileChunk(chunk, inChunk);
    }

    private void applyCatchUp(CropInstance crop, CropDefinition definition, CropPathDefinition path, long now) {
        if (crop.state() != CropState.GROWING) {
            crop.setNextGrowthAt(0L);
            return;
        }
        boolean pausedOffline = definition.growth().clock() == CropClock.LOADED_TIME
            || !definition.growth().growWhileUnloaded();
        if (pausedOffline) {
            // Resume the frozen countdown captured at unload.
            long remaining = crop.remainingMs() > 0
                ? crop.remainingMs()
                : Math.max(0L, crop.nextGrowthAt() - crop.stageStartedAt());
            crop.setStageStartedAt(now - (path.stage(crop.stage()).durationMs() - remaining));
            crop.setNextGrowthAt(now + remaining);
            crop.setRemainingMs(0L);
            return;
        }
        CatchUp.Result result = CatchUp.advance(
            path, crop.stage(), crop.stageStartedAt(), now,
            definition.growth().maxCatchUpStages(), crop.speedMultiplier());
        crop.setStage(result.stage());
        crop.setStageStartedAt(result.stageStartedAt());
        crop.setNextGrowthAt(result.nextGrowthAt());
        if (result.mature()) {
            crop.setState(CropState.MATURE);
        }
    }

    public void onChunkUnload(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Map<UUID, CropInstance> crops = byChunk.remove(key);
        loadingOrLoaded.remove(key);
        if (crops == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CropInstance crop : crops.values()) {
            registry.crop(crop.cropId()).ifPresent(definition -> {
                boolean pausedOffline = definition.growth().clock() == CropClock.LOADED_TIME
                    || !definition.growth().growWhileUnloaded();
                if (pausedOffline && crop.state() == CropState.GROWING) {
                    crop.setRemainingMs(Math.max(0L, crop.nextGrowthAt() - now));
                }
            });
            for (BlockPositionKey cell : crop.cells()) {
                byCell.remove(cell, crop);
            }
            storage.saveLater(crop);
        }
    }

    public void onWorldUnload(UUID worldId) {
        List<ChunkKey> keys = byChunk.keySet().stream()
            .filter(key -> key.worldId().equals(worldId))
            .toList();
        for (ChunkKey key : keys) {
            Map<UUID, CropInstance> crops = byChunk.remove(key);
            loadingOrLoaded.remove(key);
            if (crops == null) continue;
            for (CropInstance crop : crops.values()) {
                for (BlockPositionKey cell : crop.cells()) {
                    byCell.remove(cell, crop);
                }
                storage.saveLater(crop);
            }
        }
    }

    /** Persists everything currently loaded; called on plugin disable. */
    public void persistAll() {
        forEachLoaded(storage::saveLater);
        byChunk.clear();
        byCell.clear();
        loadingOrLoaded.clear();
    }

    /** Loads crops for chunks that are already loaded when the module enables. */
    public void bootstrapLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                onChunkLoad(chunk);
            }
        }
    }

    public Map<ChunkKey, Map<UUID, CropInstance>> loadedChunksView() {
        return Collections.unmodifiableMap(byChunk);
    }
}
