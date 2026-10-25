package codes.castled.allium.harvest;

import codes.castled.allium.harvest.api.AlliumHarvestApi;
import codes.castled.allium.harvest.api.HarvestApiImpl;
import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.CropBlockRenderer;
import codes.castled.allium.harvest.crop.CropGrowthEngine;
import codes.castled.allium.harvest.crop.CropHarvestService;
import codes.castled.allium.harvest.crop.CropInstanceService;
import codes.castled.allium.harvest.crop.CropLiquidListeners;
import codes.castled.allium.harvest.crop.CropListeners;
import codes.castled.allium.harvest.crop.CropPlacementService;
import codes.castled.allium.harvest.crop.CropVisualService;
import codes.castled.allium.harvest.crop.NoopCropBlockRenderer;
import codes.castled.allium.harvest.crop.craftengine.CraftEngineSupport;
import codes.castled.allium.harvest.crop.def.CropDefinitionLoader;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.item.CraftEngineItemResolver;
import codes.castled.allium.item.CraftEngineItemsGate;
import codes.castled.allium.item.NexoItemsGate;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.item.ItemResolvers;
import codes.castled.allium.harvest.kitchen.KitchenModule;
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
    private KitchenModule kitchen;
    private TaskHandle spawnerSweepTask;
    private boolean enabled;
    private int pendingProviders;
    private boolean providersChecked;
    private CropBlockRenderer blocks = NoopCropBlockRenderer.INSTANCE;
    private boolean blockProviderReady;
    private java.util.Set<String> craftEngineNamespaces = java.util.Set.of();

    public HarvestModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== lifecycle ====================

    /**
     * Enables the module, or — with a custom-item plugin installed — schedules
     * it for when that plugin has registered its items. See
     * {@link NexoItemsGate} and {@code CraftEngineItemsGate} for why loading
     * any earlier is unsafe.
     */
    public void enable() {
        boolean nexo = Bukkit.getPluginManager().isPluginEnabled("Nexo");
        boolean craftEngine = Bukkit.getPluginManager().isPluginEnabled("CraftEngine");
        int providers = (nexo ? 1 : 0) + (craftEngine ? 1 : 0);
        if (providers == 0) {
            enableNow();
            return;
        }
        pendingProviders = providers;
        if (nexo) {
            try {
                NexoItemsGate.await(plugin, this::providerReady, this::providerReloaded);
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME
                    + "] Could not wait for Nexo items, loading now: " + t);
                providerReady();
            }
        }
        if (craftEngine) {
            try {
                CraftEngineItemsGate.await(plugin, this::providerReady, this::providerReloaded);
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME
                    + "] Could not wait for CraftEngine items, loading now: " + t);
                providerReady();
            }
        }
        providersChecked = true;
        maybeEnable();
        if (!enabled) {
            logger.info("[" + HarvestBranding.DISPLAY_NAME
                + "] Waiting for item providers to load");
        }
    }

    /** One item provider became ready; the module starts once all of them are. */
    private void providerReady() {
        if (pendingProviders > 0) {
            pendingProviders--;
        }
        maybeEnable();
    }

    private void maybeEnable() {
        if (!providersChecked || pendingProviders > 0 || enabled) {
            return;
        }
        enableNow();
    }

    /** An item provider reloaded: pick up its new items and reload everything. */
    private void providerReloaded() {
        if (!enabled) {
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            craftEngineNamespaces.forEach(items::unregister);
            craftEngineNamespaces = ItemResolvers.registerCraftEngine(
                items, HarvestBranding.DISPLAY_NAME, logger);
        }
        reload();
    }

    private void enableNow() {
        if (enabled) {
            return;
        }
        File dataFolder = new File(plugin.getDataFolder(), HarvestBranding.DATA_FOLDER);
        saveDefault("config.yml");
        saveDefault("fertilizers.yml");
        saveDefault("spawner-models.yml");
        saveDefault("sprinklers.yml");
        saveDefault("crops/tomato.yml");
        saveDefault("kitchen.yml");

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

        setupBlockProvider();
        if (blockProviderReady) {
            buildCraftEnginePack();
        }
        items = buildResolverChain();
        registry = new CropRegistry();
        List<ValidationIssue> issues = loadDefinitions(dataFolder);
        reportIssues(issues);

        soils = new SoilService(soilStorage, config.soil());
        sprinklerModels = new SprinklerRegistry();
        reportIssues(loadSprinklers(dataFolder));
        sprinklers = new SprinklerService(sprinklerModels, sprinklerStorage, soils, config.sprinklers());

        visuals = new CropVisualService(logger, registry, items, config.visuals(), blocks);
        instances = new CropInstanceService(plugin, registry, cropStorage, visuals);
        placement = new CropPlacementService(registry, instances, cropStorage, visuals,
            soils, sprinklers, ThreadLocalRandom.current());
        harvests = new CropHarvestService(logger, registry, instances, cropStorage, visuals, items,
            ThreadLocalRandom.current());
        growthEngine = new CropGrowthEngine(plugin, registry, instances, cropStorage, visuals, config.growthEngine());

        spawnerModels = new SpawnerModelRegistry();
        reportIssues(loadSpawnerModels(dataFolder));
        spawnerTracking = new SpawnerTrackingService(plugin, spawnerModels, spawnerStorage, items, config.spawners());

        Bukkit.getPluginManager().registerEvents(
            new CropListeners(registry, instances, placement, harvests, cropStorage, items,
                soils, sprinklers), plugin);
        if (blockProviderReady) {
            try {
                Bukkit.getPluginManager().registerEvents(
                    CraftEngineSupport.createCropListener(instances, registry), plugin);
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME
                    + "] CraftEngine crop listener could not be registered: " + t);
            }
        }
        if (config.liquids().enabled()) {
            Bukkit.getPluginManager().registerEvents(
                new CropLiquidListeners(instances, harvests, config.liquids()), plugin);
        }
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

        kitchen = new KitchenModule(plugin, items, dataFolder);
        reportIssues(kitchen.enable());

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
        if (kitchen != null) {
            kitchen.disable();
        }
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
        issues.addAll(kitchen.reload());
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

    /**
     * Sets up the renderer that draws crop stages as real blocks, if a block
     * provider is installed.
     */
    private void setupBlockProvider() {
        blocks = NoopCropBlockRenderer.INSTANCE;
        blockProviderReady = false;
        if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            return;
        }
        try {
            blocks = CraftEngineSupport.createBlockRenderer(logger);
            blockProviderReady = true;
        } catch (Throwable t) {
            logger.warning("[" + HarvestBranding.DISPLAY_NAME
                + "] CraftEngine present but API mismatch: " + t);
        }
    }

    /**
     * Asks CraftEngine to build its resource pack.
     *
     * <p>CraftEngine only writes its pack when its packs are reloaded, so a
     * server that restarts without a reload keeps serving the pack it built
     * last time — which is stale the moment a pack file changed on disk. The
     * pack is what carries the crop models and textures, so a stale one means
     * crops that render as the wrong stage, or as nothing at all.
     *
     * <p>There is no configuration switch for this: the only trigger
     * CraftEngine fires itself is {@code reload_pack}, which the reload
     * command raises. The workflow has to be run, and running it is what this
     * does — a tick later, so the rest of the startup has finished first.</p>
     */
    private void buildCraftEnginePack() {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                boolean done = Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), "ce workflow default");
                logger.info("[" + HarvestBranding.DISPLAY_NAME + "] CraftEngine pack build "
                    + (done ? "finished" : "could not be started"));
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME
                    + "] Could not build the CraftEngine pack: " + t);
            }
        }, 40L);
    }

    private ItemResolverChain buildResolverChain() {
        ItemResolverChain chain = ItemResolvers.build(plugin, HarvestBranding.DISPLAY_NAME, logger);
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            try {
                craftEngineNamespaces = CraftEngineItemResolver.loadedNamespaces();
            } catch (Throwable t) {
                craftEngineNamespaces = java.util.Set.of();
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
        // Re-read the crop defaults from disk rather than using the cached
        // config: they are only ever parse-time inputs to the loader, so
        // picking them up here makes them reloadable without restarting —
        // unlike storage or visual settings, which live services hold onto.
        HarvestConfig.CropDefaults defaults = HarvestConfig.from(
            YamlConfiguration.loadConfiguration(
                new File(dataFolder, "config.yml"))).cropDefaults();
        CropDefinitionLoader loader = new CropDefinitionLoader(
            items::hasNamespace, items::exists,
            defaults.interaction(), defaults.growthRandomness(),
            blocks::exists, () -> blockProviderReady);
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
        // A missing item-provider plugin (e.g. Nexo) trips every reference in a file.
        // Report that once per file instead of once per config path.
        java.util.Set<String> reportedMissingProviders = new java.util.HashSet<>();
        for (ValidationIssue issue : issues) {
            if (issue.message().contains("has no resolver")
                    && !reportedMissingProviders.add(issue.file() + "|" + issue.message())) {
                continue;
            }
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
