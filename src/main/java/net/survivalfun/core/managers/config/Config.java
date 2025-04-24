package net.survivalfun.core.managers.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class Config {
    private final File configFile;
    private final Map<String, Object> defaultConfigValues = new HashMap<>();
    private final JavaPlugin plugin;

    public Config(JavaPlugin plugin) {
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
        defaultConfigValues.put("enable-chat-formatting", true);
        defaultConfigValues.put("block-unicode", true);
        defaultConfigValues.put("chat-format.default", "<prefix> &a<player>&f: &f<message>");


        // General settings
        defaultConfigValues.put("allow-unsafe-enchants", false);

        // Explode command settings
        defaultConfigValues.put("explode-command.default", 2);
        defaultConfigValues.put("explode-command.max", 10);
        defaultConfigValues.put("explode-command.min", 1);

        // World-specific defaults
        defaultConfigValues.put("world-defaults.world.send-command-feedback", false);
        // Debug
        defaultConfigValues.put("debug-mode", false);
    }

    public void load() {
        // Check if config file exists
        if (!configFile.exists()) {
            // Create config from template
            createDefaultConfig();
        } else {
            // Config exists, reload it
            plugin.reloadConfig();

            // Check for missing values and add them
            updateConfig();
        }

        plugin.getLogger().info("Configuration loaded from disk");
    }

    private void createDefaultConfig() {
        try {
            // First try to copy the config from the plugin's resources
            InputStream templateStream = plugin.getResource("config.yml");
            if (templateStream != null) {
                // Copy the template file to the plugin folder
                Files.copy(templateStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                templateStream.close();
                plugin.getLogger().info("Created config.yml from template with all comments preserved");
            } else {
                // If no template exists, create a basic config
                if (!configFile.createNewFile()) {
                    throw new IOException("Could not create config.yml");
                }

                // Create a new config with default values
                FileConfiguration config = new YamlConfiguration();
                for (Map.Entry<String, Object> entry : defaultConfigValues.entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }

                // Add the commented-out groups section manually
                String commentedGroups =
                        "  # Group-specific chat formats\n" +
                                "  # Uncomment and modify these as needed\n" +
                                "  groups:\n" +
                                "    #default: \"&7<player>&f: <message>\" # Default Group Format\n" +
                                "    #admin: \"&4[Admin] &c<player>&f: <message>\"\n" +
                                "    #moderator: \"&2[Mod] &a<player>&f: <message>\"\n" +
                                "    #vip: \"&6[VIP] &e<player>&f: <message>\"";

                // Save the config with a header comment
                config.options().header(
                        "############################################################\n" +
                                "# +------------------------------------------------------+ #\n" +
                                "# |                SFCore Configuration                   | #\n" +
                                "# +------------------------------------------------------+ #\n" +
                                "############################################################\n\n" +
                                "# This is the main configuration file for SFCore.\n" +
                                "# For more information, visit: https://github.com/castledking/SFCore\n"
                );

                // Save the basic config
                config.save(configFile);

                // Now we need to manually add the commented groups section
                // Read the file, find the chat-format section, and insert our commented groups
                addCommentedSections(commentedGroups);

                plugin.getLogger().info("Created basic config.yml with default values and commented sections");
            }

            // Load the newly created config
            plugin.reloadConfig();

        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create config.yml", e);
            throw new RuntimeException("Failed to create config.yml", e);
        }
    }

    private void addCommentedSections(String commentedGroups) {
        try {
            // Read the current file
            String content = new String(Files.readAllBytes(configFile.toPath()));

            // Find the chat-format section
            int chatFormatIndex = content.indexOf("chat-format:");
            if (chatFormatIndex != -1) {
                // Find the end of the default line
                int defaultLineEnd = content.indexOf("\n", chatFormatIndex);
                if (defaultLineEnd != -1) {
                    // Insert our commented groups after the default line
                    String newContent = content.substring(0, defaultLineEnd + 1) +
                            commentedGroups +
                            content.substring(defaultLineEnd + 1);

                    // Write the modified content back to the file
                    Files.write(configFile.toPath(), newContent.getBytes());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to add commented sections to config.yml", e);
        }
    }

    private void updateConfig() {
        FileConfiguration config = plugin.getConfig();
        boolean updated = false;

        // Check for missing values
        for (Map.Entry<String, Object> entry : defaultConfigValues.entrySet()) {
            if (!config.contains(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                updated = true;
                plugin.getLogger().info("Added missing config value: " + entry.getKey());
            }
        }

        // Save if updates were made
        if (updated) {
            try {
                // We need to use the config's save method to preserve comments
                config.save(configFile);
                plugin.reloadConfig(); // Reload after saving
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save updated config.yml", e);
            }
        }
    }

    public Object get(String key) {
        return plugin.getConfig().get(key, defaultConfigValues.get(key));
    }

    public boolean getBoolean(String key) {
        return plugin.getConfig().getBoolean(key,
                defaultConfigValues.containsKey(key) ? (Boolean) defaultConfigValues.get(key) : false);
    }

    public int getInt(String key) {
        return plugin.getConfig().getInt(key,
                defaultConfigValues.containsKey(key) ? (Integer) defaultConfigValues.get(key) : 0);
    }

    public String getString(String key, String s) {
        return plugin.getConfig().getString(key,
                defaultConfigValues.containsKey(key) ? (String) defaultConfigValues.get(key) : "");
    }
}
