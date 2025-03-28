package net.survivalfun.core.lang;

import net.survivalfun.core.utils.ColorUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class LangManager {
    private final File langFile;
    private final Map<String, Object> messages = new HashMap<>();
    private final Map<String, Object> defaultMessages = new HashMap<>();

    public LangManager(File pluginFolder) {
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin folder");
        }

        this.langFile = new File(pluginFolder, "lang.yml");
        initializeDefaultMessages();
        load();
    }

    private void initializeDefaultMessages() {
        // Basic messages
        defaultMessages.put("error-prefix", "&4Error: ");
        defaultMessages.put("invalid-explode-integer", "&cPlease enter an integer between &7{min}&c and &7{max}&c.");
        defaultMessages.put("explode-success", "&aExploding with power &e{power}&a.");
        defaultMessages.put("explode-success-other", "&aExploding &e{player} &awith power &e{power}&a.");
        defaultMessages.put("not-a-player", "&cYou must be a player to use this command.");
        defaultMessages.put("player-not-found", "&cPlayer &7{name}&c was not found.");
        defaultMessages.put("player-not-online", "&cPlayer &7{name}&c is not online.");
        defaultMessages.put("no-permission", "&cYou do not have permission to use this command!");

        // GC command messages
        defaultMessages.put("commands.gc.no-permission", "&cYou do not have permission to use this command.");
        defaultMessages.put("commands.gc.usage-header", "&a===== SERVER AND MACHINE USAGE =====");
        defaultMessages.put("commands.gc.disk-space-error", "Could not retrieve disk space information");
        defaultMessages.put("commands.gc.system-info-error", "Failed to process system information");
        defaultMessages.put("commands.gc.world-info-error", "Failed to gather world information");

        // GC multi-line message as List
        defaultMessages.put("commands.gc.message", Arrays.asList(
                "&eOS: &f{osName} (Version: {osVersion}, Architecture: {osArch})",
                "&eAvailable Processors: &f{availableProcessors}",
                "&eTotal Disk Space: &f{totalDiskSpace} GB",
                "&eFree Disk Space: &f{freeDiskSpace} GB",
                "&eMax Memory: &f{maxRam} MB",
                "&eTotal Allocated Memory: &f{allocatedMemory} MB",
                "&eFree Memory: &f{freeMemory} MB",
                "&eServer TPS: {tps}",
                "\\n",  // This will be converted to a blank line
                "&6&nWorld Information:",
                "&eLoaded Worlds: &f{worldCount} &7(Total Chunks: &f{totalChunks}&7)",
                "&eTotal Entities: &f{totalEntities} &7| &eTile Entities: &f{totalTileEntities}",
                "&7World Details: {worldInfo}"
        ));
    }

    public void load() {
        // Create default lang.yml if it doesn't exist
        FileConfiguration config;
        if (!langFile.exists()) {
            try {
                if (!langFile.createNewFile()) {
                    throw new IllegalStateException("Could not create lang.yml");
                }
                config = new YamlConfiguration();
                // Write all defaults to the new file
                defaultMessages.forEach(config::set);
                config.save(langFile);
                messages.putAll(defaultMessages);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create lang.yml", e);
            }
        }

        // Load the config file
        config = YamlConfiguration.loadConfiguration(langFile);
        messages.clear();

        // Merge defaults with existing config
        boolean updated = false;
        for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
            if (!config.contains(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                updated = true;
            }
        }

        // Save if we added any new defaults
        if (updated) {
            try {
                config.save(langFile);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save lang.yml", e);
            }
        }

        // Load all messages into memory with proper null checks
        loadMessagesRecursive(config, "");
    }

    private void loadMessagesRecursive(ConfigurationSection section, String path) {
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;

            if (section.isConfigurationSection(key)) {
                ConfigurationSection subSection = section.getConfigurationSection(key);
                if (subSection != null) {
                    loadMessagesRecursive(subSection, fullPath);
                }
            } else if (section.isList(key)) {
                messages.put(fullPath, section.getStringList(key));
            } else {
                messages.put(fullPath, section.getString(key, ""));
            }
        }
    }

    public String get(String key) {
        Object message = messages.getOrDefault(key, defaultMessages.get(key));
        return ColorUtils.colorize(message != null ? message.toString() : "&c[Missing key: " + key + "]");
    }

    public List<String> getList(String key) {
        Object message = messages.getOrDefault(key, defaultMessages.get(key));

        if (message instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object line : (List<?>) message) {
                String lineStr = line.toString();
                if (lineStr.equals("\\n")) {
                    list.add(""); // Add empty line
                } else {
                    list.add(lineStr);
                }
            }
            list.replaceAll(ColorUtils::colorize);
            return list;
        } else if (message != null) {
            // Handle case where it's stored as a single string with newlines
            return Arrays.stream(message.toString().split("\n"))
                    .map(line -> line.equals("\\n") ? "" : line) // Handle \n in single strings
                    .map(ColorUtils::colorize)
                    .collect(Collectors.toList());
        }

        return Collections.singletonList(ColorUtils.colorize("&c[Missing list: " + key + "]"));
    }

    public String format(String message, Map<String, String> placeholders) {
        if (message == null) return null;
        String formatted = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            formatted = formatted.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return ColorUtils.colorize(formatted);
    }
}