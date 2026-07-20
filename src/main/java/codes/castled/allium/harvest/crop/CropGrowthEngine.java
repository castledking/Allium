package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.FootprintDefinition;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.event.CropGrowEvent;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Due-time growth processing. There is no task per crop: a single repeating
 * scheduler task scans the in-memory chunk index for crops whose
 * {@code nextGrowthAt} has passed (bounded by {@code checks-per-tick}) and
 * dispatches each stage advance to the scheduler owning the crop's region, so
 * block/entity work is always Folia-safe. Unloaded chunks are untouched —
 * their crops catch up when the chunk loads.
 */
public final class CropGrowthEngine {

    private final Plugin plugin;
    private final CropRegistry registry;
    private final CropInstanceService instances;
    private final CropStorage storage;
    private final CropVisualService visuals;
    private final HarvestConfig.Growth settings;

    private TaskHandle tickTask;
    private TaskHandle flushTask;
    private volatile long lastPassNanos;
    private volatile long dueLastPass;

    public CropGrowthEngine(
        Plugin plugin,
        CropRegistry registry,
        CropInstanceService instances,
        CropStorage storage,
        CropVisualService visuals,
        HarvestConfig.Growth settings
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.instances = instances;
        this.storage = storage;
        this.visuals = visuals;
        this.settings = settings;
    }

    public void start() {
        tickTask = SchedulerAdapter.runAsyncRepeating(
            plugin, this::pass, settings.checkIntervalTicks(), settings.checkIntervalTicks());
        flushTask = SchedulerAdapter.runAsyncRepeating(
            plugin, storage::flush, settings.flushIntervalTicks(), settings.flushIntervalTicks());
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (flushTask != null) flushTask.cancel();
    }

    /** One scan pass; runs off-thread, only reads the concurrent index. */
    private void pass() {
        long started = System.nanoTime();
        long now = System.currentTimeMillis();
        List<CropInstance> due = instances.collectDue(now, settings.checksPerTick());
        dueLastPass = due.size();
        for (CropInstance crop : due) {
            World world = Bukkit.getWorld(crop.position().worldId());
            if (world == null) continue;
            BlockPositionKey position = crop.position();
            Location location = new Location(world, position.x(), position.y(), position.z());
            SchedulerAdapter.runAtLocation(plugin, location, () -> advance(world, crop));
        }
        lastPassNanos = System.nanoTime() - started;
    }

    /** Advances one crop by one stage. Region thread of the crop. */
    private void advance(World world, CropInstance crop) {
        long now = System.currentTimeMillis();
        if (!crop.isDue(now)) {
            return; // already advanced, harvested or removed meanwhile
        }
        Optional<CropDefinition> definition = registry.crop(crop.cropId());
        if (definition.isEmpty()) return;
        Optional<CropPathDefinition> pathOpt = definition.get().path(crop.pathId());
        if (pathOpt.isEmpty()) return;
        CropPathDefinition path = pathOpt.get();

        int fromStage = crop.stage();
        int toStage = Math.min(fromStage + 1, path.matureStage());
        if (toStage == fromStage) {
            crop.setState(CropState.MATURE);
            crop.setNextGrowthAt(0L);
            storage.saveLater(crop);
            return;
        }

        Location location = new Location(world,
            crop.position().x(), crop.position().y(), crop.position().z());
        CropGrowEvent event = new CropGrowEvent(
            crop.instanceId(), crop.cropId(), crop.pathId(), location, fromStage, toStage);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            crop.setNextGrowthAt(now
                + GrowthSpeed.apply(path.stage(fromStage).durationMs(), crop.speedMultiplier()));
            storage.saveLater(crop);
            return;
        }

        StageDefinition next = path.stage(toStage);
        if (!next.footprint().isSingleCell()
            && !tryReserveFootprint(world, crop, next.footprint())) {
            // Footprint obstructed: stay put and finish as a mature crop at
            // the current (single-cell) stage instead of overwriting blocks.
            crop.setState(CropState.MATURE);
            crop.setNextGrowthAt(0L);
            storage.saveLater(crop);
            return;
        }

        crop.setStage(toStage);
        crop.setStageStartedAt(crop.nextGrowthAt() > 0 ? crop.nextGrowthAt() : now);
        if (path.isMature(toStage)) {
            crop.setState(CropState.MATURE);
            crop.setNextGrowthAt(0L);
        } else {
            crop.setNextGrowthAt(crop.stageStartedAt()
                + GrowthSpeed.apply(next.durationMs(), crop.speedMultiplier()));
        }
        visuals.ensureVisual(world, crop);
        storage.saveLater(crop);
    }

    /**
     * Transactionally reserves all footprint cells: every cell must be free
     * air and unoccupied, then all cells are indexed atomically on this
     * region thread. Never overwrites foreign blocks.
     */
    private boolean tryReserveFootprint(World world, CropInstance crop, FootprintDefinition footprint) {
        List<BlockPositionKey> cells = footprint.resolve(crop.position());
        for (BlockPositionKey cell : cells) {
            if (cell.equals(crop.position())) continue;
            Optional<CropInstance> occupant = instances.at(cell);
            if (occupant.isPresent() && !occupant.get().instanceId().equals(crop.instanceId())) {
                return false;
            }
            if (!world.getBlockAt(cell.x(), cell.y(), cell.z()).getType().isAir()) {
                return false;
            }
        }
        instances.reindexCells(crop, cells);
        return true;
    }

    /** Immediately advances a crop by up to {@code stages} (admin/debug). */
    public void forceGrow(World world, CropInstance crop, int stages) {
        for (int i = 0; i < stages && crop.state() == CropState.GROWING; i++) {
            crop.setNextGrowthAt(System.currentTimeMillis());
            advance(world, crop);
        }
    }

    public long lastPassNanos() { return lastPassNanos; }
    public long duePickedLastPass() { return dueLastPass; }
}
