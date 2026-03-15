package net.survivalfun.allium.config;

import net.survivalfun.allium.Allium;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ConfigManager {

    private final Allium plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();

    private BackupManager backupManager;
    private ConfigVersionManager versionManager;
    private ResourceSyncManager syncManager;

    public ConfigManager(Allium plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        configs.put("config", plugin.getConfig());

        backupManager = new BackupManager(plugin);
        syncManager = new ResourceSyncManager(plugin);
        versionManager = new ConfigVersionManager(plugin, backupManager);

        syncManager.syncResource("config.yml");
        plugin.reloadConfig();
        configs.put("config", plugin.getConfig());

        versionManager.migrate();

        backupManager.pruneOldBackups(10);
    }

    public FileConfiguration loadConfig(String name) {
        File file = new File(plugin.getDataFolder(), name + ".yml");
        if (!file.exists()) {
            if (plugin.getResource(name + ".yml") != null) {
                plugin.saveResource(name + ".yml", false);
            }
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        configs.put(name, config);
        return config;
    }

    public void saveConfig(String name) {
        FileConfiguration config = configs.get(name);
        if (config == null) return;
        File file = new File(plugin.getDataFolder(), name + ".yml");
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.logger().severe("Failed to save " + name + ".yml: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig(String name) {
        return configs.get(name);
    }

    public void reloadAll() {
        configs.clear();
        loadAll();
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public ConfigVersionManager getVersionManager() {
        return versionManager;
    }

    public ResourceSyncManager getSyncManager() {
        return syncManager;
    }
}
