package codes.castled.allium.harvest.sprinkler;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.storage.SprinklerStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Tracks placed sprinklers and answers coverage questions about them.
 *
 * <p>Coverage is resolved by looking at the chunks a position could be reached
 * from, never by scanning the world: sprinklers are indexed by chunk, and a
 * lookup only visits the chunks within the largest configured radius. With a
 * radius of one or two blocks that is a handful of map lookups, so asking "is
 * this crop watered?" stays cheap enough to do on every plant.
 *
 * <p>There is no per-sprinkler task. Speed is folded into a crop's cached
 * multiplier when it is planted or when coverage changes, and soil upkeep runs
 * from one shared bounded pass.
 */
public final class SprinklerService {

    private final SprinklerRegistry registry;
    private final SprinklerStorage storage;
    private final SoilService soils;
    private final HarvestConfig.Sprinklers config;

    private final Map<ChunkKey, Map<BlockPositionKey, PlacedSprinkler>> byChunk = new ConcurrentHashMap<>();

    public SprinklerService(SprinklerRegistry registry, SprinklerStorage storage,
                            SoilService soils, HarvestConfig.Sprinklers config) {
        this.registry = registry;
        this.storage = storage;
        this.soils = soils;
        this.config = config;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    // ==================== coverage ====================

    /** The largest radius any configured tier reaches, used to bound lookups. */
    private int maxRadius() {
        int max = 0;
        for (SprinklerDefinition definition : registry.all()) {
            max = Math.max(max, definition.radius());
        }
        return max;
    }

    /** Every sprinkler whose coverage includes the given position. */
    public List<SprinklerDefinition> coveringDefinitions(BlockPositionKey position) {
        if (!config.enabled() || byChunk.isEmpty()) {
            return List.of();
        }
        int radius = maxRadius();
        if (radius <= 0) {
            return List.of();
        }
        List<SprinklerDefinition> covering = new ArrayList<>();
        int chunkSpan = (radius >> 4) + 1;
        ChunkKey origin = position.chunkKey();
        for (int dcx = -chunkSpan; dcx <= chunkSpan; dcx++) {
            for (int dcz = -chunkSpan; dcz <= chunkSpan; dcz++) {
                Map<BlockPositionKey, PlacedSprinkler> chunk = byChunk.get(
                    new ChunkKey(origin.worldId(), origin.chunkX() + dcx, origin.chunkZ() + dcz));
                if (chunk == null) continue;
                for (PlacedSprinkler sprinkler : chunk.values()) {
                    registry.get(sprinkler.sprinklerId()).ifPresent(definition -> {
                        if (definition.covers(
                            position.x() - sprinkler.position().x(),
                            position.z() - sprinkler.position().z())) {
                            covering.add(definition);
                        }
                    });
                }
            }
        }
        return covering;
    }

    /**
     * The combined growth-speed multiplier from every sprinkler covering a
     * position. Overlapping sprinklers stack multiplicatively, and the shared
     * clamp in {@code GrowthSpeed} keeps a stack of them from reaching zero.
     */
    public double speedMultiplierAt(BlockPositionKey position) {
        double combined = 1.0D;
        for (SprinklerDefinition definition : coveringDefinitions(position)) {
            combined *= definition.speedMultiplier();
        }
        return combined;
    }

    /** Whether any covering sprinkler satisfies a crop's water requirement. */
    public boolean isWatered(BlockPositionKey position) {
        return coveringDefinitions(position).stream().anyMatch(SprinklerDefinition::keepsSoilMoist);
    }

    // ==================== mutations ====================

    public Optional<PlacedSprinkler> at(BlockPositionKey position) {
        Map<BlockPositionKey, PlacedSprinkler> chunk = byChunk.get(position.chunkKey());
        return chunk == null ? Optional.empty() : Optional.ofNullable(chunk.get(position));
    }

    /** Registers a newly placed sprinkler. Returns empty when the chunk is full. */
    public Optional<PlacedSprinkler> place(BlockPositionKey position, SprinklerDefinition definition,
                                           UUID owner, long now) {
        Map<BlockPositionKey, PlacedSprinkler> chunk =
            byChunk.computeIfAbsent(position.chunkKey(), key -> new ConcurrentHashMap<>());
        if (!chunk.containsKey(position) && chunk.size() >= config.maxPerChunk()) {
            return Optional.empty();
        }
        PlacedSprinkler placed = new PlacedSprinkler(position, definition.id(), now, owner, null);
        chunk.put(position, placed);
        storage.save(placed);
        return Optional.of(placed);
    }

    public void remove(BlockPositionKey position) {
        Map<BlockPositionKey, PlacedSprinkler> chunk = byChunk.get(position.chunkKey());
        if (chunk != null) {
            chunk.remove(position);
        }
        storage.delete(position);
    }

    /**
     * Feeds soil under every loaded sprinkler that grants retention.
     *
     * <p>Runs from the module's shared periodic pass rather than a task per
     * sprinkler, and only touches soil that is already tracked and loaded.
     */
    public void upkeep(long now) {
        if (!config.enabled() || !soils.isEnabled()) {
            return;
        }
        for (Map<BlockPositionKey, PlacedSprinkler> chunk : byChunk.values()) {
            for (PlacedSprinkler sprinkler : chunk.values()) {
                SprinklerDefinition definition = registry.get(sprinkler.sprinklerId()).orElse(null);
                if (definition == null || definition.soilRetainMillis() <= 0L) {
                    continue;
                }
                BlockPositionKey origin = sprinkler.position();
                for (int dx = -definition.radius(); dx <= definition.radius(); dx++) {
                    for (int dz = -definition.radius(); dz <= definition.radius(); dz++) {
                        // One block down: sprinklers sit on top of the farmland
                        // row they irrigate.
                        BlockPositionKey soilKey = origin.offset(dx, -1, dz);
                        if (soils.get(soilKey).isPresent()) {
                            soils.retain(soilKey, definition.soilRetainMillis(), now);
                        }
                    }
                }
            }
        }
    }

    // ==================== chunk lifecycle ====================

    public void onChunkLoad(Chunk chunk) {
        if (!config.enabled()) {
            return;
        }
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        storage.loadChunk(key).thenAccept(records -> {
            if (records.isEmpty()) {
                return;
            }
            Map<BlockPositionKey, PlacedSprinkler> loaded = new ConcurrentHashMap<>();
            for (PlacedSprinkler sprinkler : records) {
                loaded.put(sprinkler.position(), sprinkler);
            }
            byChunk.put(key, loaded);
        });
    }

    public void onChunkUnload(Chunk chunk) {
        byChunk.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    public void onWorldUnload(World world) {
        byChunk.keySet().removeIf(key -> key.worldId().equals(world.getUID()));
    }

    public int loadedCount() {
        return byChunk.values().stream().mapToInt(Map::size).sum();
    }
}
