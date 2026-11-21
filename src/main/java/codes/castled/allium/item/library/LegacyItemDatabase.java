package codes.castled.allium.item.library;

import codes.castled.allium.PluginStart;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Immutable aliases and numeric defaults; upgrades preserve operator customizations. */
public final class LegacyItemDatabase {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /**
     * Items Bukkit renamed between the versions Allium supports (1.20.3, 1.20.5, and after 1.21.8). An alias naming
     * either name resolves to the one this server has.
     */
    private static final Map<String, String> RENAMED = Map.of(
            "GRASS", "SHORT_GRASS", "SHORT_GRASS", "GRASS",
            "SCUTE", "TURTLE_SCUTE", "TURTLE_SCUTE", "SCUTE",
            "CHAIN", "IRON_CHAIN", "IRON_CHAIN", "CHAIN");
    private final Map<String, String> values;
    private final NavigableSet<String> completions;

    public LegacyItemDatabase(Map<String, String> aliases) {
        values = Map.copyOf(aliases);
        var names = new TreeSet<String>();
        for (var entry : aliases.entrySet()) {
            // "POTION potion:long_swiftness" and "SPAWNER;ZOMBIE" both start with the material.
            Material material = Material.matchMaterial(adapt(entry.getValue()).split("[; ]", 2)[0]);
            if (material != null && material.isItem() && !material.isAir() && !numeric(entry.getKey())) {
                names.add(entry.getKey());
            }
        }
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isAir()) names.add(material.name().toLowerCase(Locale.ROOT));
        }
        completions = Collections.unmodifiableNavigableSet(names);
    }

    public static LegacyItemDatabase load(PluginStart plugin) throws IOException, InvalidConfigurationException {
        Path file = plugin.getDataFolder().toPath().resolve("itemdb.yml");
        String bundled;
        try (var resource = plugin.getResource("itemdb.yml.gz")) {
            if (resource == null) throw new IOException("Missing itemdb.yml.gz");
            try (var gzip = new GZIPInputStream(resource)) {
                bundled = new String(bounded(gzip), StandardCharsets.UTF_8);
            }
        }
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            replace(file, bundled);
        }
        if (Files.size(file) > MAX_BYTES) throw new IOException("Item database too large");
        byte[] asRead = Files.readAllBytes(file);
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        var defaults = new YamlConfiguration();
        defaults.loadFromString(bundled);
        var previous = new YamlConfiguration();
        try (var resource = plugin.getResource("itemdb-legacy-ids-v1.yml")) {
            if (resource == null) throw new IOException("Missing original numeric defaults");
            previous.loadFromString(new String(bounded(resource), StandardCharsets.UTF_8));
        }
        if (upgradeNumericDefaults(yaml, defaults, previous)) {
            Path backup = file.resolveSibling("itemdb.yml.before-legacy-id-fix");
            if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            replace(file, yaml.saveToString());
            plugin.getLogger().info("Updated Essentials legacy item IDs; custom mappings retained, original saved as " + backup.getFileName());
        }
        var earlier = new YamlConfiguration();
        try (var resource = plugin.getResource("itemdb-previous-aliases.yml")) {
            if (resource != null) earlier.loadFromString(new String(bounded(resource), StandardCharsets.UTF_8));
        }
        int upgraded = upgradeAliasDefaults(yaml, defaults, earlier);
        if (upgraded > 0) {
            Path backup = file.resolveSibling("itemdb.yml.before-alias-sync");
            // The file as it was at startup, before any upgrade above rewrote it.
            if (!Files.exists(backup)) Files.write(backup, asRead);
            replace(file, yaml.saveToString());
            plugin.getLogger().info("Updated " + upgraded + " item aliases to the bundled values (synced with EssentialsX); "
                    + "your own aliases and changes were kept, the previous file saved as " + backup.getFileName());
        }
        // Aliases added to Allium later reach an existing itemdb.yml; the server's own file wins where both name one.
        var aliases = new HashMap<String, String>();
        for (String group : List.of("aliases", "legacy-ids")) aliases.putAll(section(defaults, group));
        for (String group : List.of("aliases", "legacy-ids")) aliases.putAll(section(yaml, group));
        if (aliases.size() > 100000) throw new IOException("Too many item aliases");
        return new LegacyItemDatabase(aliases);
    }

    static boolean upgradeNumericDefaults(YamlConfiguration yaml, YamlConfiguration defaults,
                                          YamlConfiguration previous) throws IOException {
        Map<String, String> current = section(yaml, "legacy-ids");
        Map<String, String> correct = section(defaults, "legacy-ids");
        Map<String, String> old = section(previous, "legacy-ids");
        boolean changed = false;
        for (var entry : old.entrySet()) {
            String key = entry.getKey();
            if (Objects.equals(current.get(key), entry.getValue()) && !Objects.equals(current.get(key), correct.get(key))) {
                yaml.set("legacy-ids." + key, correct.get(key));
                changed = true;
            }
        }
        for (var entry : correct.entrySet()) {
            if (!yaml.contains("legacy-ids." + entry.getKey())) {
                yaml.set("legacy-ids." + entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Gives the new bundled value to each alias whose value in the server's file is one of its earlier
     * bundled values (an unedited copy). An admin's own value, or an alias the bundle no longer has,
     * is left alone. Returns how many changed.
     */
    static int upgradeAliasDefaults(YamlConfiguration yaml, YamlConfiguration defaults, YamlConfiguration earlier)
            throws IOException {
        var aliases = yaml.getConfigurationSection("aliases");
        var history = earlier.getConfigurationSection("aliases");
        if (aliases == null || history == null) return 0;
        Map<String, String> bundled = section(defaults, "aliases");
        // Keys keep the server file's own spelling; history and bundle keys are lower case.
        int changed = 0;
        for (String key : aliases.getKeys(false)) {
            String alias = key.toLowerCase(Locale.ROOT);
            Object current = aliases.get(key);
            String now = bundled.get(alias);
            if (!(current instanceof String value) || now == null || now.equals(value)) continue;
            List<String> before = history.getStringList(alias);
            if (before.stream().anyMatch(old -> old.equalsIgnoreCase(value))) {
                aliases.set(key, now);
                changed++;
            }
        }
        return changed;
    }

    private static Map<String, String> section(YamlConfiguration yaml, String group) throws IOException {
        var values = new HashMap<String, String>();
        var section = yaml.getConfigurationSection(group);
        if (section == null) return values;
        for (String name : section.getKeys(false)) {
            Object value = section.get(name);
            if (!(value instanceof String text) || text.length() > 4096) throw new IOException("Invalid item alias: " + name);
            values.put(name.toLowerCase(Locale.ROOT), text);
        }
        return values;
    }

    private static byte[] bounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Item database too large");
        return bytes;
    }

    private static void replace(Path file, String text) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".itemdb-", ".tmp");
        try {
            Files.writeString(temp, text);
            if (Files.exists(file)) {
                try { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file)); }
                catch (UnsupportedOperationException ignored) { }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }

    private static boolean numeric(String id) { return id.matches("[0-9]+(?::[0-9]+)?"); }

    public String lookup(String reference) {
        String id = reference.toLowerCase(Locale.ROOT);
        if (id.contains(":") && !numeric(id)) return null;
        if (Material.matchMaterial(reference) != null) return null;
        String value = values.get(id);
        if (value == null && numeric(id)) {
            value = id.contains(":") && id.endsWith(":0") ? values.get(id.substring(0, id.length() - 2)) : values.get(id + ":0");
        }
        return adapt(value);
    }

    /** {@code value} with a renamed item's material swapped for the name this server knows. */
    static String adapt(String value) {
        if (value == null) return null;
        int end = 0;
        while (end < value.length() && value.charAt(end) != ';' && value.charAt(end) != ' ') end++;
        String head = value.substring(0, end);
        String other = RENAMED.get(head.toUpperCase(Locale.ROOT));
        if (other != null && Material.matchMaterial(head) == null && Material.matchMaterial(other) != null) {
            return other + value.substring(end);
        }
        return value;
    }

    public List<String> complete(String prefix, Collection<String> allium) {
        var out = new TreeSet<String>();
        for (String name : allium) if (name.startsWith(prefix)) out.add(name);
        for (String name : completions.tailSet(prefix, true)) {
            if (!name.startsWith(prefix) || out.size() >= 64) break;
            out.add(name);
        }
        return out.stream().limit(64).toList();
    }
}
