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

    /**
     * Carries a legacy config.yml into this Allium's layout on its first load, and moves legacy
     * sections out of a config.yml that an earlier version carried over whole. The legacy file is
     * kept untouched as config.legacy.yml (an earlier carry-over keeps config.yml.before-legacy-cleanup).
     */
    private void migrateInstalledLegacyConfig() {
        Path folder = plugin.getDataFolder().toPath();
        Path file = folder.resolve("config.yml");
        try {
            var current = LegacyConfigMigration.read(file);
            var defaults = bundled("config.yml");
            boolean firstLoad = LegacyConfigMigration.legacy(current);
            var leftovers = firstLoad ? java.util.List.<String>of() : LegacyConfigMigration.leftovers(current, defaults);
            if (!firstLoad && leftovers.isEmpty()) return;
            YamlConfiguration source = current;
            if (!firstLoad) {
                source = new YamlConfiguration();
                for (String key : leftovers) source.set(key, current.get(key));
                // A carried-over legacy file kept these leaves under sections this Allium also has.
                for (String path : LegacyConfigMigration.RENAMED.keySet()) {
                    if (current.isSet(path) && !defaults.isSet(path)) source.set(path, current.get(path));
                }
                for (String path : LegacyConfigMigration.RETIRED.keySet()) {
                    if (path.contains(".") && current.isSet(path) && !defaults.isSet(path)) source.set(path, current.get(path));
                }
            }
            var plan = LegacyConfigMigration.plan(source, defaults);
            YamlConfiguration target;
            if (firstLoad) {
                LegacyConfigMigration.backup(file, file.resolveSibling(LegacyConfigMigration.SOURCE));
                target = defaults;
                LegacyConfigMigration.apply(plan.into("config.yml"), target, null);
                target.set("config-version", ConfigVersionManager.CURRENT_VERSION);
            } else {
                LegacyConfigMigration.backup(file, file.resolveSibling(LegacyConfigMigration.CLEANUP_BACKUP));
                if (!Files.exists(file.resolveSibling(LegacyConfigMigration.SOURCE))) {
                    Files.copy(file, file.resolveSibling(LegacyConfigMigration.SOURCE));
                }
                target = current;
                LegacyConfigMigration.apply(plan.into("config.yml"), target, defaults);
                LegacyConfigMigration.removeLegacy(target, plan, defaults);
            }
            for (String other : java.util.List.of("autorestart.yml", "explosions.yml")) {
                var moves = plan.into(other);
                if (moves.isEmpty()) continue;
                Path otherFile = folder.resolve(other);
                if (!Files.exists(otherFile)) plugin.saveResource(other, false);
                var otherConfig = LegacyConfigMigration.read(otherFile);
                var result = LegacyConfigMigration.apply(moves, otherConfig, bundled(other));
                if (!result.changed().isEmpty()) YamlFiles.replace(otherFile, otherConfig);
            }
            YamlFiles.replace(file, target);
            Path report = folder.resolve(LegacyConfigMigration.REPORT);
            Files.createDirectories(report.getParent());
            Files.writeString(report, LegacyConfigMigration.report(plan, (firstLoad
                    ? "Legacy Allium's config.yml, carried into this Allium's config.yml on "
                    : "Legacy sections moved out of config.yml on ") + java.time.LocalDate.now() + "."));
            plugin.logger().info((firstLoad ? "Carried the legacy config.yml into this Allium's layout: "
                    : "Moved legacy sections out of config.yml: ") + plan.moves().size() + " setting(s) kept, "
                    + plan.retired().size() + " retired or handed to their owner. The legacy file is "
                    + LegacyConfigMigration.SOURCE + "; see " + LegacyConfigMigration.REPORT + ".");
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalStateException("Could not read or carry over config.yml", error);
        }
    }

    /** {@code /allium import config [apply]}: carries legacy settings into the current config again, keeping local edits. */
    public void importLegacyConfig(boolean apply, Consumer<String> reply) throws IOException, InvalidConfigurationException {
        Path folder = plugin.getDataFolder().toPath();
        Path file = folder.resolve("config.yml");
        Path sourceFile = codes.castled.allium.importer.LegacyTidy.source(folder, LegacyConfigMigration.SOURCE);
        Path formatBackup = file.resolveSibling("config.yml.before-format");
        if (!Files.isRegularFile(sourceFile) && Files.isRegularFile(formatBackup)
                && LegacyConfigMigration.legacy(LegacyConfigMigration.read(formatBackup))) {
            sourceFile = formatBackup;
        }
        if (!Files.isRegularFile(sourceFile)) {
            reply.accept("&cPut the pre-recode settings in plugins/Allium/" + LegacyConfigMigration.SOURCE + " first.");
            return;
        }
        var defaults = bundled("config.yml");
        var plan = LegacyConfigMigration.plan(LegacyConfigMigration.read(sourceFile), defaults);
        var target = LegacyConfigMigration.read(file);
        var result = LegacyConfigMigration.apply(plan.into("config.yml"), target, defaults);
        Map<String, YamlConfiguration> others = new java.util.LinkedHashMap<>();
        Map<String, LegacyConfigMigration.Result> otherResults = new java.util.LinkedHashMap<>();
        for (String other : java.util.List.of("autorestart.yml", "explosions.yml")) {
            var moves = plan.into(other);
            if (moves.isEmpty()) continue;
            Path otherFile = folder.resolve(other);
            var otherConfig = Files.exists(otherFile) ? LegacyConfigMigration.read(otherFile) : bundled(other);
            others.put(other, otherConfig);
            otherResults.put(other, LegacyConfigMigration.apply(moves, otherConfig, bundled(other)));
        }
        int changed = result.changed().size() + otherResults.values().stream().mapToInt(r -> r.changed().size()).sum();
        int kept = result.kept().size() + otherResults.values().stream().mapToInt(r -> r.kept().size()).sum();
        reply.accept("&7Legacy config: &f" + changed + " &7settings to write, &f" + kept + " &7custom settings kept, &f"
                + plan.retired().size() + " &7retired or owned elsewhere.");
        plugin.logger().info("Legacy config import: config.yml " + result.changed() + ", kept " + result.kept()
                + "; " + otherResults);
        if (!apply || changed == 0) return;
        LegacyConfigMigration.backup(file, file.resolveSibling("config.yml.before-legacy-import"));
        if (!result.changed().isEmpty()) YamlFiles.normalize(file, target);
        for (var entry : others.entrySet()) {
            if (otherResults.get(entry.getKey()).changed().isEmpty()) continue;
            Path otherFile = folder.resolve(entry.getKey());
            if (Files.exists(otherFile)) {
                LegacyConfigMigration.backup(otherFile, otherFile.resolveSibling(entry.getKey() + ".before-legacy-import"));
            } else {
                plugin.saveResource(entry.getKey(), false);
            }
            YamlFiles.normalize(otherFile, entry.getValue());
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
