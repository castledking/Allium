package codes.castled.allium.harvest.spawner;

import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * Per-entity-type spawner model configuration, loaded from
 * {@code harvest/spawner-models.yml}. Supports stack-size variants; exactly
 * one variant is selected per spawner — the one with the highest qualifying
 * {@code minimum-stack} threshold.
 */
public final class SpawnerModelRegistry {

    /**
     * @param translation model offset from the block corner
     * @param rotationY yaw rotation in degrees
     */
    public record Model(
        ItemRef item,
        float scale,
        double[] translation,
        float rotationY,
        float viewRange,
        boolean glow,
        List<Variant> variants
    ) {
        public record Variant(int minimumStack, ItemRef item) {}

        /** Highest qualifying variant wins; falls back to the base item. */
        public ItemRef itemForStack(int stackSize) {
            ItemRef best = item;
            int bestThreshold = 0;
            for (Variant variant : variants) {
                if (stackSize >= variant.minimumStack() && variant.minimumStack() >= bestThreshold) {
                    bestThreshold = variant.minimumStack();
                    best = variant.item();
                }
            }
            return best;
        }
    }

    private volatile Map<String, Model> byType = Map.of();
    private volatile Model defaultModel;
    private volatile boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    /** Model for a spawner type, falling back to the {@code default} entry. */
    public Optional<Model> modelFor(EntityType type) {
        if (!enabled) return Optional.empty();
        Model model = type == null ? null : byType.get(type.name());
        return Optional.ofNullable(model != null ? model : defaultModel);
    }

    public int size() {
        return byType.size();
    }

    /** Parses and atomically swaps the model set. */
    public List<ValidationIssue> load(
        ConfigurationSection root,
        String file,
        Predicate<String> knownNamespace,
        Predicate<ItemRef> itemExists
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Model> models = new LinkedHashMap<>();
        Model fallback = null;
        boolean parsedEnabled = true;

        ConfigurationSection section = root == null ? null : root.getConfigurationSection("spawner-models");
        if (section != null) {
            parsedEnabled = section.getBoolean("enabled", true);
            ConfigurationSection defaults = section.getConfigurationSection("defaults");
            float defaultScale = defaults == null ? 1.0F : (float) defaults.getDouble("scale", 1.0);
            double[] defaultTranslation = translation(defaults, new double[] {0.5, 0.0, 0.5});
            float defaultRotation = defaults == null ? 0.0F : (float) defaults.getDouble("rotation-y", 0.0);
            float defaultViewRange = defaults == null ? 48.0F : (float) defaults.getDouble("view-range", 48.0);
            boolean defaultGlow = defaults != null && defaults.getBoolean("glow", false);

            ConfigurationSection types = section.getConfigurationSection("types");
            if (types != null) {
                for (String typeName : types.getKeys(false)) {
                    ConfigurationSection typeSection = types.getConfigurationSection(typeName);
                    String base = "spawner-models.types." + typeName;
                    if (typeSection == null) {
                        issues.add(ValidationIssue.error(file, base, "Type entry is not a section"));
                        continue;
                    }
                    boolean isDefault = typeName.equalsIgnoreCase("default");
                    if (!isDefault) {
                        try {
                            EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException e) {
                            issues.add(ValidationIssue.error(file, base,
                                "'" + typeName + "' is not a valid entity type"));
                            continue;
                        }
                    }
                    ItemRef item = parseItem(typeSection.getString("item"), file, base + ".item",
                        knownNamespace, itemExists, issues);
                    List<Model.Variant> variants = new ArrayList<>();
                    List<?> variantList = typeSection.getList("variants");
                    if (variantList != null) {
                        for (int i = 0; i < variantList.size(); i++) {
                            if (!(variantList.get(i) instanceof Map<?, ?> variantMap)) continue;
                            String variantPath = base + ".variants[" + i + "]";
                            Object minRaw = variantMap.get("minimum-stack");
                            int minStack = minRaw instanceof Number n ? n.intValue() : 1;
                            ItemRef variantItem = parseItem(
                                variantMap.get("item") == null ? null : String.valueOf(variantMap.get("item")),
                                file, variantPath + ".item", knownNamespace, itemExists, issues);
                            if (variantItem != null) {
                                variants.add(new Model.Variant(minStack, variantItem));
                            }
                        }
                    }
                    if (item == null && variants.isEmpty()) {
                        continue;
                    }
                    if (item == null) {
                        item = variants.get(variants.size() - 1).item();
                    }
                    Model model = new Model(
                        item,
                        (float) typeSection.getDouble("scale", defaultScale),
                        translation(typeSection, defaultTranslation),
                        (float) typeSection.getDouble("rotation-y", defaultRotation),
                        (float) typeSection.getDouble("view-range", defaultViewRange),
                        typeSection.getBoolean("glow", defaultGlow),
                        variants);
                    if (isDefault) {
                        fallback = model;
                    } else {
                        models.put(typeName.toUpperCase(Locale.ROOT), model);
                    }
                }
            }
        }

        // Entries whose item could not be resolved were skipped above, so what
        // remains is exactly the set of models that can actually be rendered.
        // Applying it unconditionally means a missing Nexo item costs you that
        // one model, not the whole reload.
        this.byType = models;
        this.defaultModel = fallback;
        this.enabled = parsedEnabled;
        return issues;
    }

    private static double[] translation(ConfigurationSection section, double[] fallback) {
        if (section == null) return fallback;
        List<Double> list = section.getDoubleList("translation");
        if (list.size() != 3) return fallback;
        return new double[] {list.get(0), list.get(1), list.get(2)};
    }

    private static ItemRef parseItem(
        String raw, String file, String path,
        Predicate<String> knownNamespace, Predicate<ItemRef> itemExists,
        List<ValidationIssue> issues
    ) {
        if (raw == null || raw.isBlank()) {
            issues.add(ValidationIssue.error(file, path, "Missing item reference"));
            return null;
        }
        try {
            ItemRef ref = ItemRef.parse(raw);
            if (!knownNamespace.test(ref.namespace())) {
                issues.add(ValidationIssue.error(file, path,
                    "Item namespace '" + ref.namespace() + "' has no resolver — is the "
                        + ref.namespace() + " plugin installed?"));
            } else if (!itemExists.test(ref)) {
                issues.add(ValidationIssue.error(file, path,
                    "Item '" + ref + "' does not exist"));
            }
            return ref;
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(file, path, e.getMessage()));
            return null;
        }
    }
}
