package codes.castled.allium.tool;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.disarmer.LegacyMobDisarmerConfig;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads tool definitions from {@code plugins/Allium/tools/*.yml}, in file-name order.
 *
 * <p>The first start creates {@code tools/} with the bundled {@code mob_disarmer.yml}. When
 * {@code config.yml} still has an older {@code tools.mob-disarmer} section, its values are
 * moved into that file instead, so a customized Mob Disarmer keeps behaving the same. After
 * that, the old section is no longer read.
 *
 * <p>Beyond each file's own checks, definitions must not share an identity or an appearance,
 * and must not claim an appearance another identity already owns. A definition that fails any
 * check is rejected whole; the others still load.
 */
public final class ToolDefinitionLoader {

    public static final String DIRECTORY = "tools";
    public static final String MOB_DISARMER_FILE = "mob_disarmer.yml";
    public static final String MOB_DISARMER_RESOURCE = DIRECTORY + "/" + MOB_DISARMER_FILE;
    /** Which bundled defaults were installed once, so a default the operator deleted stays deleted. */
    public static final String INSTALLED_FILE = ".defaults";

    private final File dataFolder;
    private final Supplier<InputStream> bundledMobDisarmer;
    private final Function<String, InputStream> resources;
    private final List<String> bundledDefaults;
    private final Consumer<String> info;
    private final Consumer<String> warning;

    /**
     * @param bundledMobDisarmer opens the jar's {@code tools/mob_disarmer.yml}
     */
    public ToolDefinitionLoader(File dataFolder, Supplier<InputStream> bundledMobDisarmer, Consumer<String> info, Consumer<String> warning) {
        this(dataFolder, path -> MOB_DISARMER_RESOURCE.equals(path) ? bundledMobDisarmer.get() : null, List.of(MOB_DISARMER_FILE), info, warning);
    }

    /**
     * @param resources       opens a jar resource such as {@code tools/phantom_obliterator.yml}
     * @param bundledDefaults the default definition files the jar ships, {@code mob_disarmer.yml} first
     */
    public ToolDefinitionLoader(File dataFolder, Function<String, InputStream> resources, List<String> bundledDefaults,
                                Consumer<String> info, Consumer<String> warning) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.bundledMobDisarmer = () -> resources.apply(MOB_DISARMER_RESOURCE);
        this.bundledDefaults = List.copyOf(bundledDefaults);
        this.info = Objects.requireNonNull(info, "info");
        this.warning = Objects.requireNonNull(warning, "warning");
    }

    /** Valid definitions (enabled or not) and the files that were rejected. */
    public record Loaded(List<ToolDefinition> definitions, List<ToolDefinitionParser.Result> rejected) {
        public Loaded {
            definitions = List.copyOf(definitions);
            rejected = List.copyOf(rejected);
        }

        public List<ToolDefinition> enabled() {
            return definitions.stream().filter(ToolDefinition::enabled).toList();
        }
    }

    public File directory() {
        return new File(dataFolder, DIRECTORY);
    }

    /**
     * Creates {@code tools/} on the first start, from {@code legacy} (config.yml's old
     * {@code tools.mob-disarmer}) when present, else from the bundled default.
     */
    public void install(@Nullable ConfigurationSection legacy) throws IOException {
        File directory = directory();
        if (directory.isDirectory()) {
            if (legacy != null) {
                warning.accept("config.yml still has a tools.mob-disarmer section, which is no longer read; the Mob Disarmer"
                        + " is defined in " + MOB_DISARMER_RESOURCE + ".");
            }
            installNewDefaults(directory);
            return;
        }
        installMobDisarmer(directory, legacy);
        installNewDefaults(directory);
    }

    /**
     * Each bundled default other than the Mob Disarmer is installed once, the first time this
     * Allium sees it, and recorded in {@code tools/.defaults}. A server from before that file existed
     * had only the Mob Disarmer's default; a default the operator later deleted is not put back.
     */
    private void installNewDefaults(File directory) throws IOException {
        File record = new File(directory, INSTALLED_FILE);
        java.util.LinkedHashSet<String> installed = new java.util.LinkedHashSet<>();
        if (record.isFile()) {
            Files.readAllLines(record.toPath(), StandardCharsets.UTF_8).stream().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(installed::add);
        } else {
            installed.add(MOB_DISARMER_FILE);
        }
        boolean changed = !record.isFile();
        for (String name : bundledDefaults) {
            if (installed.contains(name)) {
                continue;
            }
            File target = new File(directory, name);
            if (!target.exists()) {
                try (InputStream in = resources.apply(DIRECTORY + "/" + name)) {
                    if (in == null) {
                        warning.accept(DIRECTORY + "/" + name + " is missing from the plugin jar; not installed.");
                        continue;
                    }
                    Files.copy(in, target.toPath());
                }
                info.accept("Created " + DIRECTORY + "/" + name + ", a new default tool definition.");
            }
            installed.add(name);
            changed = true;
        }
        if (changed) {
            List<String> lines = new ArrayList<>();
            lines.add("# Default tool definitions Allium has installed once. A default listed here is not");
            lines.add("# installed again, so deleting its file removes the tool for good.");
            lines.addAll(installed);
            Files.write(record.toPath(), lines, StandardCharsets.UTF_8);
        }
    }

    private void installMobDisarmer(File directory, @Nullable ConfigurationSection legacy) throws IOException {
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create " + directory);
        }
        File target = new File(directory, MOB_DISARMER_FILE);
        if (legacy == null) {
            try (InputStream in = bundledMobDisarmer.get()) {
                Files.copy(Objects.requireNonNull(in, MOB_DISARMER_RESOURCE + " is missing from the plugin jar"), target.toPath());
            }
            info.accept("Created " + MOB_DISARMER_RESOURCE + " with the default Mob Disarmer.");
            return;
        }
        LegacyMobDisarmerConfig.Migration migration = LegacyMobDisarmerConfig.migrate(toMap(legacy));
        YamlConfiguration migrated = migrate(migration, bundledMobDisarmer);
        migrated.save(target);
        info.accept("Moved " + migration.values().size() + " Mob Disarmer setting(s) from config.yml's tools.mob-disarmer to "
                + MOB_DISARMER_RESOURCE + "; config.yml's section is no longer read.");
        if (!migration.unmapped().isEmpty()) {
            warning.accept("config.yml's tools.mob-disarmer settings " + migration.unmapped()
                    + " have no place in " + MOB_DISARMER_RESOURCE + " and were not moved.");
        }
    }

    /** The bundled definition with the old section's values written over it, comments kept. */
    static YamlConfiguration migrate(LegacyMobDisarmerConfig.Migration migration, Supplier<InputStream> bundled) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = bundled.get()) {
            yaml.load(new InputStreamReader(Objects.requireNonNull(in, MOB_DISARMER_RESOURCE + " is missing from the plugin jar"),
                    StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("the bundled " + MOB_DISARMER_RESOURCE + " is not valid YAML", e);
        }
        for (Map.Entry<String, Object> entry : migration.values().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> map) {
                yaml.createSection(entry.getKey(), map);
            } else {
                yaml.set(entry.getKey(), entry.getValue());
            }
        }
        return yaml;
    }

    /**
     * Parses every {@code .yml} file in {@code tools/}.
     *
     * @param existingOwner the identity that already owns an appearance outside tool definitions
     *                      (core pack items and addon items), if any
     */
    public Loaded load(Map<String, ToolBehaviorType<?>> behaviors, ToolEnvironment environment,
                       Function<ProviderKey, Optional<String>> existingOwner) {
        File[] files = directory().listFiles((dir, name) -> name.endsWith(".yml"));
        List<File> sorted = files == null ? List.of() : Arrays.stream(files).sorted(Comparator.comparing(File::getName)).toList();
        List<ToolDefinition> definitions = new ArrayList<>();
        List<ToolDefinitionParser.Result> rejected = new ArrayList<>();
        Map<String, String> idFiles = new HashMap<>();
        Map<ProviderKey, String> claimed = new HashMap<>();
        for (File file : sorted) {
            String name = DIRECTORY + "/" + file.getName();
            ToolDefinitionParser.Result result = parseFile(name, file, behaviors, environment);
            if (!result.valid()) {
                rejected.add(result);
                continue;
            }
            ToolDefinition definition = result.definition();
            List<String> conflicts = new ArrayList<>();
            String otherFile = idFiles.get(definition.id());
            if (otherFile != null) {
                conflicts.add("id: " + definition.id() + " is already defined by " + otherFile);
            }
            if (definition.enabled()) {
                for (ProviderKey key : definition.boundAppearances()) {
                    String owner = claimed.get(key);
                    if (owner != null) {
                        conflicts.add("appearance: " + key + " already belongs to " + owner + " (" + idFiles.get(owner) + ")");
                        continue;
                    }
                    existingOwner.apply(key).filter(other -> !other.equals(definition.id()))
                            .ifPresent(other -> conflicts.add("appearance: " + key + " already belongs to " + other));
                }
            }
            if (!conflicts.isEmpty()) {
                rejected.add(ToolDefinitionParser.Result.rejected(name, conflicts));
                continue;
            }
            idFiles.put(definition.id(), name);
            if (definition.enabled()) {
                definition.boundAppearances().forEach(key -> claimed.put(key, definition.id()));
            }
            definitions.add(definition);
        }
        return new Loaded(definitions, rejected);
    }

    static ToolDefinitionParser.Result parseFile(String name, File file, Map<String, ToolBehaviorType<?>> behaviors, ToolEnvironment environment) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException e) {
            return ToolDefinitionParser.Result.rejected(name, List.of("(file): could not be read (" + e.getMessage() + ")"));
        } catch (InvalidConfigurationException e) {
            return ToolDefinitionParser.Result.rejected(name, List.of("(file): is not valid YAML: " + firstLines(e.getMessage())));
        }
        return ToolDefinitionParser.parse(name, toMap(yaml), behaviors, environment);
    }

    /** A Bukkit section as plain nested maps and lists, the form {@link ToolDefinitionParser} reads. */
    public static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            out.put(key, plain(section.get(key)));
        }
        return out;
    }

    @Nullable
    private static Object plain(@Nullable Object value) {
        if (value instanceof ConfigurationSection nested) {
            return toMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(element -> out.add(plain(element)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        return value;
    }

    private static String firstLines(@Nullable String message) {
        if (message == null) {
            return "unknown problem";
        }
        String[] lines = message.strip().split("\\R");
        return String.join(" ", Arrays.copyOf(lines, Math.min(3, lines.length))).replaceAll("\\s+", " ");
    }
}
