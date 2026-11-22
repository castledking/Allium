package codes.castled.allium.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class LegacyConfigMigration {
    static final String SOURCE = "config.legacy.yml";

    record Result(List<String> changed, List<String> kept) {}

    private LegacyConfigMigration() {}

    static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        var config = new YamlConfiguration();
        config.load(path.toFile());
        return config;
    }

    static boolean legacy(YamlConfiguration config) {
        return config.isSet("version") && (config.isConfigurationSection("chat")
                || config.isConfigurationSection("auto-restart") || config.isConfigurationSection("redeem-settings"));
    }

    static void backup(Path file, Path backup) throws IOException {
        if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    static Result merge(ConfigurationSection source, YamlConfiguration target, YamlConfiguration defaults) {
        var changed = new ArrayList<String>();
        var kept = new ArrayList<String>();
        for (String path : source.getKeys(true)) {
            if (!path.equals("version") && !path.equals("config-version") && !source.isConfigurationSection(path)) {
                write(target, defaults, path, source.get(path), changed, kept);
            }
        }
        map(source, target, defaults, "teleport.delay", "teleport.warmup-seconds", changed, kept);
        map(source, target, defaults, "teleport.expire", "teleport.request-expiry-seconds", changed, kept);
        map(source, target, defaults, "teleport.cooldown", "teleport.cooldowns.tpa", changed, kept);
        map(source, target, defaults, "teleport.cooldown", "teleport.cooldowns.tpahere", changed, kept);
        map(source, target, defaults, "home.cooldown", "teleport.cooldowns.home", changed, kept);
        map(source, target, defaults, "restore.snapshot-retention-days", "restore.retention-days", changed, kept);
        if (source.isBoolean("economy.symbol-before-amount")) {
            write(target, defaults, "economy.currency-symbol-suffix", !source.getBoolean("economy.symbol-before-amount"),
                    changed, kept);
        }
        return new Result(List.copyOf(changed), List.copyOf(kept));
    }

    private static void map(ConfigurationSection source, YamlConfiguration target, YamlConfiguration defaults,
                            String oldPath, String newPath, List<String> changed, List<String> kept) {
        if (source.isSet(oldPath)) write(target, defaults, newPath, source.get(oldPath), changed, kept);
    }

    private static void write(YamlConfiguration target, YamlConfiguration defaults, String path, Object value,
                              List<String> changed, List<String> kept) {
        Object previous = target.get(path);
        if (Objects.equals(previous, value)) return;
        for (int separator = path.indexOf('.'); separator != -1; separator = path.indexOf('.', separator + 1)) {
            String parent = path.substring(0, separator);
            if (target.isSet(parent) && !target.isConfigurationSection(parent)) {
                kept.add(path);
                return;
            }
        }
        if (previous != null && !Objects.equals(previous, defaults.get(path))) {
            kept.add(path);
            return;
        }
        target.set(path, value);
        changed.add(path);
    }
}
