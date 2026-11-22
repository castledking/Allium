package codes.castled.allium.modguard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * modguard.yml: read it, or on the first start write it from the bundled file and legacy Allium's
 * ModGuard settings. Legacy files are found where legacy left them or where /allium import tidy
 * archived them.
 */
public final class ModGuardFiles {
    public static final String FILE = "modguard.yml";
    static final String REPORT = "import/modguard-migration.txt";

    /** Finds a legacy file by its path under plugins/Allium, in place or archived. */
    @FunctionalInterface
    public interface Locator {
        Path find(String relative) throws IOException;
    }

    private final Path folder;
    private final Function<String, InputStream> resources;
    private final Consumer<String> log;
    private final Locator legacy;

    public ModGuardFiles(Path folder, Function<String, InputStream> resources, Consumer<String> log, Locator legacy) {
        this.folder = folder;
        this.resources = resources;
        this.log = log;
        this.legacy = legacy;
    }

    public YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        try (InputStream in = resources.apply(FILE)) {
            if (in == null) throw new IOException(FILE + " is missing from the jar");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    public YamlConfiguration load() throws IOException, InvalidConfigurationException {
        Path file = folder.resolve(FILE);
        if (!Files.exists(file)) create(file);
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private void create(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration target = bundled();
        ConfigurationSection settings = null;
        String settingsFrom = null;
        Path legacySettings = legacy.find("modguard/config.yml");
        if (Files.isRegularFile(legacySettings)) {
            settings = read(legacySettings);
            settingsFrom = folder.relativize(legacySettings).toString();
        } else {
            for (String name : new String[]{"config.legacy.yml", "config.yml"}) {
                Path config = legacy.find(name);
                if (!Files.isRegularFile(config)) continue;
                var section = read(config).getConfigurationSection("modguard");
                if (section != null) {
                    settings = section;
                    settingsFrom = folder.relativize(config) + " (modguard section)";
                    break;
                }
            }
        }
        Path legacyRules = legacy.find("modguard/rules.yml");
        YamlConfiguration rules = Files.isRegularFile(legacyRules) ? read(legacyRules) : null;
        if (settings != null || rules != null) {
            var result = LegacyModGuard.apply(settings, rules, target);
            StringBuilder report = new StringBuilder("# Legacy ModGuard settings carried into modguard.yml on ")
                    .append(java.time.LocalDate.now()).append(".\n# From: ")
                    .append(settingsFrom == null ? "-" : settingsFrom).append("; rules: ")
                    .append(rules == null ? "bundled" : folder.relativize(legacyRules)).append('\n');
            result.copied().forEach(line -> report.append("copied   ").append(line).append('\n'));
            result.retired().forEach(line -> report.append("retired  ").append(line).append('\n'));
            Path reportFile = folder.resolve(REPORT);
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, report.toString());
            log.accept("[ModGuard] Carried legacy ModGuard settings into " + FILE + ": " + result.copied().size()
                    + " kept, " + result.retired().size() + " retired; see " + REPORT + ".");
        }
        Files.createDirectories(folder);
        target.save(file.toFile());
    }

    private static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        return yaml;
    }
}
