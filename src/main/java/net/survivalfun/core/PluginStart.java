package net.survivalfun.core;
import net.survivalfun.core.chat.FormatChatListener;
import net.survivalfun.core.commands.core.Heal;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.commands.utils.GC;
import net.survivalfun.core.commands.core.Give;
import net.survivalfun.core.commands.utils.ItemDB;
import net.survivalfun.core.commands.utils.Tab;
import net.survivalfun.core.lang.LangManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class PluginStart extends JavaPlugin {
    private LangManager langManager;

    @Override
    public void onEnable() {
        // Save default configs
        saveDefaultConfig();
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        
        loadConfigValues();
        
        // Initialize LangManager
        try {
            langManager = new LangManager(getDataFolder());
        } catch (Exception e) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Register events
        getServer().getPluginManager().registerEvents(new FormatChatListener(), this);

        // Register commands
        try {
            // Register explode command
            getCommand("explode").setExecutor(new Explode(this));
            getCommand("explode").setTabCompleter(new Tab(this));
            
            // Register gc command
            getCommand("gc").setExecutor(new GC(this));
            getCommand("gc").setTabCompleter(new Tab(this));
            
            // Register give command
            getCommand("give").setExecutor(new Give());
            getCommand("give").setTabCompleter(new Tab(this));

            // Register ItemDB command
            getCommand("itemdb").setExecutor(new ItemDB());

            // Register Heal command
            getCommand("heal").setExecutor(new Heal());
        } catch (Exception e) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        config.getDouble("fall-threshold", 5.0);
    }

    public LangManager getLangManager() {
        return langManager;
    }

    public void onDisable() {
    }
}
