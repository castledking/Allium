package codes.castled.allium.kit;

import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.spec.ItemSpecException;
import codes.castled.allium.item.spec.ItemSpecParser;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads and writes kit definitions under {@code plugins/Allium/kits/*.yml}.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Every kit filename stem and {@code name} field is validated against
 *       {@link KitNames#VALID_NAME} ({@code ^[a-z0-9_-]{1,32}$}), and resolved paths must stay
 *       directly inside {@code plugins/Allium/kits/}.</li>
 *   <li>Only known keys ({@code name}, {@code enabled}, {@code cooldown-seconds}, {@code one-time},
 *       {@code cost}, {@code items}) are accepted. Unknown keys or invalid values reject the whole
 *       file with path-specific diagnostics.</li>
 *   <li>Per the S14 economy rule, {@code cost} is canonical decimal text ({@code '0.00'}). Legacy
 *       numeric values in YAML (e.g. {@code cost: 0} or {@code cost: 50.0}) are accepted on load
 *       with a warning and converted to canonical decimal text; paid kits ({@code cost > 0.00})
 *       remain denied at claim time until M3 economy reservations exist.</li>
 *   <li>Every entry in {@code items} is parsed through {@link ItemSpecParser} at load time so
 *       syntax errors are reported with their exact item index.</li>
 * </ul>
 */
public final class KitDefinitionLoader {

    public static final String DIRECTORY = "kits";
    public static final String INSTALLED_FILE = ".defaults";
    public static final String STARTER_FILE = "starter.yml";
    public static final List<String> BUNDLED_DEFAULTS = List.of(STARTER_FILE);

    private static final Set<String> KNOWN_KEYS = Set.of(
            "name", "enabled", "cooldown-seconds", "one-time", "cost", "items"
    );

    public record Rejected(String file, List<String> diagnostics) {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Loaded(Map<String, KitDefinition> definitions, List<Rejected> rejected) {
        public Loaded {
            definitions = Map.copyOf(definitions);
            rejected = List.copyOf(rejected);
        }
    }

    private final File dataFolder;
    private final Function<String, InputStream> resources;
    private final Consumer<String> info;
    private final Consumer<String> warning;

    public KitDefinitionLoader(File dataFolder, Function<String, InputStream> resources,
                               Consumer<String> info, Consumer<String> warning) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.info = Objects.requireNonNull(info, "info");
        this.warning = Objects.requireNonNull(warning, "warning");
    }

    public File directory() {
        return new File(dataFolder, DIRECTORY);
    }

    public File safeKitFile(String rawName) {
        String valid = KitNames.validate(rawName);
        Path dirPath = directory().toPath().toAbsolutePath().normalize();
        Path resolved = dirPath.resolve(valid + ".yml").normalize();
        if (!dirPath.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Kit path escapes kits directory: " + rawName);
        }
        return resolved.toFile();
    }

    public boolean exists(String rawName) {
        return safeKitFile(rawName).exists();
    }

    public void installDefaults() throws IOException {
        File dir = directory();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("could not create " + dir);
        }
        File record = new File(dir, INSTALLED_FILE);
        LinkedHashSet<String> installed = new LinkedHashSet<>();
        if (record.isFile()) {
            Files.readAllLines(record.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(installed::add);
        }
        boolean changed = !record.isFile();
        for (String name : BUNDLED_DEFAULTS) {
            if (installed.contains(name)) {
                continue;
            }
            File target = new File(dir, name);
            if (!target.exists()) {
                try (InputStream in = resources.apply(DIRECTORY + "/" + name)) {
                    if (in == null) {
                        warning.accept(DIRECTORY + "/" + name + " is missing from the plugin jar; not installed.");
                        continue;
                    }
                    Files.copy(in, target.toPath());
                }
                info.accept("Created " + DIRECTORY + "/" + name + ", a default kit definition.");
            }
            installed.add(name);
            changed = true;
        }
        if (changed) {
            List<String> lines = new ArrayList<>();
            lines.add("# Default kit definitions Allium has installed once. A default listed here is not");
            lines.add("# installed again, so deleting its file removes the kit for good.");
            lines.addAll(installed);
            Files.write(record.toPath(), lines, StandardCharsets.UTF_8);
        }
    }

    public Loaded load(ItemSpecParser parser) {
        File dir = directory();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return new Loaded(Map.of(), List.of());
        }
        Path dirPath = dir.toPath().toAbsolutePath().normalize();
        Arrays.sort(files, Comparator.comparing(File::getName));
        Map<String, KitDefinition> valid = new LinkedHashMap<>();
        List<Rejected> rejected = new ArrayList<>();
        for (File file : files) {
            String fileName = DIRECTORY + "/" + file.getName();
            List<String> diagnostics = new ArrayList<>();
            Path normalizedFile = file.toPath().toAbsolutePath().normalize();
            if (!dirPath.equals(normalizedFile.getParent())) {
                rejected.add(new Rejected(fileName, List.of("path: kit file must reside directly inside " + DIRECTORY + "/")));
                continue;
            }
            KitDefinition def = parseFile(file, fileName, parser, diagnostics);
            if (def == null || !diagnostics.isEmpty()) {
                rejected.add(new Rejected(fileName, diagnostics));
                continue;
            }
            KitDefinition existing = valid.get(def.name());
            if (existing != null) {
                rejected.add(new Rejected(fileName, List.of(
                        "name: kit '" + def.name() + "' is already defined in " + existing.sourceFile())));
                continue;
            }
            valid.put(def.name(), def);
        }
        return new Loaded(valid, rejected);
    }

    public void save(KitDefinition definition) throws IOException {
        File dir = directory();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("could not create " + dir);
        }
        File file = safeKitFile(definition.name());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", definition.name());
        yaml.set("enabled", definition.enabled());
        yaml.set("cooldown-seconds", definition.cooldownSeconds());
        yaml.set("one-time", definition.oneTime());
        yaml.set("cost", definition.cost());
        yaml.set("items", definition.items());
        yaml.save(file);
    }

    public boolean delete(String kitName) throws IOException {
        File file = safeKitFile(kitName);
        if (!file.isFile()) {
            return false;
        }
        Files.delete(file.toPath());
        return true;
    }

    private KitDefinition parseFile(File file, String fileName, ItemSpecParser parser, List<String> diagnostics) {
        String fallbackStem = file.getName().substring(0, file.getName().length() - ".yml".length());
        if (!KitNames.isValid(fallbackStem)) {
            diagnostics.add("file: kit filename stem '" + fallbackStem + "' must match ^[a-z0-9_-]{1,32}$");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            diagnostics.add("yaml: " + e.getMessage());
            return null;
        }
        for (String key : yaml.getKeys(false)) {
            if (!KNOWN_KEYS.contains(key)) {
                diagnostics.add(key + ": unknown kit setting");
            }
        }
        Object rawName = yaml.contains("name") ? yaml.get("name") : fallbackStem;
        String name = null;
        if (!(rawName instanceof String nameStr)) {
            diagnostics.add("name: must be a string matching ^[a-z0-9_-]{1,32}$");
        } else {
            try {
                name = KitNames.validate(nameStr);
            } catch (IllegalArgumentException e) {
                diagnostics.add("name: " + e.getMessage());
            }
        }

        boolean enabled = true;
        if (yaml.contains("enabled")) {
            Object rawEnabled = yaml.get("enabled");
            if (!(rawEnabled instanceof Boolean b)) {
                diagnostics.add("enabled: must be true or false");
            } else {
                enabled = b;
            }
        }

        long cooldownSeconds = 0L;
        if (yaml.contains("cooldown-seconds")) {
            Object rawCooldown = yaml.get("cooldown-seconds");
            if (!(rawCooldown instanceof Number n) || !Double.isFinite(n.doubleValue())
                    || n.doubleValue() != n.longValue()
                    || n.longValue() < 0L || n.longValue() > KitDefinition.MAX_COOLDOWN_SECONDS) {
                diagnostics.add("cooldown-seconds: must be an integer between 0 and " + KitDefinition.MAX_COOLDOWN_SECONDS);
            } else {
                cooldownSeconds = n.longValue();
            }
        }

        boolean oneTime = false;
        if (yaml.contains("one-time")) {
            Object rawOneTime = yaml.get("one-time");
            if (!(rawOneTime instanceof Boolean b)) {
                diagnostics.add("one-time: must be true or false");
            } else {
                oneTime = b;
            }
        }

        String cost = KitDefinition.ZERO_COST;
        if (yaml.contains("cost")) {
            Object rawCost = yaml.get("cost");
            if (rawCost instanceof String costStr) {
                try {
                    cost = KitDefinition.normalizeCost(costStr);
                } catch (IllegalArgumentException e) {
                    diagnostics.add("cost: " + e.getMessage());
                }
            } else if (rawCost instanceof Number n) {
                if (!Double.isFinite(n.doubleValue()) || n.doubleValue() < 0.0D) {
                    diagnostics.add("cost: must be non-negative decimal text (e.g. '0.00')");
                } else {
                    String numericText = (rawCost instanceof Integer || rawCost instanceof Long)
                            ? n.toString()
                            : BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
                    try {
                        cost = KitDefinition.normalizeCost(numericText);
                        warning.accept(fileName + ": numeric cost (" + rawCost
                                + ") is deprecated; quote cost as decimal text '" + cost + "' per the S14 economy rule.");
                    } catch (IllegalArgumentException e) {
                        diagnostics.add("cost: " + e.getMessage());
                    }
                }
            } else {
                diagnostics.add("cost: must be decimal text (e.g. '0.00')");
            }
        }

        List<String> items = new ArrayList<>();
        Object rawItems = yaml.get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            diagnostics.add("items: must be a non-empty list of item spec strings");
        } else if (list.size() > KitDefinition.MAX_ITEMS) {
            diagnostics.add("items: must have at most " + KitDefinition.MAX_ITEMS + " entries");
        } else {
            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (!(entry instanceof String specText) || specText.isBlank()) {
                    diagnostics.add("items[" + i + "]: must be a non-empty item spec string");
                    continue;
                }
                try {
                    GiveSpec parsed = parser.parse(specText.trim());
                    if (parsed.overflow().isPresent()) {
                        diagnostics.add("items[" + i + "]: --overflow flag is not allowed in kit item definitions");
                    } else {
                        items.add(specText.trim());
                    }
                } catch (ItemSpecException e) {
                    diagnostics.add("items[" + i + "]: " + e.getMessage());
                }
            }
        }

        if (!diagnostics.isEmpty() || name == null) {
            return null;
        }
        return new KitDefinition(name, enabled, cooldownSeconds, oneTime, cost, items, fileName);
    }
}
