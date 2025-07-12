package net.survivalfun.core;

import net.milkbowl.vault2.chat.Chat;
import net.survivalfun.core.listeners.jobs.MailRemindListener;
import net.survivalfun.core.listeners.jobs.SummonMessageListener;
import net.survivalfun.core.listeners.jobs.CreeperExplosionListener;
import net.survivalfun.core.listeners.jobs.FireballExplosionListener;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.listeners.security.FlyOnRejoinListener;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.commands.admin.Maintenance;
import net.survivalfun.core.commands.core.Core;
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
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.commands.tabcompletes.Tab;
import net.survivalfun.core.commands.teleportation.Spawn;
import net.survivalfun.core.commands.teleportation.TP;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import net.survivalfun.core.listeners.security.MaintenanceListener;
import net.survivalfun.core.listeners.security.PlayerConnectionListener;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.core.Skull;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.listeners.jobs.SlimeCushionListener;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.economy.Economy;
import net.survivalfun.core.managers.economy.VaultEconomyProvider;
import org.bukkit.plugin.ServicePriority;

import net.survivalfun.core.commands.economy.Balance;
import net.survivalfun.core.commands.economy.Pay;
import net.survivalfun.core.commands.economy.BalTop;
import net.survivalfun.core.commands.economy.Money;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.luckperms.api.LuckPerms;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main class of the plugin
 */
public class PluginStart extends JavaPlugin {
    private static PluginStart instance;
    private Lang langManager;
    private Chat vaultChat = null;
    private LuckPerms luckPerms = null;
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
    private final Map<UUID, Long> playerLoginTimes = new ConcurrentHashMap<>();
    private TP tp;
    private FlyOnRejoinListener flyOnRejoinListener;

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public static PluginStart getInstance() {
        return instance;
    }



    public Lang getLangManager() {
        return langManager;
    }

    public Chat getVaultChat() {
        return vaultChat;
    }

    public Map<UUID, Long> getPlayerLoginTimes() {
        return playerLoginTimes;
    }

    public void reloadChatFormatter() {

        // Only proceed if chat formatting is enabled in config
        if (getConfig().getBoolean("enable-chat-formatting", true)) {
            // If we already have a chat formatter, unregister it
            if (formatChatListener != null) {
                // Unregister the old listener
                HandlerList.unregisterAll(formatChatListener);
            }

            // Create a new formatter with the updated config
            formatChatListener = new FormatChatListener(this, vaultChat, luckPerms, configManager);

            // Register the new formatter if it can be enabled
            if (formatChatListener.canEnable()) {
                getServer().getPluginManager().registerEvents(formatChatListener, this);
                getLogger().info("Chat formatter has been reloaded with new configuration.");
            } else {
                getLogger().warning("FormatChatListener is disabled due to missing dependencies.");
            }
        } else {
            getLogger().info("Chat formatting is disabled in config.yml.");
        }
    }

    public Database getDatabase() {
        return database;
    }


    public Explode getExplodeCommand() {
        return explodeCommand;
    }





    



    @Override
    public void onEnable() {
        instance = this;
        this.configManager = new Config(this);
        RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(
                net.milkbowl.vault2.chat.Chat.class);
        if (chatProvider != null) {
            vaultChat = chatProvider.getProvider();
        }


        // Try to get LuckPerms
        RegisteredServiceProvider<LuckPerms> luckPermsProvider = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (luckPermsProvider != null) {
            luckPerms = luckPermsProvider.getProvider();
        } else {
            getLogger().warning("LuckPerms not found! Group-specific command blocking will be disabled.");
        }

        if (getConfig().getBoolean("enable-chat-formatting", true)) {
            formatChatListener = new FormatChatListener(this, vaultChat, luckPerms, configManager);
            if (formatChatListener.canEnable()) {
                getServer().getPluginManager().registerEvents(formatChatListener, this);
            } else {
                getLogger().warning("FormatChatListener is disabled due to missing dependencies.");
            }
        } else {
            getLogger().info("Chat formatting is disabled in config.yml.");
        }

        // Voucher feature removed - only using redeem command

        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }

        // Initialize LangManager
        try {
            langManager = new Lang(getDataFolder(), this, getConfig());
            getLogger().info("LangManager initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LangManager: " + e.getMessage());
            e.printStackTrace(); // Added for more detailed error logging
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Alias system
        try {
            Alias.initialize(this);
            getLogger().info("Alias system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize Alias system: " + e.getMessage());
            e.printStackTrace();
            // Don't disable plugin for alias failure, just log the error
        }

        // Initialize LegacyID system
        try {
            LegacyID.initialize(this);
            getLogger().info("LegacyID system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LegacyID system: " + e.getMessage());
            e.printStackTrace();
            // Don't disable plugin for legacy ID failure, just log the error
        }

        // Register PlayerConnectionListener to manage playerLoginTimes
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getLogger().info("PlayerConnectionListener registered.");
        
        // Initialize database
        this.database = new Database(this);
        
        // Initialize Economy system
        this.economy = new Economy(this, this.database);
        
        // Initialize PlayerDeathListener for handling player deaths and the /back command
        new net.survivalfun.core.listeners.jobs.PlayerDeathListener(this, this.database);
        getLogger().info("PlayerDeathListener registered.");

        // Register commands
        PluginCommand redeemCmd = getCommand("redeem");
        if (redeemCmd != null) {
            redeemCmd.setExecutor(new Redeem(this));
        } else {
            getLogger().warning("Could not register 'redeem' command - not found in plugin.yml?");
        }

        super.onEnable();

        SlimeCushionListener slimeCushionListener = new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true);
        getServer().getPluginManager().registerEvents(slimeCushionListener, this);
        new SpectatorTeleport(this, new NV(this));


