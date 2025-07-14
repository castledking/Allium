package net.survivalfun.core;

import net.milkbowl.vault2.chat.Chat;
import net.milkbowl.vault2.permission.Permission;
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
import net.survivalfun.core.commands.utils.core.managers.Whois;
import net.survivalfun.core.commands.utils.core.player.GC;
import net.survivalfun.core.commands.utils.core.player.Help;
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
import net.survivalfun.core.managers.economy.Economy;
import net.survivalfun.core.managers.economy.VaultHook;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.migration.MigrationManager;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main plugin class for SurvivalFun, responsible for initializing managers, commands, listeners, and services.
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
    private Economy economy;
    private FlyOnRejoinListener flyOnRejoinListener;
    private Placeholder placeholder;
    private PermissionCache permissionCache;
    private final Map<UUID, Long> playerLoginTimes = new ConcurrentHashMap<>();
    private VaultHook vaultHook;
    private Economy vaultEcon;
    private MigrationManager migrationManager;

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
    public Economy getEconomy() {
        return economy;
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
     * Called when the plugin is enabled. Initializes managers, services, commands, and listeners.
     */
    @Override
    public void onEnable() {
        instance = this;
        migrationManager = new MigrationManager(getLogger(), getDataFolder());
        
        // Perform migration if needed
        if (migrationManager.isMigrationNeeded()) {
            getLogger().info("SFCore folder detected, performing migration...");
            migrationManager.performMigration();
        }
        
        // Initialize core managers
        initializeManagers();
        
        
        
        // Initialize other managers
        initializeVault();
        registerCommands();
        registerListeners();
        initializePlaceholderAPI();
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
        if (formatChatListener.canEnable()) {
            getServer().getPluginManager().registerEvents(formatChatListener, this);
            getLogger().info("Chat formatter has been reloaded with new configuration.");
        } else {
            getLogger().warning("FormatChatListener is disabled because Vault Chat or Permission service is not available. Using fallback formatting.");
            // Register with null vaultChat to enable basic formatting
            formatChatListener = new FormatChatListener(this, null, configManager);
            getServer().getPluginManager().registerEvents(formatChatListener, this);
        }
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

        // Economy manager
        economy = new Economy(this, database);

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
    }

    private void initializeVault() {
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            getLogger().info("Vault plugin found. Attempting to initialize Vault services.");

            // Initialize services
            RegisteredServiceProvider<Permission> permissionProvider = getServer().getServicesManager().getRegistration(Permission.class);
            RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(Chat.class);
            RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(Economy.class);

            // Set service instances if available
            if (permissionProvider != null) {
                vaultPerms = permissionProvider.getProvider();
                getLogger().info("Vault Permission service initialized: " + vaultPerms.getName());
            } else {
                getLogger().warning("Vault Permission service not found. Permission checks will not work correctly.");
            }

            if (chatProvider != null) {
                vaultChat = chatProvider.getProvider();
                getLogger().info("Vault Chat service initialized: " + vaultChat.getName());
            } else {
                getLogger().warning("Vault Chat service not found. Chat formatting and group-specific features may not work.");
            }

            if (economyProvider != null) {
                vaultEcon = economyProvider.getProvider();
                vaultHook = new VaultHook(this);
                vaultHook.hook();
                getLogger().info("Vault Economy service initialized: " + vaultEcon.getName());
            } else {
                getLogger().warning("Vault Economy service not found. Economy features may not work.");
            }
        } else {
            getLogger().warning("Vault plugin not found. Some features may not work correctly.");
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
            registerCommand("core", new Core(new WorldDefaults(this), this, getConfig(), commandManager, creativeManager), new Tab(this));
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

            Spawn spawnCommand = new Spawn(this, database);
            registerCommand("spawn", spawnCommand);
            registerCommand("setspawn", spawnCommand);

            // Economy commands
            Balance balanceExecutor = new Balance(this, economy);
            registerCommand("balance", balanceExecutor, balanceExecutor);
            registerCommand("bal", balanceExecutor, balanceExecutor);
            Pay payExecutor = new Pay(this, economy);
            registerCommand("pay", payExecutor, payExecutor);
            BalTop balTopExecutor = new BalTop(this, economy);
            registerCommand("baltop", balTopExecutor, balTopExecutor);
            Money moneyExecutor = new Money(this, economy);
            registerCommand("money", moneyExecutor, moneyExecutor);

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
     * Registers all event listeners.
     */
    private void registerListeners() {
        // Security listeners
        commandManager = new CommandManager(this);
        creativeManager = new CreativeManager(this);
        spectatorTeleport = new SpectatorTeleport(this, new NV(this));
        flyOnRejoinListener = new FlyOnRejoinListener(this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(creativeManager, this);
        getServer().getPluginManager().registerEvents(spectatorTeleport, this);
        getServer().getPluginManager().registerEvents(flyOnRejoinListener, this);
        getServer().getPluginManager().registerEvents(new MaintenanceListener(this), this);

        // Job listeners
        getServer().getPluginManager().registerEvents(new SummonMessageListener(), this);
        getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);
        getServer().getPluginManager().registerEvents(new FireballExplosionListener(this), this);
        getServer().getPluginManager().registerEvents(new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this, database), this);

        // Message listeners
        getServer().getPluginManager().registerEvents(msgCommand, this);
        getServer().getPluginManager().registerEvents(new MailRemindListener(msgCommand), this);

        // Chat formatter
        reloadChatFormatter();
    }
}