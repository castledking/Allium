package codes.castled.allium.config;

import codes.castled.allium.PluginStart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ConfigManager {

    private final PluginStart plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();

    private BackupManager backupManager;
    private ConfigVersionManager versionManager;
    private ResourceSyncManager syncManager;

    public ConfigManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        migrateInstalledLegacyConfig();
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

    private void migrateInstalledLegacyConfig() {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            var original = LegacyConfigMigration.read(file);
            if (original.isSet("config-version") || !LegacyConfigMigration.legacy(original)) return;
            LegacyConfigMigration.backup(file, file.resolveSibling(LegacyConfigMigration.SOURCE));
            var migrated = LegacyConfigMigration.read(file);
            LegacyConfigMigration.merge(original, migrated, bundled("config.yml"));
            migrated.set("config-version", ConfigVersionManager.CURRENT_VERSION);
            YamlFiles.normalize(file, migrated);
            plugin.logger().info("Preserved the pre-recode config in " + LegacyConfigMigration.SOURCE + ".");
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalStateException("Could not read or preserve config.yml", error);
        }
    }

    public void importLegacyConfig(boolean apply, Consumer<String> reply) throws IOException, InvalidConfigurationException {
        Path folder = plugin.getDataFolder().toPath();
        Path file = folder.resolve("config.yml");
        Path sourceFile = folder.resolve(LegacyConfigMigration.SOURCE);
        Path formatBackup = file.resolveSibling("config.yml.before-format");
        if (!Files.isRegularFile(sourceFile) && Files.isRegularFile(formatBackup)
                && LegacyConfigMigration.legacy(LegacyConfigMigration.read(formatBackup))) {
            sourceFile = formatBackup;
        }
        if (!Files.isRegularFile(sourceFile)) {
            reply.accept("&cPut the pre-recode settings in plugins/Allium/" + LegacyConfigMigration.SOURCE + " first.");
            return;
        }
        var source = LegacyConfigMigration.read(sourceFile);
        var target = LegacyConfigMigration.read(file);
        var result = LegacyConfigMigration.merge(source, target, bundled("config.yml"));
        var restartSource = source.getConfigurationSection("auto-restart");
        Path restartFile = folder.resolve("autorestart.yml");
        var restartDefaults = bundled("autorestart.yml");
        var restart = Files.exists(restartFile) ? LegacyConfigMigration.read(restartFile) : bundled("autorestart.yml");
        var restartResult = restartSource == null ? new LegacyConfigMigration.Result(java.util.List.of(), java.util.List.of())
                : LegacyConfigMigration.merge(restartSource, restart, restartDefaults);
        int changed = result.changed().size() + restartResult.changed().size();
        int kept = result.kept().size() + restartResult.kept().size();
        reply.accept("&7Legacy config: &f" + changed + " &7settings to write, &f" + kept + " &7custom settings kept.");
        plugin.logger().info("Legacy config import paths: " + result.changed() + "; kept: " + result.kept()
                + "; autorestart.yml: " + restartResult.changed() + "; kept: " + restartResult.kept());
        if (!apply || changed == 0) return;
        LegacyConfigMigration.backup(file, file.resolveSibling("config.yml.before-legacy-import"));
        if (!restartResult.changed().isEmpty() && Files.exists(restartFile)) {
            LegacyConfigMigration.backup(restartFile, restartFile.resolveSibling("autorestart.yml.before-legacy-import"));
        }
        if (!result.changed().isEmpty()) YamlFiles.normalize(file, target);
        if (!restartResult.changed().isEmpty()) {
            if (!Files.exists(restartFile)) plugin.saveResource("autorestart.yml", false);
            YamlFiles.normalize(restartFile, restart);
        }
        reply.accept("&aSaved legacy settings. Restart Allium's server to activate the imported configuration.");
    }

    private YamlConfiguration bundled(String name) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
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
