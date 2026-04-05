package net.survivalfun.core;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.commands.BalTop;
import net.survivalfun.core.commands.Balance;
import net.survivalfun.core.commands.Core;
import net.survivalfun.core.commands.CoreItemCommand;
import net.survivalfun.core.commands.Enchant;
import net.survivalfun.core.commands.EnderChestCommand;
import net.survivalfun.core.commands.Explode;
import net.survivalfun.core.commands.Feed;
import net.survivalfun.core.commands.Fly;
import net.survivalfun.core.commands.Freeze;
import net.survivalfun.core.commands.GC;
import net.survivalfun.core.commands.Gamemode;
import net.survivalfun.core.commands.Give;
import net.survivalfun.core.commands.God;
import net.survivalfun.core.commands.Heal;
import net.survivalfun.core.commands.Help;
import net.survivalfun.core.commands.Home;
import net.survivalfun.core.commands.Invsee;
import net.survivalfun.core.items.commands.Handcuffs;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.items.impl.TreeAxeItem;
import net.survivalfun.core.items.impl.SpawnerChangerItem;
import net.survivalfun.core.items.impl.SpawnerChangerManager;
import net.survivalfun.core.listeners.items.TreeAxeListener;
import net.survivalfun.core.listeners.items.SpawnerChangerListener;
import net.survivalfun.core.listeners.items.OraxenSmeltingListener;
import net.survivalfun.core.spawnercraft.MobHeadDropListener;
import net.survivalfun.core.spawnercraft.SpawnerCoreManager;
import net.survivalfun.core.spawnercraft.SpawnerCraftListener;
import net.survivalfun.core.managers.ResourcePackManager;
import net.survivalfun.core.managers.config.CustomItemsConfig;
import net.survivalfun.core.commands.ItemDB;
import net.survivalfun.core.commands.LocatorBarCommand;
import net.survivalfun.core.commands.Lore;
import net.survivalfun.core.commands.Maintenance;
import net.survivalfun.core.commands.Money;
import net.survivalfun.core.commands.More;
import net.survivalfun.core.commands.Msg;
import net.survivalfun.core.commands.NV;
import net.survivalfun.core.commands.Nick;
import net.survivalfun.core.commands.Note;
import net.survivalfun.core.commands.Notes;
import net.survivalfun.core.commands.Pay;
import net.survivalfun.core.commands.PvpCommand;
import net.survivalfun.core.commands.Redeem;
import net.survivalfun.core.commands.Rename;
import net.survivalfun.core.commands.Restore;
import net.survivalfun.core.commands.Skull;
import net.survivalfun.core.commands.Speed;
import net.survivalfun.core.commands.Spawn;
import net.survivalfun.core.commands.Spy;
import net.survivalfun.core.commands.TP;
import net.survivalfun.core.commands.Tab;
import net.survivalfun.core.commands.Time;
import net.survivalfun.core.commands.ChannelCommand;
import net.survivalfun.core.commands.Unrestrain;
import net.survivalfun.core.commands.Warp;
import net.survivalfun.core.commands.SetWarp;
import net.survivalfun.core.commands.DelWarp;
import net.survivalfun.core.commands.WarpInfo;
import net.survivalfun.core.commands.WitherToggle;
import net.survivalfun.core.commands.Whois;
import net.survivalfun.core.commands.XpBottleCommand;
import net.survivalfun.core.commands.ChequeCommand;
import net.survivalfun.core.commands.VoucherCommand;
import net.survivalfun.core.commands.Seen;
import net.survivalfun.core.commands.SpawnerCoreCommand;
import net.survivalfun.core.managers.DB.Database.PlayerLastSeenData;
import net.survivalfun.core.managers.DB.Database.RestrainedPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.survivalfun.core.listeners.PlaceholderAPIRegistrationListener;
import net.survivalfun.core.commands.TrashCommand;
import net.survivalfun.core.commands.Unnote;
import net.survivalfun.core.commands.Vanish;
import net.survivalfun.core.commands.AutoRestartCommand;
import net.survivalfun.core.listeners.chat.SignColorListener;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.packetevents.ChatPacketTracker;
import net.survivalfun.core.packetevents.PacketEventsLoader;
import net.survivalfun.core.packetevents.TabListManager;
import net.survivalfun.core.commands.PartyCommand;
import net.survivalfun.core.listeners.PartyListener;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.tfly.TFlyManager;
import net.survivalfun.core.voucher.VouchersConfig;
import net.survivalfun.core.listeners.jobs.MailManager;
import net.survivalfun.core.listeners.jobs.CreeperExplosion;
import net.survivalfun.core.listeners.jobs.FireballExplosion;
import net.survivalfun.core.listeners.jobs.Death;
import net.survivalfun.core.listeners.jobs.SlimeJump;
import net.survivalfun.core.listeners.jobs.WitherSpawnBlocker;
import net.survivalfun.core.listeners.XpBottleRedeemListener;
import net.survivalfun.core.listeners.ChequeRedeemListener;
import net.survivalfun.core.listeners.VoucherRedeemListener;
import net.survivalfun.core.listeners.jobs.WolfBehaviorListener;
import net.survivalfun.core.listeners.jobs.LootTableListener;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.listeners.security.FlightRestoration;
import net.survivalfun.core.listeners.security.HandcuffsListener;
import net.survivalfun.core.listeners.security.ConnectionManager;
import net.survivalfun.core.listeners.security.AltProtectionListener;
import net.survivalfun.core.listeners.security.InventorySnapshotListener;
import net.survivalfun.core.listeners.security.MaintenanceManager;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import net.survivalfun.core.listeners.security.VanishListener;
import net.survivalfun.core.listeners.security.VanishPingListener;
import net.survivalfun.core.listeners.security.FreezeListener;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PermissionCache;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.ItemDBManager;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.SecurityAlertManager;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.placeholderapi.AlliumPlaceholder;
import net.survivalfun.core.managers.economy.EconomyManager;
import net.survivalfun.core.managers.economy.VaultEconomyProvider;
import net.survivalfun.core.managers.warp.WarpManager;
import net.survivalfun.core.managers.world.OreGenerationManager;
import net.survivalfun.core.util.PlayerVisibilityHelper;
import net.survivalfun.core.util.SchedulerAdapter;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.migration.MigrationManager;
import net.survivalfun.core.managers.permissions.DynamicPermissionManager;
import net.survivalfun.core.managers.chat.ChatMessageManager;
import net.survivalfun.core.managers.chat.AlliumChannelManager;
import net.survivalfun.core.managers.chat.ChatFilterManager;
import net.survivalfun.core.managers.chat.SpamBlockerManager;
import net.survivalfun.core.managers.chat.DiscordSrvMessageBridge;
import net.survivalfun.core.managers.commands.CommandBridgeManager;
import net.survivalfun.core.inventory.InventoryManager;
import net.survivalfun.core.inventory.OfflineInventoryManager;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import net.survivalfun.core.commands.chat.DeleteMsg;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.HashMap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.managers.core.VanishManager;

/**
 * Main plugin class for Allium, responsible for initializing managers, commands, listeners, and services.
 */
public class PluginStart extends JavaPlugin {
    private NicknameManager nicknameManager;
    private static PluginStart instance;
    private Lang langManager;
    private Chat vaultChat;
    private Permission vaultPerms;
    private Config configManager;
    private FormatChatListener formatChatListener;
    private Database database;
    private CreativeManager creativeManager;
    private SpectatorTeleport spectatorTeleport;
    private Explode explodeCommand;
    private Msg msgCommand;
    private Spy spyCommand;
    private TP tpCommand;
    private CommandManager commandManager;
    private net.milkbowl.vault.economy.Economy vaultEcon;
    private FlightRestoration flyOnRejoinListener;
    private WitherSpawnBlocker witherSpawnBlocker;
    private AlliumPlaceholder placeholder;
    private PermissionCache permissionCache;
    private final Map<UUID, Long> playerLoginTimes = new ConcurrentHashMap<>();
    private MigrationManager migrationManager;
    private EconomyManager economyManager;
    private Time timeCycle;
    private DynamicPermissionManager dynamicPermissionManager;
    private WarpManager warpManager;
    private ChatMessageManager chatMessageManager;
    private SpamBlockerManager spamBlockerManager;
    private ChatFilterManager chatFilterManager;
    private AlliumChannelManager channelManager;
    private DiscordSrvMessageBridge discordSrvMessageBridge;
    private ChatPacketTracker chatPacketTracker = new net.survivalfun.core.packetevents.ChatPacketTrackerNoOp();
    private TabListManager tabListManager;
    private CommandBridgeManager commandBridgeManager;
    private InventoryManager inventoryManager;
    private OfflineInventoryManager offlineInventoryManager;
    private boolean commandMapEnforceUnsupportedWarned = false;
    private PartyManager partyManager;
    private HandcuffsListener handcuffsListener;
    private VanishManager vanishManager;
    private Freeze freezeCommand;
    private AutoRestartCommand autoRestartCommand;
    private Tab tabCompleter;
    private OreGenerationManager oreGenerationManager;
    private SpawnerCoreManager spawnerCoreManager;
    private TFlyManager tflyManager;
    private VouchersConfig vouchersConfig;
    private SecurityAlertManager securityAlertManager;

    /**
     * Gets the singleton instance of the plugin.
     *
     * @return The PluginStart instance.
     */
    public static PluginStart getInstance() {
        return instance;
    }

    /**
     * Gets the tab completer instance for reloading aliases.
     *
     * @return The Tab instance.
     */
    public Tab getTabCompleter() {
        return tabCompleter;
    }

    /**
     * Gets the spawner core manager (spawner craft).
     *
     * @return The SpawnerCoreManager instance, or null if not initialized.
     */
    public SpawnerCoreManager getSpawnerCoreManager() {
        return spawnerCoreManager;
    }

    /**
     * Gets the command manager.
     *
     * @return The CommandManager instance.
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * Gets the language manager.
     *
     * @return The Lang instance.
     */
    public Lang getLangManager() {
        return langManager;
    }

    public SecurityAlertManager getSecurityAlertManager() {
        return securityAlertManager;
    }

    // get handcuff listener
    public HandcuffsListener getHandcuffsListener() {
        return handcuffsListener;
    }

    /**
     * Gets the dynamic permission manager.
     *
     * @return The DynamicPermissionManager instance.
     */
    public DynamicPermissionManager getDynamicPermissionManager() {
        return dynamicPermissionManager;
    }

