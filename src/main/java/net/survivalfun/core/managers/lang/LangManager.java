package net.survivalfun.core.managers.lang;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.utils.ColorUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class LangManager {
    private File langFile;
    private final Map<String, Object> messages = new HashMap<>();
    private final Map<String, Object> defaultMessages = new HashMap<>();
    private final PluginStart plugin;
    private FileConfiguration config;

    public LangManager(File pluginFolder, PluginStart plugin, FileConfiguration config) {
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
        } else {
            plugin.getLogger().warning("Could not find default language file (lang_en.yml) in plugin resources!");
        }

    }

    public void load() {
        messages.clear();
        YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);

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
                return "&cMissing translation: " + key;
            }
        }
        return ColorUtils.colorize(message.toString());
    }

    public List<String> getList(String key) {
        Object message = messages.get(key);
        if (message == null) {
            message = defaultMessages.get(key);
            if (message == null) {
                return Collections.singletonList("&cMissing translation list: " + key);
            }
        }
        if (message instanceof List) {
            return ((List<?>) message).stream()
                    .map(Object::toString)
                    .map(ColorUtils::colorize)
                    .collect(Collectors.toList());
        } else {
            return Collections.singletonList("&cInvalid list translation: " + key);
        }
    }

    public String format(String message, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return ColorUtils.colorize(message);
    }

    public void reload() {
        String lang = config.getString("lang", "en");
        this.langFile = new File(plugin.getDataFolder(), "lang_" + lang + ".yml");
        this.config = plugin.getConfig(); // Reload main config to get updated lang value

        load();
        plugin.getLogger().info("Language file reloaded.");
    }
}