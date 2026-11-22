package codes.castled.ores;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Ores' config.yml, read once per load. */
record Settings(boolean enabled, boolean automatic, Set<String> worlds, boolean jade, int chunksPerTick,
                int delayTicks, boolean pregenOres, List<OreDefinition> ores) {

    static Settings read(ConfigurationSection config, Consumer<String> warn) {
        List<OreDefinition> ores = new ArrayList<>();
        ConfigurationSection root = config.getConfigurationSection("ores");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(name);
                if (section == null || !section.getBoolean("enabled", true)) continue;
                try {
                    ores.add(OreDefinition.read(name, section));
                } catch (IllegalArgumentException invalid) {
                    warn.accept("Ore " + name + " is skipped: " + invalid.getMessage());
                }
            }
        }
        return new Settings(config.getBoolean("enabled", false), config.getBoolean("auto-generate", true),
                Set.copyOf(config.getStringList("worlds")), config.getBoolean("jade.enabled", true),
                clamp(config.getInt("pregen.chunks-per-tick", 2), 1, 16), clamp(config.getInt("pregen.delay-ticks", 1), 1, 200),
                config.getBoolean("pregen.generate-ores", true), List.copyOf(ores));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
