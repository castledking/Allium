package codes.castled.allium.legacy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java 8 / 1.12.2-1.19.4 language manager reading Allium's real {@code lang/<locale>.yml} files
 * ({@code en_US.yml}, {@code es_ES.yml}, or custom locales) with full {@code [sound]} and
 * {@code [actionbar]} entry parsing and fallback to {@code en_US}.
 */
final class LegacyLanguageManager {

    static final String DEFAULT_LOCALE = "en_US";
    private static final String LANG_DIR = "lang";
    private static final String SOUND_PREFIX = "[sound]";
    private static final String ACTIONBAR_PREFIX = "[actionbar]";

    static final class SoundSpec {
        final String soundId;
        final Double volume;
        final Double pitch;

        SoundSpec(String soundId, Double volume, Double pitch) {
            this.soundId = soundId;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    static final class Entry {
        final String text;
        final String actionBar;
        final SoundSpec sound;
        final boolean soundDeclared;

        Entry(String text, String actionBar, SoundSpec sound, boolean soundDeclared) {
            this.text = text == null ? "" : text;
            this.actionBar = actionBar;
            this.sound = sound;
            this.soundDeclared = soundDeclared;
        }

        static Entry read(Object value) {
            if (value instanceof String) {
                String raw = (String) value;
                if (isActionBarLine(raw)) {
                    String bar = extractActionBar(raw);
                    return new Entry("", bar.isEmpty() ? null : bar, null, false);
                }
                return new Entry(raw, null, null, false);
            }
            if (value instanceof ConfigurationSection) {
                ConfigurationSection section = (ConfigurationSection) value;
                if (!section.contains("text") && !section.contains("actionbar") && !section.contains("sound")) {
                    return null;
                }
                String bar = section.contains("actionbar") ? section.getString("actionbar", "").trim() : null;
                Double vol = section.contains("volume") ? Double.valueOf(section.getDouble("volume")) : null;
                Double pitch = section.contains("pitch") ? Double.valueOf(section.getDouble("pitch")) : null;
                return new Entry(
                        section.getString("text", ""),
                        bar == null || bar.isEmpty() ? null : bar,
                        parseSound(section.getString("sound"), vol, pitch),
                        section.contains("sound")
                );
            }
            if (!(value instanceof List)) {
                return null;
            }
            List<?> lines = (List<?>) value;
            List<String> textLines = new ArrayList<String>();
            String actionBar = null;
            boolean actionBarSeen = false;
            SoundSpec selected = null;
            boolean soundSeen = false;
            for (Object raw : lines) {
                if (!(raw instanceof String)) continue;
                String line = (String) raw;
                if (line.startsWith(SOUND_PREFIX)) {
                    if (soundSeen) continue;
                    soundSeen = true;
                    String rest = line.substring(SOUND_PREFIX.length()).trim();
                    if (rest.isEmpty()) continue;
                    String[] parts = rest.split("\\s+");
                    if (parts.length < 1 || parts.length > 3) continue;
                    Double pitch = parts.length >= 2 ? parseDouble(parts[1]) : null;
                    Double volume = parts.length == 3 ? parseDouble(parts[2]) : null;
                    selected = parseSound(parts[0], volume, pitch);
                } else if (isActionBarLine(line)) {
                    if (actionBarSeen) continue;
                    actionBarSeen = true;
                    String bar = extractActionBar(line);
                    if (!bar.isEmpty()) actionBar = bar;
                } else {
                    textLines.add(line);
                }
            }
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < textLines.size(); i++) {
                if (i > 0) joined.append('\n');
                joined.append(textLines.get(i));
            }
            return new Entry(joined.toString(), actionBar, selected, soundSeen);
        }

        private static boolean isActionBarLine(String line) {
            return line.length() >= ACTIONBAR_PREFIX.length()
                    && line.regionMatches(true, 0, ACTIONBAR_PREFIX, 0, ACTIONBAR_PREFIX.length())
                    && (line.length() == ACTIONBAR_PREFIX.length()
                            || Character.isWhitespace(line.charAt(ACTIONBAR_PREFIX.length())));
        }

        private static String extractActionBar(String line) {
            return line.substring(ACTIONBAR_PREFIX.length()).trim();
        }

        private static Double parseDouble(String s) {
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? Double.valueOf(d) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static SoundSpec parseSound(String id, Double volume, Double pitch) {
            if (id == null) return null;
            String trimmed = id.trim();
            if (trimmed.isEmpty()) return null;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if ("none".equals(lower) || "off".equals(lower)) return null;
            if (trimmed.indexOf(':') < 0 && trimmed.indexOf('.') >= 0) {
                trimmed = "minecraft:" + trimmed;
            }
            return new SoundSpec(trimmed, volume, pitch);
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager configManager;
    private volatile String activeLocale = DEFAULT_LOCALE;
    private volatile Map<String, FileConfiguration> loaded = new HashMap<String, FileConfiguration>();

    LegacyLanguageManager(JavaPlugin plugin, LegacyConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    synchronized void load() {
        String active = configManager.locale();
        Map<String, FileConfiguration> next = new HashMap<String, FileConfiguration>();
        next.put(DEFAULT_LOCALE, loadLocale(DEFAULT_LOCALE));
        if (!DEFAULT_LOCALE.equals(active)) {
            next.put(active, loadLocale(active));
        }
        this.activeLocale = active;
        this.loaded = next;
    }

    void reload() {
        load();
    }

    String getActiveLocale() {
        return activeLocale;
    }

    int getLoadedEntryCount() {
        FileConfiguration cfg = loaded.get(activeLocale);
        if (cfg == null) cfg = loaded.get(DEFAULT_LOCALE);
        if (cfg == null) return 0;
        int count = 0;
        for (String key : cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) count++;
        }
        return count;
    }

    String getPrefix() {
        String prefix = getText("prefix", "&7[&dAllium&7]");
        return prefix == null || prefix.isEmpty() ? "&7[&dAllium&7]" : prefix;
    }

    String getText(String key, String defaultText) {
        Entry entry = readEntry(loaded.get(activeLocale), key);
        if (entry != null) return entry.text;
        entry = readEntry(loaded.get(DEFAULT_LOCALE), key);
        if (entry != null) return entry.text;
        Entry fallback = defaultText == null ? null : Entry.read(defaultText);
        return fallback != null ? fallback.text : (defaultText == null ? "" : defaultText);
    }

    String getActionBar(String key) {
        Entry entry = readEntry(loaded.get(activeLocale), key);
        if (entry != null) return entry.actionBar;
        entry = readEntry(loaded.get(DEFAULT_LOCALE), key);
        return entry != null ? entry.actionBar : null;
    }

    SoundSpec getSound(String key) {
        Entry active = readEntry(loaded.get(activeLocale), key);
        if (active != null && (active.soundDeclared || DEFAULT_LOCALE.equals(activeLocale))) {
            return active.sound;
        }
        Entry fallback = readEntry(loaded.get(DEFAULT_LOCALE), key);
        return fallback != null ? fallback.sound : (active != null ? active.sound : null);
    }

    private Entry readEntry(FileConfiguration cfg, String key) {
        return cfg == null ? null : Entry.read(cfg.get(key));
    }

    private FileConfiguration loadLocale(String locale) {
        String resourcePath = LANG_DIR + "/" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            if (plugin.getResource(resourcePath) != null) {
                try {
                    plugin.saveResource(resourcePath, false);
                } catch (Throwable ignored) {
                }
            } else {
                copyDefaultLocale(file);
            }
        }
        YamlConfiguration yml = file.isFile()
                ? YamlConfiguration.loadConfiguration(file)
                : loadBundledYaml(resourcePath);

        YamlConfiguration bundled = loadBundledYaml(resourcePath);
        if (bundled == null && !DEFAULT_LOCALE.equals(locale)) {
            bundled = loadBundledYaml(LANG_DIR + "/" + DEFAULT_LOCALE + ".yml");
        }
        if (bundled != null) {
            int added = LegacyConfigManager.syncMissingKeys(yml, bundled);
            if (added > 0 && file.isFile()) {
                try {
                    yml.save(file);
                } catch (IOException ignored) {
                }
            }
        }
        plugin.getLogger().info("Loaded locale: " + locale);
        return yml;
    }

    private void copyDefaultLocale(File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = plugin.getResource(LANG_DIR + "/" + DEFAULT_LOCALE + ".yml");
        if (in == null) return;
        try {
            Files.copy(in, target.toPath());
        } catch (IOException ignored) {
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private YamlConfiguration loadBundledYaml(String resourcePath) {
        InputStream in = plugin.getResource(resourcePath);
        if (in == null) return new YamlConfiguration();
        try {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
