package net.survivalfun.core;

import net.milkbowl.vault2.chat.Chat;
import net.survivalfun.core.listeners.FlyOnRejoinListener;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.commands.core.Core;
import net.survivalfun.core.commands.utils.*;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.managers.config.ConfigManager;
import net.survivalfun.core.managers.lang.LangManager;
import net.survivalfun.core.listeners.SlimeCushionListener;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.utils.ItemUtils;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.luckperms.api.LuckPerms;

import java.io.File;

public class PluginStart extends JavaPlugin {
    private LangManager langManager;
    private Chat vaultChat = null;
    private LuckPerms luckPerms = null;


    @Override
    public void onEnable() {
        // Initialize ConfigManager
        try {
            // Add config manager
            ConfigManager configManager = new ConfigManager(this);
        } catch (Exception e) {
            getLogger().severe("Failed to initialize ConfigManager: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Configuration file handling
        File configFile = new File(getDataFolder(), "config.yml"); //Get the file
        if (!configFile.exists()) {
            getLogger().info("config.yml not found. Creating default configuration...");
            saveDefaultConfig(); // Saves the default config.yml from your JAR
        }


        //Vault
        RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault2.chat.Chat.class);
        if (chatProvider != null) {
            vaultChat = chatProvider.getProvider();
        }

        //Luckperms
        RegisteredServiceProvider<LuckPerms> luckPermsProvider = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (luckPermsProvider != null) {
            luckPerms = luckPermsProvider.getProvider();
        }

        if (getConfig().getBoolean("enable-chat-formatting", true)) { // Check config
            FormatChatListener formatChatListener = new FormatChatListener(this, vaultChat, luckPerms);
            if (formatChatListener.canEnable()) {
                getServer().getPluginManager().registerEvents(formatChatListener, this);
            } else {
                getLogger().warning("FormatChatListener is disabled due to missing dependencies.");
            }

        } else {
            getLogger().info("Chat formatting is disabled in config.yml.");
        }



    File langFile = new File(getDataFolder(), "lang_en.yml");
        if (!langFile.exists()) {
            saveResource("lang_en.yml", false);
        }


        // Initialize LangManager
        try {
            langManager = new LangManager(getDataFolder(), this, getConfig());
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LangManager: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        super.onEnable();

        // Initialize your managers:
        WorldDefaults worldDefaults = new WorldDefaults(this);
        Core coreCommand = new Core(worldDefaults, this, getConfig(), langManager);

        // Register the Core command:
        getCommand("core").setExecutor(coreCommand);
        getCommand("core").setTabCompleter(coreCommand);


        ItemUtils.initialize();

        // Register events
        new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true);
        getServer().getPluginManager().registerEvents(new FormatChatListener(this, vaultChat, luckPerms), this);
        getServer().getPluginManager().registerEvents(new FlyOnRejoinListener(this), this);
        // Register commands
        try {
            // Register explode command
            getCommand("explode").setExecutor(new Explode(this));
            getCommand("explode").setTabCompleter(new Tab(this));

            // Register gc command
            getCommand("gc").setExecutor(new GC(this));
            getCommand("gc").setTabCompleter(new Tab(this));

            // Register give command
            getCommand("give").setExecutor(new Give(getConfig()));
            getCommand("give").setTabCompleter(new Tab(this));

            // Register ItemDB command
            getCommand("itemdb").setExecutor(new ItemDB());

            // Register Heal command
            getCommand("heal").setExecutor(new Heal());

            // Register Rename command
            getCommand("rename").setExecutor(new Rename(this));

            // Register Lore command
            getCommand("lore").setExecutor(new Lore(this));

            // Register God command
            getCommand("god").setExecutor(new God(this));

            // Register More command
            getCommand("more").setExecutor(new More(this));

            // Register Fly command
            getCommand("fly").setExecutor(new Fly(this));

            // Register Gamemode command
            Gamemode gamemodeCommand = new Gamemode(this);
            PluginCommand gamemodePluginCommand = this.getCommand("gamemode");
            if (gamemodePluginCommand != null) {
                gamemodePluginCommand.setExecutor(gamemodeCommand);
            } else {
                getLogger().severe("Command 'gamemode' not defined in plugin.yml!");
            }




        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }


    public LangManager getLangManager() {
        return langManager;
    }

    public void onDisable() {
    }
}