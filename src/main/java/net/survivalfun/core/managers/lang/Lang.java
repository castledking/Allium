package net.survivalfun.core.managers.lang;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Lang {
    private File langFile;
    private final Map<String, Object> messages = new HashMap<>();
    private final Map<String, Object> defaultMessages = new HashMap<>();
    private final PluginStart plugin;
    private FileConfiguration config;
    private Locale currentLocale;
    private String configLangCode;
    // Map of short language codes to full language codes
    private static final Map<String, String> LANGUAGE_SHORTCUTS = new HashMap<>();

    // List of supported languages with their display names
    private static final Map<String, String> SUPPORTED_LANGUAGES = new LinkedHashMap<>();
    static {
        // Initialize language shortcuts
        LANGUAGE_SHORTCUTS.put("en", "en_US");
        LANGUAGE_SHORTCUTS.put("fr", "fr_FR");
        LANGUAGE_SHORTCUTS.put("de", "de_DE");
        LANGUAGE_SHORTCUTS.put("es", "es_ES");
        LANGUAGE_SHORTCUTS.put("it", "it_IT");
        LANGUAGE_SHORTCUTS.put("pt", "pt_BR");
        LANGUAGE_SHORTCUTS.put("ru", "ru_RU");
        LANGUAGE_SHORTCUTS.put("zh", "zh_CN");
        LANGUAGE_SHORTCUTS.put("ja", "ja_JP");
        LANGUAGE_SHORTCUTS.put("ko", "ko_KR");
        LANGUAGE_SHORTCUTS.put("nl", "nl_NL");
        LANGUAGE_SHORTCUTS.put("pl", "pl_PL");
        LANGUAGE_SHORTCUTS.put("sv", "sv_SE");
        LANGUAGE_SHORTCUTS.put("tr", "tr_TR");
        LANGUAGE_SHORTCUTS.put("uk", "uk_UA");

        // Initialize supported languages with display names
        SUPPORTED_LANGUAGES.put("en_US", "English (US)");
        SUPPORTED_LANGUAGES.put("fr_FR", "French (France)");
        SUPPORTED_LANGUAGES.put("de_DE", "German (Germany)");
        SUPPORTED_LANGUAGES.put("es_ES", "Spanish (Spain)");
        SUPPORTED_LANGUAGES.put("it_IT", "Italian (Italy)");
        SUPPORTED_LANGUAGES.put("pt_BR", "Portuguese (Brazil)");
        SUPPORTED_LANGUAGES.put("ru_RU", "Russian (Russia)");
        SUPPORTED_LANGUAGES.put("zh_CN", "Chinese (Simplified)");
        SUPPORTED_LANGUAGES.put("ja_JP", "Japanese (Japan)");
        SUPPORTED_LANGUAGES.put("ko_KR", "Korean (Korea)");
        SUPPORTED_LANGUAGES.put("nl_NL", "Dutch (Netherlands)");
        SUPPORTED_LANGUAGES.put("pl_PL", "Polish (Poland)");
        SUPPORTED_LANGUAGES.put("sv_SE", "Swedish (Sweden)");
        SUPPORTED_LANGUAGES.put("tr_TR", "Turkish (Turkey)");
        SUPPORTED_LANGUAGES.put("uk_UA", "Ukrainian (Ukraine)");
    }



    public Lang(File pluginFolder, PluginStart plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        this.langFile = new File(pluginFolder, "lang.yml");
        // Get the language code from config, resolving any shortcuts
        String rawLangCode = config.getString("lang", "en");
        this.configLangCode = resolveLanguageCode(rawLangCode);

        // Log the language being used
        plugin.getLogger().info("Using language: " + getLanguageName(this.configLangCode) + " (" + this.configLangCode + ")");

        // Log available languages for reference if using a non-standard language
        if (!SUPPORTED_LANGUAGES.containsKey(this.configLangCode)) {
            logAvailableLanguages();
        }

        this.currentLocale = parseLocale(configLangCode);

        // Set up the language file (always named "lang.yml")
        this.langFile = new File(pluginFolder, "lang.yml");

        // Initialize default messages
        initializeDefaultMessages();

        // Load language file
        load();
    }
    /**
     * Resolve a language code, supporting shortcuts like "en" for "en_US"
     */
    private String resolveLanguageCode(String langCode) {
        if (langCode == null || langCode.isEmpty()) {
            return "en_US"; // Default to English if unspecified
        }

        // If it's a short code, resolve it to the full code
        if (LANGUAGE_SHORTCUTS.containsKey(langCode.toLowerCase())) {
            return LANGUAGE_SHORTCUTS.get(langCode.toLowerCase());
        }

        // If it already contains an underscore, assume it's a full code
        if (langCode.contains("_")) {
            return langCode;
        }

        // For any other short code not in our map, try to guess the full code
        // by appending the country code (uppercase version of language code)
        return langCode.toLowerCase() + "_" + langCode.toUpperCase();
    }


    /**
     * Parse a language code into a Locale object
     */
    private Locale parseLocale(String langCode) {
        try {
            // Handle language codes with country variants (e.g., "en_US", "fr_FR")
            if (langCode.contains("_")) {
                String[] parts = langCode.split("_");
                if (parts.length >= 2) {
                    return new Locale(parts[0], parts[1]);
                }
            }

            // Handle simple language codes (e.g., "en", "fr")
            return new Locale(langCode);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid language code: " + langCode + ". Falling back to English.");
            return Locale.ENGLISH;
        }
    }

    /**
     * Log the list of available languages to the console
     */
    private void logAvailableLanguages() {
        plugin.getLogger().info("Available languages:");
        List<String> languageInfo = new ArrayList<>();

        for (Map.Entry<String, String> entry : SUPPORTED_LANGUAGES.entrySet()) {
            String langCode = entry.getKey();
            String langName = entry.getValue();
            String shortCode = langCode.split("_")[0];

            // Add information about this language
            languageInfo.add("  - " + langName + ": '" + langCode + "' or '" + shortCode + "'");
        }

        // Sort and log the language list
        Collections.sort(languageInfo);
        for (String info : languageInfo) {
            plugin.getLogger().info(info);
        }
    }



    private void initializeDefaultMessages() {
        InputStream defConfigStream = plugin.getResource("lang.yml");
        if (defConfigStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
            defaultMessages.putAll(defaultConfig.getValues(true));

            plugin.getLogger().info("Loaded " + defaultMessages.size() + " default messages from lang.yml");
        } else {
            plugin.getLogger().warning("Could not find default language file (lang.yml) in plugin resources!");

            // Add all default messages even if the file is missing
            defaultMessages.put("core-prefix", "<gradient:#B2C3EE:#ADF3FD>Core</gradient>");
            defaultMessages.put("error-prefix", "<gradient:#CD2B39:#B82E3A>Error:</gradient><red>");
            defaultMessages.put("unknown-locale", "Translation for &7{key} &8(&7{lang} &8| &7{country}&8) &ccould not be found!");
            defaultMessages.put("creative-manager.restrict", "You are not allowed to {0} in creative mode!");
            defaultMessages.put("creative-manager.blacklist", "{0} is not allowed in creative mode!");

            // Command settings
            defaultMessages.put("command-usage", "&7Usage: /{cmd} {args}");
            defaultMessages.put("unknown-command", "Unknown command: /{cmd}. Do /help for a list of commands.");
            defaultMessages.put("unknown-command-suggestion", "Unknown command: /{cmd}. Did you mean: /{suggestion}?");
            defaultMessages.put("not-a-player", "You must be a player to use this command.");
            defaultMessages.put("player-not-found", "Player &7{name} was not found.");
            defaultMessages.put("player-not-online", "Player &7{name} is not online.");
            defaultMessages.put("invalid", "Invalid {arg}! Please check your syntax.");
            defaultMessages.put("no-permission", "You do not have permission to use /{cmd}");
            defaultMessages.put("hold-item", "&cYou must be holding an item to {modify} it.");
            defaultMessages.put("cooldown", "You must wait {time} seconds before using {cmd} again.");
            defaultMessages.put("contact-admin", "An unexpected error has occurred. Please contact an administrator.");

            // GC command
            defaultMessages.put("gc.usage-header", "&a===== SERVER AND MACHINE USAGE =====");
            defaultMessages.put("gc.disk-space-error", "&cCould not retrieve disk space information.");
            defaultMessages.put("gc.system-info-error", "&cFailed to process system information.");
            defaultMessages.put("gc.world-info-error", "&cFailed to gather world information.");
            defaultMessages.put("gc.message", Arrays.asList(
                    "&eOS: &f{osName} (Version: {osVersion}, Architecture: {osArch})",
                    "&eAvailable Processors: &f{availableProcessors}",
                    "&eTotal Disk Space: &f{totalDiskSpace} GB",
                    "&eFree Disk Space: &f{freeDiskSpace} GB",
                    "&eMax Memory: &f{maxRam} MB",
                    "&eTotal Allocated Memory: &f{allocatedMemory} MB",
                    "&eFree Memory: &f{freeMemory} MB",
                    "&eServer TPS: &f{tps}",
                    "&6&nWorld Information:",
                    "&eLoaded Worlds: &f{worldCount} &7(Total Chunks: &f{totalChunks}&7)",
                    "&eTotal Entities: &f{totalEntities} &7| &eTile Entities: &f{totalTileEntities}",
                    "&7World Details: &f{worldInfo}"
            ));

            // Gamemode command
            defaultMessages.put("gamemode.invalid", "&cInvalid gamemode: &7{mode}");
            defaultMessages.put("gamemode.switch", "&aSwitched to &e{mode} &agamemode.");
            defaultMessages.put("gamemode.switch-other", "&aSwitched &e{name}&a to &e{mode} &agamemode.");
            defaultMessages.put("gamemode.reset", "&aYour gamemode has been reset to &e{mode}&a.");

            // Reload command
            defaultMessages.put("reload.reload-success", "&aConfiguration reloaded.");
            defaultMessages.put("reload.reload-failure", "Unable to reload configuration! Check console for details.");

            // Explode command
            defaultMessages.put("explode.invalid", "Please enter an integer between &7{min} and &7{max}.");
            defaultMessages.put("explode.self", "&aExploding with power &e{power}&a.");
            defaultMessages.put("explode.other", "&aExploding &e{name} &awith power &e{power}&a.");

            // Heal command
            defaultMessages.put("heal.self", "&aYou have been healed.");
            defaultMessages.put("heal.other", "&aYou have healed &e{name}&a.");

            // Feed command
            defaultMessages.put("feed.self", "&aYou have been sated.");
            defaultMessages.put("feed.other", "&aYou have satiated &e{name}&a.");

            // Give command
            defaultMessages.put("give.inventory-full", "Inventory full, make some space before giving more items.");
            defaultMessages.put("give.success", "&aYou have given yourself {amount} {item}.");
            defaultMessages.put("give.success-other", "&aYou have given &e{name} &a{amount} {item}.");
            defaultMessages.put("give.receive", "&aYou have received {amount} {item}.");
            defaultMessages.put("give.invalid-item", "Item {item} not found.");
            defaultMessages.put("give.unsafe-level", "Enchantment {enchant} exceeds maximum level. Limiting to ({maxLevel}).");
            defaultMessages.put("give.unsafe-enchant", "Enchantment {enchant} can't be applied to {item}!");
            defaultMessages.put("give.conflicting-enchants", "{enchant} cannot be combined with {enchant2} on {item}!");

            // Fly command
            defaultMessages.put("fly.enable", "&aEnabled flight.");
            defaultMessages.put("fly.enable-other", "&aSet flying mode to &nenabled&a for &e{name}&a.");
            defaultMessages.put("fly.disable", "&aDisabled flight.");
            defaultMessages.put("fly.disable-other", "Set flying mode to &c&ndisabled&a for &e{name}&a.");

            // God command
            defaultMessages.put("god.enable", "&aEnabled god mode.");
            defaultMessages.put("god.enable-other", "&aSet god mode to &nenabled&a for &e{name}&a.");
            defaultMessages.put("god.disable", "&cDisabled god mode.");
            defaultMessages.put("god.disable-other", "Set god mode to &c&ndisabled&a for &e{name}&a.");

            // Rename command (incomplete in the provided YAML)
            defaultMessages.put("rename.success", "&aRenamed item to {name}");
            defaultMessages.put("rename.failure", "This item cannot be renamed.");

            // Lore command
            defaultMessages.put("lore.add", "&aAdded lore line &e{text}&a.");
            defaultMessages.put("lore.insert", "&aInserted lore line at index {index}: &e{text}&a.");
            defaultMessages.put("lore.remove", "&aRemoved lore line: &e{line}&a.");
            defaultMessages.put("lore.clear", "&aLore cleared successfully.");
            defaultMessages.put("lore.failure", "This item's lore cannot be modified.");

            // NV Command
            defaultMessages.put("nv.toggle", "&a{0} night vision.");
            defaultMessages.put("nv.spectator-only", "You must be in spectator mode to use night vision.");

            // Msg command
            defaultMessages.put("msg.sent", "&7(To &f{recipient)&7) &r{message}");
            defaultMessages.put("msg.received", "&7(From &f{sender}&7) &r{message}");
            defaultMessages.put("msg.reply", "You have no one to reply to.");
            defaultMessages.put("msg.spy", "&8[&7SPY&8] &f{name} &7sent message to &f{target}&8: &7{message}");
            defaultMessages.put("msg.mail-sent", "&aMail sent to &e{name}&a.");
            defaultMessages.put("msg.mail-empty", "&6You have no new messages.");
            defaultMessages.put("msg.mail-clear", "&aAll messages cleared.");
            defaultMessages.put("msg.mail-remind", "&aYou have &e{n} &anew messages.");
            defaultMessages.put("msg.mail-header", "&6=== {n}{title} &6===");
            defaultMessages.put("msg.mail-format", "&e{i}. &6{sender}&e: &r{message}");
            defaultMessages.put("msg.mail-footer", "&6=== [{date}] &6===");

            plugin.getLogger().info("Added all default messages");
        }

    }

    public void load() {
        messages.clear();

        // Check if language file exists
        if (!langFile.exists()) {
            createLanguageFile();
        }

        // Load the language file
        try {
            YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
            );

            // Get the actual language code from the file (or use the current one if not specified)
            String langCode = langConfig.getString("lang", configLangCode);

            // Check if the langfile setting in config.yml differs from what we have in the file
            String configLangCode = plugin.getConfig().getString("lang");
            if (configLangCode != null && !configLangCode.equals(langCode)) {
                plugin.getLogger().info("Note: Config 'langfile' setting (" + configLangCode +
                        ") differs from the language file's code (" + langCode + ")");
                plugin.getLogger().info("To change language, delete lang.yml and set 'langfile' in config.yml");
            }

            // Load all messages
            loadMessagesRecursive(langConfig, "");
            plugin.getLogger().info("Loaded language file: lang.yml (" + langCode + ")");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load language file", e);
        }

        // Add default messages for any missing keys
        boolean missingMessages = false;
        for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
            if (!messages.containsKey(entry.getKey())) {
                messages.put(entry.getKey(), entry.getValue());
                missingMessages = true;
            }
        }

        // Log if any default messages were added
        if (missingMessages) {
            plugin.getLogger().info("Added missing messages from defaults");

            // Update the language file with the missing messages
            try {
                YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                boolean updated = false;

                for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
                    if (!langConfig.contains(entry.getKey())) {
                        langConfig.set(entry.getKey(), entry.getValue());
                        updated = true;
                    }
                }

                if (updated) {
                    langConfig.save(langFile);
                    plugin.getLogger().info("Updated language file with missing message keys");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to update language file with missing messages", e);
            }
        }
    }

    /**
     * Create a new language file based on the language code in the config
     */
    private void createLanguageFile() {
        try {
            // Ensure parent directory exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            // Check if we have this language resource
            InputStream langResource = plugin.getResource("lang.yml");

            if (langResource != null) {
                // Copy the resource to the plugin folder
                Files.copy(langResource, langFile.toPath());
                plugin.getLogger().info("Created language file from resource: " + configLangCode);
            } else {
                // Create a language file with default messages
                YamlConfiguration config = new YamlConfiguration();

                // Add metadata
                config.set("lang", configLangCode);
                config.set("lang", getLanguageName(configLangCode));

                // Add all default messages
                for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }

                // Save the config
                config.save(langFile);
                plugin.getLogger().info("Created new language file with " + configLangCode + " defaults");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create language file", e);
        }
    }

    /**
     * Get a human-readable language name from a language code
     */
    private String getLanguageName(String langCode) {
        // First check our predefined map
        if (SUPPORTED_LANGUAGES.containsKey(langCode)) {
            return SUPPORTED_LANGUAGES.get(langCode);
        }

        // If not in our map, try to generate a name from the locale
        try {
            Locale locale = parseLocale(langCode);
            return locale.getDisplayLanguage(Locale.ENGLISH);
        } catch (Exception e) {
            return langCode; // Fall back to just using the code
        }
    }


    /**
     * Recursively load messages from a configuration section
     */
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



    /**
     * Get a message string from the language file
     */
    public String get(String key) {
        Object message = messages.get(key);
        if (message == null) {
            // Try default messages
            message = defaultMessages.get(key);

            if (message == null) {
                // Special case for error-prefix to avoid infinite recursion
                if (key.equals("error-prefix")) {
                    return "&c[Error]";
                }
                return "&c[Missing translation: " + key + "]";
            }
        }

        // Convert to string and parse colors
        return Text.parseColors(message.toString());
    }


    /**
     * Get a list of messages from the language file
     */
    public List<String> getList(String key) {
        Object message = messages.get(key);
        if (message == null) {
            message = defaultMessages.get(key);
            if (message == null) {
                return Collections.singletonList("&c[Missing translation list: " + key + "]");
            }
        }

        if (message instanceof List) {
            return ((List<?>) message).stream()
                    .map(Object::toString)
                    .map(Text::parseColors)
                    .collect(Collectors.toList());
        } else {
            return Collections.singletonList("&c[Invalid list translation: " + key + "]");
        }
    }


    public String format(String message, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return Text.parseColors(message);
    }
    /**
     * Get the current locale being used
     */
    public Locale getCurrentLocale() {
        return currentLocale;
    }

    /**
     * Check if a language file exists
     */
    public boolean doesLanguageFileExist() {
        return langFile.exists();
    }


    /**
     * Reload the language system
     * This will check if the langfile setting in config.yml has changed
     */
    public void reload() {
        // Get potentially new language setting from config, resolving any shortcuts
        String rawLangCode = config.getString("lang", "en");
        String newConfigLangCode = resolveLanguageCode(rawLangCode);

        // If language changed and file exists, inform admin they need to delete the file
        if (!newConfigLangCode.equals(configLangCode) && langFile.exists()) {
            plugin.getLogger().info("Language setting changed from " + configLangCode + " to " + newConfigLangCode);
            plugin.getLogger().info("To apply the new language, delete the lang.yml file and reload");
            this.configLangCode = newConfigLangCode;
            this.currentLocale = parseLocale(newConfigLangCode);
        } else if (!langFile.exists()) {
            // If file doesn't exist, update the locale and create the file
            this.configLangCode = newConfigLangCode;
            this.currentLocale = parseLocale(newConfigLangCode);
        }

        // Reload default messages and language file
        defaultMessages.clear();
        initializeDefaultMessages();
        load();
        plugin.getLogger().info("Language system reloaded");
    }

    /**
     * Get a list of supported language codes
     */
    public List<String> getSupportedLanguages() {
        return new ArrayList<>(SUPPORTED_LANGUAGES.keySet());
    }

    /**
     * Get a map of supported language codes and their names
     */
    public Map<String, String> getSupportedLanguageMap() {
        return new HashMap<>(SUPPORTED_LANGUAGES);
    }
}

