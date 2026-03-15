package net.survivalfun.allium;

import me.croabeast.takion.TakionLib;
import net.survivalfun.allium.command.CommandManager;
import net.survivalfun.allium.config.ConfigManager;
import net.survivalfun.allium.core.ModuleManager;
import net.survivalfun.allium.database.DatabaseManager;
import net.survivalfun.allium.listener.ListenerManager;
import net.survivalfun.allium.message.LanguageManager;
import net.survivalfun.allium.message.MessageManager;
import net.survivalfun.allium.util.AlliumLogger;
import org.bukkit.plugin.java.JavaPlugin;

public final class Allium extends JavaPlugin {

    private static Allium instance;

    private TakionLib takion;
    private AlliumLogger logger;
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ListenerManager listenerManager;

    @Override
    public void onEnable() {
        instance = this;

        logger = new AlliumLogger(this);
        logger.info("Starting Allium...");

        takion = new TakionLib(this);

        configManager = new ConfigManager(this);
        configManager.loadAll();

        languageManager = new LanguageManager(this, configManager.getSyncManager());
        languageManager.load();

        messageManager = new MessageManager(this, languageManager);

        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.connect();
        } catch (Exception e) {
            logger.severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        moduleManager = new ModuleManager(this);
        moduleManager.loadModules();

        commandManager = new CommandManager(this);
        commandManager.registerAll();

        listenerManager = new ListenerManager(this);
        listenerManager.registerAll();

        logger.info("Allium v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) moduleManager.unloadModules();
        if (databaseManager != null) databaseManager.shutdown();

        logger.info("Allium disabled.");
        instance = null;
    }

    public static Allium getInstance() {
        return instance;
    }

    public TakionLib getTakion() {
        return takion;
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
