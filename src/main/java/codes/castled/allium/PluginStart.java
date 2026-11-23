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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        itemService = new ItemService(hmacSigner, logger::warning);
        try {
            packManifest = CoreItems.loadManifest(getClassLoader());
            CoreItems.definitions(packManifest).forEach(itemService::registerAlliumItem);
            codes.castled.allium.gui.GuiIcons.init(this, packManifest);
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
        new ItemSigningKeyAudit(hmacSigner, logger::severe).install(this, scheduler);
        // Addons (depend: [Allium]) are loaded but not yet enabled here, so each is checked before it starts.
        addonManager = new codes.castled.allium.addon.AddonManager(this);
        addonManager.ensureAutoDownloaded();
        addonApi = new codes.castled.allium.addon.AlliumApiService(this);
        addonApi.install();
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
     * The pack to deliver: {@code resource-pack.pack-file} if it is a valid core-pack build, else
     * the bundled standalone pack (when {@code owner: standalone} and bundled in the jar), else the
     * bundled core pack — merged with any {@code resource-pack.delivery: allium} addon packs.
     */
    @org.jetbrains.annotations.Nullable
    private codes.castled.allium.pack.delivery.PackSource packSource(ResourcePackSettings settings,
                                                                     codes.castled.allium.pack.delivery.PackExport.Logs logs,
                                                                     java.util.List<codes.castled.allium.pack.delivery.AddonPacks.Discovered> addonPacks) {
        byte[] core = bundledPackResource(CoreItems.PACK_RESOURCE);
        if (core == null) {
            logs.warning().accept("The core resource pack is missing from the plugin jar; nothing was exported or sent.");
            return null;
        }
        codes.castled.allium.pack.delivery.PackSource base;
        if (settings.packFile() != null && !settings.packFile().isBlank()) {
            base = codes.castled.allium.pack.delivery.PackSource.choose(getDataFolder().toPath(), settings.packFile(), core, logs.warning());
        } else if (settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
            byte[] standalone = bundledPackResource(CoreItems.STANDALONE_PACK_RESOURCE);
            base = standalone != null
                    ? codes.castled.allium.pack.delivery.PackSource.bundled(standalone)
                    : codes.castled.allium.pack.delivery.PackSource.bundled(core);
        } else {
            base = codes.castled.allium.pack.delivery.PackSource.bundled(core);
        }
        var merged = codes.castled.allium.pack.delivery.AddonPacks.mergeIntoBase(base, addonPacks, logs);
        try {
            byte[] withHint = codes.castled.allium.hide.CommandHintPack.merge(merged.bytes(),
                    codes.castled.allium.hide.CommandHintPack.configured(this));
            return withHint == merged.bytes() ? merged : new codes.castled.allium.pack.delivery.PackSource(
                    withHint, codes.castled.allium.pack.delivery.PackExport.sha1(withHint), merged.source(), merged.override());
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | IllegalArgumentException failure) {
            logs.warning().accept("Could not merge the configured command hint: " + failure.getMessage());
            return merged;
        }
    }

    private codes.castled.allium.pack.delivery.PackSource exportPack(ResourcePackSettings settings,
                                                                     codes.castled.allium.pack.delivery.PackExport.Logs logs) {
        var addonPacks = codes.castled.allium.pack.delivery.AddonPacks.discover(getDataFolder().toPath(), getConfig(), logs);
        codes.castled.allium.pack.delivery.AddonPacks.exportRspm(getDataFolder().toPath().getParent(), settings, addonPacks, logs);
        if (settings.owner() == ResourcePackSettings.Owner.NONE) {
            cleanupHintPack(settings, logs);
            var waiting = addonPacks.stream()
                    .filter(d -> d.mode() == codes.castled.allium.pack.delivery.AddonPacks.Mode.ALLIUM)
                    .map(codes.castled.allium.pack.delivery.AddonPacks.Discovered::addonName)
                    .toList();
            if (!waiting.isEmpty()) {
                logs.warning().accept("Addon pack(s) " + waiting + " requested resource-pack.delivery: allium, "
                        + "but Allium's resource-pack.owner is none. Set Allium's owner to standalone/rpm-export or set the addon's delivery to rspm.");
            }
            return null;
        }
        codes.castled.allium.pack.delivery.PackSource source = packSource(settings, logs, addonPacks);
        if (source == null) {
            return null;
        }
        if (source.override()) {
            logs.info().accept("Delivering " + source.source() + " (SHA-1 " + source.sha1() + ") instead of the bundled core pack.");
        }
        try {
            if (settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
                codes.castled.allium.pack.delivery.PackExport.exportStandalone(getDataFolder().toPath(), source.bytes(), logs);
            } else {
                AssetManifest manifest = CoreItems.loadManifest(getClassLoader());
                codes.castled.allium.pack.delivery.PackExport.exportToMixer(getDataFolder().toPath().getParent(), settings, source.bytes(),
                        manifest.tools(), logs);
            }
            if (settings.owner() != ResourcePackSettings.Owner.RPM_EXPORT || !settings.dryRun()) cleanupHintPack(settings, logs);
        } catch (IOException | RuntimeException e) {
            logs.warning().accept("Could not export the core resource pack for " + settings.owner().configName() + ": " + e);
        }
        return source;
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

    /** Logs the bundled core pack and, when Allium owns delivery (standalone), starts sending it. */
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
                var quietLogs = new codes.castled.allium.pack.delivery.PackExport.Logs(m -> { }, m -> { });
                var addonPacks = codes.castled.allium.pack.delivery.AddonPacks.discover(getDataFolder().toPath(), getConfig(), quietLogs);
                codes.castled.allium.pack.delivery.PackSource source = packSource(settings, quietLogs, addonPacks);
                if (source != null) {
                    packDelivery = new codes.castled.allium.pack.delivery.PackDelivery(this, settings, source);
                    packDelivery.enable();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("Could not read the bundled core resource pack info: " + e.getMessage());
        }
    }

    /**
     * {@code /allium reload}: reloads config.yml, the lang files, disabled-commands dispatch and the
     * preference defaults (including {@code sounds.messages.fallback-volume}), and re-applies
     * resource-pack settings. It re-exports the pack and, for standalone, re-checks the
     * hosted file. Owner changes and module settings take effect on restart.
     *
     * @param reply receives progress lines for the command sender
     * @return whether the reload succeeded
     */
    public boolean reloadConfiguration(java.util.function.Consumer<String> reply) {
        try {
            configManager.reloadAll();
            if (autoRestart != null && !autoRestart.reload()) throw new IllegalArgumentException("invalid autorestart.yml");
            if(moduleManager.getModule("economy") instanceof codes.castled.allium.module.EconomyModule economy
                    && economy.isEnabled() && economy.runtime()!=null && !economy.runtime().reload())
                throw new IllegalArgumentException("invalid/busy economy settings; previous settings retained");
            languageManager.reload();
            if(moduleManager.getModule("items") instanceof codes.castled.allium.module.ItemsModule items && items.isEnabled())items.reloadDatabase();
            commandManager.reload();
            if (moduleManager.getModule("preferences") instanceof codes.castled.allium.module.PreferencesModule preferences
                    && preferences.isEnabled()) {
                logger.info("Preference defaults reloaded: " + preferences.reloadDefaults());
            }
            if (moduleManager.getModule("teleport") instanceof codes.castled.allium.module.TeleportModule tp && tp.isEnabled()
                    && !tp.teleports().reloadSettings()) throw new IllegalArgumentException("invalid teleport configuration; previous teleport settings retained");
            if (moduleManager.getModule("travel") instanceof codes.castled.allium.module.TravelModule travel && travel.isEnabled()
                    && !travel.travel().reloadSettings()) throw new IllegalArgumentException("invalid homes/warps configuration; previous settings retained");
            if (moduleManager.getModule("kits") instanceof codes.castled.allium.module.KitsModule kits && kits.isEnabled()
                    && kits.kits() != null) {
                kits.kits().reloadDefinitions();
            }
            if (moduleManager.getModule("moderation") instanceof codes.castled.allium.module.ModerationModule mod && mod.isEnabled()
                    && mod.moderation() != null) {
                mod.moderation().reloadSettings();
                if (mod.discipline() != null) mod.discipline().reloadSettings();
                if (mod.inspection() != null) mod.inspection().reloadSettings();
                mod.reloadFeeds();
            }
            if (moduleManager.getModule("info") instanceof codes.castled.allium.module.PlayerInfoModule info && info.isEnabled()
                    && info.info() != null) {
                info.info().reloadSettings();
            }
            if(moduleManager.getModule("signs") instanceof codes.castled.allium.module.SignsModule signs && signs.isEnabled())signs.reloadSettings();
            if(moduleManager.getModule("chat") instanceof codes.castled.allium.module.ChatModule chat && chat.isEnabled())chat.chat().reload();
            if(moduleManager.getModule("hide") instanceof codes.castled.allium.module.HideModule hide && hide.isEnabled())hide.hide().reload();
            if(moduleManager.getModule("creative") instanceof codes.castled.allium.module.CreativeModule creative && creative.isEnabled())creative.reloadSettings();
            if(moduleManager.getModule("essentials") instanceof codes.castled.allium.module.EssentialsModule essentials && essentials.isEnabled())essentials.reloadSettings();
            if(moduleManager.getModule("tools") instanceof codes.castled.allium.module.ToolsModule tools && tools.isEnabled())tools.reloadPowerTools();
            if(moduleManager.getModule("restore") instanceof codes.castled.allium.module.RestoreModule restoreModule && restoreModule.isEnabled())restoreModule.reloadSettings();
            if(moduleManager.getModule("rtp") instanceof codes.castled.allium.module.RtpModule rtpModule && rtpModule.isEnabled()
                    && !rtpModule.reloadSettings()) throw new IllegalArgumentException("invalid rtp.yml; previous random teleport settings retained");
            if(moduleManager.getModule("worth") instanceof codes.castled.allium.module.WorthModule worthModule && worthModule.isEnabled()
                    && !worthModule.reloadSettings()) throw new IllegalArgumentException("invalid worth.yml; previous prices retained");
            if(moduleManager.getModule("explosions") instanceof codes.castled.allium.module.ExplosionsModule explosions && explosions.isEnabled()
                    && !explosions.reloadSettings()) throw new IllegalArgumentException("invalid explosions.yml; previous explosion settings retained");
            if (moduleManager.getModule("modguard") instanceof codes.castled.allium.module.ModGuardModule modguard && modguard.isEnabled()) {
                try {
                    modguard.reloadSettings();
                } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                    throw new IllegalArgumentException("invalid modguard.yml; previous settings retained", e);
                }
            }
            refreshResourcePack(reply);
            return true;
        } catch (RuntimeException e) {
            logger.severe("Reload failed: " + e);
            return false;
        }
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
        var source = exportPack(settings, new codes.castled.allium.pack.delivery.PackExport.Logs(logger::info, logger::warning));
        if (settings.owner() == ResourcePackSettings.Owner.RPM_EXPORT) {
            reply.accept("Re-exported the core resource pack to ResourcePackManager's mixer; reload ResourcePackManager to merge it.");
        } else if (source != null && settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
            if (packDelivery == null) {
                packDelivery = new codes.castled.allium.pack.delivery.PackDelivery(this, settings, source);
                packDelivery.enable();
            } else packDelivery.reload(settings, source, reply);
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

        logger.info("Allium disabled.");
        instance = null;
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
