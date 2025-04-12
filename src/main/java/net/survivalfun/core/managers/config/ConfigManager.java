package net.survivalfun.core.managers.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class ConfigManager {
    private final File configFile;
    private final Map<String, Object> defaultConfigValues = new HashMap<>();
    private final JavaPlugin plugin; // Store a reference to your main plugin class

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File pluginFolder = plugin.getDataFolder();
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin folder");
        }

        this.configFile = new File(pluginFolder, "config.yml");
        initializeDefaultConfigValues();
        load();
    }

    private void initializeDefaultConfigValues() {
        // Chat formatting settings
        defaultConfigValues.put("enable-chat-formatting", true); // Enable/disable chat formatting
        defaultConfigValues.put("chat-format.default", "<prefix> &a<player>&f: &f<message>"); // Default chat format
        defaultConfigValues.put("chat-format.groups.default", "&7<player>&f: <message>"); // Default group format
        defaultConfigValues.put("chat-format.groups.admin", "&4[Admin] &c<player>&f: <message>"); // Admin group format
        defaultConfigValues.put("chat-format.groups.moderator", "&2[Mod] &a<player>&f: <message>"); // Moderator group format
        defaultConfigValues.put("chat-format.groups.vip", "&6[VIP] &e<player>&f: <message>"); // VIP group format

        // General settings
        defaultConfigValues.put("allow-unsafe-enchants", false); // Allow unsafe enchantments

        // Explode command settings
        defaultConfigValues.put("explode-command.default", 2); // Default explode radius
        defaultConfigValues.put("explode-command.max", 10); // Max explode radius
        defaultConfigValues.put("explode-command.min", 1); // Min explode radius

        // World-specific defaults
        defaultConfigValues.put("world-defaults.world.send-command-feedback", false); // Disable command feedback in 'world'
    }

    public void load() {
        FileConfiguration config = plugin.getConfig(); // Use the plugin's getConfig()
        if (!configFile.exists()) {
            try {
                if (!configFile.createNewFile()) {
                    throw new IllegalStateException("Could not create config.yml");
                }

                // Write all defaults to the new file
                defaultConfigValues.forEach(config::set);
                plugin.saveConfig(); // Use the plugin's saveConfig()
                return;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create config.yml", e);
            }
        }
        plugin.reloadConfig(); // Reload in case of external changes
        config = plugin.getConfig();

        // Merge missing default values
        boolean updated = false;
        for (Map.Entry<String, Object> entry : defaultConfigValues.entrySet()) {
            if (!config.contains(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                updated = true;
            }
        }

        // Save if any defaults were added
        if (updated) {
            plugin.saveConfig(); // Use the plugin's saveConfig()
        }
    }


    public Object get(String key) {
        return plugin.getConfig().get(key, defaultConfigValues.get(key)); // Use plugin.getConfig()
    }

}