    /**
     * Gets the Vault Chat service.
     *
     * @return The Chat instance, or null if not available.
     */
    public Chat getVaultChat() {
        return vaultChat;
    }

    /**
     * Gets the Vault Permission service.
     *
     * @return The Permission instance, or null if not available.
     */
    public Permission getVaultPermission() {
        return vaultPerms;
    }

    /**
     * Gets the player login times map.
     *
     * @return A map of player UUIDs to their login times.
     */
    public Map<UUID, Long> getPlayerLoginTimes() {
        return playerLoginTimes;
    }

    /**
     * Gets the database manager.
     *
     * @return The Database instance.
     */
    public Database getDatabase() {
        return database;
    }

    /**
     * Gets the explode command instance.
     *
     * @return The Explode command instance.
     */
    public Explode getExplodeCommand() {
        return explodeCommand;
    }

    /**
     * Gets the configuration manager.
     *
     * @return The Config instance.
     */
    public Config getConfigManager() {
        return configManager;
    }

    /**
     * Gets the economy manager.
     *
     * @return The Economy instance.
     */
    public net.milkbowl.vault.economy.Economy getEconomy() {
        return vaultEcon;
    }

    /**
     * Gets the economy manager.
     *
     * @return The EconomyManager instance.
     */
    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public VouchersConfig getVouchersConfig() {
        return vouchersConfig;
    }

    public TFlyManager getTFlyManager() {
        return tflyManager;
    }

    public CommandBridgeManager getCommandBridgeManager() {
        return commandBridgeManager;
    }
    
    /**
     * Gets the inventory manager instance.
     * @return The InventoryManager instance
     */
    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    public OfflineInventoryManager getOfflineInventoryManager() {
        return offlineInventoryManager;
    }

    /**
     * Gets the vanish manager.
     *
     * @return The VanishManager instance.
     */
    public VanishManager getVanishManager() {
        return vanishManager;
    }

    /**
     * Gets the PacketEvents chat tracker, if available.
     * Returns a no-op implementation when PacketEvents is not present.
     *
     * @return the ChatPacketTracker instance (never null)
     */
    public ChatPacketTracker getChatPacketTracker() {
        return chatPacketTracker;
    }

    public DiscordSrvMessageBridge getDiscordSrvMessageBridge() {
        return discordSrvMessageBridge;
    }

    public AlliumChannelManager getChannelManager() {
        return channelManager;
    }

    public ChatMessageManager getChatMessageManager() {
        return chatMessageManager;
    }

    public SpamBlockerManager getSpamBlockerManager() {
        return spamBlockerManager;
    }

    public ChatFilterManager getChatFilterManager() {
        return chatFilterManager;
    }

    /**
     * Gets the party manager.
     *
     * @return The PartyManager instance.
     */
    public PartyManager getPartyManager() {
        return partyManager;
    }

    /**
     * Gets the fly-on-rejoin listener.
     *
     * @return The FlyOnRejoinListener instance.
     */
    public FlightRestoration getFlyOnRejoinListener() {
        return flyOnRejoinListener;
    }

    /**
     * Gets the permission cache.
     *
     * @return The PermissionCache instance.
     */
    public PermissionCache getPermissionCache() {
        return permissionCache;
    }

    /**
     * Gets the spy command instance.
     *
     * @return The Spy command instance.
     */
    public Spy getSpyCommand() {
        return spyCommand;
    }

    /**
     * Gets the spectator teleport manager.
     *
     * @return The SpectatorTeleport instance.
     */
    public SpectatorTeleport getSpectatorTeleport() {
        return spectatorTeleport;
    }

    /**
     * Gets the Msg command instance.
     *
     * @return The Msg command instance.
     */
    public Msg getMsgCommand() {
        return msgCommand;
    }

    /**
     * Gets the teleport command instance.
     *
     * @return The TP instance.
     */
    public TP getTpInstance() {
        return tpCommand;
    }

    /**
     * Checks if debug mode is enabled in the configuration.
     *
     * @return True if debug mode is enabled, false otherwise.
     */
    public boolean isDebugMode() {
        return getConfig().getBoolean("debug-mode", false);
    }

    /**
     * Called when the plugin is enabled. Initializes managers, commands, listeners, and services.
     */
    public NicknameManager getNicknameManager() {
        return nicknameManager;
    }
    
    /**
     * Gets the AutoRestartCommand instance.
     *
     * @return The AutoRestartCommand instance, or null if not initialized
     */
    public AutoRestartCommand getAutoRestartCommand() {
        return autoRestartCommand;
    }
    
    @Override
    public void onEnable() {
        // Initialize NicknameManager
        this.nicknameManager = new NicknameManager(this);
        
        // Initialize DialogManager and load dialogs
        net.survivalfun.core.managers.core.DialogManager dialogManager = new net.survivalfun.core.managers.core.DialogManager(this);
        dialogManager.loadDialogs();
        dialogManager.generateDataPack();
        
        instance = this;
        Text.setPlugin(this);
        SchedulerAdapter.init(this);
        PlayerVisibilityHelper.initialize(this);
        migrationManager = new MigrationManager(getLogger(), getDataFolder());

        // Perform migration if needed
        if (migrationManager.isMigrationNeeded()) {
            Text.sendDebugLog(INFO, "Allium folder detected, performing migration...");
            migrationManager.performMigration();
        }

        // Log loaded plugins for debugging
        Text.sendDebugLog(INFO, "Loaded plugins: " + Arrays.toString(getServer().getPluginManager().getPlugins()));

        // DialogAPI bootstrap disabled; dialog features removed
        Text.sendDebugLog(INFO, "Dialog and datapack features are disabled.");

        // Initialize core managers
        initializeManagers();
        initializePlaceholderAPI();
        
        // Cleanup old restrained player records (older than 1 day) and restore active ones
        try {
            if (database.isReady()) {
                int cleanedCount = database.cleanupOldRestrainedPlayers();
                if (cleanedCount > 0) {
                    Text.sendDebugLog(INFO, "Cleaned up " + cleanedCount + " old restrained player record(s) on startup");
                }
                
                // Load and restore any still-restrained players
                loadAndRestoreRestrainedPlayers();
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to process restrained player records on startup: " + e.getMessage());
        }
        
        // Initialize PacketEvents for comprehensive chat message tracking
        initializePacketEvents();
        if (isDebugMode()) {
            PacketEventsLoader.logPacketEventsStatus(getLogger());
        }
        
        // Register commands and non-Vault-dependent listeners
        registerCommands();
        registerNonVaultListeners();

        // Register the built-in permission migration listener
        try {
            getServer().getPluginManager().registerEvents(new PlayerPermissionMigrationListener(), this);
            if (isDebugMode()) {
                getLogger().info("Registered PlayerPermissionMigrationListener");
            }
        } catch (Exception e) {
            getLogger().warning("Failed to register PlayerPermissionMigrationListener: " + e.toString());
            if (isDebugMode()) {
                e.printStackTrace();
            }
        }

        // Delay Vault initialization and Vault-dependent listeners with retry mechanism
        {
            final int maxAttempts = 10;
            final long retryDelay = 40L; // 2 seconds per retry
            final int[] attempts = {0};
            final SchedulerAdapter.TaskHandle[] handle = new SchedulerAdapter.TaskHandle[1];
            handle[0] = SchedulerAdapter.runTimer(() -> {
                attempts[0]++;
                Text.sendDebugLog(INFO, "Attempting Vault initialization (attempt " + attempts[0] + " of " + maxAttempts + ")");
                if (initializeVault()) {
                    Text.sendDebugLog(INFO, "Vault services initialized successfully after " + attempts[0] + " attempt(s).");
                    registerVaultDependentListeners();
                    performPermissionMigration();
                    handle[0].cancel();
                } else if (attempts[0] < maxAttempts) {
                    Text.sendDebugLog(WARN, "Vault services not yet available. Retrying in " + retryDelay + " ticks (attempt " + (attempts[0] + 1) + " of " + maxAttempts + ").");
                    Text.sendDebugLog(INFO, "Number of permission providers: " + getServer().getServicesManager().getRegistrations(Permission.class).size());
                    Text.sendDebugLog(INFO, "Number of chat providers: " + getServer().getServicesManager().getRegistrations(Chat.class).size());
                    Text.sendDebugLog(INFO, "Number of economy providers: " + getServer().getServicesManager().getRegistrations(net.milkbowl.vault.economy.Economy.class).size());
                } else {
                    Text.sendDebugLog(WARN, "Failed to initialize Vault services after " + maxAttempts + " attempts. Using fallback formatting for chat.", true);
                    registerVaultDependentListeners();
                    handle[0].cancel();
                }
            }, 100L, 40L);
        }
        
        // After plugins have registered their commands, force our preferred command ownerships
        try {
            forcePreferredCommandsWithRetry();
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "Failed to schedule preferred command enforcement: " + t.getMessage(), true);
        }

        // Update checker (delayed to avoid blocking startup)
        SchedulerAdapter.runLater(() -> {
            boolean enabled = getConfig().getBoolean("update-checker.enabled", true);
            String notifyMode = getConfig().getString("update-checker.notify-mode", "both");
            net.survivalfun.core.util.UpdateChecker.checkForUpdates(
                    this, 126462, enabled, notifyMode, getDescription().getVersion());
        }, 50L);

        // /dialog watchdog removed
    }

    @Override
    public void onLoad() {
        // Using external PacketEvents plugin; do not build or load API here
        try {
            if (getServer().getPluginManager().getPlugin("packetevents") != null) {
                Text.sendDebugLog(INFO, "Detected external PacketEvents plugin; API will be initialized by it.");
            } else {
                Text.sendDebugLog(WARN, "PacketEvents plugin not found. Packet-level chat tracking will be disabled.");
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "PacketEvents detection error: " + t.getMessage(), true);
        }
    }

