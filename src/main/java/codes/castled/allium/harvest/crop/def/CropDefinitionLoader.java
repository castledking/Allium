package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.util.Durations;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Parses crop definition files ({@code harvest/crops/*.yml}) and the
 * fertilizer file ({@code harvest/fertilizers.yml}) into immutable definition
 * objects, collecting every validation problem instead of failing fast. The
 * caller decides whether errors are fatal (they are on reload: an invalid
 * configuration never replaces the active registry).
 *
 * <p>Item existence is checked through the supplied predicates so the parser
 * itself stays testable without a running server or Nexo installation.</p>
 */
public final class CropDefinitionLoader {

    /** Result of a load pass. */
    public record LoadResult(
        Map<String, CropDefinition> crops,
        Map<String, FertilizerDefinition> fertilizers,
        List<ValidationIssue> issues
    ) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(ValidationIssue::isError);
        }
    }

    private final Predicate<String> knownNamespace;
    private final Predicate<ItemRef> itemExists;

    /**
     * @param knownNamespace whether a namespace has a resolver available
     *                       (e.g. {@code nexo} only when Nexo is installed)
     * @param itemExists whether a reference resolves to a real item
     */
    public CropDefinitionLoader(Predicate<String> knownNamespace, Predicate<ItemRef> itemExists) {
        this.knownNamespace = knownNamespace;
        this.itemExists = itemExists;
    }

    public LoadResult load(File cropsDir, File fertilizersFile) {
        Map<String, CropDefinition> crops = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();

        File[] files = cropsDir.isDirectory()
            ? cropsDir.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"))
            : null;
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                CropDefinition crop = parseCrop(yaml, file.getName(), issues);
                if (crop == null) continue;
                if (crops.containsKey(crop.id())) {
                    issues.add(ValidationIssue.error(file.getName(), "id",
                        "Duplicate crop id '" + crop.id() + "' (already defined in another file)"));
                    continue;
                }
                crops.put(crop.id(), crop);
            }
        }

        Map<String, FertilizerDefinition> fertilizers = new LinkedHashMap<>();
        if (fertilizersFile != null && fertilizersFile.isFile()) {
            parseFertilizers(YamlConfiguration.loadConfiguration(fertilizersFile),
                fertilizersFile.getName(), fertilizers, issues);
        }
        return new LoadResult(crops, fertilizers, issues);
    }

    // ==================== crop parsing ====================

    public CropDefinition parseCrop(ConfigurationSection yaml, String file, List<ValidationIssue> issues) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            issues.add(ValidationIssue.error(file, "id", "Crop file is missing an 'id'"));
            return null;
        }
        id = id.toLowerCase(Locale.ROOT);
        String displayName = yaml.getString("display-name", id);

        ItemRef seed = parseItemRef(yaml.getString("seed.item"), file, "seed.item", issues);
        GrowthRequirements requirements = parseRequirements(yaml, file, issues);
        GrowthSettings growth = parseGrowthSettings(yaml, file, issues);

        ConfigurationSection selection = yaml.getConfigurationSection("paths.selection");
        String timing = selection != null ? selection.getString("timing", "ON_PLANT") : "ON_PLANT";
        if (!"ON_PLANT".equalsIgnoreCase(timing)) {
            issues.add(ValidationIssue.warning(file, "paths.selection.timing",
                "Only ON_PLANT path selection is supported; '" + timing + "' will behave as ON_PLANT"));
        }
        String fallbackPath = selection != null ? selection.getString("fallback") : null;

        Map<String, CropPathDefinition> paths = new LinkedHashMap<>();
        ConfigurationSection entries = yaml.getConfigurationSection("paths.entries");
        if (entries == null || entries.getKeys(false).isEmpty()) {
            issues.add(ValidationIssue.error(file, "paths.entries",
                "Crop '" + id + "' defines no growth paths"));
        } else {
            for (String pathId : entries.getKeys(false)) {
                ConfigurationSection pathSection = entries.getConfigurationSection(pathId);
                String base = "paths.entries." + pathId;
                if (pathSection == null) {
                    issues.add(ValidationIssue.error(file, base, "Path '" + pathId + "' is not a section"));
                    continue;
                }
                CropPathDefinition path = parsePath(id, pathId.toLowerCase(Locale.ROOT),
                    pathSection, file, base, issues);
                if (path != null) {
                    if (paths.containsKey(path.id())) {
                        issues.add(ValidationIssue.error(file, base, "Duplicate path id '" + path.id() + "'"));
                    } else {
                        paths.put(path.id(), path);
                    }
                }
            }
        }

        if (!paths.isEmpty()) {
            boolean anyPositive = paths.values().stream().anyMatch(p -> p.weight() > 0);
            if (!anyPositive && (fallbackPath == null || !paths.containsKey(fallbackPath.toLowerCase(Locale.ROOT)))) {
                issues.add(ValidationIssue.error(file, "paths.entries",
                    "Crop '" + id + "': all path weights are zero or negative and no valid fallback is set"));
            }
        }
        if (fallbackPath != null) {
            fallbackPath = fallbackPath.toLowerCase(Locale.ROOT);
            if (!paths.containsKey(fallbackPath)) {
                issues.add(ValidationIssue.error(file, "paths.selection.fallback",
                    "Fallback path '" + fallbackPath + "' does not exist"));
                fallbackPath = null;
            }
        }

        if (seed == null || paths.isEmpty()) {
            return null;
        }
        return new CropDefinition(id, displayName, seed, requirements, growth, paths, fallbackPath);
    }

    private GrowthRequirements parseRequirements(ConfigurationSection yaml, String file, List<ValidationIssue> issues) {
        Set<Material> soils = new LinkedHashSet<>();
        for (String raw : yaml.getStringList("placement.valid-soils")) {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (!codes.castled.allium.harvest.util.Materials.isBlockOrUnknown(material)) {
                issues.add(ValidationIssue.error(file, "placement.valid-soils",
                    "'" + raw + "' is not a valid block material"));
            } else {
                soils.add(material);
            }
        }
        if (soils.isEmpty()) {
            soils.add(Material.FARMLAND);
        }
        Set<String> biomes = new LinkedHashSet<>();
        for (String biome : yaml.getStringList("placement.biomes")) {
            String key = biome.toLowerCase(Locale.ROOT);
            biomes.add(key.contains(":") ? key : "minecraft:" + key);
        }
        return new GrowthRequirements(
            soils,
            yaml.getBoolean("placement.requires-water", false),
            yaml.getInt("placement.minimum-light", 0),
            biomes,
            new LinkedHashSet<>(yaml.getStringList("placement.worlds.allow")),
            new LinkedHashSet<>(yaml.getStringList("placement.worlds.deny")),
            yaml.getString("placement.permission", "")
        );
    }

    private GrowthSettings parseGrowthSettings(ConfigurationSection yaml, String file, List<ValidationIssue> issues) {
        String clockRaw = yaml.getString("growth.clock", "REAL_TIME");
        CropClock clock;
        try {
            clock = CropClock.valueOf(clockRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(file, "growth.clock",
                "Unknown growth clock '" + clockRaw + "' (expected REAL_TIME or LOADED_TIME)"));
            clock = CropClock.REAL_TIME;
        }
        int maxCatchUp = yaml.getInt("growth.maximum-catch-up-stages", 10);
        if (maxCatchUp < 0) {
            issues.add(ValidationIssue.warning(file, "growth.maximum-catch-up-stages",
                "Negative catch-up limit treated as 0"));
            maxCatchUp = 0;
        }
        return new GrowthSettings(clock, yaml.getBoolean("growth.grow-while-unloaded", true), maxCatchUp);
    }

    private CropPathDefinition parsePath(
        String cropId, String pathId, ConfigurationSection section,
        String file, String base, List<ValidationIssue> issues
    ) {
        double weight = section.getDouble("weight", 0.0D);
        if (weight < 0.0D) {
            issues.add(ValidationIssue.error(file, base + ".weight",
                "Path '" + pathId + "' has a negative weight"));
        }

        List<StageDefinition> stages = new ArrayList<>();
        List<?> stageList = section.getList("stages");
        if (stageList == null || stageList.isEmpty()) {
            issues.add(ValidationIssue.error(file, base + ".stages",
                "Path '" + pathId + "' has no growth stages"));
        } else {
            for (int i = 0; i < stageList.size(); i++) {
                Object element = stageList.get(i);
                String stagePath = base + ".stages[" + i + "]";
                if (!(element instanceof Map<?, ?> map)) {
                    issues.add(ValidationIssue.error(file, stagePath, "Stage is not a mapping"));
                    continue;
                }
                ItemRef model = parseItemRef(asString(map.get("model")), file, stagePath + ".model", issues);
                long duration = 60_000L;
                Object durationRaw = map.get("duration");
                if (durationRaw == null) {
                    if (i < stageList.size() - 1) {
                        issues.add(ValidationIssue.error(file, stagePath + ".duration",
                            "Stage " + (i + 1) + " is missing a duration"));
                    } else {
                        duration = 0L; // mature stage needs no duration
                    }
                } else {
                    try {
                        duration = Durations.parseMillis(String.valueOf(durationRaw));
                    } catch (IllegalArgumentException e) {
                        issues.add(ValidationIssue.error(file, stagePath + ".duration", e.getMessage()));
                    }
                }
                FootprintDefinition footprint = parseFootprint(map.get("footprint"), file, stagePath, issues);
                StageDefinition.VisualOverride visual =
                    parseVisualOverride(map.get("visual"), file, stagePath + ".visual", issues);
                if (model != null) {
                    stages.add(new StageDefinition(i, model, duration, footprint, visual));
                }
            }
        }

        HarvestDefinition harvest = parseHarvest(
            section.getConfigurationSection("mature-harvest"), file, base + ".mature-harvest", issues);

        RegrowthDefinition regrowth = RegrowthDefinition.DISABLED;
        ConfigurationSection regrowthSection = section.getConfigurationSection("regrowth");
        if (regrowthSection != null && regrowthSection.getBoolean("enabled", false)) {
            int stage = regrowthSection.getInt("stage", 0);
            if (stage < 0 || stage >= Math.max(1, stages.size())) {
                issues.add(ValidationIssue.error(file, base + ".regrowth.stage",
                    "Regrowth stage " + stage + " is outside path '" + pathId
                        + "' (0.." + Math.max(0, stages.size() - 1) + ")"));
            } else {
                regrowth = new RegrowthDefinition(true, stage);
            }
        }

        if (stages.isEmpty()) {
            return null;
        }
        BreakDefinition breakDrops = parseBreakDrops(
            section.getConfigurationSection("break-drops"), file, base + ".break-drops", issues);

        return new CropPathDefinition(pathId, weight, stages, harvest, regrowth, breakDrops);
    }

    private FootprintDefinition parseFootprint(Object raw, String file, String stagePath, List<ValidationIssue> issues) {
        if (!(raw instanceof Map<?, ?> map)) {
            return FootprintDefinition.SINGLE;
        }
        Object cellsRaw = map.get("cells");
        if (!(cellsRaw instanceof List<?> cellList)) {
            issues.add(ValidationIssue.error(file, stagePath + ".footprint.cells",
                "Footprint has no 'cells' list"));
            return FootprintDefinition.SINGLE;
        }
        List<FootprintDefinition.Cell> cells = new ArrayList<>();
        for (Object cellRaw : cellList) {
            if (!(cellRaw instanceof List<?> triple) || triple.size() != 3) {
                issues.add(ValidationIssue.error(file, stagePath + ".footprint.cells",
                    "Each footprint cell must be a [x, y, z] triple"));
                continue;
            }
            try {
                int dx = ((Number) triple.get(0)).intValue();
                int dy = ((Number) triple.get(1)).intValue();
                int dz = ((Number) triple.get(2)).intValue();
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) {
                    issues.add(ValidationIssue.warning(file, stagePath + ".footprint.cells",
                        "Footprint cell [" + dx + "," + dy + "," + dz + "] is unusually far from the anchor"));
                }
                cells.add(new FootprintDefinition.Cell(dx, dy, dz));
            } catch (ClassCastException e) {
                issues.add(ValidationIssue.error(file, stagePath + ".footprint.cells",
                    "Footprint cell entries must be numbers"));
            }
        }
        return new FootprintDefinition(cells);
    }

    private HarvestDefinition parseHarvest(ConfigurationSection section, String file, String base, List<ValidationIssue> issues) {
        if (section == null) {
            issues.add(ValidationIssue.warning(file, base,
                "Path has no mature-harvest section; harvesting will drop nothing"));
            return HarvestDefinition.empty();
        }
        HarvestTable primary = parseTable(section.getConfigurationSection("primary"), file, base + ".primary", true, issues);
        HarvestTable additional = parseTable(section.getConfigurationSection("additional"), file, base + ".additional", false, issues);
        DeliveryMode delivery = DeliveryMode.DROP_AT_CROP;
        String deliveryRaw = section.getString("delivery");
        if (deliveryRaw != null) {
            try {
                delivery = DeliveryMode.valueOf(deliveryRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                issues.add(ValidationIssue.error(file, base + ".delivery",
                    "Unknown delivery mode '" + deliveryRaw + "'"));
            }
        }
        return new HarvestDefinition(primary, additional, delivery);
    }

    private HarvestTable parseTable(ConfigurationSection section, String file, String base, boolean primary, List<ValidationIssue> issues) {
        if (section == null) {
            if (primary) {
                issues.add(ValidationIssue.warning(file, base, "No primary harvest table configured"));
            }
            return HarvestTable.empty();
        }
        String modeRaw = section.getString("mode", primary ? "WEIGHTED_ONE" : "INDEPENDENT");
        LootMode mode;
        try {
            mode = LootMode.valueOf(modeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(file, base + ".mode", "Unknown loot mode '" + modeRaw + "'"));
            mode = primary ? LootMode.WEIGHTED_ONE : LootMode.INDEPENDENT;
        }
        if (primary && mode == LootMode.INDEPENDENT) {
            issues.add(ValidationIssue.warning(file, base + ".mode",
                "INDEPENDENT is not mutually exclusive — a harvest may yield several primary outcomes at once. "
                    + "Use WEIGHTED_ONE for quality tiers."));
        }
        int picks = section.getInt("picks", 1);
        boolean stopOnFail = section.getBoolean("stop-on-fail", true);

        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        ConfigurationSection entries = section.getConfigurationSection("entries");
        if (entries != null) {
            for (String outcomeId : entries.getKeys(false)) {
                ConfigurationSection outcomeSection = entries.getConfigurationSection(outcomeId);
                String outcomeBase = base + ".entries." + outcomeId;
                if (outcomeSection == null) {
                    issues.add(ValidationIssue.error(file, outcomeBase, "Outcome is not a section"));
                    continue;
                }
                double weight = outcomeSection.getDouble("weight", 1.0D);
                if (weight < 0.0D) {
                    issues.add(ValidationIssue.error(file, outcomeBase + ".weight", "Negative outcome weight"));
                }
                double chance = outcomeSection.getDouble("chance", 1.0D);
                if (chance <= 0.0D || chance > 1.0D) {
                    issues.add(ValidationIssue.error(file, outcomeBase + ".chance",
                        "Chance must be in (0,1], got " + chance));
                    chance = 1.0D;
                }
                List<DropSpec> drops = parseDrops(outcomeSection.getList("drops"), file, outcomeBase + ".drops", issues);
                List<String> commands = outcomeSection.getStringList("commands");
                outcomes.put(outcomeId, new HarvestOutcome(outcomeId, weight, chance, drops, commands));
            }
        }
        if (primary && outcomes.isEmpty()) {
            issues.add(ValidationIssue.error(file, base + ".entries", "Primary harvest table has no entries"));
        }
        if ((mode == LootMode.WEIGHTED_ONE || mode == LootMode.WEIGHTED_MULTIPLE_WITHOUT_REPLACEMENT)
            && !outcomes.isEmpty()
            && outcomes.values().stream().noneMatch(o -> o.weight() > 0)) {
            issues.add(ValidationIssue.error(file, base + ".entries",
                "Weighted table has no entry with a positive weight"));
        }
        return new HarvestTable(mode, picks, stopOnFail, outcomes);
    }

    private List<DropSpec> parseDrops(List<?> raw, String file, String base, List<ValidationIssue> issues) {
        List<DropSpec> drops = new ArrayList<>();
        if (raw == null) {
            return drops;
        }
        for (int i = 0; i < raw.size(); i++) {
            Object element = raw.get(i);
            String dropPath = base + "[" + i + "]";
            if (!(element instanceof Map<?, ?> map)) {
                issues.add(ValidationIssue.error(file, dropPath, "Drop is not a mapping"));
                continue;
            }
            ItemRef item = parseItemRef(asString(map.get("item")), file, dropPath + ".item", issues);
            if (item == null) continue;
            int min = 1;
            int max = 1;
            Object amount = map.get("amount");
            if (amount instanceof Number number) {
                min = max = number.intValue();
            } else if (amount instanceof Map<?, ?> range) {
                min = range.get("min") instanceof Number n ? n.intValue() : 1;
                max = range.get("max") instanceof Number n ? n.intValue() : min;
            }
            double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 1.0D;
            try {
                drops.add(new DropSpec(item, min, max, chance));
            } catch (IllegalArgumentException e) {
                issues.add(ValidationIssue.error(file, dropPath, e.getMessage()));
            }
        }
        return drops;
    }

    /**
     * Parses the optional {@code break-drops:} block.
     *
     * <p>Each side is an ordinary loot table, so every mode is available — a
     * fixed seed return is {@code GUARANTEED_ALL}, a chance-based one is
     * {@code INDEPENDENT}, and a "one of these" return is {@code WEIGHTED_ONE}.
     */
    private BreakDefinition parseBreakDrops(
        ConfigurationSection section, String file, String base, List<ValidationIssue> issues
    ) {
        if (section == null) {
            return BreakDefinition.NONE;
        }
        HarvestTable immature = section.isConfigurationSection("immature")
            ? parseTable(section.getConfigurationSection("immature"), file, base + ".immature", false, issues)
            : HarvestTable.empty();
        HarvestTable mature = section.isConfigurationSection("mature")
            ? parseTable(section.getConfigurationSection("mature"), file, base + ".mature", false, issues)
            : HarvestTable.empty();
        if (immature.isEmpty() && mature.isEmpty()) {
            issues.add(ValidationIssue.warning(file, base,
                "break-drops is present but defines neither 'immature' nor 'mature' entries"));
        }
        return new BreakDefinition(immature, mature);
    }

    /**
     * Parses a stage's optional {@code visual:} block. Absent keys stay null so
     * the renderer can fall back to the global setting per field, rather than
     * a stage having to restate values it does not want to change.
     */
    private StageDefinition.VisualOverride parseVisualOverride(
        Object raw, String file, String path, List<ValidationIssue> issues
    ) {
        if (!(raw instanceof Map<?, ?> map)) {
            if (raw != null) {
                issues.add(ValidationIssue.error(file, path, "Expected a section"));
            }
            return StageDefinition.VisualOverride.NONE;
        }

        org.bukkit.entity.ItemDisplay.ItemDisplayTransform transform = null;
        Object transformRaw = map.get("transform");
        if (transformRaw != null) {
            try {
                transform = org.bukkit.entity.ItemDisplay.ItemDisplayTransform.valueOf(
                    String.valueOf(transformRaw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                issues.add(ValidationIssue.error(file, path + ".transform",
                    "'" + transformRaw + "' is not a display transform. Valid values: "
                        + "NONE, FIXED, GROUND, HEAD, GUI, FIRSTPERSON_LEFTHAND, "
                        + "FIRSTPERSON_RIGHTHAND, THIRDPERSON_LEFTHAND, THIRDPERSON_RIGHTHAND"));
            }
        }

        Float scale = null;
        Object scaleRaw = map.get("scale");
        if (scaleRaw instanceof Number number) {
            if (number.floatValue() <= 0.0F) {
                issues.add(ValidationIssue.error(file, path + ".scale",
                    "Scale must be greater than zero"));
            } else {
                scale = number.floatValue();
            }
        } else if (scaleRaw != null) {
            issues.add(ValidationIssue.error(file, path + ".scale", "Expected a number"));
        }

        Double yOffset = null;
        Object offsetRaw = map.get("y-offset");
        if (offsetRaw instanceof Number number) {
            yOffset = number.doubleValue();
        } else if (offsetRaw != null) {
            issues.add(ValidationIssue.error(file, path + ".y-offset", "Expected a number"));
        }

        return new StageDefinition.VisualOverride(transform, scale, yOffset);
    }

    // ==================== fertilizers ====================

    private void parseFertilizers(
        ConfigurationSection yaml, String file,
        Map<String, FertilizerDefinition> out, List<ValidationIssue> issues
    ) {
        ConfigurationSection root = yaml.getConfigurationSection("fertilizers");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String base = "fertilizers." + id;
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            ItemRef item = parseItemRef(section.getString("item"), file, base + ".item", issues);
            if (item == null) continue;

            Map<String, Double> multipliers = parseQuality(section, file, base, issues);
            FertilizerDefinition.YieldEffect yield = parseYield(section, file, base, issues);
            FertilizerDefinition.VariationEffect variation = parseVariation(section, file, base, issues);

            double speed = section.getDouble("growth.speed-multiplier", 1.0D);
            if (speed <= 0.0D) {
                issues.add(ValidationIssue.error(file, base + ".growth.speed-multiplier",
                    "Speed multiplier must be greater than zero"));
                speed = 1.0D;
            }

            long retainMs = 0L;
            String retain = section.getString("soil.retain-for");
            if (retain != null && !retain.isBlank()) {
                try {
                    retainMs = Durations.parseMillis(retain);
                } catch (IllegalArgumentException e) {
                    issues.add(ValidationIssue.error(file, base + ".soil.retain-for", e.getMessage()));
                }
            }

            // Where a fertilizer may be used defaults to whichever effects it
            // actually has: variation and soil effects are meaningless on an
            // already-planted crop, so those default to soil-only.
            boolean needsSoil = !variation.isNone() || retainMs > 0L;
            boolean appliesToSoil = section.getBoolean("apply-to-soil", true);
            boolean appliesToCrop = section.getBoolean("apply-to-crop", !needsSoil);
            if (!appliesToSoil && !appliesToCrop) {
                issues.add(ValidationIssue.error(file, base,
                    "Fertilizer can be applied to neither soil nor crops"));
                continue;
            }
            if (!variation.isNone() && !appliesToSoil) {
                issues.add(ValidationIssue.warning(file, base + ".variation",
                    "Variation only applies during the plant-time path roll, so this "
                        + "fertilizer must be usable on soil to ever have an effect"));
            }

            String key = id.toLowerCase(Locale.ROOT);
            out.put(key, new FertilizerDefinition(
                key, item, section.getString("display-name", key),
                multipliers, yield, speed, variation, retainMs, appliesToSoil, appliesToCrop));
        }
    }

    private Map<String, Double> parseQuality(
        ConfigurationSection section, String file, String base, List<ValidationIssue> issues
    ) {
        Map<String, Double> multipliers = new LinkedHashMap<>();
        // Long form: modifiers.<outcome>.multiply-weight (kept for existing configs).
        ConfigurationSection modifiers = section.getConfigurationSection("modifiers");
        if (modifiers != null) {
            for (String outcomeId : modifiers.getKeys(false)) {
                double factor = modifiers.getDouble(outcomeId + ".multiply-weight", 1.0D);
                if (factor < 0.0D) {
                    issues.add(ValidationIssue.error(file, base + ".modifiers." + outcomeId,
                        "Negative weight multiplier"));
                    continue;
                }
                multipliers.put(outcomeId.toLowerCase(Locale.ROOT), factor);
            }
        }
        // Short form: quality.<outcome>: <multiplier>
        ConfigurationSection quality = section.getConfigurationSection("quality");
        if (quality != null) {
            for (String outcomeId : quality.getKeys(false)) {
                double factor = quality.getDouble(outcomeId, 1.0D);
                if (factor < 0.0D) {
                    issues.add(ValidationIssue.error(file, base + ".quality." + outcomeId,
                        "Negative weight multiplier"));
                    continue;
                }
                multipliers.put(outcomeId.toLowerCase(Locale.ROOT), factor);
            }
        }
        return multipliers;
    }

    private FertilizerDefinition.YieldEffect parseYield(
        ConfigurationSection section, String file, String base, List<ValidationIssue> issues
    ) {
        ConfigurationSection yield = section.getConfigurationSection("yield");
        if (yield == null) {
            return FertilizerDefinition.YieldEffect.NONE;
        }
        double multiply = yield.getDouble("multiply", 1.0D);
        if (multiply < 0.0D) {
            issues.add(ValidationIssue.error(file, base + ".yield.multiply",
                "Yield multiplier cannot be negative"));
            multiply = 1.0D;
        }
        int bonus = yield.getInt("bonus", 0);
        int maximum = yield.getInt("maximum", 0);
        if (maximum < 0) {
            issues.add(ValidationIssue.error(file, base + ".yield.maximum",
                "Maximum cannot be negative (use 0 for uncapped)"));
            maximum = 0;
        }
        return new FertilizerDefinition.YieldEffect(multiply, bonus, maximum);
    }

    private FertilizerDefinition.VariationEffect parseVariation(
        ConfigurationSection section, String file, String base, List<ValidationIssue> issues
    ) {
        ConfigurationSection variation = section.getConfigurationSection("variation");
        if (variation == null) {
            return FertilizerDefinition.VariationEffect.NONE;
        }
        double special = variation.getDouble("special-path-multiplier", 1.0D);
        if (special < 0.0D) {
            issues.add(ValidationIssue.error(file, base + ".variation.special-path-multiplier",
                "Multiplier cannot be negative"));
            special = 1.0D;
        }
        Map<String, Double> paths = new LinkedHashMap<>();
        ConfigurationSection perPath = variation.getConfigurationSection("paths");
        if (perPath != null) {
            for (String pathId : perPath.getKeys(false)) {
                double factor = perPath.getDouble(pathId, 1.0D);
                if (factor < 0.0D) {
                    issues.add(ValidationIssue.error(file, base + ".variation.paths." + pathId,
                        "Multiplier cannot be negative"));
                    continue;
                }
                paths.put(pathId.toLowerCase(Locale.ROOT), factor);
            }
        }
        return new FertilizerDefinition.VariationEffect(paths, special);
    }

    // ==================== shared ====================

    private ItemRef parseItemRef(String raw, String file, String path, List<ValidationIssue> issues) {
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
            return ref;
        }
        if (!itemExists.test(ref)) {
            issues.add(ValidationIssue.error(file, path,
                ref.isVanilla()
                    ? "'" + ref.id() + "' is not a vanilla item"
                    : capitalize(ref.namespace()) + " item '" + ref.id() + "' does not exist"));
        }
        return ref;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
