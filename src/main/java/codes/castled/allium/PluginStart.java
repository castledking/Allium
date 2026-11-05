package codes.castled.allium;

import me.croabeast.takion.TakionLib;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.config.ConfigManager;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.listener.ListenerManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginStart extends JavaPlugin {

    private static PluginStart instance;

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

        GodModeCompatProvider.init(this);

        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.connect();
        } catch (Exception e) {
            logger.severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        commandManager = new CommandManager(this);
        listenerManager = new ListenerManager(this);

        moduleManager = new ModuleManager(this);
        moduleManager.loadModules();

        commandManager.registerAll();
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

    public static PluginStart getInstance() {
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
