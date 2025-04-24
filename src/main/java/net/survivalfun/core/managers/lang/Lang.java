package net.survivalfun.core.managers.lang;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.utils.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Lang {
    private File langFile;
    private final Map<String, Object> messages = new HashMap<>();
    private final Map<String, Object> defaultMessages = new HashMap<>();
    private final PluginStart plugin;
    private FileConfiguration config;

    public Lang(File pluginFolder, PluginStart plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        String lang = config.getString("lang", "en");
        this.langFile = new File(pluginFolder, "lang_" + lang + ".yml");
        initializeDefaultMessages();
        load();
    }

    private void initializeDefaultMessages() {
        InputStream defConfigStream = plugin.getResource("lang_en.yml");
        if (defConfigStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
            defaultMessages.putAll(defaultConfig.getValues(true));

            // Add essential keys if they're missing
            if (!defaultMessages.containsKey("no-permission")) {
                defaultMessages.put("no-permission", "You don't have permission to use /{command}");
                plugin.getLogger().warning("Added missing 'no-permission' key to default messages");
            }
            if (!defaultMessages.containsKey("unknown-command")) {
                defaultMessages.put("unknown-command", "Unknown command: /{command}");
                plugin.getLogger().warning("Added missing 'unknown-command' key to default messages");
            }
            if (!defaultMessages.containsKey("unknown-command-suggestion")) {
                defaultMessages.put("unknown-command-suggestion", "Unknown command: /{command}. Did you mean: /{suggestion}?");
                plugin.getLogger().warning("Added missing 'unknown-command-suggestion' key to default messages");
            }
            if (!defaultMessages.containsKey("error-prefix")) {
                defaultMessages.put("error-prefix", "§c[Error]");
                plugin.getLogger().warning("Added missing 'error-prefix' key to default messages");
            }
        } else {
            plugin.getLogger().warning("Could not find default language file (lang_en.yml) in plugin resources!");

            // Add essential default messages even if the file is missing
            defaultMessages.put("no-permission", "You don't have permission to use /{command}");
            defaultMessages.put("unknown-command", "Unknown command: /{command}");
            defaultMessages.put("unknown-command-suggestion", "Unknown command: /{command}. Did you mean: /{suggestion}?");
            defaultMessages.put("error-prefix", "§c[Error]");
            plugin.getLogger().info("Added essential default messages");
        }
    }

    public void load() {
        messages.clear();

        // Create the language file if it doesn't exist
        if (!langFile.exists()) {
            try {
                String lang = langFile.getName().replace("lang_", "").replace(".yml", "");
                plugin.getLogger().info("Creating language file for " + lang);

                InputStream defaultLangStream = plugin.getResource("lang_" + lang + ".yml");
                if (defaultLangStream == null) {
                    defaultLangStream = plugin.getResource("lang_en.yml");
                }

                if (defaultLangStream != null) {
                    java.nio.file.Files.copy(defaultLangStream, langFile.toPath());
                } else {
                    langFile.createNewFile();
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to create language file: " + e.getMessage());
            }
        }

        // Load the language file with UTF-8 encoding to properly handle special characters
        YamlConfiguration langConfig;
        try {
            // Use InputStreamReader with UTF-8 encoding for user language file too
            langConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(java.nio.file.Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
            );
            // Debug: Print all keys in the language file
            plugin.getLogger().info("Language keys loaded from " + langFile.getName() + ":");
            for (String key : langConfig.getKeys(true)) {
                plugin.getLogger().info(" - " + key);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load language file with UTF-8 encoding: " + e.getMessage());
            // Fallback to standard loading
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        }

        // Check for missing keys and add them
        boolean needsUpdate = false;

        // Check for unknown-command key
        if (!langConfig.contains("unknown-command")) {
            langConfig.set("unknown-command", "Unknown command. Type /help for a list of commands.");
            needsUpdate = true;
            plugin.getLogger().info("Added missing 'unknown-command' key to " + langFile.getName());
        }

        // Add more checks for other missing keys here if needed

        // Save the file if any updates were made
        if (needsUpdate) {
            try {
                langConfig.save(langFile);
                plugin.getLogger().info("Updated " + langFile.getName() + " with missing keys");

                // Reload the config after saving
                langConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(java.nio.file.Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
                );
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save updated language file: " + e.getMessage());
            }
        }

        // Load messages from the language file
        loadMessagesRecursive(langConfig, "");

        // Add default messages that are missing in the language file
        for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
            if (!messages.containsKey(entry.getKey())) {
                messages.put(entry.getKey(), entry.getValue());
            }
        }
    }


    private void loadMessagesRecursive(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            Object value = section.get(key);

            if (value instanceof ConfigurationSection) {
                loadMessagesRecursive((ConfigurationSection) value, fullPath);
            } else {
                messages.put(fullPath, value);
            }
        }
    }

    public String get(String key) {
        Object message = messages.get(key);
        if (message == null) {
            message = defaultMessages.get(key);
            if (message == null) {
                // Special case for error-prefix to avoid infinite recursion
                if (key.equals("error-prefix")) {
                    return Text.parseColors("&c[Error]");
                }
                return Text.formatErrorMessage("Missing translation", key, this);
            }
        }

        // Convert the message to string and ensure it's properly processed for hex colors
        String messageStr = message.toString();
        return Text.parseColors(messageStr);
    }

    public List<String> getList(String key) {
        Object message = messages.get(key);
        if (message == null) {
            message = defaultMessages.get(key);
            if (message == null) {
                return Collections.singletonList(Text.parseColors("&cMissing translation list: " + key));
            }
        }
        if (message instanceof List) {
            return ((List<?>) message).stream()
                    .map(Object::toString)
                    .map(Text::parseColors)
                    .collect(Collectors.toList());
        } else {
            return Collections.singletonList(Text.parseColors("&cInvalid list translation: " + key));
        }
    }

    public String format(String message, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return Text.parseColors(message);
    }

    public void reload() {
        String lang = config.getString("lang", "en");
        this.langFile = new File(plugin.getDataFolder(), "lang_" + lang + ".yml");
        this.config = plugin.getConfig(); // Reload main config to get updated lang value

        load();
        plugin.getLogger().info("Language file reloaded.");
    }
}
