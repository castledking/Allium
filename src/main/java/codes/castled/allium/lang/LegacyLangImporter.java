package codes.castled.allium.lang;

import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.ToolDefinitionLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code /allium import lang [apply]}: moves messages from a pre-recode {@code plugins/Allium/lang.yml}
 * into the active {@code lang/<locale>.yml}, through {@link LegacyLangImport}.
 *
 * <p>Without {@code apply} it is a dry run that writes nothing. A message is written only when the
 * target still has Allium's own default (or nothing) there; a message an operator already changed
 * in the recode is kept and reported. Running it again changes nothing ({@code SAME}). Applying
 * first copies the target to {@code <file>.before-import-<timestamp>}. The legacy file is only
 * read.
 */
public final class LegacyLangImporter {

    public static final String SOURCE = "lang.yml";

    public enum Status { WRITE, SAME, KEPT }

    public record Item(LegacyLangImport.Entry entry, Status status) {
    }

    public record Plan(File source, File target, List<Item> items, LegacyLangImport.Report report) {
        public Plan {
            items = List.copyOf(items);
        }

        public long count(Status status) {
            return items.stream().filter(i -> i.status() == status).count();
        }
    }

    /** One message as stored in a lang file, for comparison. */
    record Stored(String text, @Nullable String sound, @Nullable Double volume, @Nullable Double pitch) {
        static Stored of(LegacyLangImport.Entry entry) {
            return new Stored(entry.text(), entry.sound(), entry.volume(), entry.pitch());
        }

        @Nullable
        static Stored at(@Nullable ConfigurationSection root, String key) {
            if (root == null || !root.contains(key)) {
                return null;
            }
            if (root.isConfigurationSection(key)) {
                ConfigurationSection section = Objects.requireNonNull(root.getConfigurationSection(key));
                return new Stored(section.getString("text", ""), section.getString("sound"),
                        section.contains("volume") ? section.getDouble("volume") : null,
                        section.contains("pitch") ? section.getDouble("pitch") : null);
            }
            return new Stored(String.valueOf(root.get(key)), null, null, null);
        }
    }

    private LegacyLangImporter() {
    }

    /**
     * @param bundled opens the jar's {@code lang/<locale>.yml}, or returns null when it has none
     */
    public static Plan plan(File dataFolder, String locale, Function<String, InputStream> bundled) throws IOException, InvalidConfigurationException {
        File source = new File(dataFolder, SOURCE);
        File target = new File(dataFolder, "lang/" + locale + ".yml");
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.load(source);
        YamlConfiguration current = target.exists() ? YamlConfiguration.loadConfiguration(target) : new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = bundled.apply("lang/" + locale + ".yml")) {
            if (in != null) {
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        LegacyLangImport.Report report = LegacyLangImport.convert(ToolDefinitionLoader.toMap(legacy), key -> {
            Messages message = Messages.fromKey(key);
            return message == null ? null : message.getDefault();
        });
        List<Item> items = new ArrayList<>();
        for (LegacyLangImport.Entry entry : report.entries()) {
            Stored wanted = Stored.of(entry);
            Stored now = Stored.at(current, entry.key());
            Status status;
            if (wanted.equals(now)) {
                status = Status.SAME;
            } else if (now == null || now.equals(Stored.at(defaults, entry.key())) || isBuiltIn(entry.key(), now)) {
                status = Status.WRITE;
            } else {
                status = Status.KEPT;
            }
            items.add(new Item(entry, status));
        }
        return new Plan(source, target, items, report);
    }

    /** Writes every {@code WRITE} item; returns the backup of the target. */
    public static File apply(Plan plan) throws IOException {
        File target = plan.target();
        File backup = new File(target.getParentFile(), target.getName() + ".before-import-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
        YamlConfiguration yaml = target.exists() ? YamlConfiguration.loadConfiguration(target) : new YamlConfiguration();
        for (Item item : plan.items()) {
            if (item.status() != Status.WRITE) {
                continue;
            }
            LegacyLangImport.Entry entry = item.entry();
            yaml.set(entry.key(), null);
            if (entry.sound() == null) {
                yaml.set(entry.key(), entry.text());
            } else {
                ConfigurationSection section = yaml.createSection(entry.key());
                section.set("text", entry.text());
                section.set("sound", entry.sound());
                if (entry.volume() != null) {
                    section.set("volume", entry.volume());
                }
                if (entry.pitch() != null) {
                    section.set("pitch", entry.pitch());
                }
            }
        }
        target.getParentFile().mkdirs();
        yaml.save(target);
        return backup;
    }

    private static boolean isBuiltIn(String key, Stored now) {
        Messages message = Messages.fromKey(key);
        return message != null && now.sound() == null && now.text().equals(message.getDefault());
    }
}
