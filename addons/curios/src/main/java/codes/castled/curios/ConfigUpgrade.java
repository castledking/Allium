package codes.castled.curios;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/** Upgrades a {@code config.yml} written by an earlier Curios build. */
final class ConfigUpgrade {
    /** The config.yml layout this build writes. */
    static final int VERSION = 2;

    private ConfigUpgrade() {}

    /**
     * Brings a config.yml written by an earlier Curios build up to {@link #VERSION}. It adds
     * the top-level sections the file lacks: Bukkit reads a section that is only in the jar's
     * defaults as empty, which left card names white and dropped the card menu settings. It also
     * turns {@code textured-menu} back on, which builds before version 2 wrote as {@code false}
     * while the card art was still being ported. A config already at this version is left alone,
     * so a later {@code textured-menu: false} is kept.
     *
     * @return whether the config changed and should be saved
     */
    static boolean apply(Configuration config) {
        if (config.isSet("config-version") && config.getInt("config-version") >= VERSION) return false;
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
        }
        if (config.isSet("textured-menu") && !config.getBoolean("textured-menu")) config.set("textured-menu", true);
        config.set("config-version", VERSION);
        return true;
    }
}
