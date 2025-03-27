package net.survivalfun.core.lang;

import net.survivalfun.core.utils.ColorUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class LangManager {
    private FileConfiguration config;
    private final File langFile; // Path to lang.yml
    private final Map<String, String> messages = new HashMap<>(); // Stores loaded messages

    // Default messages for fallback
    private final Map<String, String> defaultMessages = new HashMap<>();

    // Constructor
    public LangManager(File pluginFolder) {

        // Ensure plugin folder exists
        if (!pluginFolder.exists()) {
            pluginFolder.mkdirs();
        }

        // Initialize lang.yml file
        this.langFile = new File(pluginFolder, "lang.yml");
        
        initializeDefaultMessages();
        load(); // Load or create the language file
    }

    /**
     * Initializes default messages for fallback.
     */
    private void initializeDefaultMessages() {
        defaultMessages.put("error-prefix", "&4Error: ");
        defaultMessages.put("invalid-explode-integer", "&cPlease enter an integer between &7{min}&c and &7{max}&c.");
        defaultMessages.put("explode-success", "&aExploding with power &e{power}&a.");
        defaultMessages.put("explode-success-other", "&aExploding &e{player} &awith power &e{power}&a.");
        defaultMessages.put("not-a-player", "&cYou must be a player to use this command.");
        defaultMessages.put("player-not-found", "&cPlayer &7{name}&c was not found.");
        defaultMessages.put("player-not-online", "&cPlayer &7{name}&c is not online.");
        defaultMessages.put("no-permission", "&cYou do not have permission to use this command!");
        defaultMessages.put("commands.gc.no_permission", "&cYou do not have permission to use this command.");
        defaultMessages.put("commands.gc.usage_header", "&a===== SERVER AND MACHINE USAGE =====");
        defaultMessages.put("commands.gc.message", "&eOS: &f{osName} (Version: {osVersion}, Architecture: {osArch})\n" +
                "&eAvailable Processors: &f{availableProcessors}\n" +
                "&eTotal Disk Space: &f{totalDiskSpace} GB\n" +
                "&eFree Disk Space: &f{freeDiskSpace} GB\n" +
                "&eMax Memory: &f{maxRam} MB\n" +
                "&eTotal Allocated Memory: &f{allocatedMemory} MB\n" +
                "&eFree Memory: &f{freeMemory} MB\n" +
                "&eServer TPS: &f{tps}");
    }

    /**
     * Loads the lang.yml file, falling back to default messages if needed.
     * Also updates the lang.yml file with missing keys.
     */
    public void load() {
        if (!langFile.exists()) {
            return;
        }

        // Load config from lang.yml
        config = YamlConfiguration.loadConfiguration(langFile);
        messages.clear();

        // Add missing default messages
        boolean updated = false;
        for (Map.Entry<String, String> entry : defaultMessages.entrySet()) {
            if (!config.contains(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                updated = true;
            }
        }

        // Save the updated config if new defaults were added
        if (updated) {
            try {
                config.save(langFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Populate messages from lang.yml
        for (String key : config.getKeys(true)) {
            if (config.isString(key)) {
                messages.put(key, config.getString(key));
            } else if (config.isList(key)) {
                // For lists, we'll store them as a special format in the messages map
                List<String> list = config.getStringList(key);
                messages.put(key, String.join("\n", list));
            }
        }
    }

    /**
     * Get a single message by key.
     * Falls back to defaultMessages if the key is missing.
     *
     * @param key The key for the message.
     * @return The corresponding message or a placeholder if missing.
     */
    public String get(String key) {
        if (messages.containsKey(key)) {
            return ColorUtils.colorize(messages.get(key)); // Found in lang.yml
        }

        if (defaultMessages.containsKey(key)) {
            return ColorUtils.colorize(defaultMessages.get(key)); // Fallback to defaultMessages
        }

        return ColorUtils.colorize("&c[Missing language key: " + key + "]");
    }

    public List<String> getList(String key) {
        // First check if we have a stored message
        if (messages.containsKey(key)) {
            String storedMessage = messages.get(key);
            // If the message contains newlines, it's a list
            if (storedMessage.contains("\n")) {
                List<String> list = Arrays.asList(storedMessage.split("\n"));
                list.replaceAll(ColorUtils::colorize);
                return list;
            }
        }

        // If not found in messages, try to get directly from config
        if (config.isList(key)) {
            List<String> messages = config.getStringList(key);
            messages.replaceAll(ColorUtils::colorize);
            return messages;
        }

        // If not a list, try to get as a string
        if (config.isString(key)) {
            String message = config.getString(key);
            return Collections.singletonList(ColorUtils.colorize(message));
        }

        // If no value found, return empty list
        return Collections.emptyList();
    }

    /**
     * Replaces placeholders in messages.
     *
     * @param message      The message to process.
     * @param placeholders A map of placeholders and their replacements.
     * @return The processed message with placeholders replaced.
     */
    public String format(String message, Map<String, String> placeholders) {
        if (message == null) return null;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return ColorUtils.colorize(message);
    }
}