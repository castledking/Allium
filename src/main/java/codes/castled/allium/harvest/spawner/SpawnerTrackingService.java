package codes.castled.allium.harvest.spawner;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.event.SpawnerModelUpdateEvent;
import codes.castled.allium.harvest.event.SpawnerTrackEvent;
import codes.castled.allium.harvest.event.SpawnerUntrackEvent;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.storage.SpawnerStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import codes.castled.allium.harvest.visual.VisualTags;
import codes.castled.allium.scheduler.SchedulerAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Tracks spawner locations and keeps exactly one visual overlay per spawner
 * that has a configured model. The real spawner block is never touched — the
 * overlay is purely visual, and all vanilla or third-party spawner behavior
 * continues unchanged.
 *
 * <p>Everything converges through the idempotent {@link #refresh}: it can be
 * called any number of times, from tracking events, chunk loads, the periodic
 * revalidation sweep or admin commands, and always ends in the correct state
 * (spawner + configured model = one tagged display; anything else = none).</p>
 */
public final class SpawnerTrackingService {

    private final Plugin plugin;
    private final Logger logger;
    private final SpawnerModelRegistry models;
    private final SpawnerStorage storage;
    private final ItemResolverChain items;
    private final HarvestConfig.Spawners settings;
    private final VanillaSpawnerProvider vanillaProvider = new VanillaSpawnerProvider();

    private final Map<BlockPositionKey, TrackedSpawner> tracked = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<BlockPositionKey>> byChunk = new ConcurrentHashMap<>();
    /** Round-robin revalidation queue over loaded tracked spawners. */
    private final ArrayDeque<BlockPositionKey> sweepQueue = new ArrayDeque<>();

    private long duplicatesRemoved;
    private long orphansRemoved;
    /**
     * Model refs that could not be resolved to a real item.
     *
     * <p>This is both a log filter and a retry guard. Reconciliation runs from
     * a periodic sweep, so a model naming an item that does not exist in the
     * pack would otherwise be retried — and logged — for every tracked spawner,
     * every few seconds, forever. Once a model is known to be unresolvable it
     * is treated as "no model configured" until the next reload, which is
     * exactly what it is.
     *
     * <p>Cleared by {@link #clearUnresolvableModels()} on reload, so adding the
     * missing item to the pack and running {@code /harvest reload} recovers
     * without a restart.
     */
    private final Set<String> unresolvableModels = ConcurrentHashMap.newKeySet();
    /** Set on disable so reconciliation stops creating entities we are about to abandon. */
    private volatile boolean shuttingDown;

    public SpawnerTrackingService(
        Plugin plugin,
        SpawnerModelRegistry models,
        SpawnerStorage storage,
        ItemResolverChain items,
        HarvestConfig.Spawners settings
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.models = models;
        this.storage = storage;
        this.items = items;
        this.settings = settings;
    }

    /** Resolves the active provider: ServicesManager registration wins. */
    public SpawnerProvider provider() {
        RegisteredServiceProvider<SpawnerProvider> registration =
            Bukkit.getServicesManager().getRegistration(SpawnerProvider.class);
        return registration != null ? registration.getProvider() : vanillaProvider;
    }

    public Optional<TrackedSpawner> trackedAt(BlockPositionKey position) {
        return Optional.ofNullable(tracked.get(position));
    }

    public int trackedCount() {
        return tracked.size();
    }

    public long duplicatesRemoved() { return duplicatesRemoved; }
    public long orphansRemoved() { return orphansRemoved; }

    // ==================== the one idempotent entry point ====================

    /**
     * Converges the given position to the correct state. Must run on the
     * region thread owning the position, with the chunk loaded.
     */
    public void refresh(Block block) {
        BlockPositionKey position = keyOf(block);
        if (block.getType() != Material.SPAWNER) {
            untrack(block.getWorld(), position);
            return;
        }
        SpawnerProvider provider = provider();
        EntityType type = provider.getEntityType(block)
            .or(() -> vanillaProvider.getEntityType(block))
            .orElse(null);
        int stackSize = Math.max(1, provider.getStackSize(block));

        TrackedSpawner spawner = tracked.get(position);
        boolean isNew = spawner == null;
        String typeName = type == null ? "" : type.name();
        if (isNew) {
            spawner = new TrackedSpawner(position, typeName, null, System.currentTimeMillis());
            tracked.put(position, spawner);
            byChunk.computeIfAbsent(position.chunkKey(), k -> ConcurrentHashMap.newKeySet()).add(position);
            sweepQueue.add(position);
        } else if (!typeName.equals(spawner.entityType())) {
            spawner.setEntityType(typeName);
        }

        reconcileVisual(block, spawner, type, stackSize, isNew);
        storage.save(spawner);
        if (isNew) {
            Bukkit.getPluginManager().callEvent(new SpawnerTrackEvent(block.getLocation(), type));
        }
    }

    /** Removes tracking, persistence and any tagged visuals for a position. */
    public void untrack(World world, BlockPositionKey position) {
        TrackedSpawner spawner = tracked.remove(position);
        Set<BlockPositionKey> chunkSet = byChunk.get(position.chunkKey());
        if (chunkSet != null) {
            chunkSet.remove(position);
        }
        boolean removedVisual = removeVisualsAt(world, position, null) > 0;
        if (spawner != null || removedVisual) {
            storage.delete(position);
        }
        if (spawner != null) {
            Location location = new Location(world, position.x(), position.y(), position.z());
            Bukkit.getPluginManager().callEvent(new SpawnerUntrackEvent(location));
            if (removedVisual) {
                Bukkit.getPluginManager().callEvent(new SpawnerModelUpdateEvent(
                    location, null, SpawnerModelUpdateEvent.Action.REMOVE));
            }
        }
    }

    // ==================== visual reconciliation ====================

    private void reconcileVisual(Block block, TrackedSpawner spawner, EntityType type, int stackSize, boolean isNew) {
        World world = block.getWorld();
        BlockPositionKey position = spawner.position();
        Optional<SpawnerModelRegistry.Model> modelOpt = models.modelFor(type);
        if (modelOpt.isEmpty()) {
            removeVisualsAt(world, position, null);
            spawner.setVisualEntityId(null);
            return;
        }
        SpawnerModelRegistry.Model model = modelOpt.get();
        String modelRef = model.itemForStack(stackSize).toString();

        // A model whose item does not exist is treated as no model at all,
        // rather than being retried on every sweep.
        if (unresolvableModels.contains(modelRef)) {
            removeVisualsAt(world, position, null);
            spawner.setVisualEntityId(null);
            return;
        }

        // Find existing tagged visuals at this position; keep at most one
        // that already shows the right model.
        ItemDisplay keep = null;
        for (ItemDisplay display : taggedDisplaysAt(world, position)) {
            if (keep == null && modelRef.equals(VisualTags.modelRefOf(display).orElse(null))) {
                keep = display;
            } else if (keep != null) {
                display.remove();
                duplicatesRemoved++;
            }
        }
        if (keep == null) {
            // Either no display, or displays with a stale model: replace all.
            boolean hadStale = removeVisualsAt(world, position, null) > 0;
            keep = spawnVisual(world, position, model, modelRef, type);
            if (keep != null) {
                Bukkit.getPluginManager().callEvent(new SpawnerModelUpdateEvent(
                    block.getLocation(), type,
                    hadStale || !isNew
                        ? SpawnerModelUpdateEvent.Action.UPDATE
                        : SpawnerModelUpdateEvent.Action.CREATE));
            }
        }
        spawner.setVisualEntityId(keep == null ? null : keep.getUniqueId());
    }

    private List<ItemDisplay> taggedDisplaysAt(World world, BlockPositionKey position) {
        List<ItemDisplay> found = new ArrayList<>();
        Chunk chunk = world.getChunkAt(position.x() >> 4, position.z() >> 4);
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            if (!VisualTags.isManaged(display)) continue;
            if (VisualTags.kindOf(display).orElse(null) != VisualTags.Kind.SPAWNER) continue;
            if (VisualTags.positionOf(display).map(position::equals).orElse(false)) {
                found.add(display);
            }
        }
        return found;
    }

    private int removeVisualsAt(World world, BlockPositionKey position, UUID except) {
        int removed = 0;
        for (ItemDisplay display : taggedDisplaysAt(world, position)) {
            if (except != null && display.getUniqueId().equals(except)) continue;
            display.remove();
            removed++;
        }
        return removed;
    }

    private ItemDisplay spawnVisual(World world, BlockPositionKey position,
                                    SpawnerModelRegistry.Model model, String modelRef, EntityType type) {
        if (shuttingDown) {
            return null;
        }
        ItemStack item = items.create(codes.castled.allium.harvest.item.ItemRef.parse(modelRef), 1).orElse(null);
        if (item == null) {
            if (unresolvableModels.add(modelRef)) {
                logger.warning("[AlliumHarvest] Spawner model item '" + modelRef + "' does not exist"
                    + " — check harvest/spawner-models.yml against your item pack."
                    + " Spawners using this model will show no overlay, and this model will not be"
                    + " retried until /harvest reload.");
            }
            return null;
        }
        Location location = new Location(world,
            position.x() + model.translation()[0],
            position.y() + model.translation()[1],
            position.z() + model.translation()[2]);
        return world.spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setGravity(false);
            display.setItemStack(item);
            display.setItemDisplayTransform(settings.displayTransform());
            display.setBillboard(Display.Billboard.FIXED);
            display.setViewRange(model.viewRange() / 64.0F);
            display.setShadowStrength(0.0F);
            display.setGlowing(model.glow());
            display.setInterpolationDuration(0);
            display.setTeleportDuration(0);
            Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(model.rotationY()));
            display.setTransformation(new Transformation(
                new Vector3f(), rotation,
                new Vector3f(model.scale(), model.scale(), model.scale()),
                new Quaternionf()));
            VisualTags.tagSpawner(display, position,
                type == null ? "" : type.name(), modelRef);
        });
    }

    // ==================== chunk lifecycle ====================

    /**
     * Reconciles a freshly loaded chunk: queries tracked spawners in that
     * chunk from the database, verifies each is still a spawner, drops stale
     * rows, and (optionally) discovers untracked spawners through the chunk's
     * block-entity list — never by scanning every block.
     */
    public void onChunkLoad(Chunk chunk) {
        World world = chunk.getWorld();
        ChunkKey key = new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
        storage.loadChunk(key).whenComplete((rows, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[AlliumHarvest] Failed loading tracked spawners for " + key, error);
                return;
            }
            Location anchor = new Location(world, (chunk.getX() << 4) + 8, world.getMinHeight(), (chunk.getZ() << 4) + 8);
            SchedulerAdapter.runAtLocation(plugin, anchor, () -> {
                if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) return;
                Set<BlockPositionKey> seen = new HashSet<>();
                for (TrackedSpawner row : rows) {
                    BlockPositionKey position = row.position();
                    seen.add(position);
                    refresh(world.getBlockAt(position.x(), position.y(), position.z()));
                }
                if (settings.scanChunkOnLoad()) {
                    discoverInChunk(chunk, seen);
                }
            });
        });
    }

    /** Finds untracked spawners through the chunk's tile entity list. */
    private void discoverInChunk(Chunk chunk, Set<BlockPositionKey> alreadySeen) {
        int budget = settings.maxBlockEntitiesPerTick();
        for (BlockState state : chunk.getTileEntities()) {
            if (budget-- <= 0) break;
            if (!(state instanceof CreatureSpawner)) continue;
            BlockPositionKey position = new BlockPositionKey(
                chunk.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
            if (alreadySeen.contains(position) || tracked.containsKey(position)) continue;
            refresh(state.getBlock());
        }
    }

    public void onChunkUnload(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Set<BlockPositionKey> positions = byChunk.remove(key);
        if (positions == null) return;
        for (BlockPositionKey position : positions) {
            TrackedSpawner spawner = tracked.remove(position);
            if (spawner != null) {
                storage.save(spawner);
            }
        }
        // Non-persistent visuals vanish with the chunk on their own.
    }

    public void onWorldUnload(UUID worldId) {
        byChunk.keySet().removeIf(key -> key.worldId().equals(worldId));
        tracked.keySet().removeIf(position -> position.worldId().equals(worldId));
    }

    /**
     * Periodic bounded revalidation of loaded tracked spawners — catches
     * spawner type changes made by other plugins (spawner changer items,
     * stacking plugins) without any per-spawner tasks.
     */
    /**
     * Stops all visual work ahead of shutdown.
     *
     * <p>During shutdown the plugins that provide custom items are disabled on
     * their own schedule, quite possibly before Allium. Continuing to
     * reconcile visuals past that point can only fail, so the correct
     * behaviour is to stop rather than to log about it.
     */
    public void shutdown() {
        shuttingDown = true;
    }

    /**
     * Forgets which models failed to resolve, so a reload retries them.
     * Called after the registry is reloaded — the item may now exist, or the
     * config may no longer reference it.
     */
    public void clearUnresolvableModels() {
        unresolvableModels.clear();
    }

    public void sweep() {
        if (shuttingDown) {
            return;
        }
        int budget = Math.min(settings.maxBlockEntitiesPerTick(), tracked.size());
        for (int i = 0; i < budget; i++) {
            BlockPositionKey position;
            synchronized (sweepQueue) {
                position = sweepQueue.poll();
            }
            if (position == null) {
                synchronized (sweepQueue) {
                    sweepQueue.addAll(tracked.keySet());
                }
                return;
            }
            TrackedSpawner spawner = tracked.get(position);
            if (spawner == null) continue;
            World world = Bukkit.getWorld(position.worldId());
            if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
            Location location = new Location(world, position.x(), position.y(), position.z());
            SchedulerAdapter.runAtLocation(plugin, location,
                () -> refresh(world.getBlockAt(position.x(), position.y(), position.z())));
            synchronized (sweepQueue) {
                sweepQueue.add(position);
            }
        }
    }

    /** Rescans a bounded cube of loaded chunks around a location (admin command). */
    public int rescan(Location center, int radiusChunks) {
        World world = center.getWorld();
        int cx = center.getBlockX() >> 4;
        int cz = center.getBlockZ() >> 4;
        int found = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof CreatureSpawner) {
                        refresh(state.getBlock());
                        found++;
                    }
                }
            }
        }
        return found;
    }

    public void bootstrapLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                onChunkLoad(chunk);
            }
        }
    }

    private static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
