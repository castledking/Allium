package net.survivalfun.core;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.commands.admin.Maintenance;
import net.survivalfun.core.commands.core.Core;
import net.survivalfun.core.commands.economy.Balance;
import net.survivalfun.core.commands.economy.BalTop;
import net.survivalfun.core.commands.economy.Money;
import net.survivalfun.core.commands.economy.Pay;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.commands.tabcompletes.Tab;
import net.survivalfun.core.commands.teleportation.Spawn;
import net.survivalfun.core.commands.teleportation.TP;
import net.survivalfun.core.commands.utils.core.managers.Feed;
import net.survivalfun.core.commands.utils.core.managers.Fly;
import net.survivalfun.core.commands.utils.core.managers.Gamemode;
import net.survivalfun.core.commands.utils.core.managers.God;
import net.survivalfun.core.commands.utils.core.managers.Heal;
import net.survivalfun.core.commands.utils.core.managers.NV;
import net.survivalfun.core.commands.utils.core.managers.Redeem;
import net.survivalfun.core.commands.utils.core.managers.Spy;
import net.survivalfun.core.commands.utils.core.managers.TimeCycle;
import net.survivalfun.core.commands.utils.core.managers.Whois;
import net.survivalfun.core.commands.utils.core.player.GC;
import net.survivalfun.core.commands.utils.core.player.Help;
import net.survivalfun.core.commands.utils.core.player.Home;
import net.survivalfun.core.commands.utils.core.player.Msg;
import net.survivalfun.core.commands.utils.core.staff.NoteCommand;
import net.survivalfun.core.commands.utils.core.staff.NotesCommand;
import net.survivalfun.core.commands.utils.core.staff.UnnoteCommand;
import net.survivalfun.core.commands.utils.items.Enchant;
import net.survivalfun.core.commands.utils.items.Give;
import net.survivalfun.core.commands.utils.items.Invsee;
import net.survivalfun.core.commands.utils.items.ItemDB;
import net.survivalfun.core.commands.utils.items.Lore;
import net.survivalfun.core.commands.utils.items.More;
import net.survivalfun.core.commands.utils.items.Rename;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.listeners.jobs.CreeperExplosionListener;
import net.survivalfun.core.listeners.jobs.FireballExplosionListener;
import net.survivalfun.core.listeners.jobs.MailRemindListener;
import net.survivalfun.core.listeners.jobs.PlayerDeathListener;
import net.survivalfun.core.listeners.jobs.SlimeCushionListener;
import net.survivalfun.core.listeners.jobs.SummonMessageListener;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.listeners.security.FlyOnRejoinListener;
import net.survivalfun.core.listeners.security.MaintenanceListener;
import net.survivalfun.core.listeners.security.PlayerConnectionListener;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PermissionCache;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Placeholder;
import net.survivalfun.core.managers.core.Skull;
import net.survivalfun.core.managers.economy.EconomyManager;
import net.survivalfun.core.managers.economy.VaultEconomyProvider;
import net.survivalfun.core.managers.economy.VaultHook;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.migration.MigrationManager;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Main plugin class for Allium, responsible for initializing managers, commands, listeners, and services.
 */
public class PluginStart extends JavaPlugin {
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
    private FlyOnRejoinListener flyOnRejoinListener;
    private Placeholder placeholder;
    private PermissionCache permissionCache;
    private final Map<UUID, Long> playerLoginTimes = new ConcurrentHashMap<>();
    private MigrationManager migrationManager;
    private EconomyManager economyManager;
    private TimeCycle timeCycle;

