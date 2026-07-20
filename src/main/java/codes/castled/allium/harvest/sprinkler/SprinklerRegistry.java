package codes.castled.allium.harvest.sprinkler;

import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.util.Durations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Loads and holds sprinkler tiers from {@code harvest/sprinklers.yml}.
 *
 * <p>Like the crop and spawner registries, a reload swaps the whole map at
 * once, and entries that fail validation are skipped rather than taking the
 * rest of the file down with them.
 */
public final class SprinklerRegistry {

    private volatile Map<String, SprinklerDefinition> byId = Map.of();
    private volatile Map<ItemRef, String> byItem = Map.of();

    public int size() {
        return byId.size();
    }

    public Optional<SprinklerDefinition> get(String id) {
        return id == null ? Optional.empty()
            : Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /** Resolves the sprinkler tier a held item corresponds to, if any. */
    public Optional<SprinklerDefinition> byItem(ItemRef item) {
        String id = byItem.get(item);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Iterable<SprinklerDefinition> all() {
        return byId.values();
    }

    /** Parses the file and atomically swaps in every tier that validated. */
    public List<ValidationIssue> load(
        ConfigurationSection yaml, String file,
        Predicate<String> knownNamespace, Predicate<ItemRef> itemExists
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, SprinklerDefinition> parsed = new LinkedHashMap<>();
        Map<ItemRef, String> items = new LinkedHashMap<>();

        ConfigurationSection root = yaml == null ? null : yaml.getConfigurationSection("sprinklers");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                String base = "sprinklers." + rawId;
                ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    issues.add(ValidationIssue.error(file, base, "Sprinkler entry is not a section"));
                    continue;
                }
                ItemRef item = parseItem(section.getString("item"), file, base + ".item",
                    knownNamespace, itemExists, issues);
                if (item == null) {
                    continue;
                }
                ItemRef model = section.getString("model") == null ? item
                    : parseItem(section.getString("model"), file, base + ".model",
                        knownNamespace, itemExists, issues);

                int radius = section.getInt("radius", 1);
                if (radius < 0 || radius > 16) {
                    issues.add(ValidationIssue.error(file, base + ".radius",
                        "Radius must be between 0 and 16"));
                    continue;
                }
                double speed = section.getDouble("speed-multiplier", 1.0D);
                if (speed <= 0.0D) {
                    issues.add(ValidationIssue.error(file, base + ".speed-multiplier",
                        "Speed multiplier must be greater than zero"));
                    continue;
                }
                if (speed == 1.0D && !section.getBoolean("keeps-soil-moist", false)
                    && section.getString("soil-retain") == null) {
                    issues.add(ValidationIssue.warning(file, base,
                        "Sprinkler has no effect: speed-multiplier is 1.0 and it neither "
                            + "waters nor retains soil"));
                }

                long retain = 0L;
                String retainRaw = section.getString("soil-retain");
                if (retainRaw != null && !retainRaw.isBlank()) {
                    try {
                        retain = Durations.parseMillis(retainRaw);
                    } catch (IllegalArgumentException e) {
                        issues.add(ValidationIssue.error(file, base + ".soil-retain", e.getMessage()));
                    }
                }

                String id = rawId.toLowerCase(Locale.ROOT);
                parsed.put(id, new SprinklerDefinition(
                    id, item, section.getString("display-name", id), radius, speed,
                    section.getBoolean("keeps-soil-moist", false), retain,
                    model == null ? item : model));
                items.putIfAbsent(item, id);
            }
        }

        this.byId = Map.copyOf(parsed);
        this.byItem = Map.copyOf(items);
        return issues;
    }

    private ItemRef parseItem(
        String raw, String file, String path,
        Predicate<String> knownNamespace, Predicate<ItemRef> itemExists, List<ValidationIssue> issues
    ) {
        if (raw == null || raw.isBlank()) {
            issues.add(ValidationIssue.error(file, path, "Missing item reference"));
            return null;
        }
        ItemRef ref;
        try {
            ref = ItemRef.parse(raw);
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(file, path, e.getMessage()));
            return null;
        }
        if (!knownNamespace.test(ref.namespace())) {
            issues.add(ValidationIssue.error(file, path,
                "Item namespace '" + ref.namespace() + "' has no resolver — is the "
                    + ref.namespace() + " plugin installed?"));
            return null;
        }
        if (!itemExists.test(ref)) {
            issues.add(ValidationIssue.error(file, path,
                ref.isVanilla()
                    ? "'" + ref.id() + "' is not a vanilla item"
                    : "Item '" + raw + "' does not exist"));
            return null;
        }
        return ref;
    }
}
