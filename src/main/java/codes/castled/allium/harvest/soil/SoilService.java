package codes.castled.allium.harvest.soil;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.storage.SoilStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Tracks the lifecycle of soil blocks: when they were established, when they
 * wear out, and which fertilizer is currently worked into them.
 *
 * <p>The whole feature is optional. When {@code soil.enabled} is false every
 * query answers "this soil is fine, forever", so no records are written and no
 * planting is ever refused — the rest of the plugin needs no knowledge of
 * whether the feature is on.
 *
 * <p>Nothing here runs on a timer. Soil does not need to be swept for
 * expiry — a record simply carries the timestamp at which it becomes
 * unusable, and every read compares it against the current time. Wear is
 * therefore exact even across restarts and unloaded chunks, at zero
 * per-tick cost.
 */
public final class SoilService {

    private final SoilStorage storage;
    private final HarvestConfig.Soil config;

    /** Loaded soil records, keyed by chunk so unload can drop them cheaply. */
    private final Map<ChunkKey, Map<BlockPositionKey, SoilState>> byChunk = new ConcurrentHashMap<>();

    public SoilService(SoilStorage storage, HarvestConfig.Soil config) {
        this.storage = storage;
        this.config = config;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    /** Player-facing reason shown when planting on worn-out soil. */
    public String exhaustedMessage() {
        return config.blockPlantMessage();
    }

    // ==================== queries ====================

    /** The tracked state of a soil block, if any is loaded. */
    public Optional<SoilState> get(BlockPositionKey position) {
        Map<BlockPositionKey, SoilState> chunk = byChunk.get(position.chunkKey());
        return chunk == null ? Optional.empty() : Optional.ofNullable(chunk.get(position));
    }

    /**
     * Whether a crop may be planted on this soil.
     *
     * <p>Untracked soil is always usable: it gets tracked on first use rather
     * than being refused, so enabling the feature never bricks an existing farm.
     */
    public boolean isUsable(BlockPositionKey position, long now) {
        if (!config.enabled()) {
            return true;
        }
        return get(position).map(soil -> !soil.isExhausted(now)).orElse(true);
    }

    /** Remaining soil life in millis, or {@link Long#MAX_VALUE} when unlimited. */
    public long remainingMillis(BlockPositionKey position, long now) {
        if (!config.enabled()) {
            return Long.MAX_VALUE;
        }
        return get(position).map(soil -> soil.remainingMillis(now)).orElse(config.lifetimeMillis());
    }

    /** The fertilizer worked into this soil, if any. */
    public Optional<String> fertilizerAt(BlockPositionKey position) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        return get(position).map(SoilState::fertilizerId).filter(id -> id != null && !id.isBlank());
    }

    // ==================== mutations ====================

    /**
     * Returns the tracked state for a soil block, creating it if this is the
     * first time the block has been seen.
     *
     * <p>New ground starts with the full configured lifetime — which is also
     * the longest one available — so placing fresh farmland somewhere genuinely
     * new is never a penalty. Placing it where soil is <em>already</em> tracked
     * returns the existing record with its existing expiry, which is what makes
     * breaking and replacing farmland useless as a reset.
     */
    public SoilState establish(BlockPositionKey position, long now) {
        Map<BlockPositionKey, SoilState> chunk =
            byChunk.computeIfAbsent(position.chunkKey(), key -> new ConcurrentHashMap<>());
        SoilState existing = chunk.get(position);
        if (existing != null) {
            return existing;
        }
        long lifetime = config.lifetimeMillis();
        SoilState created = new SoilState(
            position, now, lifetime <= 0L ? 0L : now + lifetime, null);
        chunk.put(position, created);
        storage.save(created);
        return created;
    }

    /** Works a fertilizer into the soil, tracking the block if needed. */
    public SoilState applyFertilizer(BlockPositionKey position, String fertilizerId,
                                     long retainMillis, long now) {
        SoilState soil = establish(position, now).withFertilizer(fertilizerId);
        if (retainMillis > 0L) {
            soil = soil.extendedBy(retainMillis, now);
        }
        put(soil);
        return soil;
    }

    /** Extends soil life without changing the worked-in fertilizer. */
    public SoilState retain(BlockPositionKey position, long retainMillis, long now) {
        SoilState soil = establish(position, now).extendedBy(retainMillis, now);
        put(soil);
        return soil;
    }

    /**
     * Consumes the fertilizer worked into the soil.
     *
     * <p>Called when a seed is planted: the fertilizer has now been spent on
     * that crop, so it must not silently buff every future planting too.
     */
    public Optional<String> consumeFertilizer(BlockPositionKey position) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        Optional<String> fertilizer = fertilizerAt(position);
        fertilizer.ifPresent(id -> get(position).ifPresent(soil -> put(soil.withFertilizer(null))));
        return fertilizer;
    }

    private void put(SoilState soil) {
        byChunk.computeIfAbsent(soil.position().chunkKey(), key -> new ConcurrentHashMap<>())
            .put(soil.position(), soil);
        storage.save(soil);
    }

    /**
     * Forgets a soil block entirely, so it behaves like new ground again.
     * Reserved for administrative use — ordinary block breaking deliberately
     * does <em>not</em> call this.
     */
    public void forget(BlockPositionKey position) {
        Map<BlockPositionKey, SoilState> chunk = byChunk.get(position.chunkKey());
        if (chunk != null) {
            chunk.remove(position);
        }
        storage.delete(position);
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
            Map<BlockPositionKey, SoilState> loaded = new ConcurrentHashMap<>();
            for (SoilState soil : records) {
                loaded.put(soil.position(), soil);
            }
            byChunk.put(key, loaded);
        });
    }

    public void onChunkUnload(Chunk chunk) {
        // Records are written through on every change, so dropping the cache
        // here loses nothing.
        byChunk.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    public void onWorldUnload(World world) {
        byChunk.keySet().removeIf(key -> key.worldId().equals(world.getUID()));
    }

    /** Drops long-dead records so the table cannot grow without bound. */
    public void purgeExhausted(long now) {
        if (!config.enabled() || config.forgetAfterMillis() <= 0L) {
            return;
        }
        storage.purgeExhausted(now, config.forgetAfterMillis());
    }

    public int loadedCount() {
        return byChunk.values().stream().mapToInt(Map::size).sum();
    }

    /** Convenience: the position key of a block. */
    public static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
