package codes.castled.allium;

import codes.castled.allium.command.CommandManager;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.config.ConfigManager;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.listener.ListenerManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class PluginStart extends JavaPlugin {

    private static PluginStart instance;

    private AlliumLogger logger;
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private ItemService itemService;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ListenerManager listenerManager;

    @Override
    public void onEnable() {
        instance = this;

        logger = new AlliumLogger(this);
        logger.info("Starting Allium...");

        configManager = new ConfigManager(this);
        configManager.loadAll();

        languageManager = new LanguageManager(this, configManager.getSyncManager());
        languageManager.load();

        messageManager = new MessageManager(this, languageManager);

        GodModeCompatProvider.init(this);

        itemService = new ItemService();
        registerCoreItems(itemService);

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

    private static void registerCoreItems(ItemService service) {
        service.registerAlliumItem(new AlliumItemDefinition(
                "handcuffs", Material.IRON_SHOVEL, "allium:handcuffs", 1001, "§fHandcuffs", Map.of()));
        service.registerAlliumItem(new AlliumItemDefinition(
                "mob_disarmer", Material.GOLDEN_HOE, "allium:mob_disarmer", 1002, "§6Mob Disarmer", Map.of("charges", "3")));
        service.registerAlliumItem(new AlliumItemDefinition(
                "phantom_obliterator", Material.BLAZE_ROD, "allium:phantom_obliterator", 1003, "§bPhantom Obliterator", Map.of("charges", "3")));
        service.registerAlliumItem(new AlliumItemDefinition(
                "item_renamer", Material.NAME_TAG, "allium:item_renamer", 1004, "§eItem Renamer", Map.of()));
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

    public ItemService getItemService() {
        return itemService;
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
