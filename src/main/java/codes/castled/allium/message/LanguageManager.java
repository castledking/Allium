package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import codes.castled.allium.config.ResourceSyncManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class LanguageManager {

    public static final String DEFAULT_LOCALE = "en_US";
    private static final String LANG_DIR = "lang";

    private final PluginStart plugin;
    private final ResourceSyncManager syncManager;
    private final Map<String, FileConfiguration> loadedLocales = new HashMap<>();
    private String activeLocale;

    public LanguageManager(PluginStart plugin, ResourceSyncManager syncManager) {
        this.plugin = plugin;
        this.syncManager = syncManager;
    }

    public void load() {
        activeLocale = plugin.getConfig().getString("settings.locale", DEFAULT_LOCALE);

        loadLocale(DEFAULT_LOCALE);

        if (!DEFAULT_LOCALE.equals(activeLocale)) {
            loadLocale(activeLocale);
        }

        syncAllMissingKeys();
    }

    private void loadLocale(String locale) {
        String resourcePath = LANG_DIR + "/" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), resourcePath);

        if (!file.exists()) {
            if (plugin.getResource(resourcePath) != null) {
                plugin.saveResource(resourcePath, false);
            } else {
                plugin.logger().warning("Locale file not bundled: " + resourcePath + ". Generating from defaults.");
                generateLocaleFile(file);
            }
        }

        syncManager.syncResource(resourcePath);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        loadedLocales.put(locale, config);
        plugin.logger().info("Loaded locale: " + locale);
    }

    private void generateLocaleFile(File file) {
        file.getParentFile().mkdirs();
        YamlConfiguration config = new YamlConfiguration();
        for (Messages msg : Messages.values()) {
            config.set(msg.getKey(), msg.getDefault());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.logger().severe("Failed to generate locale file: " + file.getName());
        }
    }

    private void syncAllMissingKeys() {
        for (Map.Entry<String, FileConfiguration> entry : loadedLocales.entrySet()) {
            String locale = entry.getKey();
            FileConfiguration config = entry.getValue();
            File file = new File(plugin.getDataFolder(), LANG_DIR + "/" + locale + ".yml");
            boolean dirty = false;

            for (Messages msg : Messages.values()) {
                if (!config.contains(msg.getKey())) {
                    config.set(msg.getKey(), msg.getDefault());
                    dirty = true;
                    plugin.logger().debug("Inserted missing key '" + msg.getKey() + "' into " + locale);
                }
            }

            if (dirty) {
                try {
                    config.save(file);
                } catch (IOException e) {
                    plugin.logger().severe("Failed to save synced locale: " + locale);
                }
            }
        }
    }

    public String get(Messages message) {
        return get(message.getKey(), message.getDefault());
    }

    /** The text at {@code key} in the active locale, then the default locale, else {@code defaultText}. */
    public String get(String key, String defaultText) {
        FileConfiguration active = loadedLocales.get(activeLocale);
        String value = readText(active, key);
        if (value != null) {
            return value;
        }

        FileConfiguration fallback = loadedLocales.get(DEFAULT_LOCALE);
        value = readText(fallback, key);
        if (value != null) {
            return value;
        }

        return defaultText;
    }

    @Nullable
    public MessageSoundSpec getSound(Messages message) {
        return getSound(message.getKey());
    }

    @Nullable
    public MessageSoundSpec getSound(String key) {
        FileConfiguration active = loadedLocales.get(activeLocale);
        MessageSoundSpec spec = readSound(active, key);
        if (spec != null) {
            return spec;
        }

        FileConfiguration fallback = loadedLocales.get(DEFAULT_LOCALE);
        return readSound(fallback, key);
    }

    /** Whether the active or default locale has a message (text or sound) at {@code key}. */
    public boolean hasKey(String key) {
        return readText(loadedLocales.get(activeLocale), key) != null
                || readText(loadedLocales.get(DEFAULT_LOCALE), key) != null;
    }

    @Nullable
    private static String readText(@Nullable FileConfiguration config, String key) {
        if (config == null) {
            return null;
        }
        if (config.isConfigurationSection(key)) {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null) {
                return null;
            }
            if (section.contains("text")) {
                return section.getString("text", "");
            }
            if (section.contains("sound")) {
                // Sound-only entry is valid and has empty text
                return "";
            }
            return null;
        }
        return config.getString(key);
    }

    @Nullable
    private static MessageSoundSpec readSound(@Nullable FileConfiguration config, String key) {
        if (config == null || !config.isConfigurationSection(key)) {
            return null;
        }
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null) {
            return null;
        }
        String soundId = section.getString("sound");
        // "none" (or "off") switches a message's sound off without a diagnostic.
        if (soundId == null || soundId.isBlank() || soundId.trim().equalsIgnoreCase("none") || soundId.trim().equalsIgnoreCase("off")) {
            return null;
        }
        Double volume = section.contains("volume") ? section.getDouble("volume") : null;
        Double pitch = section.contains("pitch") ? section.getDouble("pitch") : null;
        return new MessageSoundSpec(soundId, volume, pitch);
    }

    public String getRaw(Messages message) {
        return get(message);
    }

    public String getActiveLocale() {
        return activeLocale;
    }

    public void setActiveLocale(String locale) {
        if (!loadedLocales.containsKey(locale)) {
            loadLocale(locale);
        }
        this.activeLocale = locale;
    }

    public void reload() {
        loadedLocales.clear();
        load();
    }

    public boolean isLocaleLoaded(String locale) {
        return loadedLocales.containsKey(locale);
    }
}
