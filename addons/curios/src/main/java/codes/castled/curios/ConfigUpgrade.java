package codes.castled.curios;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/** Upgrades a {@code config.yml} written by an earlier Curios build. */
final class ConfigUpgrade {
    /** The config.yml layout this build writes. */
    static final int VERSION = 3;

    private ConfigUpgrade() {}

    /**
     * Brings a config.yml written by an earlier Curios build up to {@link #VERSION}.
     * <ul>
     *   <li>v1 -&gt; v2: adds missing top-level sections and turns {@code textured-menu} back on.</li>
     *   <li>v2 -&gt; v3: adds missing relic {@code abilities}, {@code attributes}, and {@code effects}
     *       blocks from defaults onto existing bundled relic entries without overwriting user edits
     *       or flipping {@code textured-menu}.</li>
     * </ul>
     *
     * @return whether the config changed and should be saved
     */
    static boolean apply(Configuration config) {
        int current = config.isSet("config-version") ? config.getInt("config-version") : 1;
        if (current >= VERSION) return false;
        Configuration defaults = config.getDefaults();
        if (defaults != null) {
            for (String key : defaults.getKeys(false)) {
                // Reading a missing section through getConfigurationSection leaves an empty one behind.
                if (config.isSet(key) && !(config.get(key) instanceof ConfigurationSection present
                        && present.getKeys(false).isEmpty())) continue;
                var section = defaults.getConfigurationSection(key);
                if (section == null) {
                    config.set(key, defaults.get(key));
                    continue;
                }
                for (String path : section.getKeys(true)) {
                    if (!section.isConfigurationSection(path)) config.set(key + "." + path, section.get(path));
                }
            }
            if (current < 3) {
                upgradeRelicDefaults(config, defaults);
            }
        }
        if (current < 2 && config.isSet("textured-menu") && !config.getBoolean("textured-menu")) {
            config.set("textured-menu", true);
        }
        config.set("config-version", VERSION);
        return true;
    }

    private static void upgradeRelicDefaults(Configuration config, Configuration defaults) {
        ConfigurationSection defaultItems = defaults.getConfigurationSection("items");
        ConfigurationSection currentItems = config.getConfigurationSection("items");
        if (defaultItems == null || currentItems == null) return;
        for (String id : defaultItems.getKeys(false)) {
            if (!currentItems.isConfigurationSection(id)) continue;
            ConfigurationSection defRow = defaultItems.getConfigurationSection(id);
            ConfigurationSection curRow = currentItems.getConfigurationSection(id);
            if (defRow == null || curRow == null) continue;
            if (defRow.isSet("abilities") && !curRow.isSet("abilities")) {
                curRow.set("abilities", defRow.getStringList("abilities"));
            }
            if (defRow.isSet("attributes") && !curRow.isSet("attributes")) {
                curRow.set("attributes", defRow.getList("attributes"));
            }
            if (defRow.isConfigurationSection("effects") && !curRow.isSet("effects")) {
                ConfigurationSection defEffects = defRow.getConfigurationSection("effects");
                if (defEffects != null) {
                    for (String k : defEffects.getKeys(false)) {
                        curRow.set("effects." + k, defEffects.get(k));
                    }
                }
            }
        }
    }
}
