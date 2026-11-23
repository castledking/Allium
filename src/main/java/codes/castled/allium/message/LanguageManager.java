package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import codes.castled.allium.config.ResourceSyncManager;
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
    private volatile LocaleSnapshot locales = new LocaleSnapshot(DEFAULT_LOCALE, Map.of());

    /** Each configuration is prepared before publication and read only afterward. */
    private record LocaleSnapshot(String active, Map<String, FileConfiguration> loaded) {
        LanguageEntry activeEntry(String key) { return readEntry(loaded.get(active), key); }
        LanguageEntry defaultEntry(String key) { return readEntry(loaded.get(DEFAULT_LOCALE), key); }
    }

    public LanguageManager(PluginStart plugin, ResourceSyncManager syncManager) {
        this.plugin = plugin;
        this.syncManager = syncManager;
    }

    public synchronized void load() {
        String active = plugin.getConfig().getString("settings.locale", DEFAULT_LOCALE);
        Map<String, FileConfiguration> loaded = new HashMap<>();
        loaded.put(DEFAULT_LOCALE, loadLocale(DEFAULT_LOCALE));
        if (!DEFAULT_LOCALE.equals(active)) {
            loaded.put(active, loadLocale(active));
        }
        syncAllMissingKeys(loaded);
        locales = new LocaleSnapshot(active, Map.copyOf(loaded));
    }

    private FileConfiguration loadLocale(String locale) {
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
        plugin.logger().info("Loaded locale: " + locale);
        return config;
    }

    /** A locale Allium does not ship starts as a copy of the bundled default locale, sounds included. */
    private void generateLocaleFile(File file) {
        file.getParentFile().mkdirs();
        try (java.io.InputStream bundled = plugin.getResource(LANG_DIR + "/" + DEFAULT_LOCALE + ".yml")) {
            if (bundled != null) {
                java.nio.file.Files.copy(bundled, file.toPath());
                return;
            }
        } catch (IOException e) {
            plugin.logger().warning("Could not copy the default locale for " + file.getName() + "; generating it from defaults.");
        }
        YamlConfiguration config = new YamlConfiguration();
        for (Messages msg : Messages.values()) {
            config.set(msg.getKey(), msg==Messages.PREFIX?msg.getDefault():java.util.List.of(msg.getDefault()));
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.logger().severe("Failed to generate locale file: " + file.getName());
        }
    }

    private void syncAllMissingKeys(Map<String, FileConfiguration> loaded) {
        for (Map.Entry<String, FileConfiguration> entry : loaded.entrySet()) {
            String locale = entry.getKey();
            FileConfiguration config = entry.getValue();
            File file = new File(plugin.getDataFolder(), LANG_DIR + "/" + locale + ".yml");
            boolean dirty = false;

            for (Messages msg : Messages.values()) {
                if (!config.contains(msg.getKey())) {
                    config.set(msg.getKey(), msg==Messages.PREFIX?msg.getDefault():java.util.List.of(msg.getDefault()));
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
        LocaleSnapshot current = locales;
        LanguageEntry entry = current.activeEntry(key);
        if (entry != null) {
            return entry.text();
        }

        entry = current.defaultEntry(key);
        if (entry != null) {
            return entry.text();
        }

        LanguageEntry fallbackEntry = defaultText == null ? null : LanguageEntry.read(defaultText);
        return fallbackEntry == null ? defaultText : fallbackEntry.text();
    }

    @Nullable
    public String getActionBar(Messages message) {
        return getActionBar(message.getKey(), message.getDefault());
    }

    @Nullable
    public String getActionBar(String key) {
        return getActionBar(key, null);
    }

    @Nullable
    public String getActionBar(String key, @Nullable String defaultText) {
        LocaleSnapshot current = locales;
        LanguageEntry entry = current.activeEntry(key);
        if (entry != null) {
            return entry.actionBar();
        }

        entry = current.defaultEntry(key);
        if (entry != null) {
            return entry.actionBar();
        }

        LanguageEntry fallbackEntry = defaultText == null ? null : LanguageEntry.read(defaultText);
        return fallbackEntry == null ? null : fallbackEntry.actionBar();
    }

    @Nullable
    public MessageSoundSpec getSound(Messages message) {
        return getSound(message.getKey());
    }

    @Nullable
    public MessageSoundSpec getSound(String key) {
        LocaleSnapshot current = locales;
        LanguageEntry active = current.activeEntry(key);
        // A translation without a sound line keeps the default locale's sound; [sound] none silences it.
        if (active != null && (active.soundDeclared() || DEFAULT_LOCALE.equals(current.active()))) return active.sound();

        LanguageEntry fallback = current.defaultEntry(key);
        return fallback != null ? fallback.sound() : active == null ? null : active.sound();
    }

    /** Whether the active or default locale has a message (text, actionbar, or sound) at {@code key}. */
    public boolean hasKey(String key) {
        LocaleSnapshot current = locales;
        return current.activeEntry(key) != null || current.defaultEntry(key) != null;
    }

    @Nullable
    private static LanguageEntry readEntry(@Nullable FileConfiguration config, String key) {
        return config == null ? null : LanguageEntry.read(config.get(key));
    }

    public String getRaw(Messages message) {
        return get(message);
    }

    public String getActiveLocale() {
        return locales.active();
    }

    public synchronized void setActiveLocale(String locale) {
        Map<String, FileConfiguration> loaded = locales.loaded();
        if (!loaded.containsKey(locale)) {
            var updated = new HashMap<>(loaded);
            updated.put(locale, loadLocale(locale));
            loaded = Map.copyOf(updated);
        }
        locales = new LocaleSnapshot(locale, loaded);
    }

    public void reload() {
        load();
    }

    public boolean isLocaleLoaded(String locale) {
        return locales.loaded().containsKey(locale);
    }
}