    private void performPermissionMigration() {
        if (vaultPerms == null) {
            Text.sendDebugLog(ERROR, "Cannot perform permission migration: Vault permission provider not found!");
            return;
        }

        // Check if group migration has already been completed
        if (getConfig().getBoolean("group_migration_completed", false)) {
            Text.sendDebugLog(INFO, "Group permission migration already completed, skipping.");
            return;
        }

        boolean migrated = false;
        String[] permissionsToMigrate = {
            "explode", "gc", "give", "itemdb", "heal", "feed", "admin", "rename", "lore",
            "god", "more", "fly", "gamemode", "nv", "skull", "home", "homes", "sethome",
            "delhome", "balance", "pay", "baltop", "tp", "tpa", "tpcancel", "tpaccept",
            "tpdeny", "tppet", "tpmob", "tppos", "tphere", "tpahere", "tptoggle", "top",
            "bottom", "whois", "invsee", "enchant", "staff", "note", "notes", "unnote",
            "time", "time.set", "time.add",
            "escalate", "spy", "spy.others", "gamemode.others", "gamemode.spectator",
            "gamemode.survival", "gamemode.survival.nv", "gamemode.creative",
            "gamemode.creative.inventory", "gamemode.creative.container", "gamemode.creative.pickup",
            "gamemode.creative.drop", "gamemode.creative.break", "gamemode.creative.place",
            "gamemode.creative.interact", "gamemode.creative.use", "gamemode.creative.spawn",
            "gamemode.creative.blacklist", "gamemode.creative.nv", "gamemode.adventure",
            "gamemode.adventure.nv", "gamemode.spectator", "nv.others", "nv.adventure",
            "nv.creative", "nv.spectator", "nv.survival", "tp.others", "tp.override",
            "tpa.nodelay", "tpa.nocooldown", "voucher.give", "lockdown.bypass", "tpall",
            "tptoggle.others", "tpauto", "spawn", "setspawn", "help", "sethome.unlimited",
            "home.others", "home.nocooldown", "hide.group", "hide.bypass", "hide.default",
            "fly.others", "explode.others", "explode.exempt", "heal.others", "heal.nocooldown",
            "feed.others", "feed.nocooldown", "*"
        };

        // Migrate group permissions using Vault
        for (String group : vaultPerms.getGroups()) {
            for (String perm : permissionsToMigrate) {
                String oldPerm = "core." + perm;
                String newPerm = "allium." + perm;
                if (vaultPerms.groupHas((String) null, group, oldPerm)) {
                    vaultPerms.groupRemove((String) null, group, oldPerm);
                    vaultPerms.groupAdd((String) null, group, newPerm);
                    Text.sendDebugLog(INFO, "Migrated group " + group + ": " + oldPerm + " -> " + newPerm);
                    migrated = true;
                }
            }
        }

        // Mark group migration as completed only if changes were made
        if (migrated) {
            getConfig().set("group_migration_completed", true);
            saveConfig();
            Text.sendDebugLog(INFO, "Group permission migration from core.* to allium.* completed!");
        } else {
            Text.sendDebugLog(INFO, "No group permissions needed migration.");
        }
    }

    /**
     * Called when the plugin is disabled. Saves data and cleans up resources.
     */
    @Override
    public void onDisable() {
        // Save pending messages
        if (msgCommand != null) {
            msgCommand.savePendingMessages();
        }

        // Cleanup creative manager
        if (creativeManager != null) {
            creativeManager.cleanup();
            Text.sendDebugLog(INFO, "Creative inventory manager cleaned up.", true);
        }

        if (offlineInventoryManager != null) {
            offlineInventoryManager.saveOnlinePlayerStates();
        }

        // Unregister PlaceholderAPI expansion
        if (placeholder != null && placeholder.isRegistered()) {
            placeholder.unregister();
            Text.sendDebugLog(INFO, "PlaceholderAPI expansion unregistered.", true);
        }

        // Cleanup dynamic permissions
        if (dynamicPermissionManager != null) {
            dynamicPermissionManager.cleanup();
            Text.sendDebugLog(INFO, "Dynamic permissions cleaned up.", true);
        }

        // Cleanup handcuffs listener (save restrained players first)
        if (handcuffsListener != null) {
            // Save restrained players to database for restoration on restart
            saveRestrainedPlayersToDatabase();
            handcuffsListener.cleanup();
            Text.sendDebugLog(INFO, "HandcuffsListener cleaned up.", true);
        }

        // Stop TFly tick task
        if (tflyManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tflyManager.savePlayerState(player);
            }
            tflyManager.stop();
            Text.sendDebugLog(INFO, "TFlyManager stopped.", true);
        }

        // Shutdown packet-based trackers (unregister from PacketEvents when applicable)
        try {
            if (chatPacketTracker != null) {
                chatPacketTracker.shutdown();
            }
            if (tabListManager != null) {
                tabListManager.shutdown();
            }
            if (discordSrvMessageBridge != null) {
                discordSrvMessageBridge.shutdown();
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "Failed to shutdown packet listeners: " + t.getMessage());
        }

