package codes.castled.allium.harvest;

import codes.castled.allium.harvest.api.AlliumHarvestApi;
import codes.castled.allium.harvest.api.HarvestApiImpl;
import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.CropGrowthEngine;
import codes.castled.allium.harvest.crop.CropHarvestService;
import codes.castled.allium.harvest.crop.CropInstanceService;
import codes.castled.allium.harvest.crop.CropListeners;
import codes.castled.allium.harvest.crop.CropPlacementService;
import codes.castled.allium.harvest.crop.CropVisualService;
import codes.castled.allium.harvest.crop.def.CropDefinitionLoader;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.integration.NexoItemResolver;
import codes.castled.allium.harvest.integration.OraxenItemResolver;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.spawner.SpawnerListeners;
import codes.castled.allium.harvest.spawner.SpawnerModelRegistry;
import codes.castled.allium.harvest.spawner.SpawnerProvider;
import codes.castled.allium.harvest.spawner.SpawnerTrackingService;
import codes.castled.allium.harvest.spawner.VanillaSpawnerProvider;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.sprinkler.SprinklerListeners;
import codes.castled.allium.harvest.sprinkler.SprinklerRegistry;
import codes.castled.allium.harvest.sprinkler.SprinklerService;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.storage.HarvestDatabase;
import codes.castled.allium.harvest.storage.SoilStorage;
import codes.castled.allium.harvest.storage.SpawnerStorage;
import codes.castled.allium.harvest.storage.SprinklerStorage;
import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

/**
 * Facade wiring the entire harvest subsystem (custom crops + spawner models)
 * into Allium. Everything is dependency-injected through constructors from
 * here; nothing in the subsystem reaches for global plugin state.
 */
public final class HarvestModule {

    private final JavaPlugin plugin;
    private final Logger logger;

    private HarvestConfig config;
    private HarvestDatabase database;
    private CropStorage cropStorage;
    private SpawnerStorage spawnerStorage;
    private SoilStorage soilStorage;
    private SprinklerStorage sprinklerStorage;
    private SoilService soils;
    private SprinklerRegistry sprinklerModels;
    private SprinklerService sprinklers;
    private TaskHandle upkeepTask;
    private ItemResolverChain items;
    private CropRegistry registry;
    private CropVisualService visuals;
    private CropInstanceService instances;
    private CropPlacementService placement;
    private CropHarvestService harvests;
    private CropGrowthEngine growthEngine;
    private SpawnerModelRegistry spawnerModels;
    private SpawnerTrackingService spawnerTracking;
    private TaskHandle spawnerSweepTask;
    private boolean enabled;

    public HarvestModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== lifecycle ====================

