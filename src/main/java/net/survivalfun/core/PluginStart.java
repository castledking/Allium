package net.survivalfun.core;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.milkbowl.vault2.chat.Chat;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.listeners.security.FlyOnRejoinListener;
import net.survivalfun.core.listeners.chat.FormatChatListener;
import net.survivalfun.core.commands.core.Core;
import net.survivalfun.core.commands.utils.*;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.config.CommandBlockerConfig;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.core.Luck;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.listeners.jobs.SlimeCushionListener;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.utils.Item;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
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
    private ProtocolManager protocolManager;
    private CommandBlockerConfig commandBlockerConfig;

    private void initializeCommandBlocker() {
        // Only proceed if ProtocolLib is available
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! Command blocking features will be disabled.");
            return;
        }

        // Initialize ProtocolManager
        this.protocolManager = ProtocolLibrary.getProtocolManager();

        // Create the command blocker config
        this.commandBlockerConfig = new CommandBlockerConfig(this, luck);
        commandBlockerConfig.saveDefaultConfig();

        // Register command packet listeners
        registerCommandPacketListeners();


        getLogger().info("Command Blocker initialized successfully!");
    }

    private void registerCommandPacketListeners() {
        // Command packet listener (for blocking commands)
        protocolManager.addPacketListener(new PacketAdapter(this,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.CHAT_COMMAND) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                String command = event.getPacket().getStrings().read(0);

                // Get the first part of the command (the command name)
                String commandName = command.split(" ")[0].toLowerCase();

                if (commandBlockerConfig.isCommandBlocked(event.getPlayer(), commandName)) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(commandBlockerConfig.getBlockedCommandMessage());
                }
            }
        });

        // Tab complete packet listener
        protocolManager.addPacketListener(new PacketAdapter(this,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.TAB_COMPLETE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                String text = event.getPacket().getStrings().read(0);

                // Only handle command tab completion (starts with /)
                if (text.startsWith("/")) {
                    String input = text.substring(1).toLowerCase();
                    String commandName = input.split(" ")[0];

                    if (commandBlockerConfig.isTabCompleteBlocked(event.getPlayer(), commandName)) {
                        event.setCancelled(true);
                    }
                }
            }
        });
    }

    // Add this getter for the command blocker config
    public CommandBlockerConfig getCommandBlockerConfig() {
        return commandBlockerConfig;
    }

    // Add this method to reload the command blocker config
    public void reloadCommandBlockerConfig() {
        if (commandBlockerConfig != null) {
            commandBlockerConfig.reloadConfig();
            getLogger().info("Command Blocker configuration reloaded");
        }
    }

    // Add this method to PluginStart.java
    public void reloadChatFormatter() {
        // Only proceed if chat formatting is enabled in config
        if (getConfig().getBoolean("enable-chat-formatting", true)) {
            // If we already have a chat formatter, unregister it
            if (formatChatListener != null) {
                // Unregister the old listener
                org.bukkit.event.HandlerList.unregisterAll(formatChatListener);
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

        //Command Blocker
        initializeCommandBlocker();

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

        // Then pass it to CommandBlockerConfig instead of passing the raw LuckPerms instance
        commandBlockerConfig = new CommandBlockerConfig(this, luck);



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

        File langFile = new File(getDataFolder(), "lang_en.yml");
        if (!langFile.exists()) {
            saveResource("lang_en.yml", false);
        }

        // Initialize LangManager
        try {
            langManager = new Lang(getDataFolder(), this, getConfig());
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

        Item.initialize();

        // Register events
        SlimeCushionListener slimeCushionListener = new SlimeCushionListener(this, 2.0, 0.5, 0.2, 2.0, true, "&aThe slime cushioned your fall!", true);
        getServer().getPluginManager().registerEvents(slimeCushionListener, this);
        new SpectatorTeleport(this, new NV(this));

        // Disable command block output (which includes unknown command messages)
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        }
        // Initialize database
        this.database = new Database(this);
        new FlyOnRejoinListener(this);
        this.creativeManager = new CreativeManager(this);
        this.spectatorTeleport = new SpectatorTeleport(this, new NV(this));



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
            getCommand("itemdb").setExecutor(new ItemDB());

            // Register Heal command
            getCommand("heal").setExecutor(new Heal(langManager));
            getCommand("heal").setTabCompleter(new Tab(this));

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


            // Register Gamemode command
            Gamemode gamemodeCommand = new Gamemode(this);
            PluginCommand gamemodePluginCommand = this.getCommand("gamemode");
            if (gamemodePluginCommand != null) {
                gamemodePluginCommand.setExecutor(gamemodeCommand);
                getCommand(gamemodePluginCommand.getName()).setTabCompleter(new Tab(this));
            } else {
                getLogger().severe("Command 'gamemode' not defined in plugin.yml!");
            }

        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }


    public Config getConfigManager() {
        return configManager;
    }

    public Lang getLangManager() {
        return langManager;
    }
    @Override
    public void onDisable() {
        // Clean up ProtocolLib packet listeners
        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }
        // Close database connection
        if (database != null) {
            database.closeConnection();
        }

        if (spectatorTeleport != null) {
            spectatorTeleport.saveAllLocations();
        }

        super.onDisable();

    }
}
