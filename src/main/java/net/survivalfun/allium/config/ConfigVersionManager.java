package net.survivalfun.allium.config;

import net.survivalfun.allium.Allium;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class ConfigVersionManager {

    public static final int CURRENT_VERSION = 1;

    private final Allium plugin;
    private final BackupManager backupManager;
    private final List<Migration> migrations = new ArrayList<>();

    public ConfigVersionManager(Allium plugin, BackupManager backupManager) {
        this.plugin = plugin;
        this.backupManager = backupManager;
        registerMigrations();
    }

    private void registerMigrations() {
        // Example: register(1, 2, config -> { config.set("new.key", "value"); });
        // Add future migrations here as the config schema evolves.
    }

    public void register(int fromVersion, int toVersion, Consumer<FileConfiguration> action) {
        migrations.add(new Migration(fromVersion, toVersion, action));
    }

    public boolean migrate() {
        FileConfiguration config = plugin.getConfig();
        int currentVersion = config.getInt("config-version", 0);

        if (currentVersion >= CURRENT_VERSION) return false;

        if (currentVersion > 0) {
            plugin.logger().info("Config version " + currentVersion + " -> " + CURRENT_VERSION
                    + ". Backing up before migration...");
            backupManager.backupAll();
        }

        migrations.sort(Comparator.comparingInt(m -> m.fromVersion));

        int version = currentVersion;
        for (Migration m : migrations) {
            if (m.fromVersion == version) {
                try {
                    plugin.logger().info("Running migration: v" + m.fromVersion + " -> v" + m.toVersion);
                    m.action.accept(config);
                    version = m.toVersion;
                } catch (Exception e) {
                    plugin.logger().severe("Migration v" + m.fromVersion + " -> v" + m.toVersion
                            + " failed: " + e.getMessage());
                    return false;
                }
            }
        }

        config.set("config-version", CURRENT_VERSION);
        plugin.saveConfig();
        plugin.logger().info("Config migrated to version " + CURRENT_VERSION + ".");
        return true;
    }

    private static final class Migration {
        final int fromVersion;
        final int toVersion;
        final Consumer<FileConfiguration> action;

        Migration(int fromVersion, int toVersion, Consumer<FileConfiguration> action) {
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.action = action;
        }
    }
}
