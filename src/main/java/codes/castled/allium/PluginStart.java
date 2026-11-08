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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public final class PluginStart extends JavaPlugin {

    private static PluginStart instance;

    private AlliumLogger logger;
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private ItemService itemService;
    private ProtectionService protectionService;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ListenerManager listenerManager;
    private AssetManifest packManifest;
    private AlliumScheduler scheduler;
    private codes.castled.allium.pack.delivery.PackDelivery packDelivery;
    private codes.castled.allium.addon.AlliumApiService addonApi;

    @Override
    public void onEnable() {
        instance = this;

        logger = new AlliumLogger(this);
        logger.info("Starting Allium...");
        // The one owner of Allium's server-thread tasks, timers and worker pools; closed last.
        scheduler = new AlliumScheduler(new BukkitServerThread(this), logger::severe);
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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        itemService = new ItemService(hmacSigner, logger::warning);
        try {
            packManifest = CoreItems.loadManifest(getClassLoader());
            CoreItems.definitions(packManifest).forEach(itemService::registerAlliumItem);
        } catch (IOException | IllegalArgumentException e) {
            logger.severe("Failed to load the core item manifest: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        commandManager = new CommandManager(this);
        listenerManager = new ListenerManager(this);

        moduleManager = new ModuleManager(this);
        moduleManager.loadModules();

        commandManager.registerAll();
        listenerManager.registerAll();
        new ProviderStartupDiagnostics(itemService, getServer().getPluginManager(), logger::warning).install(scheduler);
        new ItemSigningKeyAudit(hmacSigner, logger::severe).install(this, scheduler);
        // Addons (depend: [Allium]) are loaded but not yet enabled here, so each is checked before it starts.
        addonApi = new codes.castled.allium.addon.AlliumApiService(this);
        addonApi.install();
        logger.info("Item signing keyring loaded: active=" + hmacSigner.activeKeyId()
                + ", verification keys=" + hmacSigner.keyIds()
                + ". Loaded inventories are audited for unknown key IDs.");

        logger.info("Allium v" + getDescription().getVersion() + " enabled.");
    }

    /** The bundled core pack's bytes, or {@code null} if the jar lacks it. */
    @org.jetbrains.annotations.Nullable
    private byte[] bundledPack() {
        try (java.io.InputStream in = getResource(CoreItems.PACK_RESOURCE)) {
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
        ResourcePackSettings settings = ResourcePackSettings.parse(getConfig().getConfigurationSection("resource-pack"));
        if (settings.owner() == ResourcePackSettings.Owner.NONE) {
            return;
        }
        byte[] pack = bundledPack();
        if (pack == null) {
            getLogger().warning("The core resource pack is missing from the plugin jar; nothing was exported.");
            return;
        }
        codes.castled.allium.pack.delivery.PackExport.Logs logs =
                new codes.castled.allium.pack.delivery.PackExport.Logs(getLogger()::info, getLogger()::warning);
        try {
            if (settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
                codes.castled.allium.pack.delivery.PackExport.exportStandalone(getDataFolder().toPath(), pack, logs);
            } else {
                AssetManifest manifest = CoreItems.loadManifest(getClassLoader());
                codes.castled.allium.pack.delivery.PackExport.exportToMixer(getDataFolder().toPath().getParent(), settings, pack,
                        manifest.tools(), logs);
            }
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Could not export the core resource pack for " + settings.owner().configName() + ": " + e);
        }
    }

    /** Logs the bundled core pack and, when Allium owns delivery (standalone), starts sending it. */
    private void reportCorePack() {
        ResourcePackSettings settings = ResourcePackSettings.parse(getConfig().getConfigurationSection("resource-pack"));
        settings.warnings().forEach(logger::warning);
        try (java.io.InputStream in = getClassLoader().getResourceAsStream(CoreItems.PACK_INFO_RESOURCE)) {
            if (in == null) {
                logger.warning("The core resource pack is missing from the plugin jar.");
                return;
            }
            java.util.Map<String, Object> info = Json.parseObject(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String owner = switch (settings.owner()) {
                case NONE -> "none, so Allium does not send or export it";
                case STANDALONE -> "standalone, so Allium sends it from resource-pack.standalone.url";
                case RPM_EXPORT -> "rpm-export, so ResourcePackManager merges and sends it";
            };
            logger.info("Core resource pack " + info.get("sha1") + " (" + info.get("bytes") + " bytes) is bundled; delivery owner " + owner + ".");
            if (settings.sendsUrl()) {
                packDelivery = new codes.castled.allium.pack.delivery.PackDelivery(this, settings, String.valueOf(info.get("sha1")));
                packDelivery.enable();
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("Could not read the bundled core resource pack info: " + e.getMessage());
        }
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
        if (moduleManager != null) moduleManager.unloadModules();
        if (databaseManager != null) databaseManager.shutdown();
        if (scheduler != null) {
            // Modules drain their own pools first; anything still running here is stopped.
            java.util.List<String> unfinished = scheduler.close(5_000L);
            if (!unfinished.isEmpty()) {
                logger.warning("Stopped Allium worker pools that were still busy at shutdown: " + unfinished);
            }
        }

        logger.info("Allium disabled.");
        instance = null;
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
}
