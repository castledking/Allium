package codes.castled.allium.config;

import codes.castled.allium.PluginStart;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class ResourceSyncManager {

    private final PluginStart plugin;

    public ResourceSyncManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public int syncResource(String resourcePath) {
        InputStream bundled = plugin.getResource(resourcePath);
        if (bundled == null) return 0;

        File installedFile = new File(plugin.getDataFolder(), resourcePath);
        if (!installedFile.exists()) {
            plugin.saveResource(resourcePath, false);
            return -1;
        }

        FileConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.logger().severe("Failed to read bundled resource: " + resourcePath);
            return 0;
        }

        FileConfiguration installed = YamlConfiguration.loadConfiguration(installedFile);
        int added = mergeDefaults(defaults, installed, "");

        if (added > 0) {
            try {
                installed.save(installedFile);
                plugin.logger().info("Synced " + added + " missing key(s) into " + resourcePath);
            } catch (IOException e) {
                plugin.logger().severe("Failed to save synced file: " + resourcePath);
            }
        }

        return added;
    }

    private int mergeDefaults(ConfigurationSection defaults, ConfigurationSection target, String path) {
        int added = 0;
        Set<String> keys = defaults.getKeys(false);

        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;

            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
                if (defaultSection == null) continue;

                if (!target.isConfigurationSection(key)) {
                    if (!target.contains(key)) {
                        target.createSection(key);
                        added++;
                    } else {
                        continue;
                    }
                }

                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection != null) {
                    added += mergeDefaults(defaultSection, targetSection, fullPath);
                }
            } else {
                if (!target.contains(key)) {
                    target.set(key, defaults.get(key));
                    added++;
                    plugin.logger().debug("Inserted missing key: " + fullPath);
                }
            }
        }

        return added;
    }
}