    /**
     * Gets the singleton instance of the plugin.
     *
     * @return The PluginStart instance.
     */
    public static PluginStart getInstance() {
        return instance;
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
     * Gets the PlaceholderAPI manager.
     *
     * @return The Placeholder instance.
     */
    public Placeholder getPlaceholder() {
        return placeholder;
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

    /**
     * Gets the fly-on-rejoin listener.
     *
     * @return The FlyOnRejoinListener instance.
     */
    public FlyOnRejoinListener getFlyOnRejoinListener() {
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
    @Override
    public void onEnable() {
        instance = this;
        migrationManager = new MigrationManager(getLogger(), getDataFolder());
        
        // Perform migration if needed
        if (migrationManager.isMigrationNeeded()) {
            getLogger().info("Allium folder detected, performing migration...");
            migrationManager.performMigration();
        }
        
        // Log loaded plugins for debugging
        getLogger().info("Loaded plugins: " + Arrays.toString(getServer().getPluginManager().getPlugins()));
        
        // Initialize core managers
        initializeManagers();
        
        // Register commands and non-Vault-dependent listeners
        registerCommands();
        registerNonVaultListeners();

        // Register player join listener for permission migration
        getServer().getPluginManager().registerEvents(new PlayerPermissionMigrationListener(), this);
        
        // Delay Vault initialization and Vault-dependent listeners with retry mechanism
        new BukkitRunnable() {
            private int attempts = 0;
            private final int maxAttempts = 10;
            private final long retryDelay = 40L; // 2 seconds per retry

            @Override
            public void run() {
                attempts++;
                getLogger().info("Attempting Vault initialization (attempt " + attempts + " of " + maxAttempts + ")");
                if (initializeVault()) {
                    getLogger().info("Vault services initialized successfully after " + attempts + " attempt(s).");
                    registerVaultDependentListeners();
                    performPermissionMigration(); // Run permission migration after Vault initialization
                    cancel(); // Stop retrying
                } else if (attempts < maxAttempts) {
                    getLogger().warning("Vault services not yet available. Retrying in " + retryDelay + " ticks (attempt " + (attempts + 1) + " of " + maxAttempts + ").");
                    getLogger().info("Number of permission providers: " + getServer().getServicesManager().getRegistrations(Permission.class).size());
                    getLogger().info("Number of chat providers: " + getServer().getServicesManager().getRegistrations(Chat.class).size());
                    getLogger().info("Number of economy providers: " + getServer().getServicesManager().getRegistrations(net.milkbowl.vault.economy.Economy.class).size());
                } else {
                    getLogger().warning("Failed to initialize Vault services after " + maxAttempts + " attempts. Using fallback formatting for chat.");
                    registerVaultDependentListeners(); // Register with null services
                    cancel();
                }
            }
        }.runTaskTimer(this, 100L, 40L); // Initial delay 5 seconds, retry every 2 seconds
    }

    /**
     * Performs migration of group permissions from core.* to allium.* on startup.
     */
    private void performPermissionMigration() {
        if (vaultPerms == null) {
            getLogger().severe("Cannot perform permission migration: Vault permission provider not found!");
            return;
        }

        // Check if group migration has already been completed
        if (getConfig().getBoolean("group_migration_completed", false)) {
            getLogger().info("Group permission migration already completed, skipping.");
            return;
        }

        // List of permissions to migrate (based on plugin.yml)
        String[] permissionsToMigrate = {
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
                    getLogger().info("Migrated group " + group + ": " + oldPerm + " -> " + newPerm);
                }
            }
        }

        // Mark group migration as completed
        getConfig().set("group_migration_completed", true);
        saveConfig();
        getLogger().info("Group permission migration from core.* to allium.* completed!");
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

        // Save spectator locations
        if (spectatorTeleport != null) {
            spectatorTeleport.saveAllLocations();
        }

        // Save player inventories
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerInventories inventories = new PlayerInventories(
                    player.getInventory().getContents(),
                    player.getInventory().getArmorContents(),
                    player.getInventory().getItemInOffHand(),
                    null, null, null
            );
            database.savePlayerInventories(uuid, inventories);
        }

        // Save fly states
        if (flyOnRejoinListener != null) {
            flyOnRejoinListener.saveAllPlayersState();
        }

        // Close database connection
        if (database != null) {
            database.closeConnection();
        }

        // Save creative inventories and cleanup
        if (creativeManager != null) {
            creativeManager.saveAllInventories();
            creativeManager.cleanup();
        }

        // Unregister PlaceholderAPI expansion
        if (placeholder != null && placeholder.isRegistered()) {
            placeholder.unregister();
            getLogger().info("PlaceholderAPI expansion unregistered.");
        }

        // Unregister all listeners
        HandlerList.unregisterAll(this);
    }

