package net.survivalfun.core;

import net.milkbowl.vault2.chat.Chat;
import net.survivalfun.core.listeners.jobs.MailRemindListener;
import net.survivalfun.core.listeners.jobs.SummonMessageListener;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.listeners.security.FlyOnRejoinListener;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.commands.core.Core;
import net.survivalfun.core.commands.utils.*;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.core.Luck;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.listeners.jobs.SlimeCushionListener;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Item;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.luckperms.api.LuckPerms;

import java.io.File;

public class PluginStart extends JavaPlugin {
    private Lang langManager;
    private Chat vaultChat = null;
    private LuckPerms luckPerms = null;
    private Config configManager;
    private FormatChatListener formatChatListener;
    private Database database;
    private CreativeManager creativeManager;
    private SpectatorTeleport spectatorTeleport;
    private Explode explodeCommand;
    private Luck luck;
    private Msg msgCommand;
    private Spy spyCommand;
    private TP tpCommand;
    private CommandManager commandManager;

    // Add this method to PluginStart.java
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

    // Add this method to PluginStart.java
    public Explode getExplodeCommand() {
        return explodeCommand; // You'll need to store the Explode instance in a field
    }



    @Override
    public void onEnable() {
        this.configManager = new Config(this);
        //Vault
        RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault2.chat.Chat.class);
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

        luck = new Luck(this);

        // Initialize chat formatter
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

        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }

        // Initialize LangManager
        try {
            langManager = new Lang(getDataFolder(), this, getConfig());
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LangManager: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize database
        this.database = new Database(this);

        super.onEnable();

        // Register events
        SlimeCushionListener slimeCushionListener = new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true);
        getServer().getPluginManager().registerEvents(slimeCushionListener, this);
        new SpectatorTeleport(this, new NV(this));


        getServer().getPluginManager().registerEvents(new SummonMessageListener(), this);


        // Disable command block output (which includes unknown command messages)
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        }

        // Security Listeners
        new FlyOnRejoinListener(this);
        this.creativeManager = new CreativeManager(this);
        this.spectatorTeleport = new SpectatorTeleport(this, new NV(this));
        commandManager = new CommandManager(this);

        // Initialize your managers:
        WorldDefaults worldDefaults = new WorldDefaults(this);
        Core coreCommand = new Core(worldDefaults, this, getConfig(), langManager, commandManager);
        // Register the Core command:
        getCommand("core").setExecutor(coreCommand);
        getCommand("core").setTabCompleter(coreCommand);


        Item.initialize();

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
            getCommand("give").setExecutor(new Give(this, getConfig(), langManager));
            getCommand("give").setTabCompleter(new Tab(this));

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

            this.msgCommand = new Msg(this, new Spy(this));
            getCommand("msg").setExecutor(msgCommand);
            getCommand("msg").setTabCompleter(msgCommand);

            getServer().getPluginManager().registerEvents(msgCommand, this);
            getServer().getPluginManager().registerEvents(new MailRemindListener(msgCommand), this);


            // Register TP commands
            this.tpCommand = new TP(this);
            getCommand("tp").setExecutor(tpCommand);
            getCommand("tp").setTabCompleter(tpCommand);

            // Register Help command

            Help helpCommand = new Help(this, langManager);
            helpCommand.register();

            // Register Gamemode command and its variants
            getCommand("gamemode").setExecutor(new Gamemode(this));
            getCommand("gamemode").setTabCompleter(new Tab(this));

        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }

    }

    public Msg getMsgCommand() {
        return msgCommand;
    }



    public Config getConfigManager() {
        return configManager;
    }

    public Lang getLangManager() {
        return langManager;
    }
    @Override
    public void onDisable() {

        if(msgCommand != null){
            msgCommand.savePendingMessages();
        }
        if (spectatorTeleport != null) {
            spectatorTeleport.saveAllLocations();
        }
        // Close database connection
        if (database != null) {
            database.closeConnection();
        }
        if (creativeManager != null) {
            creativeManager.cleanup();
        }


        super.onDisable();

    }
}
