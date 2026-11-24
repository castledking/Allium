package codes.castled.partylocator;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Legacy Allium kept the party locator settings in its config.yml ({@code party-manager}). On the
 * addon's first start they are copied into its own config.yml; Allium keeps the legacy file as
 * config.legacy.yml.
 */
final class LegacyPartySettings {
    static final List<String> KEYS = List.of("party-locator-bar", "show-non-party-members-radius", "sync-with-mcmmo");

    private LegacyPartySettings() {}

    /** Copies {@code party-manager.*} into {@code target}; returns the keys it wrote. */
    static List<String> apply(ConfigurationSection legacy, YamlConfiguration target) {
        List<String> written = new ArrayList<>();
        for (String key : KEYS) {
            String from = "party-manager." + key;
            if (legacy.isSet(from)) {
                target.set(key, legacy.get(from));
                written.add(key);
            }
        }
        return List.copyOf(written);
    }

    /** Writes config.yml from legacy settings when there is no config.yml yet and legacy had some. */
    static void importOnFirstStart(JavaPlugin plugin) {
        importOnFirstStart(plugin, List.of());
    }

    /** Writes config.yml from legacy settings using Allium's exposed active/archived config sources. */
    static void importOnFirstStart(JavaPlugin plugin, List<java.nio.file.Path> sharedSources) {
        File config = new File(plugin.getDataFolder(), "config.yml");
        var allium = plugin.getServer().getPluginManager().getPlugin("Allium");
        if (config.exists() || allium == null) return;
        List<File> candidates = sharedSources != null && !sharedSources.isEmpty()
                ? sharedSources.stream().map(java.nio.file.Path::toFile).toList()
                : sources(allium.getDataFolder());
        for (File source : candidates) {
            try {
                var legacy = new YamlConfiguration();
                legacy.load(source);
                if (!legacy.isConfigurationSection("party-manager")) continue;
                var target = new YamlConfiguration();
                try (var reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("config.yml")), StandardCharsets.UTF_8)) {
                    target.load(reader);
                }
                List<String> written = apply(legacy, target);
                plugin.getDataFolder().mkdirs();
                target.save(config);
                plugin.getLogger().info("Took " + written.size() + " party setting(s) from legacy Allium's "
                        + allium.getDataFolder().toPath().relativize(source.toPath()) + ".");
                return;
            } catch (IOException | InvalidConfigurationException unreadable) {
                plugin.getLogger().warning("Could not read " + source + " for party settings: " + unreadable.getMessage());
            }
        }
    }

    /** Legacy Allium's config, newest first: the kept copy, the copy /allium import tidy archived, then config.yml. */
    static List<File> sources(File allium) {
        List<File> found = new ArrayList<>();
        File kept = new File(allium, "config.legacy.yml");
        if (kept.isFile()) found.add(kept);
        File[] archives = new File(allium, "import/backups").listFiles(file -> file.isDirectory() && file.getName().startsWith("tidy-"));
        if (archives != null) {
            Arrays.sort(archives, Comparator.comparing(File::getName).reversed());
            for (File archive : archives) {
                File archived = new File(archive, "config.legacy.yml");
                if (archived.isFile()) found.add(archived);
            }
        }
        File config = new File(allium, "config.yml");
        if (config.isFile()) found.add(config);
        return found;
    }
}