    public void enable() {
        File dataFolder = new File(plugin.getDataFolder(), HarvestBranding.DATA_FOLDER);
        saveDefault("config.yml");
        saveDefault("fertilizers.yml");
        saveDefault("spawner-models.yml");
        saveDefault("sprinklers.yml");
        saveDefault("crops/tomato.yml");

        config = HarvestConfig.from(
            YamlConfiguration.loadConfiguration(new File(dataFolder, "config.yml")));
        if (!config.enabled()) {
            logger.info("[" + HarvestBranding.DISPLAY_NAME + "] Disabled in harvest/config.yml");
            return;
        }

        try {
            database = new HarvestDatabase(logger, dataFolder, config.storage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[" + HarvestBranding.DISPLAY_NAME
                + "] Could not initialize storage — module disabled", e);
            return;
        }
        cropStorage = new CropStorage(database);
        spawnerStorage = new SpawnerStorage(database);
        soilStorage = new SoilStorage(database);
        sprinklerStorage = new SprinklerStorage(database);

        items = buildResolverChain();
        registry = new CropRegistry();
        List<ValidationIssue> issues = loadDefinitions(dataFolder);
        reportIssues(issues);

        soils = new SoilService(soilStorage, config.soil());
        sprinklerModels = new SprinklerRegistry();
        reportIssues(loadSprinklers(dataFolder));
        sprinklers = new SprinklerService(sprinklerModels, sprinklerStorage, soils, config.sprinklers());

        visuals = new CropVisualService(logger, registry, items, config.visuals());
        instances = new CropInstanceService(plugin, registry, cropStorage, visuals);
        placement = new CropPlacementService(registry, instances, cropStorage, visuals,
            soils, sprinklers, ThreadLocalRandom.current());
        harvests = new CropHarvestService(registry, instances, cropStorage, visuals, items, ThreadLocalRandom.current());
        growthEngine = new CropGrowthEngine(plugin, registry, instances, cropStorage, visuals, config.growthEngine());

        spawnerModels = new SpawnerModelRegistry();
        reportIssues(loadSpawnerModels(dataFolder));
        spawnerTracking = new SpawnerTrackingService(plugin, spawnerModels, spawnerStorage, items, config.spawners());

        Bukkit.getPluginManager().registerEvents(
            new CropListeners(registry, instances, placement, harvests, cropStorage, items,
                soils, sprinklers), plugin);
        Bukkit.getPluginManager().registerEvents(
            new SprinklerListeners(sprinklerModels, sprinklers, soils, items), plugin);
        // One shared periodic pass covers sprinkler soil upkeep and pruning of
        // long-dead soil records — no task per sprinkler, no task per block.
        upkeepTask = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            long now = System.currentTimeMillis();
            sprinklers.upkeep(now);
            soils.purgeExhausted(now);
        }, 1200L, 1200L);
        if (config.spawners().enabled()) {
            Bukkit.getPluginManager().registerEvents(new SpawnerListeners(plugin, spawnerTracking), plugin);
            spawnerSweepTask = SchedulerAdapter.runAsyncRepeating(plugin, spawnerTracking::sweep, 100L, 100L);
        }
        growthEngine.start();

        Bukkit.getServicesManager().register(SpawnerProvider.class,
            new VanillaSpawnerProvider(), plugin, ServicePriority.Lowest);
        Bukkit.getServicesManager().register(AlliumHarvestApi.class,
            new HarvestApiImpl(registry, instances, placement, harvests, spawnerTracking),
            plugin, ServicePriority.Normal);

        instances.bootstrapLoadedChunks();
        if (config.spawners().enabled()) {
            spawnerTracking.bootstrapLoadedChunks();
        }