        getServer().getPluginManager().registerEvents(new SummonMessageListener(), this);


        // Disable command block output (which includes unknown command messages)
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        }

        // Security Listeners
        this.flyOnRejoinListener = new FlyOnRejoinListener(this);
        this.creativeManager = new CreativeManager(this);
        getServer().getPluginManager().registerEvents(this.creativeManager, this); // Ensure CreativeManager is registered
        this.spectatorTeleport = new SpectatorTeleport(this, new NV(this));
        commandManager = new CommandManager(this);
        


        // Register creeper explosion listener
        getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);

        // Register fireball explosion listener
        getServer().getPluginManager().registerEvents(new FireballExplosionListener(this), this);


        WorldDefaults worldDefaults = new WorldDefaults(this);
        Core coreCommand = new Core(worldDefaults, this, getConfig(), commandManager, creativeManager);
        // Register the Core command:
        getCommand("core").setExecutor(coreCommand);
        getCommand("core").setTabCompleter(coreCommand);

        Item.initialize(this);

        // Register commands
        try {
            // Register explode command
            // In your onEnable method, when registering commands

            this.explodeCommand = new Explode(this);
            getCommand("explode").setExecutor(explodeCommand);
            getCommand("explode").setTabCompleter(new Tab(this));

            // Register gc command
            getCommand("gc").setExecutor(new GC(this));
            getCommand("gc").setTabCompleter(new Tab(this));

            // Register give command
            getCommand("give").setExecutor(new Give(this));
            getCommand("give").setTabCompleter(new Tab(this));
            
            // Register skull command
            getCommand("skull").setExecutor(new Skull(this));

            // Register ItemDB command
            getCommand("itemdb").setExecutor(new ItemDB(this));

            // Register Heal command
            getCommand("heal").setExecutor(new Heal(langManager, getConfig(), this));
            getCommand("heal").setTabCompleter(new Tab(this));

            // Register Feed command
            getCommand("feed").setExecutor(new Feed(langManager, getConfig(), this));
            getCommand("feed").setTabCompleter(new Tab(this));

            // Register Rename command
            getCommand("rename").setExecutor(new Rename(this));
            getCommand("rename").setTabCompleter(new Tab(this));

            // Initialize TP command handler
            this.tpCommand = new TP(this);

            // Register Teleport commands defined in plugin.yml
            String[] tpCommands = {"tp", "tphere", "tpahere", "tpa", "tpcancel", "tpacancel", "tpaccept", "tpdeny", "tppet", "tppos", "tptoggle", "top", "bottom", "otp", "tpmob", "tpentity", "tpent", "teleportmob", "tpe", "tpm"};

            // Register the commands that are still handled by TP
            for (String cmdName : tpCommands) {
                PluginCommand command = getCommand(cmdName);
                if (command != null) {
                    command.setExecutor(this.tpCommand);
                    command.setTabCompleter(this.tpCommand);
                } else {
                    getLogger().warning("Failed to register teleport command: " + cmdName + " - not found in plugin.yml or already registered by another plugin?");
                }
            }

            // Register Economy commands
            // Balance command
            PluginCommand balanceCmd = getCommand("balance");
            if (balanceCmd != null) {
                Balance balanceExecutor = new Balance(this, economy);
                balanceCmd.setExecutor(balanceExecutor);
                balanceCmd.setTabCompleter(balanceExecutor);
                getLogger().info("Registered 'balance' command");
            } else {
                getLogger().warning("Could not register 'balance' command - not found in plugin.yml?");
            }
            
            // Bal alias
            PluginCommand balCmd = getCommand("bal");
            if (balCmd != null) {
                Balance balanceExecutor = new Balance(this, economy);
                balCmd.setExecutor(balanceExecutor);
                balCmd.setTabCompleter(balanceExecutor);
                getLogger().info("Registered 'bal' command");
            } else {
                getLogger().warning("Could not register 'bal' command - not found in plugin.yml?");
            }
            
            // Pay command
            PluginCommand payCmd = getCommand("pay");
            if (payCmd != null) {
                Pay payExecutor = new Pay(this, economy);
                payCmd.setExecutor(payExecutor);
                payCmd.setTabCompleter(payExecutor);
                getLogger().info("Registered 'pay' command");
            } else {
                getLogger().warning("Could not register 'pay' command - not found in plugin.yml?");
            }
            
            // BalTop command
            PluginCommand balTopCmd = getCommand("baltop");
            if (balTopCmd != null) {
                BalTop balTopExecutor = new BalTop(this, economy);
                balTopCmd.setExecutor(balTopExecutor);
                balTopCmd.setTabCompleter(balTopExecutor);
                getLogger().info("Registered 'baltop' command");
            } else {
                getLogger().warning("Could not register 'baltop' command - not found in plugin.yml?");
            }
            
            // Money admin command
            PluginCommand moneyCmd = getCommand("money");
            if (moneyCmd != null) {
                Money moneyExecutor = new Money(this, economy);
                moneyCmd.setExecutor(moneyExecutor);
                moneyCmd.setTabCompleter(moneyExecutor);
                getLogger().info("Registered 'money' command");
            } else {
                getLogger().warning("Could not register 'money' command - not found in plugin.yml?");
            }
            
            // Register Lore command
            getCommand("lore").setExecutor(new Lore(this));
            getCommand("lore").setTabCompleter(new Tab(this));

            // Register God command
            getCommand("god").setExecutor(new God(this));
            getCommand("god").setTabCompleter(new Tab(this));

            // Register More command
            getCommand("more").setExecutor(new More(this));

            // Register Fly command
            getCommand("fly").setExecutor(new Fly(this));
            getCommand("fly").setTabCompleter(new Tab(this));

            // Register NV command
            getCommand("nv").setExecutor(new NV(this));

            // Register Msg & Spy commands
            this.spyCommand = new Spy(this);
            getCommand("spy").setExecutor(spyCommand);
            getCommand("spy").setTabCompleter(spyCommand);

            Msg msgCommand = new Msg(this, spyCommand);
            getCommand("msg").setExecutor(msgCommand);
            getCommand("msg").setTabCompleter(msgCommand);
            getCommand("reply").setExecutor(msgCommand);
            getCommand("reply").setTabCompleter(msgCommand);
            getCommand("mail").setExecutor(msgCommand);
            getCommand("mail").setTabCompleter(msgCommand);


            getServer().getPluginManager().registerEvents(msgCommand, this);            getServer().getPluginManager().registerEvents(new MailRemindListener(msgCommand), this);

            // Register Help command

            Help helpCommand = new Help(this);
            helpCommand.register();

            // Register Gamemode command
            Gamemode gamemodeCommand = new Gamemode(this);
            gamemodeCommand.register();
            
            // Register Spawn command
            Spawn spawnCommand = new Spawn(this, database);
            getCommand("spawn").setExecutor(spawnCommand);
            getCommand("setspawn").setExecutor(spawnCommand);
            

            
            // Register creeper explosion listener for block regeneration
            getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);

            // Hook into Vault
            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                getServer().getServicesManager().register(net.milkbowl.vault2.economy.Economy.class, new VaultEconomyProvider(this), this, ServicePriority.Normal);
                                getLogger().info("Successfully hooked into Vault for economy services.");
            }

            // Register Whois command
            PluginCommand whoisCmd = getCommand("whois");
            if (whoisCmd != null) {
                whoisCmd.setExecutor(new Whois(this));
                // whoisCmd.setTabCompleter(new WhoisTabCompleter(this)); // Placeholder for future tab completer
            } else {
                getLogger().warning("Could not register 'whois' command - not found in plugin.yml?");
            }

            // Register Maintenance command
            getCommand("maintenance").setExecutor(new Maintenance(this));
            
            // Register Maintenance listener
            getServer().getPluginManager().registerEvents(new MaintenanceListener(this), this);

            // Register Invsee command
            getCommand("invsee").setExecutor(new Invsee(this));

            // Register EnchantCommand
            getCommand("enchant").setExecutor(new Enchant(this));

            // Register note commands
            getCommand("note").setExecutor(new NoteCommand(this));
            getCommand("notes").setExecutor(new NotesCommand(this));
            getCommand("unnote").setExecutor(new UnnoteCommand(this));

        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }

    }

    public Config getConfigManager() {
        return configManager;
    }
    
    /**
     * Get the economy manager
     * @return The economy manager
     */
    public Economy getEconomy() {
        return economy;
    }

    public FlyOnRejoinListener getFlyOnRejoinListener() {
        return flyOnRejoinListener;
    }

    @Override
    public void onDisable() {
        if (msgCommand != null) {
            msgCommand.savePendingMessages();
        }
        if (spectatorTeleport != null) {
            spectatorTeleport.saveAllLocations();
        }
        
        // Save all online player inventories
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerInventories inventories = new PlayerInventories(
                player.getInventory().getContents(),
                player.getInventory().getArmorContents(),
                player.getInventory().getItemInOffHand(),
                null, // Creative inventory not implemented
                null,
                null
            );
            database.savePlayerInventories(uuid, inventories);
        }
        
        // Save all player states (flight and spectator gamemode) before shutdown
        flyOnRejoinListener.saveAllPlayersState();
        
        // Close database connection
        if (database != null) {
            database.closeConnection();
        }
        if (creativeManager != null) {
            creativeManager.saveAllInventories();
            creativeManager.cleanup();
        }

        super.onDisable();
    }

    public TP getTpInstance() {
        return tp;
    }

    public boolean isDebugMode() {
        return getConfig().getBoolean("debug-mode", false);
    }
}
