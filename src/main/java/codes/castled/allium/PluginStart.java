package codes.castled.allium;

import codes.castled.allium.command.CommandManager;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.config.ConfigManager;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ItemSigningKeyAudit;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.item.provider.ProviderAdapterBootstrap;
import codes.castled.allium.item.provider.ProviderStartupDiagnostics;
import codes.castled.allium.listener.ListenerManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.pack.CoreItems;
import codes.castled.allium.pack.Json;
import codes.castled.allium.pack.ResourcePackSettings;
import codes.castled.allium.protection.ProtectionHookBootstrap;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class PluginStart implements Plugin, AlliumRuntime {

    private static PluginStart instance;

    private final JavaPlugin host;

    public PluginStart(JavaPlugin host) {
        this.host = host;
    }

    /** Protected no-arg constructor for test mocks. */
    protected PluginStart() {
        this.host = null;
    }

    /** The real Bukkit {@link JavaPlugin} registered by {@code PluginClassLoader} (or {@code this} in unit tests). */
    public Plugin bukkitPlugin() {
        return host != null ? host : this;
    }

    /** The underlying {@link JavaPlugin} host when running on a live server, or {@code null} in unit tests. */
    @org.jetbrains.annotations.Nullable
    public JavaPlugin javaPlugin() {
        return host;
    }

    private AlliumLogger logger;
    private ConfigManager configManager;
    private codes.castled.allium.restart.AutoRestart autoRestart;
    private LanguageManager languageManager;
    private MessageManager messageManager;
    private codes.castled.allium.naming.PlayerNameService playerNames;
    private codes.castled.allium.placeholder.AlliumPlaceholders placeholders;
    private DatabaseManager databaseManager;
    private ItemService itemService;
    private ProtectionService protectionService;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ListenerManager listenerManager;
    private AssetManifest packManifest;
    private AlliumScheduler scheduler;
    private codes.castled.allium.pack.delivery.PackDelivery packDelivery;
    private volatile codes.castled.allium.pack.delivery.PackGeneration activePackGeneration;
    private ResourcePackSettings.Owner startupPackOwner = ResourcePackSettings.Owner.NONE;
    private codes.castled.allium.addon.AlliumApiService addonApi;
    private codes.castled.allium.addon.AddonManager addonManager;
    private codes.castled.allium.importer.ImportApplyCoordinator importApplyCoordinator;

    @Override
    public void onEnable() {
        instance = this;

        logger = new AlliumLogger(this);
        logger.info("Starting Allium...");
        // The one owner of Allium's server-thread tasks, timers and worker pools; closed last.
        scheduler = new AlliumScheduler(BukkitServerThread.global(this), logger::severe);
        if (FaultInjection.enabled()) {
            logger.warning("Fault injection is enabled (-D" + FaultInjection.PROPERTY + "=true). This is for disposable "
                    + "test servers only: armed fault points make Allium operations fail on purpose.");
        }

        configManager = new ConfigManager(this);
        configManager.loadAll();

        languageManager = new LanguageManager(this, configManager.getSyncManager());
        languageManager.load();

        messageManager = new MessageManager(this, languageManager);

        GodModeCompatProvider.init(this);

        ItemHmacSigner hmacSigner;
        try {
            Path secretFile = getDataFolder().toPath().resolve(".item-hmac-secret");
            hmacSigner = ItemHmacSigner.loadOrGenerate(secretFile);
        } catch (IOException e) {
            logger.severe("Failed to initialize item HMAC secret: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(bukkitPlugin());
            return;
        }
        itemService = new ItemService(hmacSigner, logger::warning);
        try {
            packManifest = CoreItems.loadManifest(getClassLoader());
            CoreItems.definitions(packManifest).forEach(itemService::registerAlliumItem);
            codes.castled.allium.gui.GuiIcons.init(this, packManifest);
        } catch (IOException | IllegalArgumentException e) {
            logger.severe("Failed to load the core item manifest: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(bukkitPlugin());
            return;
        }
        reportCorePack();
        ProviderAdapterBootstrap.registerAll(
                this,
                getServer().getPluginManager(),
                itemService,
                logger::info,
                logger::warning
        );

        protectionService = new ProtectionService(
                ProtectionHookBootstrap.loadAll(getServer().getPluginManager(), logger::info, logger::warning),
                getServer().getPluginManager(),
                logger::warning);

        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.connect();
        } catch (Exception e) {
            logger.severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(bukkitPlugin());
            return;
        }

        playerNames = new codes.castled.allium.naming.PlayerNameService(this,
                new codes.castled.allium.naming.PlayerNameRepository(databaseManager.connectionSource(), databaseManager.dialect(),
                        System::currentTimeMillis),
                Math.max(1, Math.min(1_000_000, getConfig().getInt("player-names.cache-size", 10_000))),
                scheduler.boundedPool("Allium-Names", 1, 1024));
        try {
            playerNames.enable();
        } catch (java.sql.SQLException e) {
            logger.warning("The player name index is unavailable (mail and offline-name completion are off): " + e.getMessage());
            playerNames = null;
        }

        commandManager = new CommandManager(this);
        listenerManager = new ListenerManager(this);
        importApplyCoordinator = new codes.castled.allium.importer.ImportApplyCoordinator(this);
        listenerManager.register(importApplyCoordinator);

        moduleManager = new ModuleManager(this);
        moduleManager.loadModules();

        commandManager.register(new codes.castled.allium.command.AlliumCommand(this));
        commandManager.register(new codes.castled.allium.command.HelpCommand(this));
        commandManager.register(new codes.castled.allium.command.SettingsCommand(this, true));
        commandManager.register(new codes.castled.allium.command.SettingsCommand(this, false));
        var redeem = new codes.castled.allium.command.RedeemCommand(this);
        commandManager.register(redeem);
        listenerManager.register(redeem);
        listenerManager.register(new codes.castled.allium.chat.JoinQuitMessages(this));
        autoRestart = new codes.castled.allium.restart.AutoRestart(this);
        autoRestart.reload();
        commandManager.register(autoRestart);
        commandManager.register(autoRestart.voteCommand());
        commandManager.registerAll();
        listenerManager.registerAll();
        placeholders = new codes.castled.allium.placeholder.AlliumPlaceholders(this);
        for (String key : java.util.List.of("restart_time", "restart_seconds", "restart_scheduled")) {
            placeholders.register(key, ignored -> autoRestart.placeholder(key));
        }
        placeholders.start();
        new ProviderStartupDiagnostics(itemService, getServer().getPluginManager(), logger::warning).install(scheduler);
        new ItemSigningKeyAudit(hmacSigner, logger::severe).install(bukkitPlugin(), scheduler);
        // Addons (depend: [Allium]) are loaded but not yet enabled here, so each is checked before it starts.
        addonManager = new codes.castled.allium.addon.AddonManager(this);
        int downloadedOnEnable = addonManager.ensureAutoDownloaded();
        addonApi = new codes.castled.allium.addon.AlliumApiService(this);
        getServer().getServicesManager().register(codes.castled.allium.api.MenuData.class,
                new codes.castled.allium.addon.CoreMenuData(this), bukkitPlugin(), org.bukkit.plugin.ServicePriority.Normal);
        getServer().getServicesManager().register(codes.castled.allium.api.TradeItemCatalog.class,
                new codes.castled.allium.addon.CoreTradeItemCatalog(itemService), bukkitPlugin(), org.bukkit.plugin.ServicePriority.Normal);
        addonApi.install();
        if (downloadedOnEnable > 0) {
            refreshResourcePack(logger::info);
        }
        logger.info("Item signing keyring loaded: active=" + hmacSigner.activeKeyId()
                + ", verification keys=" + hmacSigner.keyIds()
                + ". Loaded inventories are audited for unknown key IDs.");

        logger.info("Allium v" + getDescription().getVersion() + " enabled.");
    }

    /** Reads a bundled pack resource from the jar, or {@code null} if absent. */
    @org.jetbrains.annotations.Nullable
    private byte[] bundledPackResource(String resource) {
        try (java.io.InputStream in = getResource(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Exports the core pack for its delivery owner. This runs before any plugin enables, so
     * ResourcePackManager (rpm-export) merges the current pack when it starts.
     */
    @Override
    public void onLoad() {
        codes.castled.allium.addon.AddonManager.ensureAutoDownloaded(
                getDataFolder().toPath(),
                getConfig(),
                getDescription().getVersion(),
                codes.castled.allium.addon.AddonManager.defaultDownloader(),
                getLogger()::info,
                getLogger()::warning
        );
        ResourcePackSettings settings = ResourcePackSettings.parse(getConfig().getConfigurationSection("resource-pack"));
        exportPack(settings, new codes.castled.allium.pack.delivery.PackExport.Logs(getLogger()::info, getLogger()::warning));
    }

    /**
     * Assembles one immutable {@link codes.castled.allium.pack.delivery.PackGeneration} from the
     * validated addon inventory, bundled core/standalone pack, and configured command hint.
     */
    private codes.castled.allium.pack.delivery.PackGeneration assemblePackGeneration(
            ResourcePackSettings settings,
            codes.castled.allium.pack.delivery.PackExport.Logs logs
    ) {
        Path dataFolder = getDataFolder().toPath();
        Path pluginsFolder = dataFolder.getParent();
        byte[] core = bundledPackResource(CoreItems.PACK_RESOURCE);
        byte[] standalone = settings.owner() == ResourcePackSettings.Owner.STANDALONE
                ? bundledPackResource(CoreItems.STANDALONE_PACK_RESOURCE)
                : null;
        String hint = "";
        try {
            hint = codes.castled.allium.hide.CommandHintPack.configured(this);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | IllegalArgumentException failure) {
            logs.warning().accept("Could not read the configured command hint: " + failure.getMessage());
        }
        return codes.castled.allium.pack.delivery.PackGeneration.assemble(
                dataFolder, pluginsFolder, getConfig(), settings, core, standalone, hint, logs);
    }

    private codes.castled.allium.pack.delivery.PackGeneration.PublishOutcome exportPack(
            ResourcePackSettings settings,
            codes.castled.allium.pack.delivery.PackExport.Logs logs
    ) {
        Path dataFolder = getDataFolder().toPath();
        Path pluginsFolder = dataFolder.getParent();
        var generation = assemblePackGeneration(settings, logs);
        this.activePackGeneration = generation;
        java.util.List<AssetManifest.Tool> tools = java.util.List.of();
        if (settings.owner() == ResourcePackSettings.Owner.RPM_EXPORT) {
            try {
                tools = CoreItems.loadManifest(getClassLoader()).tools();
            } catch (IOException | RuntimeException e) {
                logs.warning().accept("Could not load core item manifest for mixer audit: " + e.getMessage());
            }
        }
        var outcome = generation.publishExports(dataFolder, pluginsFolder, tools, logs);
        if (settings.owner() != ResourcePackSettings.Owner.RPM_EXPORT || !settings.dryRun()) {
            cleanupHintPack(settings, logs);
        }
        return outcome;
    }

    private void cleanupHintPack(ResourcePackSettings settings, codes.castled.allium.pack.delivery.PackExport.Logs logs) {
        var root = getDataFolder().toPath().getParent();
        var rpm = getServer().getPluginManager().getPlugin("ResourcePackManager");
        var oldMixer = (rpm == null ? root.resolve("ResourcePackManager") : rpm.getDataFolder().toPath()).resolve("mixer");
        try {
            codes.castled.allium.hide.CommandHintPack.cleanup(this, oldMixer);
            if (settings.owner() == ResourcePackSettings.Owner.RPM_EXPORT) {
                var configured = root.resolve(settings.mixerDirectory()).normalize();
                if (!configured.equals(oldMixer)) codes.castled.allium.hide.CommandHintPack.cleanup(this, configured);
            }
        } catch (IOException failure) {
            logs.warning().accept("Could not remove the old command hint pack: " + failure.getMessage());
        }
    }

    /** Logs the bundled core pack and, when Allium owns delivery (standalone), starts sending the already-assembled generation. */
    private void reportCorePack() {
        ResourcePackSettings settings = ResourcePackSettings.parse(getConfig().getConfigurationSection("resource-pack"));
        settings.warnings().forEach(logger::warning);
        String infoResource = settings.owner() == ResourcePackSettings.Owner.STANDALONE
                && getClassLoader().getResource(CoreItems.STANDALONE_PACK_INFO_RESOURCE) != null
                ? CoreItems.STANDALONE_PACK_INFO_RESOURCE
                : CoreItems.PACK_INFO_RESOURCE;
        try (java.io.InputStream in = getClassLoader().getResourceAsStream(infoResource)) {
            if (in == null) {
                logger.warning("The core resource pack is missing from the plugin jar.");
                return;
            }
            java.util.Map<String, Object> info = Json.parseObject(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String owner = switch (settings.owner()) {
                case NONE -> "none, so Allium does not send or export it";
                case STANDALONE -> settings.selfHost().enabled()
                        ? "standalone (self-hosted), so Allium serves and sends it directly"
                        : "standalone, so Allium sends it from resource-pack.standalone.url";
                case RPM_EXPORT -> "rpm-export, so ResourcePackManager merges and sends it";
            };
            logger.info("Core resource pack " + info.get("sha1") + " (" + info.get("bytes") + " bytes) is bundled; delivery owner " + owner + ".");
            startupPackOwner = settings.owner();
            if (settings.sendsUrl()) {
                codes.castled.allium.pack.delivery.PackGeneration generation = this.activePackGeneration;
                if (generation == null || generation.settings().owner() != settings.owner()) {
                    var quietLogs = new codes.castled.allium.pack.delivery.PackExport.Logs(m -> { }, m -> { });
                    generation = assemblePackGeneration(settings, quietLogs);
                    this.activePackGeneration = generation;
                }
                if (generation.coreArtifact() != null) {
                    packDelivery = new codes.castled.allium.pack.delivery.PackDelivery(this, generation);
                    packDelivery.enable();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("Could not read the bundled core resource pack info: " + e.getMessage());
        }
    }

    /**
     * {@code /allium reload [target]}: prepares and validates candidate configuration(s) before
     * publishing any live changes, then returns a truthful report of what was applied, rejected,
     * or requires a server restart.
     */
    public boolean reloadConfiguration(java.util.function.Consumer<String> reply) {
        return reloadConfigurationReport(null, reply).success();
    }

    public boolean reloadConfiguration(@org.jetbrains.annotations.Nullable String target,
                                       java.util.function.Consumer<String> reply) {
        return reloadConfigurationReport(target, reply).success();
    }

    /** Two-phase reload returning structured applied, rejected, and restart-required diagnostics. */
    public codes.castled.allium.config.ReloadReport reloadConfigurationReport(java.util.function.Consumer<String> reply) {
        return reloadConfigurationReport(null, reply);
    }

    /** Two-phase full or targeted reload delegating candidate preparation & ordering to modules. */
    public codes.castled.allium.config.ReloadReport reloadConfigurationReport(
            @org.jetbrains.annotations.Nullable String target,
            java.util.function.Consumer<String> reply
    ) {
        var rootSource = new codes.castled.allium.config.ReloadCoordinator.RootConfigSource() {
            @Override
            public org.bukkit.configuration.file.FileConfiguration prepareRoot() throws Exception {
                return configManager.prepareRootConfig();
            }

            @Override
            public java.util.List<codes.castled.allium.config.ReloadReport.Entry> publishRoot(
                    org.bukkit.configuration.file.FileConfiguration candidateRoot) {
                var previous = getConfig();
                configManager.reloadAll();
                return codes.castled.allium.config.ConfigManager.detectRestartOnlyChanges(previous, getConfig());
            }
        };

        java.util.List<codes.castled.allium.config.ReloadCoordinator.Participant> participants = new java.util.ArrayList<>();
        if (autoRestart != null) {
            participants.add(codes.castled.allium.config.ReloadCoordinator.Participant.of("autorestart", 10,
                    autoRestart::prepareReload));
        }
        if (languageManager != null) {
            participants.add(codes.castled.allium.config.ReloadCoordinator.Participant.of("language", 25, candidate -> {
                languageManager.validateLocaleFiles(candidate);
                return codes.castled.allium.config.ReloadCoordinator.Prepared.of(languageManager::reload);
            }));
        }
        if (commandManager != null) {
            participants.add(codes.castled.allium.config.ReloadCoordinator.Participant.of("commands", 40,
                    commandManager::prepareReload));
        }
        if (moduleManager != null) {
            for (codes.castled.allium.core.AbstractModule module : moduleManager.getModules().values()) {
                if (!module.isEnabled()) continue;
                participants.add(codes.castled.allium.config.ReloadCoordinator.Participant.of(
                        module.getName(), module.reloadOrder(), module::prepareReload));
            }
        }
        participants.add(codes.castled.allium.config.ReloadCoordinator.Participant.of("resource-pack", 200, candidate -> {
            var parsed = ResourcePackSettings.parse(candidate.getConfigurationSection("resource-pack"));
            return () -> {
                if (parsed.owner() != startupPackOwner) {
                    String message = "resource-pack.owner changed from " + startupPackOwner.configName()
                            + " to " + parsed.owner().configName() + "; owner changes take effect after a restart.";
                    logger.warning(message);
                    return java.util.List.of(new codes.castled.allium.config.ReloadReport.Entry("resource-pack", message));
                }
                refreshResourcePack(reply);
                return java.util.List.of();
            };
        }));

        var report = (target == null || target.isBlank())
                ? codes.castled.allium.config.ReloadCoordinator.execute(rootSource, participants, reply, logger::warning)
                : codes.castled.allium.config.ReloadCoordinator.executeTargeted(rootSource, participants, target, reply, logger::warning);
        if (!report.success()) {
            logger.severe("Reload failed: " + report.rejected());
        }
        return report;
    }

    /** Re-exports or updates the single delivered pack after settings change. Runs on the server thread. */
    public void refreshResourcePack(java.util.function.Consumer<String> reply) {
        var settings = ResourcePackSettings.parse(getConfig().getConfigurationSection("resource-pack"));
        settings.warnings().forEach(logger::warning);
        if (settings.owner() != startupPackOwner) {
            String message = "resource-pack.owner changed from " + startupPackOwner.configName() + " to " + settings.owner().configName()
                    + "; owner changes take effect after a restart.";
            logger.warning(message);
            reply.accept(message);
            return;
        }
        var outcome = exportPack(settings, new codes.castled.allium.pack.delivery.PackExport.Logs(logger::info, logger::warning));
        if (settings.owner() == ResourcePackSettings.Owner.RPM_EXPORT) {
            reply.accept(codes.castled.allium.pack.delivery.PackExport.formatRpmReloadReply(outcome.coreMixer(), outcome.addonMixer()));
        } else if (outcome.generation().coreArtifact() != null && settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
            if (packDelivery == null) {
                packDelivery = new codes.castled.allium.pack.delivery.PackDelivery(this, outcome.generation());
                packDelivery.enable();
            } else {
                packDelivery.reload(outcome.generation(), reply);
            }
        }
    }

    /** Most recently assembled immutable resource-pack generation; {@code null} before {@code onLoad()}. */
    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.pack.delivery.PackGeneration getActivePackGeneration() {
        return activePackGeneration;
    }

    /** Allium's own pack delivery, when {@code resource-pack.owner} is standalone; otherwise {@code null}. */
    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.pack.delivery.PackDelivery getPackDelivery() {
        return packDelivery;
    }

    @Override
    public void onDisable() {
        if (addonApi != null) addonApi.closeAll();
        if (packDelivery != null) packDelivery.disable();
        if (placeholders != null) {
            placeholders.stop();
            placeholders = null;
        }
        if (autoRestart != null) autoRestart.close();
        if (moduleManager != null) moduleManager.unloadModules();
        if (databaseManager != null) databaseManager.shutdown();
        if (scheduler != null) {
            // Modules drain their own pools first; anything still running here is stopped.
            java.util.List<String> unfinished = scheduler.close(5_000L);
            if (!unfinished.isEmpty()) {
                logger.warning("Stopped Allium worker pools that were still busy at shutdown: " + unfinished);
            }
        }
        HandlerList.unregisterAll(this);
        if (host != null) {
            HandlerList.unregisterAll(host);
            getServer().getServicesManager().unregisterAll(host);
        }

        logger.info("Allium disabled.");
        instance = null;
    }

    // ==================== Plugin / JavaPlugin delegation to host ====================

    public PluginCommand getCommand(String name) {
        return host != null ? host.getCommand(name) : null;
    }

    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public File getDataFolder() {
        return host.getDataFolder();
    }

    @Override
    public PluginDescriptionFile getDescription() {
        return host.getDescription();
    }

    @Override
    public FileConfiguration getConfig() {
        return host.getConfig();
    }

    @Override
    public InputStream getResource(String filename) {
        return host.getResource(filename);
    }

    @Override
    public void saveConfig() {
        host.saveConfig();
    }

    @Override
    public void saveDefaultConfig() {
        host.saveDefaultConfig();
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        host.saveResource(resourcePath, replace);
    }

    @Override
    public void reloadConfig() {
        host.reloadConfig();
    }

    @Override
    public PluginLoader getPluginLoader() {
        return host.getPluginLoader();
    }

    @Override
    public Server getServer() {
        return host.getServer();
    }

    @Override
    public boolean isEnabled() {
        return host != null && host.isEnabled();
    }

    @Override
    public boolean isNaggable() {
        return host.isNaggable();
    }

    @Override
    public void setNaggable(boolean canNag) {
        host.setNaggable(canNag);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return host.getDefaultWorldGenerator(worldName, id);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        return host.getDefaultBiomeProvider(worldName, id);
    }

    @Override
    public Logger getLogger() {
        return host.getLogger();
    }

    @Override
    public String getName() {
        return host.getName();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return java.util.Collections.emptyList();
    }

    /** {@code %allium_...%} for PlaceholderAPI; null before enable or after disable. */
    public codes.castled.allium.placeholder.AlliumPlaceholders getPlaceholders() {
        return placeholders;
    }

    /** Where Allium's work runs: server-thread tasks, timers and bounded worker pools. */
    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public static PluginStart getInstance() {
        return instance;
    }

    public AlliumLogger logger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    /** The shared index of known player names (mail, and M3's /pay); {@code null} if storage failed. */
    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.naming.PlayerNameService getPlayerNames() {
        return playerNames;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ItemService getItemService() {
        return itemService;
    }

    /** The bundled core pack's asset manifest (core item carriers, model data and visual states). */
    public AssetManifest getPackManifest() {
        return packManifest;
    }

    public ProtectionService getProtectionService() {
        return protectionService;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.addon.AlliumApiService getAddonApi() {
        return addonApi;
    }

    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.addon.AddonManager getAddonManager() {
        return addonManager;
    }

    @org.jetbrains.annotations.Nullable
    public codes.castled.allium.importer.ImportApplyCoordinator getImportApplyCoordinator() {
        return importApplyCoordinator;
    }
}