        // Unregister all Bukkit listeners
        HandlerList.unregisterAll(this);
    }

    /**
     * Check if PacketEvents plugin is available.
     */
    private boolean isPacketEventsAvailable() {
        return PacketEventsLoader.isPacketEventsAvailable();
    }

    /**
     * Loads and restores restrained players from the database on startup
     */
    private void loadAndRestoreRestrainedPlayers() {
        if (handcuffsListener == null || !database.isReady()) {
            return;
        }

        try {
            List<RestrainedPlayerData> restrainedPlayers = database.loadRestrainedPlayers();
            if (restrainedPlayers.isEmpty()) {
                Text.sendDebugLog(INFO, "No restrained players to restore from database");
                return;
            }

            Text.sendDebugLog(INFO, "Loading " + restrainedPlayers.size() + " restrained players from database");

            int restoredCount = 0;
            for (RestrainedPlayerData data : restrainedPlayers) {
                UUID restrainedUUID = data.getRestrainedUUID();
                UUID handcufferUUID = data.getHandcufferUUID();
                Location handcufferLocation = data.getHandcufferLocation();

                // Check if both players are online
                Player restrainedPlayer = Bukkit.getPlayer(restrainedUUID);
                Player handcufferPlayer = Bukkit.getPlayer(handcufferUUID);

                if (restrainedPlayer != null && restrainedPlayer.isOnline()) {
                    if (handcufferPlayer != null && handcufferPlayer.isOnline()) {
                        // Both players are online, restore the restraint
                        try {
                            // Use reflection to call the applyHandcuffs method since it's private
                            java.lang.reflect.Method applyHandcuffs = handcuffsListener.getClass()
                                .getDeclaredMethod("applyHandcuffs", Player.class, Player.class, boolean.class);
                            applyHandcuffs.setAccessible(true);
                            applyHandcuffs.invoke(handcuffsListener, restrainedPlayer, handcufferPlayer, false);

                            Text.sendDebugLog(INFO, "Restored restraint: " + restrainedPlayer.getName() + " <- " + handcufferPlayer.getName());
                            restoredCount++;
                        } catch (Exception e) {
                            Text.sendDebugLog(ERROR, "Failed to restore restraint for " + restrainedPlayer.getName() + ": " + e.getMessage());
                        }
                    } else {
                        // Handcuffer is offline, check offline time and execute commands-on-quit if needed
                        checkOfflineTimeAndExecuteCommands(restrainedUUID, handcufferUUID, handcufferLocation);
                    }
                } else {
                    // Restrained player is offline, check offline time and execute commands-on-quit if needed
                    checkOfflineTimeAndExecuteCommands(restrainedUUID, handcufferUUID, handcufferLocation);
                }

                // Remove from database regardless
                try {
                    database.removeRestrainedPlayer(restrainedUUID);
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Failed to remove restored restrained player from database: " + e.getMessage());
                }
            }

            Text.sendDebugLog(INFO, "Restored " + restoredCount + " restrained players from database");

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to load restrained players from database: " + e.getMessage(), e);
        }
    }

    /**
     * Checks offline time for a restrained player and executes commands-on-quit if they've been offline too long
     */
    private void checkOfflineTimeAndExecuteCommands(UUID restrainedUUID, UUID handcufferUUID, Location handcufferLocation) {
        try {
            // Get the last seen date for the restrained player
            PlayerLastSeenData lastSeenData = database.getPlayerLastSeen(restrainedUUID);
            if (lastSeenData != null && lastSeenData.lastSeenDate() != null) {
                long offlineTimeMs = System.currentTimeMillis() - lastSeenData.lastSeenDate().getTime();
                long offlineTimeHours = offlineTimeMs / (1000 * 60 * 60);

                // If offline for more than 24 hours (1 day), execute commands-on-quit
                if (offlineTimeHours > 24) {
                    Text.sendDebugLog(INFO, "Player " + restrainedUUID + " has been offline for " + offlineTimeHours + " hours, executing commands-on-quit");

                    // Execute commands-on-quit using the database method
                    database.executeCommandsOnQuitForOfflinePlayer(restrainedUUID, handcufferUUID);
                } else {
                    Text.sendDebugLog(INFO, "Player " + restrainedUUID + " has been offline for " + offlineTimeHours + " hours, keeping restraint active");
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to check offline time for restrained player " + restrainedUUID + ": " + e.getMessage());
        }
    }

    /**
     * Saves all currently restrained players to the database for restoration after restart
     */
    private void saveRestrainedPlayersToDatabase() {
        if (handcuffsListener == null) {
            return;
        }

        try {
            // Get all restrained players from the listener
            Map<UUID, UUID> restrainedPlayers = getRestrainedPlayersMap();
            if (restrainedPlayers.isEmpty()) {
                Text.sendDebugLog(INFO, "No restrained players to save to database");
                return;
            }

            int savedCount = 0;
            for (Map.Entry<UUID, UUID> entry : restrainedPlayers.entrySet()) {
                UUID restrainedUUID = entry.getKey();
                UUID handcufferUUID = entry.getValue();

                // Get handcuffer's current location
                Player handcuffer = Bukkit.getPlayer(handcufferUUID);
                if (handcuffer != null && handcuffer.isOnline()) {
                    database.saveRestrainedPlayer(restrainedUUID, handcufferUUID, handcuffer.getLocation());
                    savedCount++;
                }
            }

            Text.sendDebugLog(INFO, "Saved " + savedCount + " restrained players to database for restart restoration");
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to save restrained players to database: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the restrained players map from the handcuffs listener
     * Uses reflection to access the private field since it's not exposed publicly
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, UUID> getRestrainedPlayersMap() {
        try {
            java.lang.reflect.Field restrainedPlayersField = handcuffsListener.getClass().getDeclaredField("restrainedPlayers");
            restrainedPlayersField.setAccessible(true);
            return (Map<UUID, UUID>) restrainedPlayersField.get(handcuffsListener);
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to access restrained players map via reflection: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Reloads the chat formatter with the current configuration.
     */
    public void reloadChatFormatter() {
        if (!getConfig().getBoolean("enable-chat-formatting", true)) {
            Text.sendDebugLog(INFO, "Chat formatting is disabled in config.yml.", true);
            return;
        }

        // Unregister existing chat formatter
        if (formatChatListener != null) {
            HandlerList.unregisterAll(formatChatListener);
        }

        // Create and register new formatter
        formatChatListener = new FormatChatListener(this, vaultChat, configManager, chatMessageManager);
        getServer().getPluginManager().registerEvents(formatChatListener, this);
        Text.sendDebugLog(INFO, "Chat formatter has been reloaded with new configuration.", true);
    }

    /**
     * Initializes chat packet tracking. Uses PacketEvents when available, otherwise no-op.
     */
    private void initializePacketEvents() {
        try {
            this.chatPacketTracker = PacketEventsLoader.createChatPacketTracker(this, chatMessageManager);
            if (chatPacketTracker.supportsResend()) {
                Text.sendDebugLog(INFO, "PacketEvents chat tracking enabled (clear+resend on /delmsg)");
            } else {
                Text.sendDebugLog(WARN, "PacketEvents not available. Chat deletion will only send header (no clear/resend).");
                SchedulerAdapter.runLater(this::retryChatPacketTrackerInit, 40L);
            }
        } catch (Throwable e) {
            Text.sendDebugLog(WARN, "Failed to initialize chat packet tracker: " + e.getMessage());
            this.chatPacketTracker = new net.survivalfun.core.packetevents.ChatPacketTrackerNoOp();
            SchedulerAdapter.runLater(this::retryChatPacketTrackerInit, 40L);
        }
    }

    /**
     * Retry ChatPacketTracker init if currently using NoOp. Called from /delmsg when PacketEvents
     * may have loaded late. Also used at startup.
     */
    public void retryChatPacketTrackerInitIfNeeded() {
        retryChatPacketTrackerInit();
    }

    /**
     * Retry ChatPacketTracker init (e.g. when PacketEvents loads after Allium at startup).
     */
    private void retryChatPacketTrackerInit() {
        if (chatPacketTracker != null && chatPacketTracker.supportsResend()) return;
        try {
            ChatPacketTracker next = PacketEventsLoader.createChatPacketTracker(this, chatMessageManager);
            if (next.supportsResend()) {
                if (chatPacketTracker != null) chatPacketTracker.shutdown();
                chatPacketTracker = next;
                Text.sendDebugLog(INFO, "PacketEvents chat tracking enabled (delayed init) - clear+resend on /delmsg");
            }
        } catch (Throwable e) {
            Text.sendDebugLog(WARN, "ChatPacketTracker retry failed: " + e.getMessage());
        }
    }
    
    private void initializePlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            Text.sendDebugLog(WARN, "PlaceholderAPI not found. Expansion will not be available.");
            return;
        }
        getServer().getPluginManager().registerEvents(new PlaceholderAPIRegistrationListener(this), this);
        SchedulerAdapter.runLater(() -> {
            if (placeholder == null || !placeholder.isRegistered()) {
                if (registerPlaceholderExpansion()) {
                    getLogger().info("PlaceholderAPI expansion 'allium' registered.");
                }
            }
        }, 20L);
    }

    public AlliumPlaceholder getPlaceholderExpansion() {
        return placeholder;
    }

    public void setPlaceholderExpansion(AlliumPlaceholder expansion) {
        this.placeholder = expansion;
    }

    private boolean registerPlaceholderExpansion() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return false;
        }
        if (placeholder != null && placeholder.isRegistered()) {
            return true;
        }
        try {
            placeholder = new AlliumPlaceholder(this);
            boolean ok = placeholder.register();
            if (!ok && isDebugMode()) {
                getLogger().info("AlliumPlaceholder.register() returned false - expansion may already exist or validation failed.");
            }
            return ok;
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI expansion registration threw: " + t.getMessage());
            if (isDebugMode()) {
                t.printStackTrace();
            }
            return false;
        }
    }

    private boolean initializeVault() {
        // Support both Vault and VaultUnlocked (same API, different plugin names)
        Plugin vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
        if (vaultPlugin == null) {
            vaultPlugin = getServer().getPluginManager().getPlugin("VaultUnlocked");
        }
        if (vaultPlugin != null) {
            Text.sendDebugLog(INFO, "Vault plugin found. Attempting to initialize Vault services.");

            // Load the Vault service classes using Vault's class loader
            ClassLoader vaultClassLoader = vaultPlugin.getClass().getClassLoader();
            Class<?> permissionClass = null;
            Class<?> chatClass = null;
            try {
                permissionClass = Class.forName("net.milkbowl.vault.permission.Permission", true, vaultClassLoader);
                chatClass = Class.forName("net.milkbowl.vault.chat.Chat", true, vaultClassLoader);
                Class.forName("net.milkbowl.vault.economy.Economy", true, vaultClassLoader);
            } catch (ClassNotFoundException e) {
                Text.sendDebugLog(ERROR, "Failed to load Vault service class: " + e.getMessage());
                return false;
            }

            // Initialize services
            RegisteredServiceProvider<?> permissionProvider = getServer().getServicesManager().getRegistration(permissionClass);
            RegisteredServiceProvider<?> chatProvider = getServer().getServicesManager().getRegistration(chatClass);
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);

            boolean coreServicesReady = false; // Track readiness of permission/chat/economy

            // Set service instances (use delegate to avoid "not an interface" - Permission is abstract class)
            if (permissionProvider != null) {
                try {
                    Object permProvider = permissionProvider.getProvider();
                    vaultPerms = new net.survivalfun.core.managers.vault.VaultPermissionDelegate(permProvider);
                    Text.sendDebugLog(INFO, "Vault Permission service initialized: " + vaultPerms.getName());
                    coreServicesReady = true;
                } catch (Throwable t) {
                    Text.sendDebugLog(WARN, "Failed to initialize Vault Permission (LuckPerms classloader?): " + t.getMessage());
                }
            } else {
                Text.sendDebugLog(WARN, "Vault Permission service not found. Permission checks will not work correctly.");
            }

            if (chatProvider != null) {
                try {
                    Object chatProviderObj = chatProvider.getProvider();
                    vaultChat = createVaultChatProxy(chatProviderObj, chatClass);
                    Text.sendDebugLog(INFO, "Vault Chat service initialized: " + vaultChat.getName());
                    coreServicesReady = true;
                } catch (Throwable t) {
                    Text.sendDebugLog(WARN, "Failed to initialize Vault Chat (LuckPerms classloader?): " + t.getMessage());
                }
            } else {
                Text.sendDebugLog(WARN, "Vault Chat service not found. Chat formatting and group-specific features may not work.");
            }

            // Ensure our Economy provider is present and mark core readiness if economy is available
            if (economyProvider != null) {
                vaultEcon = economyProvider.getProvider();
                Text.sendDebugLog(INFO, "Vault Economy service initialized: " + vaultEcon.getName());
                coreServicesReady = true; // Economy alone is sufficient for core functionality
            } else {
                try {
                    // If not present, (re)register our provider without affecting core readiness
                    getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, new VaultEconomyProvider(this, economyManager), this, ServicePriority.Highest);
                    RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> reg = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                    if (reg != null) {
                        vaultEcon = reg.getProvider();
                        Text.sendDebugLog(INFO, "Registered Allium's Vault Economy provider");
                        coreServicesReady = true; // Our economy provider is sufficient
                    }
                } catch (Throwable t) {
                    Text.sendDebugLog(WARN, "Failed to register Allium's Vault Economy provider: " + t.getMessage());
                }
            }

            // Return true if any service is available (permission, chat, or economy)
            return coreServicesReady;
        } else {
            Text.sendDebugLog(WARN, "Vault plugin not found. Some features may not work correctly.");
            return false;
        }
    }

    /**
     * Creates a Chat proxy that delegates to the provider via reflection.
     * Uses the Chat class from Vault's classloader to avoid "not an interface" with bundled API.
     */
    private Chat createVaultChatProxy(Object provider, Class<?> chatInterface) {
        if (!chatInterface.isInterface()) {
            Text.sendDebugLog(WARN, "Vault Chat is abstract class, skipping proxy");
            return null;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            Method m = findMethod(provider.getClass(), method.getName(), method.getParameterTypes());
            return m.invoke(provider, args);
        };
        return (Chat) Proxy.newProxyInstance(
            chatInterface.getClassLoader(),
            new Class<?>[] { chatInterface },
            handler
        );
    }

    /** Find method, trying exact match first then by name and param count for overloads. */
    private Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) throws NoSuchMethodException {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                    Class<?>[] actual = m.getParameterTypes();
                    boolean compatible = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!actual[i].isAssignableFrom(paramTypes[i])) {
                            compatible = false;
                            break;
                        }
                    }
                    if (compatible) return m;
                }
            }
            throw e;
        }
    }

    /**
     * Initializes all core managers and systems for the plugin.
     */
    private void initializeManagers() {

        // Initialize database manager
        // Database constructor initializes itself
        database = new Database(this);

        // Initialize vanish manager
        vanishManager = new VanishManager(this);
        Text.sendDebugLog(INFO, "VanishManager initialized.");
    
        // Initialize inventory manager
        inventoryManager = new InventoryManager(this);
        offlineInventoryManager = new OfflineInventoryManager(this, inventoryManager);
        permissionCache = new PermissionCache(this);

        // Initialize economy manager
        economyManager = new EconomyManager(this);
        vaultEcon = new VaultEconomyProvider(this, economyManager);
        // Register Vault economy early (Essentials-style) so /vault-info and other plugins see us.
        // No getPlugin("Vault") check: Economy class may be provided by Vault or VaultUnlocked at runtime.
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
            getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, vaultEcon, this, ServicePriority.Normal);
            Text.sendDebugLog(INFO, "Registered Allium economy with Vault API.");
        } catch (ClassNotFoundException ignored) {
            // Vault/VaultUnlocked not on classpath; delayed initializeVault() may still run when plugin loads
        }

        // Command bridge (commands.yml)
        this.commandBridgeManager = new CommandBridgeManager(this);
        this.commandBridgeManager.load();

        // Language manager
        try {
            langManager = new Lang(getDataFolder(), this, getConfig());
            Text.sendDebugLog(INFO, "LangManager initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LangManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize config manager
        configManager = new Config(this);

        // Initialize party manager
        partyManager = new PartyManager(this);
        Text.sendDebugLog(INFO, "PartyManager initialized.");

        // Initialize warp manager
        warpManager = new WarpManager(this);
        Text.sendDebugLog(INFO, "WarpManager initialized.");

        // Other core managers
        try {
            Alias.initialize(this);
            Text.sendDebugLog(INFO, "Alias system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize Alias system: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            LegacyID.initialize(this);
            Text.sendDebugLog(INFO, "LegacyID system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LegacyID system: " + e.getMessage());
            e.printStackTrace();
        }

        Item.initialize(this);
        new Skull(this);
        new WorldDefaults(this);
        timeCycle = new Time(this);

        // Initialize ChatMessageManager for chat deletion features
        chatMessageManager = new ChatMessageManager();
        Text.sendDebugLog(INFO, "ChatMessageManager initialized.");

        spamBlockerManager = new SpamBlockerManager(this);
        getServer().getPluginManager().registerEvents(spamBlockerManager, this);
        Text.sendDebugLog(INFO, "SpamBlockerManager initialized.");

        chatFilterManager = new ChatFilterManager(this);
        getServer().getPluginManager().registerEvents(chatFilterManager, this);
        Text.sendDebugLog(INFO, "ChatFilterManager initialized.");

        channelManager = new AlliumChannelManager(this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (channelManager != null) {
                channelManager.retryDiscordHook();
            }
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (channelManager != null) {
                channelManager.retryDiscordHook();
            }
        }, 100L);

        discordSrvMessageBridge = new DiscordSrvMessageBridge(this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (discordSrvMessageBridge != null) {
                discordSrvMessageBridge.retryHook();
            }
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (discordSrvMessageBridge != null) {
                discordSrvMessageBridge.retryHook();
            }
        }, 100L);

        // Initialize dynamic permission manager
        dynamicPermissionManager = new DynamicPermissionManager(this);
        dynamicPermissionManager.registerDynamicPermissions();
        Text.sendDebugLog(INFO, "Dynamic permission manager initialized.");

        // Initialize ItemDB manager and perform one-time update (after database is fully ready)
        if (database.isReady()) {
            ItemDBManager itemDBManager = new ItemDBManager(this);
            itemDBManager.checkAndUpdateItemDB();
        } else {
            Text.sendDebugLog(WARN, "Database not ready for ItemDB initialization, skipping...");
        }

        // Initialize TFly manager (temporary fly)
        tflyManager = new TFlyManager(this);
        tflyManager.start();
        Text.sendDebugLog(INFO, "TFlyManager initialized.");

        // Initialize vouchers config
        vouchersConfig = new VouchersConfig(this);
        Text.sendDebugLog(INFO, "VouchersConfig initialized.");

        // Initialize HandcuffsListener
        try {
            handcuffsListener = new HandcuffsListener(this);
            Text.sendDebugLog(INFO, "HandcuffsListener initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize HandcuffsListener: " + e.getMessage());
            e.printStackTrace();
        }

        // Initialize TabListManager for party system
        // Entity hide/show (radius) uses Bukkit API and works without PacketEvents.
        // Only tab list re-add requires PacketEvents; don't disable locator bar when PE unavailable.
        try {
            boolean locatorBarEnabled = getServer().getWorlds().stream()
                    .findFirst()
                    .map(world -> world.getGameRuleValue(org.bukkit.GameRule.LOCATOR_BAR))
                    .orElse(Boolean.TRUE);

            if (!locatorBarEnabled) {
                Text.sendDebugLog(INFO, "Locator bar gamerule disabled; TabListManager using no-op");
                this.tabListManager = new net.survivalfun.core.packetevents.TabListManagerNoOp();
                partyManager.setTabListManager(this.tabListManager);
                partyManager.setPartyLocatorBarEnabled(false);
            } else {
                this.tabListManager = PacketEventsLoader.createTabListManager(this, partyManager, vanishManager);
                partyManager.setTabListManager(this.tabListManager);

                if (tabListManager.supportsTabListUpdates()) {
                    Text.sendDebugLog(INFO, "Tab list management enabled - party-locator-bar active");
                } else {
                    Text.sendDebugLog(WARN, "PacketEvents not ready at startup; entity hide/show active. Will retry TabListManager in 2s.");
                    // Retry in case PacketEvents loads after us
                    SchedulerAdapter.runLater(() -> retryTabListManagerInit(), 40L);
                }
            }
        } catch (Throwable e) {
            Text.sendDebugLog(WARN, "Failed to initialize TabListManager: " + e.getMessage());
            this.tabListManager = new net.survivalfun.core.packetevents.TabListManagerNoOp();
            partyManager.setTabListManager(this.tabListManager);
            SchedulerAdapter.runLater(() -> retryTabListManagerInit(), 40L);
        }
    }

    /**
     * Retry TabListManager init (e.g. when PacketEvents loads after Allium at startup).
     */
    private void retryTabListManagerInit() {
        if (tabListManager != null && tabListManager.supportsTabListUpdates()) return;
        boolean locatorBarEnabled = getServer().getWorlds().stream()
                .findFirst()
                .map(w -> w.getGameRuleValue(org.bukkit.GameRule.LOCATOR_BAR))
                .orElse(Boolean.TRUE);
        if (!locatorBarEnabled) return;
        try {
            TabListManager next = PacketEventsLoader.createTabListManager(this, partyManager, vanishManager);
            if (next.supportsTabListUpdates()) {
                if (tabListManager != null) tabListManager.shutdown();
                tabListManager = next;
                partyManager.setTabListManager(tabListManager);
                Text.sendDebugLog(INFO, "Tab list management enabled (delayed init) - party-locator-bar active");
            }
        } catch (Throwable e) {
            Text.sendDebugLog(WARN, "TabListManager retry failed: " + e.getMessage());
        }
    }

    /**
     * Registers all commands and their tab completers.
     */
    private void registerCommands() {
        try {
            // Core commands - initialize managers first
            commandManager = new CommandManager(this);
            securityAlertManager = new SecurityAlertManager(this);
            creativeManager = new CreativeManager(this);
            
            // Link the managers together
            commandManager.setCreativeManager(creativeManager);
            
            Core coreCommand = new Core(new WorldDefaults(this), this, getConfig(), commandManager, creativeManager, inventoryManager);
            Objects.requireNonNull(getCommand("core")).setExecutor(coreCommand);
            Objects.requireNonNull(getCommand("core")).setTabCompleter(coreCommand);
            registerCommand("maintenance", new Maintenance(this));
            tabCompleter = new Tab(this); // Create single Tab instance for all commands
            registerCommand("gamemode", new Gamemode(this), tabCompleter);
            registerCommand("speed", new Speed(this), tabCompleter);
            Fly flyCommand = new Fly(this);
            registerCommand("fly", flyCommand, flyCommand);
            registerCommand("xpbottle", new XpBottleCommand(this), new XpBottleCommand(this));
            registerCommand("cheque", new ChequeCommand(this), new ChequeCommand(this));
            registerCommand("voucher", new VoucherCommand(this), new VoucherCommand(this));
            registerCommand("heal", new Heal(langManager, getConfig(), this), tabCompleter);
            registerCommand("feed", new Feed(langManager, getConfig(), this), tabCompleter);
            registerCommand("god", new God(this), tabCompleter);
            PvpCommand pvpCommand = new PvpCommand(this);
            registerCommand("pvp", pvpCommand, pvpCommand);
            registerCommand("nv", new NV(this));

            // Vanish commands
            Vanish vanishCommand = new Vanish(this, vanishManager);
            registerCommand("vanish", vanishCommand, vanishCommand);
            registerCommand("v", vanishCommand, vanishCommand);

            // Freeze commands
            this.freezeCommand = new Freeze(this);
            registerCommand("freeze", this.freezeCommand, this.freezeCommand);
            registerCommand("unfreeze", this.freezeCommand, this.freezeCommand);

            registerCommand("redeem", new Redeem(this));
            spyCommand = new Spy(this);
            registerCommand("spy", spyCommand, spyCommand);
            registerCommand("whois", new Whois(this));
            registerCommand("seen", new Seen(this));
            registerCommand("gc", new GC(this), tabCompleter);
            Help helpCommand = new Help(this);
            helpCommand.register();
            msgCommand = new Msg(this, spyCommand);
            registerCommand("msg", msgCommand, msgCommand);
            registerCommand("reply", msgCommand, msgCommand);
            registerCommand("mail", msgCommand, msgCommand);
            registerCommand("note", new Note(this));
            registerCommand("notes", new Notes(this));
            registerCommand("unnote", new Unnote(this));

            registerCommand("give", new Give(this), tabCompleter);
            Invsee invseeCommand = new Invsee(this);
            registerCommand("invsee", invseeCommand);
            registerListenerSafely(getServer().getPluginManager(), "Invsee", invseeCommand);
            registerCommand("enderchest", new EnderChestCommand(this));
            registerCommand("itemdb", new ItemDB(this));
            registerCommand("lore", new Lore(this), tabCompleter);
            registerCommand("more", new More(this));
            registerCommand("rename", new Rename(this), tabCompleter);
            registerCommand("skull", new Skull(this));
            registerCommand("trash", new TrashCommand(this), new TrashCommand(this));
            

            // Fun commands
            explodeCommand = new Explode(this);
            registerCommand("explode", explodeCommand, tabCompleter);

            // Teleportation commands
            tpCommand = new TP(this);
            String[] tpCommands = {
                    "tp", "back", "tphere", "tpahere", "tpa", "tpcancel", "tpacancel",
                    "tpaccept", "tpdeny", "tppet", "tpmob", "tppos", "tphere", "tpahere", "tptoggle", "top",
                    "bottom", "tpall", "tpauto", "tpo", "otp"
            };
            for (String cmd : tpCommands) {
                registerCommand(cmd, tpCommand, tpCommand);
            }
            // TP does not implement Listener; its inner listeners are registered individually where needed

            // Economy commands
            Balance balanceExecutor = new Balance(this, economyManager);
            registerCommand("balance", balanceExecutor, balanceExecutor);
            registerCommand("bal", balanceExecutor, balanceExecutor);
            Pay payExecutor = new Pay(this, economyManager);
            registerCommand("pay", payExecutor, payExecutor);
            BalTop balTopExecutor = new BalTop(this, economyManager);
            registerCommand("baltop", balTopExecutor, balTopExecutor);
            Money moneyExecutor = new Money(this, economyManager);
            registerCommand("money", moneyExecutor, moneyExecutor);

            // Home commands
            Home homeCommand = new Home(this);
            registerCommand("home", homeCommand);
            registerCommand("sethome", homeCommand);
            registerCommand("delhome", homeCommand);
            registerCommand("homes", homeCommand);
            registerCommand("removehome", homeCommand);

            // Spawn commands
            Spawn spawnCommand = new Spawn(this);
            registerCommand("spawn", spawnCommand);
            registerCommand("setspawn", spawnCommand);

            // Warp commands
            Warp warpCommand = new Warp(this, warpManager);
            registerCommand("warp", warpCommand, warpCommand);
            registerCommand("warps", warpCommand, warpCommand);
            SetWarp setWarpCommand = new SetWarp(this, warpManager);
            registerCommand("setwarp", setWarpCommand, setWarpCommand);
            DelWarp delWarpCommand = new DelWarp(this, warpManager);
            registerCommand("delwarp", delWarpCommand, delWarpCommand);
            WarpInfo warpInfoCommand = new WarpInfo(this, warpManager);
            registerCommand("warpinfo", warpInfoCommand, warpInfoCommand);

            // Delete message command
            DeleteMsg deleteMessageCommand = new DeleteMsg(this, chatMessageManager);
            registerCommand("deletemsg", deleteMessageCommand);

            // Enchant command
            Enchant enchantCommand = new Enchant(this);
            registerCommand("enchant", enchantCommand, tabCompleter);
            
            // Restore command
            Restore restoreCommand = new Restore(this, inventoryManager);
            registerCommand("restore", restoreCommand);
            
            // Party command - take over from mcMMO when present (loadafter + force ownership)
            registerCommand("party", new PartyCommand(this, partyManager), new PartyCommand(this, partyManager));
            if (getServer().getPluginManager().getPlugin("mcMMO") != null) {
                try {
                    forceCommandOwnership("party");
                } catch (Throwable t) {
                    if (isDebugMode()) {
                        Text.sendDebugLog(WARN, "Could not take over /party from mcMMO: " + t.getMessage());
                    }
                }
            }

            // Locator bar command
            LocatorBarCommand locatorBarCommand = new LocatorBarCommand(this, partyManager);
            registerCommand("locatorbar", locatorBarCommand, locatorBarCommand);

            // Time commands
            registerCommand("time", timeCycle, timeCycle);
            registerCommand("day", timeCycle, timeCycle);
            registerCommand("night", timeCycle, timeCycle);
            
            // Auto Restart command
            try {
                this.autoRestartCommand = new AutoRestartCommand(this);
                registerCommand("autorestart", autoRestartCommand, autoRestartCommand);
                registerCommand("ar", autoRestartCommand, autoRestartCommand);
                if (isDebugMode()) {
                    getLogger().info("Auto-restart command registered successfully");
                }
            } catch (Exception e) {
                getLogger().severe("Failed to initialize AutoRestart: " + e.getMessage());
                e.printStackTrace();
            }

            // Register handcuffs command (always available)
            Handcuffs handcuffsCommand = new Handcuffs(this);
            registerCommand("handcuffs", handcuffsCommand, handcuffsCommand);
            Text.sendDebugLog(INFO, "Handcuffs command registered");

            // Register unrestrain command (admin only, always available)
            Unrestrain unrestrainCommand = new Unrestrain(this);
            registerCommand("unrestrain", unrestrainCommand);
            Text.sendDebugLog(INFO, "Unrestrain command registered");

            ChannelCommand channelCommand = new ChannelCommand(this, channelManager);
            registerCommand("channel", channelCommand, channelCommand);
            registerCommand("a", channelCommand, channelCommand);
            registerCommand("staffchat", channelCommand, channelCommand);
            registerCommand("local", channelCommand, channelCommand);
            registerCommand("l", channelCommand, channelCommand);
            registerCommand("g", channelCommand, channelCommand);
            registerCommand("global", channelCommand, channelCommand);
            try {
                forceCommandOwnership("a");
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "Could not override /a (mcMMO may own it): " + t.getMessage());
            }
            Text.sendDebugLog(INFO, "Channel commands registered");

            // Initialize Custom Items system
            CustomItemsConfig.initialize(this);
            CustomItemRegistry customItemRegistry = new CustomItemRegistry(this);
            SpawnerChangerManager spawnerChangerManager = new SpawnerChangerManager(this, customItemRegistry);
            customItemRegistry.register(new TreeAxeItem(this));
            customItemRegistry.register(new SpawnerChangerItem(this, spawnerChangerManager));
            Text.sendDebugLog(INFO, "Registered " + customItemRegistry.getItemCount() + " custom items");

            // Register custom item listeners
            getServer().getPluginManager().registerEvents(new TreeAxeListener(this, customItemRegistry), this);
            getServer().getPluginManager().registerEvents(spawnerChangerManager, this);
            getServer().getPluginManager().registerEvents(new SpawnerChangerListener(this, customItemRegistry, spawnerChangerManager), this);
            getServer().getPluginManager().registerEvents(new OraxenSmeltingListener(), this);
            spawnerCoreManager = new SpawnerCoreManager(this);
            getServer().getPluginManager().registerEvents(new MobHeadDropListener(this), this);
            getServer().getPluginManager().registerEvents(new SpawnerCraftListener(this, spawnerCoreManager), this);
            SpawnerCoreCommand spawnerCoreCommand = new SpawnerCoreCommand(this);
            registerCommand("spawnercore", spawnerCoreCommand, spawnerCoreCommand);
            Text.sendDebugLog(INFO, "Custom item listeners registered");

            // Register /core item command
            CoreItemCommand coreItemCommand = new CoreItemCommand(this);
            registerCommand("core item", coreItemCommand, coreItemCommand);
            Text.sendDebugLog(INFO, "Core item command registered");

            // Initialize Resource Pack Manager
            ResourcePackManager resourcePackManager = new ResourcePackManager(this);
            Text.sendDebugLog(INFO, "Resource pack manager initialized");
            
            // Register nickname command and dialog apply command
            Nick nicknameCommand = new Nick(this);
            registerCommand("nickname", nicknameCommand);
            Text.sendDebugLog(INFO, "Nickname command registered");

            if (witherSpawnBlocker == null) {
                witherSpawnBlocker = new WitherSpawnBlocker(this);
            }
            WitherToggle witherToggleCommand = new WitherToggle(this, witherSpawnBlocker);
            registerCommand("withertoggle", witherToggleCommand, witherToggleCommand);

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to register commands: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Registers a command with an optional tab completer.
     *
     * @param name     The command name.
     * @param executor The command executor.
     * @param completer The tab completer, or null if none.
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor, org.bukkit.command.TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            if (completer != null) {
                command.setTabCompleter(completer);
            }
        } else {
            Text.sendDebugLog(WARN, "Could not register command '" + name + "' - not found in plugin.yml?");
        }
    }

    /**
     * Registers a command without a tab completer.
     *
     * @param name     The command name.
     * @param executor The command executor.
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        registerCommand(name, executor, null);
    }

    /**
     * Registers listeners that do not depend on Vault services.
     */
    private void registerNonVaultListeners() {
        PluginManager pm = getServer().getPluginManager();
        
        // Initialize ore generation manager
        getLogger().severe("[OreGen-INIT] Initializing OreGenerationManager...");
        try {
            oreGenerationManager = new OreGenerationManager(this);
            getLogger().severe("[OreGen-INIT] OreGenerationManager initialized SUCCESSFULLY");
            if (isDebugMode()) {
                getLogger().info("Successfully initialized OreGenerationManager");
            }
        } catch (Throwable t) {
            getLogger().severe("[OreGen-INIT] FAILED to initialize OreGenerationManager: " + t.getMessage());
            getLogger().warning("Failed to initialize OreGenerationManager: " + t.getMessage());
            if (isDebugMode()) {
                t.printStackTrace();
            }
            oreGenerationManager = null;
        }

        // Initialize SpectatorTeleport with proper error handling
        if (spectatorTeleport == null) {
            try {
                // Load NV class using the plugin's class loader
                Class<?> nvClass = getClass().getClassLoader().loadClass("net.survivalfun.core.commands.NV");
                Object nvInstance = nvClass.getConstructor(PluginStart.class).newInstance(this);
                spectatorTeleport = new SpectatorTeleport(this, (NV) nvInstance);
                if (isDebugMode()) {
                    getLogger().info("Successfully initialized SpectatorTeleport");
                }
            } catch (Throwable t) {
                getLogger().severe("Failed to initialize SpectatorTeleport. This feature will be disabled.");
                if (isDebugMode()) {
                    t.printStackTrace();
                }
                spectatorTeleport = null;
            }
        }

        // Initialize FlightRestoration
        if (flyOnRejoinListener == null) {
            try {
                flyOnRejoinListener = new FlightRestoration(this);
                if (isDebugMode()) {
                    getLogger().info("Successfully initialized FlightRestoration");
                }
            } catch (Throwable t) {
                getLogger().warning("Failed to initialize FlightRestoration: " + t.getMessage());
                if (isDebugMode()) {
                    t.printStackTrace();
                }
                flyOnRejoinListener = null;
            }
        }

        // Initialize WitherSpawnBlocker
        if (witherSpawnBlocker == null) {
            try {
                witherSpawnBlocker = new WitherSpawnBlocker(this);
                if (isDebugMode()) {
                    getLogger().info("Successfully initialized WitherSpawnBlocker");
                }
            } catch (Throwable t) {
                getLogger().warning("Failed to initialize WitherSpawnBlocker: " + t.getMessage());
                if (isDebugMode()) {
                    t.printStackTrace();
                }
                witherSpawnBlocker = null;
            }
        }

        // Register listeners with individual try-catch blocks
        // Using direct class loading instead of lambdas to avoid class loading issues
        try {
            // Register core listeners
            registerListenerSafely(pm, "ConnectionManager", new ConnectionManager(this));
            registerListenerSafely(pm, "AltProtectionListener", new AltProtectionListener(this));
            registerListenerSafely(pm, "UpdateCheckerListener", new net.survivalfun.core.listeners.UpdateCheckerListener());
            registerListenerSafely(pm, "DialogApplyListener", new net.survivalfun.core.listeners.DialogApplyListener(this));
            registerListenerSafely(pm, "XpBottleRedeemListener", new XpBottleRedeemListener(this));
            registerListenerSafely(pm, "ChequeRedeemListener", new ChequeRedeemListener(this));
            registerListenerSafely(pm, "VoucherRedeemListener", new VoucherRedeemListener(this));
            
            if (creativeManager != null) {
                registerListenerSafely(pm, "CreativeManager", creativeManager);
            }
            
            if (spectatorTeleport != null) {
                registerListenerSafely(pm, "SpectatorTeleport", spectatorTeleport);
            }
            
            if (flyOnRejoinListener != null) {
                registerListenerSafely(pm, "FlightRestoration", flyOnRejoinListener);
            }
            
            // Register other listeners with null checks and error handling
            try {
                registerListenerSafely(pm, "MaintenanceManager", new MaintenanceManager(this, getVaultPermission()));
            } catch (Throwable t) {
                getLogger().warning("Failed to register MaintenanceManager: " + t.toString());
            }
            
            try {
                registerListenerSafely(pm, "InventorySnapshotListener", new InventorySnapshotListener(this, inventoryManager));
            } catch (Throwable t) {
                getLogger().warning("Failed to register InventorySnapshotListener: " + t.toString());
            }

            if (offlineInventoryManager != null) {
                registerListenerSafely(pm, "OfflineInventoryManager", offlineInventoryManager);
            }
            
            if (handcuffsListener != null) {
                registerListenerSafely(pm, "HandcuffsListener", handcuffsListener);
            }

            if (channelManager != null) {
                registerListenerSafely(pm, "AlliumChannelManager", channelManager);
            }
            
            if (witherSpawnBlocker != null) {
                registerListenerSafely(pm, "WitherSpawnBlocker", witherSpawnBlocker);
            }
            
            // Register other listeners with proper error handling
            registerListenerWithErrorHandling(pm, "GUIListener", () -> new net.survivalfun.core.listeners.gui.GUIListener());
            registerListenerWithErrorHandling(pm, "CreeperExplosion", () -> new CreeperExplosion(this));
            registerListenerWithErrorHandling(pm, "FireballExplosion", () -> new FireballExplosion(this));
            registerListenerWithErrorHandling(pm, "SlimeJump", () -> new SlimeJump(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true));
            registerListenerWithErrorHandling(pm, "Death", () -> new Death(this));
            registerListenerWithErrorHandling(pm, "WolfBehaviorListener", WolfBehaviorListener::new);
            registerListenerWithErrorHandling(pm, "LootTableListener", () -> new LootTableListener(this));
            
            // Ore generation listener
            getLogger().severe("[OreGen-REGISTER] Attempting to register OreGenerationListener...");
            getLogger().severe("[OreGen-REGISTER] oreGenerationManager is " + (oreGenerationManager != null ? "NOT null" : "NULL"));
            try {
                if (oreGenerationManager != null) {
                    getLogger().severe("[OreGen-REGISTER] Calling registerListenerSafely for OreGenerationListener");
                    registerListenerSafely(pm, "OreGenerationListener", 
                        new net.survivalfun.core.listeners.world.OreGenerationListener(this, oreGenerationManager));
                    getLogger().severe("[OreGen-REGISTER] OreGenerationListener registration completed!");
                } else {
                    getLogger().severe("[OreGen-REGISTER] SKIPPING registration - oreGenerationManager is null!");
                }
            } catch (Throwable t) {
                getLogger().severe("[OreGen-REGISTER] FAILED to register OreGenerationListener: " + t.toString());
                t.printStackTrace();
            }
            
            // Register NoteBlockPhysicsListener for Resource world ore protection
            try {
                registerListenerSafely(pm, "NoteBlockPhysicsListener", 
                    new net.survivalfun.core.listeners.world.NoteBlockPhysicsListener());
                getLogger().info("[OreGen] NoteBlockPhysicsListener registered for 'Resource' world");
            } catch (Throwable t) {
                getLogger().warning("[OreGen] Failed to register NoteBlockPhysicsListener: " + t.toString());
            }

            // Message listeners
            if (msgCommand != null) {
                registerListenerSafely(pm, "MsgCommand", msgCommand);
                try {
                    registerListenerSafely(pm, "MailManager", new MailManager(msgCommand));
                } catch (Throwable t) {
                    getLogger().warning("Failed to register MailManager: " + t.toString());
                }
            }

            // Teleport listener
            registerListenerSafely(pm, "TeleportBackListener", 
                () -> new net.survivalfun.core.listeners.jobs.TeleportBackListener(this));

            // Party visibility management
            if (partyManager != null && tabListManager != null) {
                registerListenerSafely(pm, "PartyListener", 
                    () -> new PartyListener(this, partyManager, tabListManager));
            }

        } catch (Exception e) {
            getLogger().severe("Unexpected error in registerNonVaultListeners: " + e.getMessage());
            e.printStackTrace();
            throw new IllegalStateException("Failed to initialize non-Vault listeners", e);
        }
    }

    /**
     * Registers a listener with error handling and retry logic.
     * @param pm The PluginManager instance
     * @param listenerName Name of the listener for logging purposes
     * @param listenerSupplier Supplier that provides the Listener instance
     */
    private void registerListenerWithErrorHandling(PluginManager pm, String listenerName, Supplier<Listener> listenerSupplier) {
        int maxAttempts = 3;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                attempt++;
                Listener listener = listenerSupplier.get();
                if (listener != null) {
                    registerListenerSafely(pm, listenerName, listener);
                    return; // Success, exit the method
                }
            } catch (Throwable t) {
                if (attempt >= maxAttempts) {
                    // Only log error on final attempt
                    getLogger().warning("Failed to register listener " + listenerName + " after " + maxAttempts + " attempts: " + t.toString());
                    if (isDebugMode()) {
                        t.printStackTrace();
                    }
                } else {
                    // Log debug info for retries
                    if (isDebugMode()) {
                        getLogger().info("Retrying registration of " + listenerName + " (attempt " + (attempt + 1) + " of " + maxAttempts + "): " + t.getMessage());
                    }
                    // Add a small delay before retry
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
    
    /**
     * Helper method to safely register a single listener with error handling
     * @param pm The PluginManager instance
     * @param listenerName Name of the listener for logging purposes
     * @param listenerSupplier Supplier that provides the Listener instance
     */
    /**
     * Registers a listener instance directly with error handling.
     * @param pm The PluginManager instance
     * @param listenerName Name of the listener for logging purposes
     * @param listener The Listener instance to register
     */
    private void registerListenerSafely(PluginManager pm, String listenerName, Listener listener) {
        if (listener == null) {
            getLogger().warning("Cannot register null listener: " + listenerName);
            return;
        }
        
        try {
            // Check if the plugin is still enabled before proceeding
            if (!isEnabled()) {
                getLogger().warning("Skipping registration of " + listenerName + " - plugin is disabled");
                return;
            }
            
            pm.registerEvents(listener, this);
            if (isDebugMode()) {
                getLogger().info("Successfully registered listener: " + listenerName);
            }
        } catch (IllegalStateException e) {
            // Handle cases where the plugin is being disabled
            if (isEnabled()) {
                getLogger().warning("Failed to register listener " + listenerName + ": " + e.getMessage());
            }
        } catch (Exception e) {
            getLogger().warning("Failed to register listener " + listenerName + ": " + e.toString());
            if (e.getCause() != null) {
                getLogger().warning("Caused by: " + e.getCause().toString());
            }
            if (isDebugMode()) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Helper method to safely register a single listener with error handling using a Supplier
     * @param pm The PluginManager instance
     * @param listenerName Name of the listener for logging purposes
     * @param listenerSupplier Supplier that provides the Listener instance
     */
    private void registerListenerSafely(PluginManager pm, String listenerName, Supplier<Listener> listenerSupplier) {
        try {
            // Check if the plugin is still enabled before proceeding
            if (!isEnabled()) {
                getLogger().warning("Skipping registration of " + listenerName + " - plugin is disabled");
                return;
            }
            
            // Try to get the listener
            Listener listener = null;
            try {
                listener = listenerSupplier.get();
                if (listener != null) {
                    // Use the direct registration method
                    registerListenerSafely(pm, listenerName, listener);
                } else {
                    getLogger().warning("Skipping null listener from supplier: " + listenerName);
                }
            } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
                // Handle class loading errors specifically
                getLogger().severe("Failed to load listener class " + listenerName + ": " + e.toString());
                if (e.getCause() != null) {
                    getLogger().severe("Caused by: " + e.getCause().toString());
                }
            } catch (Throwable t) {
                getLogger().severe("Failed to initialize listener " + listenerName + ": " + t.toString());
                if (t.getCause() != null) {
                    getLogger().severe("Caused by: " + t.getCause().toString());
                }
                if (isDebugMode()) {
                    t.printStackTrace();
                }
            }
        } catch (Throwable t) {
            getLogger().severe("Unexpected error registering listener " + listenerName + ": " + t.toString());
            if (isDebugMode()) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Registers listeners that depend on Vault services.
     */
    private void registerVaultDependentListeners() {
        // Chat formatter
        reloadChatFormatter();
        
        // Sign color listener
        getServer().getPluginManager().registerEvents(new SignColorListener(), this);
    }
    
    /**
     * Tries several times after startup to ensure Allium's commands own key labels like '/core'.
     * This handles plugins that register conflicting commands (e.g., CoreProtect registering 'core').
     */
    private void forcePreferredCommandsWithRetry() {
        final int maxAttempts = 8;
        final long retryDelay = 40L; // 2 seconds
        final int[] attempts = {0};
        final SchedulerAdapter.TaskHandle[] handle = new SchedulerAdapter.TaskHandle[1];
        final String[] commandsToEnforce = {
            "core", "restore", "god", "heal", "gamemode", "pvp",
            "seen", "alts",
            "msg", "reply", "mail",
            "tp", "tpa", "tpcancel", "tpaccept", "tpdeny", "tppet", "tpmob", "tppos",
            "tphere", "tpahere", "tptoggle", "back", "otp", "top", "bottom"
        };
        handle[0] = SchedulerAdapter.runTimer(() -> {
            attempts[0]++;
            boolean allOk = true;
            for (String cmd : commandsToEnforce) {
                try {
                    if (!forceCommandOwnership(cmd)) {
                        allOk = false;
                    }
                } catch (Throwable t) {
                    if (isDebugMode()) {
                        Text.sendDebugLog(WARN, "forceCommandOwnership(" + cmd + ") error: " + t.getMessage());
                    }
                    allOk = false;
                }
            }

            boolean ok = allOk;
            // Stop retrying if success, ran out of attempts, or enforcement unsupported on this server
            if (ok || attempts[0] >= maxAttempts || commandMapEnforceUnsupportedWarned) {
                if (ok) {
                    if (isDebugMode()) {
                        Text.sendDebugLog(INFO, "Confirmed Allium owns preferred commands (including /allium:tp, /allium:tpa, etc.) after " + attempts[0] + " attempt(s).");
                    }
                } else {
                    Text.sendDebugLog(WARN, "Could not ensure Allium owns all preferred commands after " + attempts[0] + " attempt(s).");
                }
                handle[0].cancel();
            }
        }, 60L, retryDelay);
    }

    /**
     * Forces the command map to map 'core' to Allium's command executor, overriding others.
     *
     * @return true if after this call the mapping for 'core' points to Allium's PluginCommand
     */
    private boolean forceCommandOwnership(String command) {
        PluginCommand our = getCommand(command);
        if (our == null) {
            Text.sendDebugLog(WARN, "Our '" + command + "' command is not defined in plugin.yml; cannot enforce ownership.");
            return false;
        }

        try {
            // Obtain CommandMap via reflection to avoid CraftBukkit imports
            Object server = getServer();
            java.lang.reflect.Method getCommandMap = server.getClass().getMethod("getCommandMap");
            CommandMap map = (CommandMap) getCommandMap.invoke(server);

            // Access underlying known commands map robustly across implementations
            java.util.Map<String, Command> known = reflectKnownCommands(map);
            if (known == null) {
                if (!commandMapEnforceUnsupportedWarned) {
                    commandMapEnforceUnsupportedWarned = true;
                    Text.sendDebugLog(WARN, "CommandMap implementation changed; cannot enforce '/" + command + "' cleanly: no accessible known-commands map");
                }
                return false;
            }

            String lowerCommand = command.toLowerCase(Locale.ROOT);
            String namespace = getName().toLowerCase(Locale.ROOT);

            // Normalize label entries we care about
            String[] labels = new String[] { lowerCommand, namespace + ":" + lowerCommand };
            for (String label : labels) {
                Command existing = known.get(label);
                if (existing != our) {
                    known.put(label, our);
                }
            }

            // Optionally log competing namespaced alias if present
            Command competing = known.get("coreprotect:" + lowerCommand);
            if (competing != null && competing != our && isDebugMode()) {
                Text.sendDebugLog(WARN, "Detected competing namespaced command owning '/" + lowerCommand + "': " + competing);
            }

            // Validate
            Command current = known.get(lowerCommand);
            boolean owned = (current == our);
            if (!owned && isDebugMode()) {
                Text.sendDebugLog(WARN, "Another plugin still owns '/" + lowerCommand + "': " + current);
            }
            return owned;
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            Text.sendDebugLog(WARN, "Failed to access CommandMap to enforce '/" + command + "': " + e.getMessage());
            return false;
        }
    }
    private java.util.Map<String, Command> reflectKnownCommands(CommandMap map) {
        // Try common field names up the class hierarchy
        String[] fieldNames = {"knownCommands", "known"};
        Class<?> cls = map.getClass();
        while (cls != null) {
            for (String name : fieldNames) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(map);
                    Map<String, Command> typed = tryCastCommandMap(val);
                    if (typed != null) {
                        return typed;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }
            cls = cls.getSuperclass();
        }

        // As a last resort, scan for any Map field that looks like String->Command
        cls = map.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(map);
                    Map<String, Command> typed = tryCastCommandMap(val);
                    if (typed != null) {
                        // Heuristic: if it contains a 'help' or 'version' key, assume it's the command map
                        if (typed.containsKey("help") || typed.containsKey("version") || typed.values().stream().anyMatch(Command.class::isInstance)) {
                            return typed;
                        }
                    }
                } catch (IllegalAccessException ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> tryCastCommandMap(Object candidate) {
        if (!(candidate instanceof Map<?, ?> rawMap)) {
            return null;
        }
        for (Object key : rawMap.keySet()) {
            if (!(key instanceof String)) {
                return null;
            }
        }
        for (Object value : rawMap.values()) {
            if (!(value instanceof Command)) {
                return null;
            }
        }
        return (Map<String, Command>) rawMap;
    }
    
    /**
     * Periodically logs the presence/owner of the native '/dialog' command shortly after enable.
     * This does not modify the command map; it is diagnostics-only.
     */
    private void scheduleDialogCommandWatchdog() {
        // /dialog watchdog removed
    }

    /**
     * Builds a one-line diagnostic summary of the '/dialog' command mapping state.
     * Safe to call at any time. Does not change state.
     */
    public String dumpDialogCommandState() {
        // Dialog features removed
        return null;
    }
    
    /**
     * Inner class to handle player permission migration on join.
     */
    private class PlayerPermissionMigrationListener implements Listener {
        private final String[] permissionsToMigrate = {
            "explode", "gc", "give", "itemdb", "heal", "feed", "admin", "rename", "lore",
            "god", "more", "fly", "gamemode", "nv", "skull", "home", "homes", "sethome",
            "delhome", "balance", "pay", "baltop", "tp", "tpa", "tpcancel", "tpaccept",
            "tpdeny", "tppet", "tpmob", "tppos", "tphere", "tpahere", "tptoggle", "tp.offline",
            "top", "bottom", "whois", "invsee", "enchant", "staff", "note", "notes", "unnote",
            "time", "time.set", "time.add",
            "escalate", "spy", "spy.others", "gamemode.others", "gamemode.spectator",
            "gamemode.survival", "gamemode.survival.nv", "gamemode.creative",
            "gamemode.creative.inventory", "gamemode.creative.container", "gamemode.creative.pickup",
            "gamemode.creative.drop", "gamemode.creative.break", "gamemode.creative.place",
            "gamemode.creative.interact", "gamemode.creative.use", "gamemode.creative.spawn",
            "gamemode.creative.blacklist", "gamemode.creative.nv", "gamemode.adventure",
            "gamemode.adventure.nv", "gamemode.spectator", "nv.others", "nv.adventure",
            "nv.creative", "nv.spectator", "nv.survival", "tp.others", "tp.override",
            "tpa.nodelay", "tpa.nocooldown", "voucher.give", "lockdown.bypass", "tpall",
            "tptoggle.others", "tpauto", "spawn", "setspawn", "help", "sethome.unlimited",
            "home.others", "home.nocooldown", "hide.group", "hide.bypass", "hide.default",
            "fly.others", "explode.others", "explode.exempt", "heal.others", "heal.nocooldown",
            "feed.others", "feed.nocooldown", "speed", "speed.others", "*"
        };

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            // Skip if player migration is already completed globally
            if (getConfig().getBoolean("player_migration_completed", false)) {
                return;
            }

            if (vaultPerms == null) {
                Text.sendDebugLog(ERROR, "Cannot migrate permissions for player " + event.getPlayer().getName() + ": Vault permission provider not found!");
                return;
            }

            org.bukkit.entity.Player player = event.getPlayer();
            String uuid = player.getUniqueId().toString();

            try (Connection conn = database.getConnection()) { // Use Database.getConnection()
                // Check if player exists in player_data and get migrated_perms status
                try (PreparedStatement stmt = conn.prepareStatement("SELECT migrated_perms FROM player_data WHERE uuid = ?")) {
                    stmt.setString(1, uuid);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        boolean migratedPerms = rs.getBoolean("migrated_perms");
                        if (migratedPerms) {
                            Text.sendDebugLog(INFO, "Skipping permission migration for " + player.getName() + " (UUID: " + uuid + "): Already migrated.");
                            return;
                        }
                    } else {
                        // Player not in player_data, insert and skip migration
                        try (PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT INTO player_data (uuid, name, is_flying, allow_flight, last_updated) VALUES (?, ?, FALSE, FALSE, CURRENT_TIMESTAMP)")) {
                            insertStmt.setString(1, uuid);
                            insertStmt.setString(2, player.getName());
                            insertStmt.executeUpdate();
                            Text.sendDebugLog(INFO, "Added player " + player.getName() + " (UUID: " + uuid + ") to player_data. Skipping permission migration.");
                            return;
                        }
                    }

                    // Check and migrate core.* wildcard permission first
                    boolean migrated = false;
                    if (vaultPerms.playerHas((String) null, player, "core.*")) {
                        vaultPerms.playerRemove((String) null, player, "core.*");
                        vaultPerms.playerAdd((String) null, player, "allium.*");
                        Text.sendDebugLog(INFO, "Migrated player " + player.getName() + ": core.* -> allium.*");
                        migrated = true;
                    }

                    // Migrate individual player permissions
                    for (String perm : permissionsToMigrate) {
                        String oldPerm = "core." + perm;
                        String newPerm = "allium." + perm;
                        if (vaultPerms.playerHas((String) null, player, oldPerm)) {
                            vaultPerms.playerRemove((String) null, player, oldPerm);
                            vaultPerms.playerAdd((String) null, player, newPerm);
                            Text.sendDebugLog(INFO, "Migrated player " + player.getName() + ": " + oldPerm + " -> " + newPerm);
                            migrated = true;
                        }
                    }

                    // Update migrated_perms if migration occurred
                    if (migrated) {
                        try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE player_data SET migrated_perms = TRUE WHERE uuid = ?")) {
                            updateStmt.setString(1, uuid);
                            updateStmt.executeUpdate();
                            Text.sendDebugLog(INFO, "Marked player " + player.getName() + " (UUID: " + uuid + ") as migrated in player_data.");
                        }
                    }

                    // Count players with unmigrated permissions
                    try (PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) AS unmigrated FROM player_data WHERE migrated_perms = FALSE OR migrated_perms IS NULL")) {
                        ResultSet countRs = countStmt.executeQuery();
                        if (countRs.next()) {
                            int unmigratedCount = countRs.getInt("unmigrated");
                            Text.sendDebugLog(INFO, "Players remaining with unmigrated permissions in player_data: " + unmigratedCount);
                            if (unmigratedCount == 0) {
                                getConfig().set("player_migration_completed", true);
                                saveConfig();
                                Text.sendDebugLog(INFO, "All player permissions migrated. Set player_migration_completed to true in config.");
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                Text.sendDebugLog(ERROR, "Failed to process permission migration for player " + player.getName() + " (UUID: " + uuid + ")", e);
            }
        }
    }
}