    /**
     * Reloads the chat formatter with the current configuration.
     */
    public void reloadChatFormatter() {
        if (!getConfig().getBoolean("enable-chat-formatting", true)) {
            getLogger().info("Chat formatting is disabled in config.yml.");
            return;
        }

        // Unregister existing chat formatter
        if (formatChatListener != null) {
            HandlerList.unregisterAll(formatChatListener);
        }

        // Create and register new formatter
        formatChatListener = new FormatChatListener(this, vaultChat, configManager);
        getServer().getPluginManager().registerEvents(formatChatListener, this);
        getLogger().info("Chat formatter has been reloaded with new configuration.");
    }

    /**
     * Initializes core managers (config, lang, database, economy, etc.).
     */
    private void initializeManagers() {
        // Config manager
        configManager = new Config(this);

        // Language manager
        try {
            langManager = new Lang(getDataFolder(), this, getConfig());
            getLogger().info("LangManager initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LangManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Database manager
        database = new Database(this);
        permissionCache = new PermissionCache(database);

        // Initialize economy manager
        vaultEcon = new VaultEconomyProvider(this);
        economyManager = new EconomyManager(this, database);

        // Other core managers
        try {
            Alias.initialize(this);
            getLogger().info("Alias system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize Alias system: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            LegacyID.initialize(this);
            getLogger().info("LegacyID system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LegacyID system: " + e.getMessage());
            e.printStackTrace();
        }

        Item.initialize(this);
        new Skull(this);
        new WorldDefaults(this);
        timeCycle = new TimeCycle(this);
    }

    private boolean initializeVault() {
        Plugin vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
        if (vaultPlugin != null) {
            getLogger().info("Vault plugin found. Attempting to initialize Vault services.");

            // Load the Vault service classes using Vault's class loader
            ClassLoader vaultClassLoader = vaultPlugin.getClass().getClassLoader();
            Class<?> permissionClass = null;
            Class<?> chatClass = null;
            Class<?> economyClass = null;
            try {
                permissionClass = Class.forName("net.milkbowl.vault.permission.Permission", true, vaultClassLoader);
                chatClass = Class.forName("net.milkbowl.vault.chat.Chat", true, vaultClassLoader);
                economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, vaultClassLoader);
            } catch (ClassNotFoundException e) {
                getLogger().severe("Failed to load Vault service class: " + e.getMessage());
                return false;
            }

            // Initialize services
            RegisteredServiceProvider<?> permissionProvider = getServer().getServicesManager().getRegistration(permissionClass);
            RegisteredServiceProvider<?> chatProvider = getServer().getServicesManager().getRegistration(chatClass);
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);

            boolean servicesInitialized = false;

            // Set service instances if available
            if (permissionProvider != null) {
                vaultPerms = (Permission) permissionProvider.getProvider();
                getLogger().info("Vault Permission service initialized: " + vaultPerms.getName());
                servicesInitialized = true;
            } else {
                getLogger().warning("Vault Permission service not found. Permission checks will not work correctly.");
            }

            if (chatProvider != null) {
                vaultChat = (Chat) chatProvider.getProvider();
                getLogger().info("Vault Chat service initialized: " + vaultChat.getName());
                servicesInitialized = true;
            } else {
                getLogger().warning("Vault Chat service not found. Chat formatting and group-specific features may not work.");
            }

            if (economyProvider != null) {
                vaultEcon = economyProvider.getProvider();
                getLogger().info("Vault Economy service initialized: " + vaultEcon.getName());
                servicesInitialized = true;
            } else {
                // Register our own economy provider
                getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, new VaultEconomyProvider(this), this, ServicePriority.Normal);
                vaultEcon = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class).getProvider();
                getLogger().info("Registered Allium's Vault Economy provider");
                servicesInitialized = true;
            }

            return servicesInitialized;
        } else {
            getLogger().warning("Vault plugin not found. Some features may not work correctly.");
            return false;
        }
    }

    private void initializePlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholder = new Placeholder();
            placeholder.register();
            getLogger().info("PlaceholderAPI expansion registered successfully.");
        } else {
            getLogger().warning("PlaceholderAPI not found. Custom placeholders will not be available.");
        }
    }

    /**
     * Registers all commands and their tab completers.
     */
    private void registerCommands() {
        try {
            // Core commands
            CommandManager commandManager = new CommandManager(this);
            CreativeManager creativeManager = new CreativeManager(this);
            Core coreCommand = new Core(new WorldDefaults(this), this, getConfig(), commandManager, creativeManager);
            Objects.requireNonNull(getCommand("core")).setExecutor(coreCommand);
            Objects.requireNonNull(getCommand("core")).setTabCompleter(coreCommand);
            registerCommand("maintenance", new Maintenance(this));
            registerCommand("gamemode", new Gamemode(this), new Tab(this));
            registerCommand("fly", new Fly(this), new Tab(this));
            registerCommand("heal", new Heal(langManager, getConfig(), this), new Tab(this));
            registerCommand("feed", new Feed(langManager, getConfig(), this), new Tab(this));
            registerCommand("god", new God(this), new Tab(this));
            registerCommand("nv", new NV(this));
            registerCommand("redeem", new Redeem(this));
            spyCommand = new Spy(this);
            registerCommand("spy", spyCommand, spyCommand);
            registerCommand("whois", new Whois(this));
            registerCommand("gc", new GC(this), new Tab(this));
            Help helpCommand = new Help(this);
            helpCommand.register();
            msgCommand = new Msg(this, spyCommand);
            registerCommand("msg", msgCommand, msgCommand);
            registerCommand("reply", msgCommand, msgCommand);
            registerCommand("mail", msgCommand, msgCommand);
            registerCommand("note", new NoteCommand(this));
            registerCommand("notes", new NotesCommand(this));
            registerCommand("unnote", new UnnoteCommand(this));

            // Item commands
            registerCommand("enchant", new Enchant(this));
            registerCommand("give", new Give(this), new Tab(this));
            registerCommand("invsee", new Invsee(this));
            registerCommand("itemdb", new ItemDB(this));
            registerCommand("lore", new Lore(this), new Tab(this));
            registerCommand("more", new More(this));
            registerCommand("rename", new Rename(this), new Tab(this));
            registerCommand("skull", new Skull(this));

            // Fun commands
            explodeCommand = new Explode(this);
            registerCommand("explode", explodeCommand, new Tab(this));

            // Teleportation commands
            tpCommand = new TP(this);
            String[] tpCommands = {
                    "tp", "tphere", "tpahere", "tpa", "tpcancel", "tpacancel",
                    "tpaccept", "tpdeny", "tppet", "tppos", "tptoggle", "top",
                    "bottom", "otp", "tpmob", "tpentity", "tpent", "teleportmob", "tpe", "tpm"
            };
            for (String cmd : tpCommands) {
                registerCommand(cmd, tpCommand, tpCommand);
            }

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
            Home homeCommand = new Home(this, database);
            registerCommand("home", homeCommand);
            registerCommand("sethome", homeCommand);
            registerCommand("delhome", homeCommand);
            registerCommand("homes", homeCommand);
            registerCommand("removehome", homeCommand);

            // Spawn commands
            Spawn spawnCommand = new Spawn(this, database);
            registerCommand("spawn", spawnCommand);
            registerCommand("setspawn", spawnCommand);

            // Time commands
            registerCommand("time", timeCycle, timeCycle);
            registerCommand("day", timeCycle, timeCycle);
            registerCommand("night", timeCycle, timeCycle);

        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
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
            getLogger().warning("Could not register command '" + name + "' - not found in plugin.yml?");
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
        // Security listeners
        commandManager = new CommandManager(this);
        creativeManager = new CreativeManager(this);
        spectatorTeleport = new SpectatorTeleport(this, new NV(this));
        flyOnRejoinListener = new FlyOnRejoinListener(this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(creativeManager, this);
        getServer().getPluginManager().registerEvents(spectatorTeleport, this);
        getServer().getPluginManager().registerEvents(flyOnRejoinListener, this);
        getServer().getPluginManager().registerEvents(new MaintenanceListener(this, getVaultPermission()), this);

        // Job listeners
        getServer().getPluginManager().registerEvents(new SummonMessageListener(), this);
        getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);
        getServer().getPluginManager().registerEvents(new FireballExplosionListener(this), this);
        getServer().getPluginManager().registerEvents(new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this, database), this);

        // Message listeners
        getServer().getPluginManager().registerEvents(msgCommand, this);
        getServer().getPluginManager().registerEvents(new MailRemindListener(msgCommand), this);
    }

    /**
     * Registers listeners that depend on Vault services.
     */
    private void registerVaultDependentListeners() {
        // Chat formatter
        reloadChatFormatter();
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
            "feed.others", "feed.nocooldown", "*", "money"
        };

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            // Skip if player migration is already completed globally
            if (getConfig().getBoolean("player_migration_completed", false)) {
                return;
            }

            if (vaultPerms == null) {
                getLogger().severe("Cannot migrate permissions for player " + event.getPlayer().getName() + ": Vault permission provider not found!");
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
                            getLogger().info("Skipping permission migration for " + player.getName() + " (UUID: " + uuid + "): Already migrated.");
                            return;
                        }
                    } else {
                        // Player not in player_data, insert and skip migration
                        try (PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT INTO player_data (uuid, name, is_flying, allow_flight, last_updated) VALUES (?, ?, FALSE, FALSE, CURRENT_TIMESTAMP)")) {
                            insertStmt.setString(1, uuid);
                            insertStmt.setString(2, player.getName());
                            insertStmt.executeUpdate();
                            getLogger().info("Added player " + player.getName() + " (UUID: " + uuid + ") to player_data. Skipping permission migration.");
                            return;
                        }
                    }

                    // Check and migrate core.* wildcard permission first
                    boolean migrated = false;
                    if (vaultPerms.playerHas((String) null, player, "core.*")) {
                        vaultPerms.playerRemove((String) null, player, "core.*");
                        vaultPerms.playerAdd((String) null, player, "allium.*");
                        getLogger().info("Migrated player " + player.getName() + ": core.* -> allium.*");
                        migrated = true;
                    }

                    // Migrate individual player permissions
                    for (String perm : permissionsToMigrate) {
                        String oldPerm = "core." + perm;
                        String newPerm = "allium." + perm;
                        if (vaultPerms.playerHas((String) null, player, oldPerm)) {
                            vaultPerms.playerRemove((String) null, player, oldPerm);
                            vaultPerms.playerAdd((String) null, player, newPerm);
                            getLogger().info("Migrated player " + player.getName() + ": " + oldPerm + " -> " + newPerm);
                            migrated = true;
                        }
                    }

                    // Update migrated_perms if migration occurred
                    if (migrated) {
                        try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE player_data SET migrated_perms = TRUE WHERE uuid = ?")) {
                            updateStmt.setString(1, uuid);
                            updateStmt.executeUpdate();
                            getLogger().info("Marked player " + player.getName() + " (UUID: " + uuid + ") as migrated in player_data.");
                        }
                    }

                    // Count players with unmigrated permissions
                    try (PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) AS unmigrated FROM player_data WHERE migrated_perms = FALSE OR migrated_perms IS NULL")) {
                        ResultSet countRs = countStmt.executeQuery();
                        if (countRs.next()) {
                            int unmigratedCount = countRs.getInt("unmigrated");
                            getLogger().info("Players remaining with unmigrated permissions in player_data: " + unmigratedCount);
                            if (unmigratedCount == 0) {
                                getConfig().set("player_migration_completed", true);
                                saveConfig();
                                getLogger().info("All player permissions migrated. Set player_migration_completed to true in config.");
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to process permission migration for player " + player.getName() + " (UUID: " + uuid + ")", e);
            }
        }
    }
}