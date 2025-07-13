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
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.listeners.jobs.SlimeCushionListener;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.economy.Economy;
import net.survivalfun.core.managers.economy.VaultEconomyProvider;
import net.survivalfun.core.managers.core.Placeholder;
import net.survivalfun.core.managers.core.Skull;

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
    private Placeholder placeholder;

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

    public Placeholder getPlaceholder() {
        return placeholder;
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

        // Initialize PlaceholderAPI expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholder = new Placeholder();
            this.placeholder.register();
            getLogger().info("PlaceholderAPI expansion registered successfully.");
        } else {
            getLogger().warning("PlaceholderAPI not found! Custom placeholders will not be available.");
        }

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
            e.printStackTrace();
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
        }

        // Initialize LegacyID system
        try {
            LegacyID.initialize(this);
            getLogger().info("LegacyID system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LegacyID system: " + e.getMessage());
            e.printStackTrace();
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
        getServer().getPluginManager().registerEvents(this.creativeManager, this);
        this.spectatorTeleport = new SpectatorTeleport(this, new NV(this));
        commandManager = new CommandManager(this);

        // Register creeper explosion listener
        getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);

        // Register fireball explosion listener
        getServer().getPluginManager().registerEvents(new FireballExplosionListener(this), this);

        WorldDefaults worldDefaults = new WorldDefaults(this);
        Core coreCommand = new Core(worldDefaults, this, getConfig(), commandManager, creativeManager);
        getCommand("core").setExecutor(coreCommand);
        getCommand("core").setTabCompleter(coreCommand);

        Item.initialize(this);

        // Register commands
        try {
            this.explodeCommand = new Explode(this);
            getCommand("explode").setExecutor(explodeCommand);
            getCommand("explode").setTabCompleter(new Tab(this));

            getCommand("gc").setExecutor(new GC(this));
            getCommand("gc").setTabCompleter(new Tab(this));

            getCommand("give").setExecutor(new Give(this));
            getCommand("give").setTabCompleter(new Tab(this));
            
            getCommand("skull").setExecutor(new Skull(this));

            getCommand("itemdb").setExecutor(new ItemDB(this));

            getCommand("heal").setExecutor(new Heal(langManager, getConfig(), this));
            getCommand("heal").setTabCompleter(new Tab(this));

            getCommand("feed").setExecutor(new Feed(langManager, getConfig(), this));
            getCommand("feed").setTabCompleter(new Tab(this));

            getCommand("rename").setExecutor(new Rename(this));
            getCommand("rename").setTabCompleter(new Tab(this));

            this.tpCommand = new TP(this);
            String[] tpCommands = {"tp", "tphere", "tpahere", "tpa", "tpcancel", "tpacancel", "tpaccept", "tpdeny", "tppet", "tppos", "tptoggle", "top", "bottom", "otp", "tpmob", "tpentity", "tpent", "teleportmob", "tpe", "tpm"};
            for (String cmdName : tpCommands) {
                PluginCommand command = getCommand(cmdName);
                if (command != null) {
                    command.setExecutor(this.tpCommand);
                    command.setTabCompleter(this.tpCommand);
                } else {
                    getLogger().warning("Failed to register teleport command: " + cmdName + " - not found in plugin.yml or already registered by another plugin?");
                }
            }

            PluginCommand balanceCmd = getCommand("balance");
            if (balanceCmd != null) {
                Balance balanceExecutor = new Balance(this, economy);
                balanceCmd.setExecutor(balanceExecutor);
                balanceCmd.setTabCompleter(balanceExecutor);
                getLogger().info("Registered 'balance' command");
            } else {
                getLogger().warning("Could not register 'balance' command not found in plugin.yml?");
            }
            
            PluginCommand balCmd = getCommand("bal");
            if (balCmd != null) {
                Balance balanceExecutor = new Balance(this, economy);
                balCmd.setExecutor(balanceExecutor);
                balCmd.setTabCompleter(balanceExecutor);
                getLogger().info("Registered 'bal' command");
            } else {
                getLogger().warning("Could not register 'bal' command - not found in plugin.yml?");
            }
            
            PluginCommand payCmd = getCommand("pay");
            if (payCmd != null) {
                Pay payExecutor = new Pay(this, economy);
                payCmd.setExecutor(payExecutor);
                payCmd.setTabCompleter(payExecutor);
                getLogger().info("Registered 'pay' command");
            } else {
                getLogger().warning("Could not register 'pay' command - not found in plugin.yml?");
            }
            
            PluginCommand balTopCmd = getCommand("baltop");
            if (balTopCmd != null) {
                BalTop balTopExecutor = new BalTop(this, economy);
                balTopCmd.setExecutor(balTopExecutor);
                balTopCmd.setTabCompleter(balTopExecutor);
                getLogger().info("Registered 'baltop' command");
            } else {
                getLogger().warning("Could not register 'baltop' command - not found in plugin.yml?");
            }
            
            PluginCommand moneyCmd = getCommand("money");
            if (moneyCmd != null) {
                Money moneyExecutor = new Money(this, economy);
                moneyCmd.setExecutor(moneyExecutor);
                moneyCmd.setTabCompleter(moneyExecutor);
                getLogger().info("Registered 'money' command");
            } else {
                getLogger().warning("Could not register 'money' command - not found in plugin.yml?");
            }
            
            getCommand("lore").setExecutor(new Lore(this));
            getCommand("lore").setTabCompleter(new Tab(this));

            getCommand("god").setExecutor(new God(this));
            getCommand("god").setTabCompleter(new Tab(this));

            getCommand("more").setExecutor(new More(this));

            getCommand("fly").setExecutor(new Fly(this));
            getCommand("fly").setTabCompleter(new Tab(this));

            getCommand("nv").setExecutor(new NV(this));

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

            getServer().getPluginManager().registerEvents(msgCommand, this);
            getServer().getPluginManager().registerEvents(new MailRemindListener(msgCommand), this);

            Help helpCommand = new Help(this);
            helpCommand.register();

            Gamemode gamemodeCommand = new Gamemode(this);
            gamemodeCommand.register();
            
            Spawn spawnCommand = new Spawn(this, database);
            getCommand("spawn").setExecutor(spawnCommand);
            getCommand("setspawn").setExecutor(spawnCommand);
            
            getServer().getPluginManager().registerEvents(new CreeperExplosionListener(this), this);

            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                getServer().getServicesManager().register(net.milkbowl.vault2.economy.Economy.class, new VaultEconomyProvider(this), this, ServicePriority.Normal);
                getLogger().info("Successfully hooked into Vault for economy services.");
            }

            PluginCommand whoisCmd = getCommand("whois");
            if (whoisCmd != null) {
                whoisCmd.setExecutor(new Whois(this));
            } else {
                getLogger().warning("Could not register 'whois' command - not found in plugin.yml?");
            }

            getCommand("maintenance").setExecutor(new Maintenance(this));
            
            getServer().getPluginManager().registerEvents(new MaintenanceListener(this), this);

            getCommand("invsee").setExecutor(new Invsee(this));

            getCommand("enchant").setExecutor(new Enchant(this));

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
        
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerInventories inventories = new PlayerInventories(
                player.getInventory().getContents(),
                player.getInventory().getArmorContents(),
                player.getInventory().getItemInOffHand(),
                null,
                null,
                null
            );
            database.savePlayerInventories(uuid, inventories);
        }
        
        flyOnRejoinListener.saveAllPlayersState();
        
        if (database != null) {
            database.closeConnection();
        }
        if (creativeManager != null) {
            creativeManager.saveAllInventories();
            creativeManager.cleanup();
        }

        // Unregister PlaceholderAPI expansion
        if (placeholder != null && placeholder.isRegistered()) {
            placeholder.unregister();
            getLogger().info("PlaceholderAPI expansion unregistered.");
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