        enabled = true;
        logger.info("[" + HarvestBranding.DISPLAY_NAME + "] Enabled with "
            + registry.size() + " crop definition(s), " + spawnerModels.size()
            + " spawner model(s), storage=" + config.storage().dialect());
    }

    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        // Quiesce visual work first: from here on the only job is to get state
        // safely onto disk, and anything that touches other plugins' items may
        // already be running against a disabled plugin.
        if (spawnerTracking != null) {
            spawnerTracking.shutdown();
        }
        growthEngine.stop();
        if (spawnerSweepTask != null) {
            spawnerSweepTask.cancel();
        }
        if (upkeepTask != null) {
            upkeepTask.cancel();
        }
        instances.persistAll();
        database.flushAndWait(cropStorage::flush);
        database.close();
        Bukkit.getServicesManager().unregisterAll(plugin);
    }

    // ==================== reload ====================

    /**
     * Reloads all harvest configuration.
     *
     * <p>Reload is best-effort rather than all-or-nothing: definitions that
     * parse cleanly are activated, and definitions with fatal problems (a
     * missing Nexo item being by far the most common) are skipped instead of
     * blocking every other change in the file. Every problem is logged to the
     * console and also returned so the caller can summarise it.
     *
     * <p>Loaded crop visuals are then re-converged; this never duplicates,
     * because reconciliation keys off instance UUIDs.
     */
    public List<ValidationIssue> reload() {
        if (!enabled) {
            return List.of(ValidationIssue.error("harvest/config.yml", "enabled",
                "Harvest module is not enabled; restart after enabling it"));
        }
        File dataFolder = new File(plugin.getDataFolder(), HarvestBranding.DATA_FOLDER);
        List<ValidationIssue> issues = loadDefinitions(dataFolder);
        issues.addAll(loadSpawnerModels(dataFolder));
        issues.addAll(loadSprinklers(dataFolder));
        reportIssues(issues);

        // Models that previously failed to resolve get another chance: the
        // item may have been added to the pack, or the entry removed.
        if (spawnerTracking != null) {
            spawnerTracking.clearUnresolvableModels();
        }

        // Re-converge visuals of everything loaded on its own region thread.
        instances.forEachLoaded(crop -> {
            org.bukkit.World world = Bukkit.getWorld(crop.position().worldId());
            if (world == null) return;
            org.bukkit.Location location = new org.bukkit.Location(
                world, crop.position().x(), crop.position().y(), crop.position().z());
            SchedulerAdapter.runAtLocation(plugin, location, () -> visuals.ensureVisual(world, crop));
        });
        return issues;
    }

    // ==================== internals ====================

    private ItemResolverChain buildResolverChain() {
        ItemResolverChain chain = new ItemResolverChain();
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            try {
                chain.register(new NexoItemResolver());
                logger.info("[" + HarvestBranding.DISPLAY_NAME + "] Nexo item integration enabled");
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Nexo present but API mismatch: " + t);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
            try {
                chain.register(new OraxenItemResolver());
                logger.info("[" + HarvestBranding.DISPLAY_NAME + "] Oraxen item integration enabled");
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Oraxen present but API mismatch: " + t);
            }
        }
        return chain;
    }

    /**
     * Parses every crop and fertilizer definition and activates the ones that
     * parsed cleanly. Definitions with fatal errors are dropped by the loader
     * (it returns {@code null} for them), so a single broken file never takes
     * the rest of the configuration down with it.
     */
    private List<ValidationIssue> loadDefinitions(File dataFolder) {
        CropDefinitionLoader loader = new CropDefinitionLoader(items::hasNamespace, items::exists);
        CropDefinitionLoader.LoadResult result = loader.load(
            new File(dataFolder, "crops"), new File(dataFolder, "fertilizers.yml"));
        registry.swap(result.crops(), result.fertilizers());
        return new java.util.ArrayList<>(result.issues());
    }

    private List<ValidationIssue> loadSprinklers(File dataFolder) {
        File file = new File(dataFolder, "sprinklers.yml");
        return sprinklerModels.load(
            YamlConfiguration.loadConfiguration(file), file.getName(),
            items::hasNamespace, items::exists);
    }

    private List<ValidationIssue> loadSpawnerModels(File dataFolder) {
        File file = new File(dataFolder, "spawner-models.yml");
        return spawnerModels.load(
            YamlConfiguration.loadConfiguration(file), file.getName(),
            items::hasNamespace, items::exists);
    }

    private void reportIssues(List<ValidationIssue> issues) {
        for (ValidationIssue issue : issues) {
            String line = "[" + HarvestBranding.DISPLAY_NAME + "] " + issue;
            if (issue.isError()) {
                logger.severe(line);
            } else {
                logger.warning(line);
            }
        }
    }

    private void saveDefault(String name) {
        File target = new File(plugin.getDataFolder(),
            HarvestBranding.DATA_FOLDER + File.separator + name.replace('/', File.separatorChar));
        if (target.exists()) {
            return;
        }
        String resource = HarvestBranding.DATA_FOLDER + "/" + name;
        if (plugin.getResource(resource) != null) {
            plugin.saveResource(resource, false);
        } else {
            target.getParentFile().mkdirs();
        }
    }

    // ==================== accessors for the command ====================

    public HarvestConfig config() { return config; }
    public CropRegistry registry() { return registry; }
    public CropInstanceService instances() { return instances; }
    public CropPlacementService placement() { return placement; }
    public CropHarvestService harvests() { return harvests; }
    public CropGrowthEngine growthEngine() { return growthEngine; }
    public CropVisualService visuals() { return visuals; }
    public CropStorage cropStorage() { return cropStorage; }
    public SpawnerTrackingService spawnerTracking() { return spawnerTracking; }
    public SpawnerModelRegistry spawnerModels() { return spawnerModels; }
    public ItemResolverChain items() { return items; }
    public SoilService soils() { return soils; }
    public SprinklerService sprinklers() { return sprinklers; }
    public SprinklerRegistry sprinklerModels() { return sprinklerModels; }
}
