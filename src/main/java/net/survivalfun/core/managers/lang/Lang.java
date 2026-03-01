package net.survivalfun.core.managers.lang;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class Lang {
    private static final String CURRENT_LANG_VERSION = "0.1.4a";
    private static final List<String> DEPRECATED_KEYS = Arrays.asList(
        "pay-invalid-amount",
        "pay-negative-amount",
        "give.error"
    );

    private File langFile;
    private final Map<String, Object> messages = new HashMap<>();
    private final Map<String, Object> defaultMessages = new LinkedHashMap<>();
    private final PluginStart plugin;
    private FileConfiguration config;
    private Locale currentLocale;
    private String configLangCode;
    private YamlConfiguration langConfig;
    private YamlConfiguration defaultLangConfig;
    // Map of short language codes to full language codes

    /**
     * Retrieves a style string from the language configuration.
     *
     * @param styleKey The dot-separated key for the style (e.g., "styles.error.message-color").
     * @param defaultValue The default value to return if the key is not found.
     * @return The style string or the default value.
     */
    public String getStyle(String styleKey, String defaultValue) {
        Object value = messages.get(styleKey);
        if (value instanceof String) {
            return (String) value;
        }
        // Attempt to get from default messages if not found in current
        value = defaultMessages.get(styleKey);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }

    /**
     * Retrieves the error prefix string from the language configuration.
     *
     * @return The error prefix string or a hardcoded default.
     */
    public String getErrorPrefixString() {
        Object prefix = messages.get("error-prefix");
        if (prefix instanceof String) {
            return (String) prefix;
        }
        prefix = defaultMessages.get("error-prefix");
        if (prefix instanceof String) {
            return (String) prefix;
        }
        return "<gradient:#CD2B39:#B82E3A>Error:</gradient><red>"; // Hardcoded fallback
    }

    private static final Map<String, String> LANGUAGE_SHORTCUTS = new HashMap<>();
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private static final Map<Character, String> LEGACY_TO_MINIMESSAGE = new HashMap<>();
    
    static {
        // Initialize legacy to MiniMessage mappings
        LEGACY_TO_MINIMESSAGE.put('0', "<black>");
        LEGACY_TO_MINIMESSAGE.put('1', "<dark_blue>");
        LEGACY_TO_MINIMESSAGE.put('2', "<dark_green>");
        LEGACY_TO_MINIMESSAGE.put('3', "<dark_aqua>");
        LEGACY_TO_MINIMESSAGE.put('4', "<dark_red>");
        LEGACY_TO_MINIMESSAGE.put('5', "<dark_purple>");
        LEGACY_TO_MINIMESSAGE.put('6', "<gold>");
        LEGACY_TO_MINIMESSAGE.put('7', "<gray>");
        LEGACY_TO_MINIMESSAGE.put('8', "<dark_gray>");
        LEGACY_TO_MINIMESSAGE.put('9', "<blue>");
        LEGACY_TO_MINIMESSAGE.put('a', "<green>");
        LEGACY_TO_MINIMESSAGE.put('b', "<aqua>");
        LEGACY_TO_MINIMESSAGE.put('c', "<red>");
        LEGACY_TO_MINIMESSAGE.put('d', "<light_purple>");
        LEGACY_TO_MINIMESSAGE.put('e', "<yellow>");
        LEGACY_TO_MINIMESSAGE.put('f', "<white>");
        LEGACY_TO_MINIMESSAGE.put('k', "<obfuscated>");
        LEGACY_TO_MINIMESSAGE.put('l', "<bold>");
        LEGACY_TO_MINIMESSAGE.put('m', "<strikethrough>");
        LEGACY_TO_MINIMESSAGE.put('n', "<underline>");
        LEGACY_TO_MINIMESSAGE.put('o', "<italic>");
        LEGACY_TO_MINIMESSAGE.put('r', "<reset>");
    }
    
    /**
     * Converts legacy color codes (&) to MiniMessage format
     */
    private String convertLegacyColors(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        
        // If the string already contains MiniMessage tags, return as-is
        if (input.matches(".*<[a-zA-Z0-9_]+>.*")) {
            return input;
        }
        
        // If no legacy color codes, return as-is
        if (!input.contains("&")) {
            return input;
        }
        
        // Replace legacy color codes with MiniMessage tags
        StringBuffer result = new StringBuffer();
        Matcher matcher = LEGACY_COLOR_PATTERN.matcher(input);
        
        while (matcher.find()) {
            char code = matcher.group(1).toLowerCase().charAt(0);
            String replacement = LEGACY_TO_MINIMESSAGE.getOrDefault(code, "");
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Processes a message, automatically converting legacy color codes to MiniMessage format.
     * Handles both legacy (&) and MiniMessage formats transparently.
     */
    private String processMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        
        // If message already has § codes, it's already processed, return as-is
        if (message.contains("§")) {
            return message;
        }
        
        // Convert legacy color codes to MiniMessage format
        return convertLegacyColors(message);
    }
    
    static {
        LEGACY_TO_MINIMESSAGE.put('0', "<black>");
        LEGACY_TO_MINIMESSAGE.put('1', "<dark_blue>");
        LEGACY_TO_MINIMESSAGE.put('2', "<dark_green>");
        LEGACY_TO_MINIMESSAGE.put('3', "<dark_aqua>");
        LEGACY_TO_MINIMESSAGE.put('4', "<dark_red>");
        LEGACY_TO_MINIMESSAGE.put('5', "<dark_purple>");
        LEGACY_TO_MINIMESSAGE.put('6', "<gold>");
        LEGACY_TO_MINIMESSAGE.put('7', "<gray>");
        LEGACY_TO_MINIMESSAGE.put('8', "<dark_gray>");
        LEGACY_TO_MINIMESSAGE.put('9', "<blue>");
        LEGACY_TO_MINIMESSAGE.put('a', "<green>");
        LEGACY_TO_MINIMESSAGE.put('b', "<aqua>");
        LEGACY_TO_MINIMESSAGE.put('c', "<red>");
        LEGACY_TO_MINIMESSAGE.put('d', "<light_purple>");
        LEGACY_TO_MINIMESSAGE.put('e', "<yellow>");
        LEGACY_TO_MINIMESSAGE.put('f', "<white>");
        LEGACY_TO_MINIMESSAGE.put('k', "<obfuscated>");
        LEGACY_TO_MINIMESSAGE.put('l', "<bold>");
        LEGACY_TO_MINIMESSAGE.put('m', "<strikethrough>");
        LEGACY_TO_MINIMESSAGE.put('n', "<underlined>");
        LEGACY_TO_MINIMESSAGE.put('o', "<italic>");
        LEGACY_TO_MINIMESSAGE.put('r', "<reset>");
    }

    // List of supported languages with their display names
    private static final Map<String, String> SUPPORTED_LANGUAGES = new LinkedHashMap<>();
    private static final boolean PAPER_AVAILABLE;
    
    static {
        // Check if Paper is available at runtime
        boolean paperCheck = false;
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            paperCheck = true;
        } catch (ClassNotFoundException e) {
            paperCheck = false;
        }
        PAPER_AVAILABLE = paperCheck;
        
        // Initialize legacy to MiniMessage mappings
        LEGACY_TO_MINIMESSAGE.put('0', "<black>");
        LEGACY_TO_MINIMESSAGE.put('1', "<dark_blue>");
        LEGACY_TO_MINIMESSAGE.put('2', "<dark_green>");
        LEGACY_TO_MINIMESSAGE.put('3', "<dark_aqua>");
        LEGACY_TO_MINIMESSAGE.put('4', "<dark_red>");
        LEGACY_TO_MINIMESSAGE.put('5', "<dark_purple>");
        LEGACY_TO_MINIMESSAGE.put('6', "<gold>");
        LEGACY_TO_MINIMESSAGE.put('7', "<gray>");
        LEGACY_TO_MINIMESSAGE.put('8', "<dark_gray>");
        LEGACY_TO_MINIMESSAGE.put('9', "<blue>");
        LEGACY_TO_MINIMESSAGE.put('a', "<green>");
        LEGACY_TO_MINIMESSAGE.put('b', "<aqua>");
        LEGACY_TO_MINIMESSAGE.put('c', "<red>");
        LEGACY_TO_MINIMESSAGE.put('d', "<light_purple>");
        LEGACY_TO_MINIMESSAGE.put('e', "<yellow>");
        LEGACY_TO_MINIMESSAGE.put('f', "<white>");
        LEGACY_TO_MINIMESSAGE.put('k', "<obfuscated>");
        LEGACY_TO_MINIMESSAGE.put('l', "<bold>");
        LEGACY_TO_MINIMESSAGE.put('m', "<strikethrough>");
        LEGACY_TO_MINIMESSAGE.put('n', "<underlined>");
        LEGACY_TO_MINIMESSAGE.put('o', "<italic>");
        LEGACY_TO_MINIMESSAGE.put('r', "<reset>");
    }
    
    /**
     * Checks if the server is running Paper or a fork with Paper's API
     * @return true if Paper is available, false otherwise
     */
    public static boolean isPaperAvailable() {
        return PAPER_AVAILABLE;
    }
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
        if(config.getBoolean("debug-mode")) {
            Text.sendDebugLog(INFO, "Using language: " + getLanguageName(this.configLangCode) + " (" + this.configLangCode + ")");
        }

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
    private String resolveLanguageCode(String langCode) {
        if (langCode == null || langCode.isEmpty()) {
            return "en_US"; // Default to English if unspecified
        }

        // Trim the language code and convert to lowercase for consistent lookup
        String normalizedCode = langCode.trim().toLowerCase();

        // If it's a short code, resolve it to the full code
        if (LANGUAGE_SHORTCUTS.containsKey(normalizedCode)) {
            return LANGUAGE_SHORTCUTS.get(normalizedCode);
        }

        // If it already contains an underscore, assume it's a full code
        if (normalizedCode.contains("_")) {
            // Preserve the original casing for the full code
            return langCode.trim();
        }

        // For any other short code not in our map, try to guess the full code
        // by appending the country code (uppercase version of language code)
        return normalizedCode + "_" + normalizedCode.toUpperCase();
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
            Text.sendDebugLog(WARN, "Invalid language code: " + langCode + ". Falling back to English.");
            return Locale.ENGLISH;
        }
    }

    private boolean replaceTopLevelValue(String key, String newValue) {
        try {
            List<String> lines = Files.readAllLines(langFile.toPath(), StandardCharsets.UTF_8);
            String prefix = key + ":";

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix)) {
                    String leading = line.substring(0, line.indexOf(trimmed));
                    String replacement = leading + prefix + " " + newValue;
                    if (!line.equals(replacement)) {
                        lines.set(i, replacement);
                        Files.write(langFile.toPath(), lines, StandardCharsets.UTF_8);
                        reloadLangConfigurationFromDisk();
                        return true;
                    }
                    return false;
                }
            }
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to update " + key + " in lang.yml", e);
        }
        return false;
    }

    private void reloadLangConfigurationFromDisk() {
        try {
            langConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to reload lang.yml", e);
        }
    }

    /**
     * Log the list of available languages to the console
     */
    private void logAvailableLanguages() {
        Text.sendDebugLog(INFO, "Available languages:");
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
            Text.sendDebugLog(INFO, info);
        }
    }



    private void initializeDefaultMessages() {
        defaultMessages.clear();
        defaultLangConfig = null;

        InputStream defConfigStream = plugin.getResource("lang.yml");
        if (defConfigStream == null) {
            Text.sendDebugLog(WARN, "Could not find default language file (lang.yml) in plugin resources!");
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)) {
            defaultLangConfig = YamlConfiguration.loadConfiguration(reader);
            loadDefaultMessagesRecursive(defaultLangConfig, "");
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to load default language resource", e);
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
            langConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
            );

            // Get the actual language code from the file (or use the current one if not specified)
            String langCode = langConfig.getString("lang", configLangCode);

            // Check if the langfile setting in config.yml differs from what we have in the file
            String configLangCode = plugin.getConfig().getString("lang");
            if (configLangCode != null && !configLangCode.equals(langCode)) {
                // Check if configLangCode is a language shortcut
                String resolvedShortcut = configLangCode;
                if (LANGUAGE_SHORTCUTS.containsKey(configLangCode.toLowerCase())) {
                    resolvedShortcut = LANGUAGE_SHORTCUTS.get(configLangCode.toLowerCase());
                }

                // Only show the warning if the resolved code doesn't match either
                if (!resolvedShortcut.equals(langCode)) {
                    Text.sendDebugLog(INFO, "Note: Config 'langfile' setting (" + configLangCode +
                            ") differs from the language file's code (" + langCode + ")");
                    Text.sendDebugLog(INFO, "To change language, delete lang.yml and set 'lang' in config.yml");
                }
            }

            // Perform version check and cleanup
            performVersionMigration();

            synchronizeWithDefaults();

            // Reload after synchronization to ensure in-memory state matches disk
            langConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(Files.newInputStream(langFile.toPath()), StandardCharsets.UTF_8)
            );

            // Load all messages
            loadMessagesRecursive(langConfig, "");

            // Ensure in-memory messages have fallback defaults without mutating file content
            for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
                messages.putIfAbsent(entry.getKey(), entry.getValue());
            }

            Text.sendDebugLog(INFO, "Loaded language file: lang.yml (" + langCode + ")");

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to load language file", e);
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
                Text.sendDebugLog(INFO, "Created language file from resource: " + configLangCode);
            } else {
                // Create a language file with default messages
                YamlConfiguration config = new YamlConfiguration();

                // Add metadata
                config.set("lang", configLangCode);
                config.set("lang", getLanguageName(configLangCode));
                config.set("version", CURRENT_LANG_VERSION);

                // Add all default messages
                for (Map.Entry<String, Object> entry : defaultMessages.entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }

                // Save the config
                config.save(langFile);
                Text.sendDebugLog(INFO, "Created new language file with " + configLangCode + " defaults");
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to create language file", e);
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

    private void loadDefaultMessagesRecursive(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            Object value = section.get(key);

            if (value instanceof ConfigurationSection) {
                loadDefaultMessagesRecursive((ConfigurationSection) value, fullPath);
            } else {
                defaultMessages.put(fullPath, value);
            }
        }
    }

    private void synchronizeWithDefaults() {
        if (defaultLangConfig == null) {
            initializeDefaultMessages();
            if (defaultLangConfig == null) {
                return;
            }
        }

        LinkedHashSet<String> defaultPaths = new LinkedHashSet<>();
        collectValuePathsInOrder(defaultLangConfig, "", defaultPaths);

        List<String> missingKeys = new ArrayList<>();

        for (String path : defaultPaths) {
            if (!langConfig.contains(path)) {
                missingKeys.add(path);
            }
        }

        if (!missingKeys.isEmpty()) {
            Text.sendDebugLog(INFO, "Missing lang keys detected. Using defaults in-memory only: " + String.join(", ", missingKeys));
        }
    }

    private void collectValuePathsInOrder(ConfigurationSection section, String parentPath, LinkedHashSet<String> paths) {
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            String currentPath = parentPath.isEmpty() ? key : parentPath + "." + key;
            Object value = section.get(key);

            if (value instanceof ConfigurationSection nestedSection) {
                collectValuePathsInOrder(nestedSection, currentPath, paths);
            } else {
                paths.add(currentPath);
            }
        }
    }

    private void performVersionMigration() {
        String fileVersion = langConfig.getString("version", "0.0.0");

        if (compareVersions(fileVersion, CURRENT_LANG_VERSION) < 0) {
            Text.sendDebugLog(INFO, "Migrating lang.yml from version " + fileVersion + " to " + CURRENT_LANG_VERSION);

            if (compareVersions(fileVersion, "0.1.4a") < 0) {
                performChangesForVersion0_1_4a();
            }

            langConfig.set("version", CURRENT_LANG_VERSION);
            replaceTopLevelValue("version", CURRENT_LANG_VERSION);
        }
    }

    private void performChangesForVersion0_1_4a() {
        Text.sendDebugLog(INFO, "Applying 0.1.4a changes to lang.yml...");

        // Add all missing keys from default lang.yml (new keys in 0.1.4a)
        addMissingKeysFromDefault();

        // Ensure invalid message follows new syntax format using targeted replacement
        String invalidMessage = langConfig.getString("invalid");
        String updatedInvalid = "Invalid {arg} {syntax}";
        if (invalidMessage == null || !invalidMessage.equals(updatedInvalid)) {
            boolean replaced = replaceTopLevelValue("invalid", "'" + updatedInvalid + "'");
            if (!replaced) {
                Text.sendDebugLog(INFO, "Unable to update 'invalid' entry automatically; please adjust manually to '" + updatedInvalid + "'.");
            } else {
                Text.sendDebugLog(INFO, "Updated invalid message syntax for 0.1.4a");
            }
            langConfig.set("invalid", updatedInvalid);
        }
    }

    /**
     * Adds any keys present in the default lang.yml but missing from the user's lang file.
     * Used during 0.1.3a -> 0.1.4a migration to seamlessly add new message keys.
     */
    private void addMissingKeysFromDefault() {
        if (defaultLangConfig == null) {
            initializeDefaultMessages();
        }
        if (defaultLangConfig == null) {
            Text.sendDebugLog(WARN, "Cannot add missing lang keys - default lang resource not loaded");
            return;
        }

        List<String> addedKeys = new ArrayList<>();
        addMissingKeysRecursive(defaultLangConfig, langConfig, "", addedKeys);

        if (!addedKeys.isEmpty()) {
            Text.sendDebugLog(INFO, "Added " + addedKeys.size() + " missing lang keys from 0.1.4a: " + String.join(", ", addedKeys));
            try {
                langConfig.save(langFile);
                Text.sendDebugLog(INFO, "Saved lang.yml with new keys");
            } catch (IOException e) {
                Text.sendDebugLog(ERROR, "Failed to save lang.yml after adding missing keys", e);
            }
        }
    }

    private void addMissingKeysRecursive(ConfigurationSection from, ConfigurationSection to, String path, List<String> addedKeys) {
        for (String key : from.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            Object fromValue = from.get(key);

            if (fromValue instanceof ConfigurationSection fromSection) {
                ConfigurationSection toSection = to.getConfigurationSection(key);
                if (toSection == null) {
                    toSection = to.createSection(key);
                    // Create the section and recurse - we need to add all nested keys
                }
                addMissingKeysRecursive(fromSection, toSection, fullPath, addedKeys);
            } else {
                if (!to.contains(key)) {
                    to.set(key, fromValue);
                    addedKeys.add(fullPath);
                }
            }
        }
    }

    private void repositionMailBroadcastSent() {
        try {
            String content = new String(Files.readAllBytes(langFile.toPath()));
            
            // Remove existing mail-broadcast-sent if present
            int broadcastStart = content.indexOf("mail-broadcast-sent:");
            if (broadcastStart != -1) {
                int broadcastEnd = content.indexOf("\n  ", broadcastStart);
                if (broadcastEnd == -1) {
                    // Look for next key at same level or end of file
                    int nextKeyIndex = content.indexOf("\n", broadcastStart);
                    while (nextKeyIndex != -1 && nextKeyIndex < content.length() - 1) {
                        if (content.charAt(nextKeyIndex + 1) != ' ') {
                            broadcastEnd = nextKeyIndex;
                            break;
                        }
                        nextKeyIndex = content.indexOf("\n", nextKeyIndex + 1);
                    }
                    if (broadcastEnd == -1) {
                        broadcastEnd = content.length();
                    }
                }
                content = content.substring(0, broadcastStart) + content.substring(broadcastEnd);
                Text.sendDebugLog(INFO, "Removed existing mail-broadcast-sent from lang.yml");
            }
            
            // Find mail-sent and insert mail-broadcast-sent after it
            int mailSentIndex = content.indexOf("mail-sent:");
            if (mailSentIndex != -1) {
                // Find the end of the mail-sent entry (next key at same level)
                int nextKeyIndex = content.indexOf("\n  ", mailSentIndex);
                while (nextKeyIndex != -1 && nextKeyIndex < content.length() - 1) {
                    if (content.charAt(nextKeyIndex + 1) != ' ') {
                        break;
                    }
                    nextKeyIndex = content.indexOf("\n  ", nextKeyIndex + 1);
                }
                
                if (nextKeyIndex != -1) {
                    String mailBroadcastEntry = "\n  mail-broadcast-sent: |\n    &aMail sent to &e{count}&a players.\n    &f{message}";
                    String newContent = content.substring(0, nextKeyIndex) + 
                                      mailBroadcastEntry + 
                                      content.substring(nextKeyIndex);
                    
                    Files.write(langFile.toPath(), newContent.getBytes());
                    Text.sendDebugLog(INFO, "Successfully repositioned mail-broadcast-sent after mail-sent");
                } else {
                    Text.sendDebugLog(WARN, "Could not find proper position to insert mail-broadcast-sent");
                }
            } else {
                Text.sendDebugLog(WARN, "Could not find mail-sent to position mail-broadcast-sent after it");
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to reposition mail-broadcast-sent in lang.yml", e);
        }
    }
    
    private void repositionWorldNotFound() {
        try {
            String content = new String(Files.readAllBytes(langFile.toPath()));
            
            // Remove existing world-not-found if present
            int worldNotFoundStart = content.indexOf("world-not-found:");
            if (worldNotFoundStart != -1) {
                // Find the end of the world-not-found entry (next line that doesn't start with space)
                int worldNotFoundEnd = content.indexOf("\n", worldNotFoundStart);
                if (worldNotFoundEnd != -1) {
                    worldNotFoundEnd++; // Include the newline
                }
                if (worldNotFoundEnd == -1) {
                    worldNotFoundEnd = content.length();
                }
                content = content.substring(0, worldNotFoundStart) + content.substring(worldNotFoundEnd);
                Text.sendDebugLog(INFO, "Removed existing world-not-found from lang.yml");
            }
            
            // Find player-not-online and insert world-not-found after it
            int playerNotOnlineIndex = content.indexOf("player-not-online:");
            if (playerNotOnlineIndex != -1) {
                // Find the end of the player-not-online entry (next line)
                int nextLineIndex = content.indexOf("\n", playerNotOnlineIndex);
                if (nextLineIndex != -1) {
                    String worldNotFoundEntry = "\nworld-not-found: \"World {world} not found.\"";
                    String newContent = content.substring(0, nextLineIndex) + 
                                      worldNotFoundEntry + 
                                      content.substring(nextLineIndex);
                    
                    Files.write(langFile.toPath(), newContent.getBytes());
                    Text.sendDebugLog(INFO, "Successfully repositioned world-not-found after player-not-online");
                } else {
                    Text.sendDebugLog(WARN, "Could not find proper position to insert world-not-found");
                }
            } else {
                Text.sendDebugLog(WARN, "Could not find player-not-online to position world-not-found after it");
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to reposition world-not-found in lang.yml", e);
        }
    }

    public String parse(String input) {
        // Replace placeholders (e.g., %player_name%) if needed
        // For simplicity, assuming no placeholders for now
        return input;
    }

    public String parse(String input, String playerName) {
        // Replace %player_name% with the actual player name
        String processed = input.replace("%player_name%", playerName);
        return processed;
    }

    /**
     * Compare two version strings (e.g., "1.2.3")
     * @return negative if v1 < v2, positive if v1 > v2, zero if equal
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = (i < parts1.length) ? parseVersionPart(parts1[i]) : 0;
            int num2 = (i < parts2.length) ? parseVersionPart(parts2[i]) : 0;

            if (num1 != num2) {
                return num1 - num2;
            }
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            // Remove any non-numeric suffix (like 'a' in '0.1.2a')
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Holder for a parsed language entry with optional sound.
     */
    public static final class MessageBundle {
        private final List<String> rawLines;
        private final SoundInstruction soundInstruction;
        private final ParticleInstruction particleInstruction;
        private final List<String> commandInstructions;
        private List<String> cachedColoredLines;
        private static final net.kyori.adventure.text.minimessage.MiniMessage MINI_MESSAGE = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();

        private MessageBundle(List<String> rawLines, SoundInstruction soundInstruction, 
                            ParticleInstruction particleInstruction, List<String> commandInstructions) {
            this.rawLines = Collections.unmodifiableList(rawLines);
            this.soundInstruction = soundInstruction;
            this.particleInstruction = particleInstruction;
            this.commandInstructions = Collections.unmodifiableList(new ArrayList<>(commandInstructions));
            this.cachedColoredLines = null;
        }

        public List<String> rawLines() {
            return rawLines;
        }

        // Map of simple Kyori tags to their legacy color code equivalents
        private static final Map<String, String> SIMPLE_TAG_TO_LEGACY = new HashMap<>();
        static {
            // Colors
            SIMPLE_TAG_TO_LEGACY.put("<black>", "&0");
            SIMPLE_TAG_TO_LEGACY.put("<dark_blue>", "&1");
            SIMPLE_TAG_TO_LEGACY.put("<dark_green>", "&2");
            SIMPLE_TAG_TO_LEGACY.put("<dark_aqua>", "&3");
            SIMPLE_TAG_TO_LEGACY.put("<dark_red>", "&4");
            SIMPLE_TAG_TO_LEGACY.put("<dark_purple>", "&5");
            SIMPLE_TAG_TO_LEGACY.put("<gold>", "&6");
            SIMPLE_TAG_TO_LEGACY.put("<gray>", "&7");
            SIMPLE_TAG_TO_LEGACY.put("<dark_gray>", "&8");
            SIMPLE_TAG_TO_LEGACY.put("<blue>", "&9");
            SIMPLE_TAG_TO_LEGACY.put("<green>", "&a");
            SIMPLE_TAG_TO_LEGACY.put("<aqua>", "&b");
            SIMPLE_TAG_TO_LEGACY.put("<red>", "&c");
            SIMPLE_TAG_TO_LEGACY.put("<light_purple>", "&d");
            SIMPLE_TAG_TO_LEGACY.put("<yellow>", "&e");
            SIMPLE_TAG_TO_LEGACY.put("<white>", "&f");
            // Formatting
            SIMPLE_TAG_TO_LEGACY.put("<obfuscated>", "&k");
            SIMPLE_TAG_TO_LEGACY.put("<bold>", "&l");
            SIMPLE_TAG_TO_LEGACY.put("<strikethrough>", "&m");
            SIMPLE_TAG_TO_LEGACY.put("<underlined>", "&n");
            SIMPLE_TAG_TO_LEGACY.put("<italic>", "&o");
            SIMPLE_TAG_TO_LEGACY.put("<reset>", "&r");
            // Close tags
            SIMPLE_TAG_TO_LEGACY.put("</black>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_blue>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_green>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_aqua>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_red>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_purple>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</gold>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</gray>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</dark_gray>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</blue>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</green>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</aqua>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</red>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</light_purple>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</yellow>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</white>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</obfuscated>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</bold>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</strikethrough>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</underlined>", "&r");
            SIMPLE_TAG_TO_LEGACY.put("</italic>", "&r");
        }

        // List of unsupported Kyori tags that should show an error
        private static final List<String> UNSUPPORTED_TAGS = Arrays.asList(
            "<click:", "<hover:", "<font:", "<keybind:", "<translatable:", "<score:", "<selector:", "<lang:", "<nbt:"
        );

        private String convertSimpleKyoriToLegacy(String input) {
            String result = input;
            // First check for unsupported tags
            for (String unsupportedTag : UNSUPPORTED_TAGS) {
                if (input.contains(unsupportedTag)) {
                    String errorMsg = "[ERROR: Unsupported Kyori tag detected: " + unsupportedTag + " - Paper is required for this feature]";
                    Text.sendDebugLog(ERROR, errorMsg + " in message: " + input);
                    return "&c" + errorMsg;
                }
            }
            
            // Convert simple tags
            for (Map.Entry<String, String> entry : SIMPLE_TAG_TO_LEGACY.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            
            return result;
        }

        public List<String> coloredLines() {
            if (cachedColoredLines != null) {
                return cachedColoredLines;
            }

            List<String> result = new ArrayList<>();
            boolean paperAvailable = isPaperAvailable();
            
            for (String line : rawLines) {
                if (line == null || line.isEmpty()) {
                    result.add("");
                    continue;
                }

                try {
                    // Check if the line contains MiniMessage tags
                    boolean hasMiniMessageTags = line.matches("(?s).*<[a-z0-9_/]+>.*");
                    
                    if (hasMiniMessageTags && !paperAvailable) {
                        // If we're not on Paper, try to convert Kyori tags to legacy
                        String converted = convertSimpleKyoriToLegacy(line);
                        if (converted.startsWith("&c[ERROR:")) {
                            // If there was an error with unsupported tags, add it to the result
                            result.add(Text.parseColors(converted));
                            continue;
                        }
                        // Use the converted line with legacy colors
                        result.add(Text.parseColors(converted));
                        continue;
                    }
                    
                    // If we have legacy colors, parse them first
                    if (line.contains("&") || line.contains("§")) {
                        String legacyParsed = Text.parseColors(line);
                        
                        if (paperAvailable) {
                            try {
                                // If Paper is available, try to parse MiniMessage tags
                                net.kyori.adventure.text.Component component = MINI_MESSAGE.deserialize(legacyParsed);
                                result.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component));
                            } catch (Exception e) {
                                // If MiniMessage parsing fails, fall back to just legacy colors
                                Text.sendDebugLog(WARN, "Failed to parse MiniMessage in line (falling back to legacy): " + line);
                                result.add(legacyParsed);
                            }
                        } else {
                            // If Paper is not available, just use the legacy parsed colors
                            result.add(legacyParsed);
                        }
                    } else if (paperAvailable) {
                        // If no legacy colors but Paper is available, try to parse as MiniMessage
                        try {
                            net.kyori.adventure.text.Component component = MINI_MESSAGE.deserialize(line);
                            result.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component));
                        } catch (Exception e) {
                            // If MiniMessage parsing fails, use the line as-is
                            Text.sendDebugLog(WARN, "Failed to parse MiniMessage in line (using as-is): " + line);
                            result.add(line);
                        }
                    } else {
                        // No legacy colors and no Paper, use the line as-is
                        result.add(line);
                    }
                } catch (Exception e) {
                    // If any other error occurs, log it and use the line as-is
                    Text.sendDebugLog(ERROR, "Error processing message line: " + e.getMessage() + " in line: " + line);
                    result.add(line);
                }
            }

            cachedColoredLines = Collections.unmodifiableList(result);
            return cachedColoredLines;
        }

        public String firstColoredLine() {
            List<String> colored = coloredLines();
            return colored.isEmpty() ? "" : colored.get(0);
        }

        public Optional<SoundInstruction> sound() {
            return Optional.ofNullable(soundInstruction);
        }
        
        public Optional<ParticleInstruction> particle() {
            return Optional.ofNullable(particleInstruction);
        }

        public List<String> commandInstructions() {
            return commandInstructions;
        }
    }

    public record SoundInstruction(Sound sound, float volume, float pitch) { }
    
    public record ParticleInstruction(
        Particle particle,
        int count,
        double speed,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        public void spawn(Location location) {
            World world = location.getWorld();
            if (world == null) return;
            
            world.spawnParticle(
                particle,
                location,
                count,
                offsetX,
                offsetY,
                offsetZ,
                speed
            );
        }
        
        public void spawn(Player player) {
            spawn(player.getLocation().add(offsetX, offsetY + 2, offsetZ));
        }
    }

    private static final class SoundCacheKey {
        private final String name;

        private SoundCacheKey(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            SoundCacheKey other = (SoundCacheKey) obj;
            return Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    private final Map<SoundCacheKey, SoundInstruction> soundCache = new ConcurrentHashMap<>();
    private final Map<String, SoundInstruction> messageSoundCache = new ConcurrentHashMap<>();

    private Optional<Object> resolveMessageObject(String key) {
        Object message = messages.get(key);
        if (message == null) {
            message = defaultMessages.get(key);
        }
        return Optional.ofNullable(message);
    }

    /**
     * Get a message string from the language file
     */
    public String getRaw(String key, Object... args) {
        MessageBundle bundle = getBundle(key, args);
        List<String> rawLines = bundle.rawLines();
        if (rawLines.isEmpty()) {
            if (key.equals("error-prefix")) {
                return "&c[Error]";
            }
            // Check if this looks like a literal string rather than a key
            boolean isLiteralString = key.contains(" ") || key.contains("§") || key.contains("&") 
                    || key.contains("<") || key.length() > 50;
            if (isLiteralString) {
                return key; // Return literal string directly
            }
            return String.format("&c[Missing translation: %s]", key);
        }
        return rawLines.get(0);
    }

    /**
     * Get a message string from the language file
     */
    public String get(String key, Object... args) {
        return get(key, null, args);
    }

    /**
     * Gets a message from the language file with optional sound and command execution.
     * If a player is provided and the message has an associated sound, it will be played.
     *
     * @param key The message key from the language file
     * @param player The player to play the sound for (can be null)
     * @param args Optional format arguments or placeholder replacements
     * @return The formatted message string
     */
    public String get(String key, Player player, Object... args) {
        // Get the message bundle which may contain sound and command instructions
        MessageBundle bundle = getBundle(key, args);
        
        // Update the sound cache for this message key
        updateMessageSoundCache(key, bundle);
        
        // Execute any command instructions if a player is provided
        executeCommandInstructions(player, bundle);
        
        // Play sound if a player is provided and the message has a sound
        if (player != null && player.isOnline()) {
            try {
                bundle.sound().ifPresent(soundInstruction -> {
                    try {
                        player.playSound(
                            player.getLocation(), 
                            soundInstruction.sound(), 
                            soundInstruction.volume(), 
                            soundInstruction.pitch()
                        );
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Failed to play sound for message " + key + ": " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error processing sound for message " + key + ": " + e.getMessage());
            }
        }

        // Get the colored message lines
        List<String> coloredLines;
        try {
            coloredLines = bundle.coloredLines();
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error processing message " + key + ": " + e.getMessage());
            return Text.parseColors(String.format("&c[Error processing message: %s]", key));
        }
        
        if (coloredLines.isEmpty()) {
            // Check if this looks like a literal string rather than a key
            boolean isLiteralString = key.contains(" ") || key.contains("§") || key.contains("&") 
                    || key.contains("<") || key.length() > 50;
            if (isLiteralString) {
                return Text.parseColors(key); // Return literal string directly
            }
            return Text.parseColors(String.format("&c[Missing translation: %s]", key));
        }
        
        // Apply string formatting to each line if there are format arguments
        if (args != null && args.length > 0) {
            try {
                List<String> formattedLines = new ArrayList<>();
                for (String line : coloredLines) {
                    formattedLines.add(String.format(line, args));
                }
                return String.join("\n", formattedLines);
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error formatting message " + key + " with args: " + Arrays.toString(args));
                return String.join("\n", coloredLines);
            }
        }
        
        return String.join("\n", coloredLines);
    }

    private void updateMessageSoundCache(String key, MessageBundle bundle) {
        bundle.sound()
                .ifPresentOrElse(soundInstruction -> messageSoundCache.put(key, soundInstruction),
                        () -> messageSoundCache.remove(key));
    }

    private void executeCommandInstructions(Player player, MessageBundle bundle) {
        if (player == null || bundle.commandInstructions().isEmpty()) {
            return;
        }

        for (String command : bundle.commandInstructions()) {
            String resolved = applyCommandPlaceholders(command, player);
            if (resolved.isBlank()) {
                continue;
            }

            boolean executed = player.performCommand(resolved);
            if (!executed) {
                Text.sendDebugLog(WARN, "Failed to execute lang command '" + resolved + "' for player " + player.getName());
            }
        }
    }

    public Optional<SoundInstruction> getSoundInstruction(String key) {
        SoundInstruction cached = messageSoundCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        MessageBundle bundle = getBundle(key);
        updateMessageSoundCache(key, bundle);
        return Optional.ofNullable(messageSoundCache.get(key));
    }

    public void playSoundAtPlayer(Player player, String key) {
        if (player == null) {
            return;
        }

        getSoundInstruction(key).ifPresent(soundInstruction ->
                player.playSound(player.getLocation(), soundInstruction.sound(), soundInstruction.volume(), soundInstruction.pitch()));
    }

    /**
     * Get the first raw Bukkit color code (e.g., &a) from a message string by its key.
     * This method accesses the raw message before Text.parseColors is applied.
     *
     * @param key The key of the message.
     * @return The first color code (e.g., "§a") or an empty string if not found or invalid.
     */
    public String getFirstColorCode(String key) {
        Object messageObj = messages.get(key);
        if (messageObj == null) {
            messageObj = defaultMessages.get(key);
        }

        if (messageObj == null) {
            return ""; // Key not found in messages or defaultMessages
        }

        String rawMessage = messageObj.toString();
        int ampersandIndex = rawMessage.indexOf('&');

        if (ampersandIndex != -1 && ampersandIndex + 1 < rawMessage.length()) {
            char colorChar = rawMessage.charAt(ampersandIndex + 1);
            // Validate if it's a known Minecraft color/format code
            if ("0123456789abcdefklmnor".indexOf(Character.toLowerCase(colorChar)) != -1) {
                return "§" + colorChar;
            }
        }
        return ""; // No valid color code found at the beginning
    }


    /**
     * Get a list of messages from the language file
     */
    public List<String> getList(String key, Object... args) {
        MessageBundle bundle = getBundle(key);
        List<String> colored = bundle.coloredLines();
        if (colored.isEmpty()) {
            // Check if this looks like a literal string rather than a key
            boolean isLiteralString = key.contains(" ") || key.contains("§") || key.contains("&") 
                    || key.contains("<") || key.length() > 50;
            if (isLiteralString) {
                return Collections.singletonList(Text.parseColors(key)); // Return literal string directly
            }
            return Collections.singletonList(Text.parseColors(String.format("&c[Missing translation list: %s]", key)));
        }
        // Apply string formatting to each line if there are format arguments
        if (args != null && args.length > 0) {
            List<String> formattedLines = new ArrayList<>();
            for (String line : colored) {
                formattedLines.add(String.format(line, args));
            }
            return formattedLines;
        }
        return colored;
    }

    public MessageBundle getBundle(String key, Object... args) {
        Optional<Object> message = resolveMessageObject(key);
        if (message.isEmpty()) {
            // Check if this looks like a literal string (already translated) rather than a key
            // Literal strings typically contain spaces, color codes, or are full sentences
            boolean isLiteralString = key.contains(" ") || key.contains("§") || key.contains("&") 
                    || key.contains("<") || key.length() > 50;
            
            if (isLiteralString) {
                // Treat as a literal message - return it directly without error
                return new MessageBundle(
                        Collections.singletonList(key),
                        null,
                        null,
                        Collections.emptyList()
                );
            }
            
            // It's a missing key - show error
            String errorMsg = String.format("&c[Missing translation: %s]", key);
            return new MessageBundle(
                    Collections.singletonList(errorMsg),
                    null,
                    null,
                    Collections.emptyList()
            );
        }

        Object value = message.get();
        List<String> rawLines = new ArrayList<>();
        SoundInstruction soundInstruction = null;
        ParticleInstruction particleInstruction = null;
        List<String> commandInstructions = new ArrayList<>();

        if (value instanceof List<?>) {
            List<?> rawList = (List<?>) value;
            for (Object entry : rawList) {
                if (entry == null) {
                    continue;
                }
                
                if (entry instanceof String rawString) {
                    // Check for sound instruction first
                    String lowerTrimmed = rawString.trim().toLowerCase();
                    if (lowerTrimmed.startsWith("[sound")) {
                        soundInstruction = parseSoundInstruction(rawString).orElse(null);
                        continue;
                    }
                    // Check for particle instruction
                    if (lowerTrimmed.startsWith("[particle")) {
                        particleInstruction = parseParticleInstruction(rawString).orElse(null);
                        continue;
                    }
                    // Then check for command instruction
                    if (lowerTrimmed.startsWith("[command")) {
                        parseCommandInstruction(rawString).ifPresent(commandInstructions::add);
                        continue;
                    }
                    // If not a special instruction, add as regular message line
                    rawLines.add(rawString);
                } else if (entry instanceof List<?> nestedList) {
                    // Handle nested lists (unlikely in YAML, but just in case)
                    String joined = joinTokens(nestedList);
                    if (joined.trim().toLowerCase().startsWith("[sound")) {
                        soundInstruction = parseSoundInstruction(joined).orElse(null);
                    } else if (joined.trim().toLowerCase().startsWith("[command")) {
                        parseCommandInstruction(joined).ifPresent(commandInstructions::add);
                    } else {
                        rawLines.add(joined);
                    }
                } else {
                    rawLines.add(entry.toString());
                }
            }
        } else if (value instanceof String rawString) {
            // Handle single string value
            String lowerTrimmed = rawString.trim().toLowerCase();
            if (lowerTrimmed.startsWith("[sound")) {
                soundInstruction = parseSoundInstruction(rawString).orElse(null);
            } else if (lowerTrimmed.startsWith("[particle")) {
                particleInstruction = parseParticleInstruction(rawString).orElse(null);
            } else if (lowerTrimmed.startsWith("[command")) {
                parseCommandInstruction(rawString).ifPresent(commandInstructions::add);
            } else {
                rawLines.add(rawString);
            }
    } else {
        rawLines.add(value.toString());
    }

    if (rawLines.isEmpty()) {
        rawLines = Collections.singletonList("");
        return new MessageBundle(rawLines, soundInstruction, particleInstruction, commandInstructions);
    }

    return new MessageBundle(rawLines, soundInstruction, particleInstruction, commandInstructions);
}

    private Optional<SoundInstruction> parseSoundInstruction(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[")) {
            return Optional.empty();
        }

        // Handle both [sound] SOUND_NAME VOLUME PITCH and [sound SOUND_NAME VOLUME PITCH] formats
        if (trimmed.startsWith("[sound] ") || trimmed.startsWith("[sound ")) {
            // Remove the [sound] prefix and any trailing ]
            String soundPart = trimmed.startsWith("[sound] ") ? 
                trimmed.substring(8) :  // Remove "[sound] "
                trimmed.substring(7);   // Remove "[sound "
                
            // Remove trailing ] if present (for the [sound ...] format)
            if (soundPart.endsWith("]")) {
                soundPart = soundPart.substring(0, soundPart.length() - 1).trim();
            }

            // Split into tokens and parse
            List<String> tokens = new ArrayList<>();
            tokens.add("sound");
            tokens.addAll(Arrays.asList(soundPart.split("\\s+")));
            return parseSoundTokens(tokens, raw);
        }

        return Optional.empty();
    }

    private Optional<String> parseCommandInstruction(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[command")) {
            return Optional.empty();
        }

        if (!trimmed.endsWith("]")) {
            Text.sendDebugLog(WARN, "Command directive missing closing bracket: " + raw);
            return Optional.empty();
        }

        // Remove the [command] prefix and any trailing ]
        String commandPart = trimmed.startsWith("[command] ") ? 
            trimmed.substring(9) :  // Remove "[command] "
            trimmed.substring(8);   // Remove "[command "
            
        // Remove trailing ] if present (for the [command ...] format)
        if (commandPart.endsWith("]")) {
            commandPart = commandPart.substring(0, commandPart.length() - 1).trim();
        }

        // Split into tokens and parse
        List<String> tokens = new ArrayList<>();
        tokens.add("command");
        tokens.addAll(Arrays.asList(commandPart.split("\\s+")));
        return parseCommandTokens(tokens, raw);
    }

    private Optional<String> parseCommandTokens(List<String> tokens, Object source) {
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        String directive = tokens.get(0);
        if (!"command".equalsIgnoreCase(directive)) {
            if (directive.startsWith("[command") && directive.length() > 8) {
                // Handle cases where tokenization kept the closing bracket
                String normalized = directive.replace("[", "").replace("]", "");
                if (!"command".equalsIgnoreCase(normalized)) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }

        if (tokens.size() < 2) {
            Text.sendDebugLog(WARN, "Command directive missing command text: " + source);
            return Optional.empty();
        }

        String command = tokens.subList(1, tokens.size()).stream()
                .collect(Collectors.joining(" "))
                .trim();

        if (command.isEmpty()) {
            Text.sendDebugLog(WARN, "Command directive produced empty command: " + source);
            return Optional.empty();
        }

        return Optional.of(command);
    }

    private Optional<SoundInstruction> parseSoundTokens(List<String> tokens, Object source) {
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        String directive = tokens.get(0);
        if (!"sound".equalsIgnoreCase(directive)) {
            return Optional.empty();
        }

        if (tokens.size() < 2) {
            Text.sendDebugLog(WARN, "Sound directive missing sound name: " + source);
            return Optional.empty();
        }

        String soundName = tokens.get(1).toUpperCase(Locale.ROOT);
        float volume = tokens.size() > 2 ? parseFloat(tokens.get(2), 1.0f) : 1.0f;
        float pitch = tokens.size() > 3 ? parseFloat(tokens.get(3), 1.0f) : 1.0f;

        SoundCacheKey key = new SoundCacheKey(soundName + ":" + volume + ":" + pitch);
        SoundInstruction cached = soundCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        Sound sound = null;
        List<String> availableSounds = new ArrayList<>();
        
        for (Sound s : Sound.values()) {
            availableSounds.add(s.name());
            if (s.name().equalsIgnoreCase(soundName)) {
                sound = s;
            }
        }
        
        if (sound == null) {
            Text.sendDebugLog(WARN, "Unknown sound name in lang file: " + soundName);
            // Log all available sounds for debugging
            Text.sendDebugLog(INFO, "Available sounds: " + String.join(", ", availableSounds));
            // Try to find a similar sound
            Optional<Sound> similarSound = availableSounds.stream()
                .filter(s -> s.contains("EAT") || s.contains("BURP") || s.contains("EATING"))
                .findFirst()
                .map(Sound::valueOf);
                
            if (similarSound.isPresent()) {
                Text.sendDebugLog(INFO, "Try using this sound instead: " + similarSound.get().name());
                sound = similarSound.get();
            } else {
                return Optional.empty();
            }
        }

        SoundInstruction created = new SoundInstruction(sound, volume, pitch);
        soundCache.put(new SoundCacheKey(soundName + ":" + volume + ":" + pitch), created);
        return Optional.of(created);
    }

    private float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
    
    private Optional<ParticleInstruction> parseParticleInstruction(String raw) {
        try {
            String[] parts = raw.trim().substring(1, raw.length() - 1).split("\\s+");
            if (parts.length < 2) {
                Text.sendDebugLog(WARN, "Particle directive missing particle name: " + raw);
                return Optional.empty();
            }
            
            // Parse particle type (case-insensitive)
            String particleName = parts[1].toUpperCase();
            Particle particle;
            try {
                particle = Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                Text.sendDebugLog(WARN, "Unknown particle type: " + particleName);
                return Optional.empty();
            }
            
            // Default values
            int count = 1;
            double speed = 0.1;
            double offsetX = 0, offsetY = 0, offsetZ = 0;
            
            // Check if using simple syntax: [particle] TYPE COUNT SPEED X Y Z
            if (parts.length >= 7) {
                try {
                    count = Integer.parseInt(parts[2]);
                    speed = Double.parseDouble(parts[3]);
                    offsetX = Double.parseDouble(parts[4]);
                    offsetY = Double.parseDouble(parts[5]);
                    offsetZ = Double.parseDouble(parts[6]);
                    return Optional.of(new ParticleInstruction(particle, count, speed, offsetX, offsetY, offsetZ));
                } catch (NumberFormatException e) {
                    Text.sendDebugLog(WARN, "Invalid number in particle directive: " + raw);
                    return Optional.empty();
                }
            }
            
            // Fall back to named parameters
            for (int i = 2; i < parts.length; i++) {
                String[] keyValue = parts[i].split("=");
                if (keyValue.length != 2) continue;
                
                String key = keyValue[0].toLowerCase();
                String value = keyValue[1];
                
                try {
                    switch (key) {
                        case "count" -> count = Integer.parseInt(value);
                        case "speed" -> speed = Double.parseDouble(value);
                        case "x", "offsetx" -> offsetX = Double.parseDouble(value);
                        case "y", "offsety" -> offsetY = Double.parseDouble(value);
                        case "z", "offsetz" -> offsetZ = Double.parseDouble(value);
                    }
                } catch (NumberFormatException e) {
                    Text.sendDebugLog(WARN, "Invalid number in particle directive: " + parts[i]);
                }
            }
            
            return Optional.of(new ParticleInstruction(
                particle, count, speed, offsetX, offsetY, offsetZ
            ));
            
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error parsing particle directive: " + raw, e);
            return Optional.empty();
        }
    }

    private String joinTokens(List<?> rawTokens) {
        return rawTokens.stream().map(String::valueOf).collect(Collectors.joining(" "));
    }

    /**
     * Sends a message from the language file to a command sender.
     * Supports both legacy color codes and MiniMessage formatting.
     *
     * @param sender The command sender to send the message to
     * @param key    The message key from the language file
     * @param placeholders Alternating key-value pairs for placeholders (e.g., "{player}", "Steve")
     */
    public void sendMessage(CommandSender sender, String key, Object... placeholders) {
        if (sender == null || key == null) {
            return;
        }

        // Process placeholders into a map
        Map<String, Object> placeholderMap = new HashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                String placeholder = placeholders[i].toString();
                // Remove { and } if present for consistency
                if (placeholder.startsWith("{") && placeholder.endsWith("}")) {
                    placeholder = placeholder.substring(1, placeholder.length() - 1);
                }
                placeholderMap.put(placeholder, placeholders[i + 1]);
            }
        }

        MessageBundle bundle = getBundle(key);
        if (sender instanceof Player) {
            Player player = (Player) sender;
            // For players, use Adventure API for better formatting
            for (String line : bundle.coloredLines()) {
                // Apply placeholders
                for (Map.Entry<String, Object> entry : placeholderMap.entrySet()) {
                    line = line.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
                }
                
                net.kyori.adventure.text.Component component;
                // If message already has § codes, use legacy serializer
                if (line.contains("§")) {
                    component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line);
                } else {
                    // Otherwise, use MiniMessage
                    String processedLine = processMessage(line);
                    component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(processedLine);
                }
                player.sendMessage(component);
            }
            
            // Show particle effect if specified
            bundle.particle().ifPresent(particle -> particle.spawn(player));
        } else {
            // For console/command blocks, use legacy format
            for (String line : bundle.coloredLines()) {
                // Apply placeholders
                for (Map.Entry<String, Object> entry : placeholderMap.entrySet()) {
                    line = line.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
                }
                sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line));
            }
        }

        // Play sound if specified
        bundle.sound().ifPresent(soundInstruction -> {
            if (sender instanceof Player player) {
                player.playSound(player.getLocation(), soundInstruction.sound(), soundInstruction.volume(), soundInstruction.pitch());
            }
        });
    }

    /**
     * Sends a custom message to a sender while using a key for sound lookup.
     *
     * @param sender     The command sender to send the message to
     * @param soundKey  The message key from the language file (used for sound only)
     * @param message   The custom message to send
     */
    public void sendMessage(CommandSender sender, String soundKey, String message) {
        if (sender == null || message == null) {
            return;
        }

        MessageBundle bundle = getBundle(soundKey);
        
        if (sender instanceof Player player) {
            for (String line : message.split("\n")) {
                net.kyori.adventure.text.Component component;
                if (line.contains("§")) {
                    component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line);
                } else {
                    String processedLine = processMessage(line);
                    component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(processedLine);
                }
                player.sendMessage(component);
            }
            
            bundle.particle().ifPresent(particle -> particle.spawn(player));
        } else {
            for (String line : message.split("\n")) {
                sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line));
            }
        }

        bundle.sound().ifPresent(soundInstruction -> {
            if (sender instanceof Player player) {
                player.playSound(player.getLocation(), soundInstruction.sound(), soundInstruction.volume(), soundInstruction.pitch());
            }
        });
    }

    /**
     * Sends a message from the language file to a command sender.
     * Supports both legacy color codes and MiniMessage formatting.
     *
     * @param sender The command sender to send the message to
     * @param key    The message key from the language file
     */
    public void sendMessage(CommandSender sender, String key) {
        if (sender == null) {
            return;
        }

        MessageBundle bundle = getBundle(key);
        for (String line : bundle.coloredLines()) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            
            try {
                // First, check if the line contains MiniMessage tags
                if (line.matches(".*<[/]?[a-zA-Z0-9_:-]+>.*")) {
                    // Process with MiniMessage
                    net.kyori.adventure.text.Component component = 
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line);
                    
                    if (sender instanceof Player) {
                        ((Player) sender).sendMessage(component);
                    } else {
                        // For console, convert to legacy format
                        String legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection()
                            .serialize(component);
                        sender.sendMessage(legacy);
                    }
                } else {
                    // Handle legacy color codes
                    String legacyLine = line.replace('&', '§');
                    sender.sendMessage(legacyLine);
                }
            } catch (Exception e) {
                // If anything fails, fall back to legacy format
                String legacyLine = line.replace('&', '§');
                sender.sendMessage(legacyLine);
            }
        }

        // Play sound if specified
        bundle.sound().ifPresent(soundInstruction -> {
            if (sender instanceof Player player) {
                player.playSound(player.getLocation(), 
                    soundInstruction.sound(), 
                    soundInstruction.volume(), 
                    soundInstruction.pitch()
                );
            }
        });
    }

    /**
     * Sends a component message to a command sender with optional sound effects.
     * This method preserves all rich text formatting and interactivity of Adventure Components.
     *
     * @param sender    The command sender to send the message to
     * @param component The Adventure Component to send
     */
    public void sendMessage(CommandSender sender, net.kyori.adventure.text.Component component) {
        if (sender == null || component == null) {
            return;
        }

        if (sender instanceof Player) {
            ((Player) sender).sendMessage(component);
        } else {
            // Convert to legacy for console/command blocks
            sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component));
        }
    }

    /**
     * Sends a component message from the language file to a command sender.
     * This is a convenience method that combines getting a message by key and sending it as a component.
     *
     * @param sender The command sender to send the message to
     * @param key    The message key from the language file
     * @param args   Optional format arguments for the message
     * @return The sent component, or null if the message couldn't be sent
     */
    public net.kyori.adventure.text.Component sendComponentMessage(CommandSender sender, String key, Object... args) {
        if (sender == null) {
            return null;
        }

        MessageBundle bundle = getBundle(key, args);
        net.kyori.adventure.text.Component result = null;
        
        for (String line : bundle.coloredLines()) {
            net.kyori.adventure.text.Component component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line);
            if (result == null) result = component;
            
            if (sender instanceof Player) {
                ((Player) sender).sendMessage(component);
            } else {
                sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component));
            }
        }

        // Handle sound
        bundle.sound().ifPresent(soundInstruction -> {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (soundInstruction.sound() != null) {
                    player.playSound(player.getLocation(), soundInstruction.sound(), soundInstruction.volume(), soundInstruction.pitch());
                }
            }
        });

        return result;
    }

    private String applyCommandPlaceholders(String command, Player player) {
        if (command == null || player == null) {
            return "";
        }

        String resolved = command;
        resolved = resolved.replace("{player}", player.getName());
        resolved = resolved.replace("{uuid}", player.getUniqueId().toString());
        resolved = resolved.replace("{world}", player.getWorld().getName());

        return resolved.trim();
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
            Text.sendDebugLog(INFO, "Language setting changed from " + configLangCode + " to " + newConfigLangCode);
            Text.sendDebugLog(INFO, "To apply the new language, delete the lang.yml file and reload");
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
        Text.sendDebugLog(INFO, "Language system reloaded");
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
