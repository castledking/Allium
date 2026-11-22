package codes.castled.ores;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Legacy Allium kept ore settings in its own config.yml ({@code ore-generation} and {@code pregen}).
 * On Ores' first start they are copied into Ores' config through this key map; Allium keeps the
 * untouched legacy file as {@code config.legacy.yml}.
 */
final class LegacyOres {
    /** Legacy path -> Ores path. {@code ore-generation.ores} is copied whole. */
    static final Map<String, String> KEYS = Map.of(
            "ore-generation.enabled", "enabled",
            "ore-generation.auto-generate", "auto-generate",
            "ore-generation.worlds", "worlds",
            "ore-generation.jade.enabled", "jade.enabled",
            "pregen.chunks-per-tick", "pregen.chunks-per-tick",
            "pregen.delay-ticks", "pregen.delay-ticks",
            "pregen.generate-ores", "pregen.generate-ores");

    private LegacyOres() {}

    static boolean present(ConfigurationSection legacy) {
        return legacy.isConfigurationSection("ore-generation") || legacy.isConfigurationSection("pregen");
    }

    /** Copies the legacy ore settings into {@code target} (Ores' bundled config); returns the paths it wrote. */
    static List<String> apply(ConfigurationSection legacy, YamlConfiguration target) {
        List<String> written = new ArrayList<>();
        KEYS.forEach((from, to) -> {
            if (legacy.isSet(from) && !legacy.isConfigurationSection(from)) {
                target.set(to, legacy.get(from));
                written.add(to);
            }
        });
        ConfigurationSection ores = legacy.getConfigurationSection("ore-generation.ores");
        if (ores == null) ores = firstVersionOres(legacy.getConfigurationSection("ore-generation"));
        if (ores != null && !ores.getKeys(false).isEmpty()) {
            target.set("ores", null);
            for (String name : ores.getKeys(false)) {
                ConfigurationSection ore = ores.getConfigurationSection(name);
                if (ore == null) continue;
                for (String key : ore.getKeys(true)) {
                    if (!ore.isConfigurationSection(key)) target.set("ores." + name + "." + key, ore.get(key));
                }
                written.add("ores." + name);
            }
        }
        return List.copyOf(written);
    }

    /** Legacy's first ore settings listed each ore straight under ore-generation, with an oraxen-id. */
    private static ConfigurationSection firstVersionOres(ConfigurationSection root) {
        if (root == null) return null;
        var ores = new YamlConfiguration();
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section != null && (section.isSet("block") || section.isSet("oraxen-id"))) {
                for (String key : section.getKeys(true)) {
                    if (!section.isConfigurationSection(key)) ores.set(name + "." + key, section.get(key));
                }
            }
        }
        return ores.getKeys(false).isEmpty() ? null : ores;
    }
}
