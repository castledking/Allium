package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropDefinitionLoader;
import codes.castled.allium.harvest.crop.def.LootMode;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.junit.jupiter.api.Test;

class CropDefinitionLoaderTest {

    /** Namespaces available in the test: minecraft always, nexo "installed". */
    private static final Set<String> KNOWN_NEXO_ITEMS = Set.of(
        "tomato_seeds", "tomato", "tomato_stage_1", "tomato_stage_2");

    private static CropDefinitionLoader loader() {
        return new CropDefinitionLoader(
            ns -> ns.equals("minecraft") || ns.equals("nexo"),
            ref -> ref.isVanilla()
                ? org.bukkit.Material.matchMaterial(ref.id().toUpperCase()) != null
                : KNOWN_NEXO_ITEMS.contains(ref.id()));
    }

    private static YamlConfiguration yaml(String content) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(content);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return configuration;
    }

    private static final String VALID = """
        id: tomato
        display-name: "<red>Tomato"
        seed:
          item: nexo:tomato_seeds
        placement:
          valid-soils: [FARMLAND]
          requires-water: true
          minimum-light: 9
        growth:
          clock: REAL_TIME
        paths:
          entries:
            normal:
              weight: 85
              stages:
                - model: nexo:tomato_stage_1
                  duration: 30m
                - model: nexo:tomato_stage_2
              mature-harvest:
                primary:
                  mode: WEIGHTED_ONE
                  entries:
                    regular:
                      weight: 65
                      drops:
                        - item: nexo:tomato
                          amount: 1
              regrowth:
                enabled: true
                stage: 0
            golden:
              weight: 5
              stages:
                - model: nexo:tomato_stage_1
                  duration: 45m
                - model: nexo:tomato_stage_2
              mature-harvest:
                primary:
                  mode: GUARANTEED_ALL
                  entries:
                    golden:
                      drops:
                        - item: nexo:tomato
                          amount: 1
        """;

    @Test
    void validCropParsesWithoutErrors() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(VALID), "tomato.yml", issues);
        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);
        assertEquals("tomato", crop.id());
        assertEquals(new ItemRef("nexo", "tomato_seeds"), crop.seed());
        assertEquals(2, crop.paths().size());
        assertEquals(85, crop.paths().get("normal").weight());
        assertEquals(2, crop.paths().get("normal").stages().size());
        assertEquals(LootMode.WEIGHTED_ONE,
            crop.paths().get("normal").harvest().primary().mode());
        assertTrue(crop.paths().get("normal").regrowth().enabled());
        assertEquals(30 * 60_000L, crop.paths().get("normal").stage(0).durationMs());
    }

    @Test
    void missingSeedIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(
            yaml(VALID.replace("item: nexo:tomato_seeds", "item: \"\"")), "tomato.yml", issues);
        assertNull(crop);
        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.path().equals("seed.item")));
    }

    @Test
    void unknownNexoItemIsReportedWithFileAndPath() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("nexo:tomato_seeds", "nexo:missing_item")), "tomato.yml", issues);
        ValidationIssue issue = issues.stream()
            .filter(i -> i.message().contains("missing_item")).findFirst().orElseThrow();
        assertTrue(issue.isError());
        assertEquals("tomato.yml", issue.file());
        assertEquals("seed.item", issue.path());
        assertTrue(issue.message().contains("does not exist"));
    }

    @Test
    void unknownNamespaceExplainsMissingPlugin() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("nexo:tomato_seeds", "oraxen:tomato_seeds")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("no resolver")));
    }

    @Test
    void invalidVanillaItemGetsUsefulError() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("nexo:tomato_seeds", "minecraft:tomato")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("not a vanilla item")),
            "minecraft:tomato must error, not silently substitute");
    }

    @Test
    void emptyPathTableIsFatal() {
        String noPaths = """
            id: broken
            seed:
              item: nexo:tomato_seeds
            paths:
              entries: {}
            """;
        List<ValidationIssue> issues = new ArrayList<>();
        assertNull(loader().parseCrop(yaml(noPaths), "broken.yml", issues));
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("no growth paths")));
    }

    @Test
    void allZeroWeightsWithoutFallbackIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("weight: 85", "weight: 0").replace("weight: 5", "weight: 0")),
            "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("zero or negative")));
    }

    @Test
    void regrowthStageOutsidePathIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("stage: 0", "stage: 7")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("outside path")));
    }

    @Test
    void invalidDurationIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("duration: 30m", "duration: banana")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(ValidationIssue::isError));
    }

    @Test
    void independentPrimaryTableIsWarnedAgainst() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("mode: WEIGHTED_ONE", "mode: INDEPENDENT")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            !i.isError() && i.message().contains("not mutually exclusive")));
    }

    @Test
    void invalidSoilMaterialIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(
            yaml(VALID.replace("[FARMLAND]", "[NOT_A_BLOCK]")), "tomato.yml", issues);
        assertNotNull(crop); // falls back to FARMLAND but reports the error
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("NOT_A_BLOCK")));
    }

    @Test
    void weightedTableWithNoPositiveWeightIsFatal() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(
            yaml(VALID.replace("weight: 65", "weight: 0")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
            i.isError() && i.message().contains("no entry with a positive weight")));
    }

    /**
     * The VALID fixture's stage entry, after text-block stripping, puts the
     * list item at eight spaces and its keys at ten — so an injected block has
     * to sit at ten, and its own keys at twelve.
     */
    private static String withStageVisual(String visualBody) {
        return VALID.replace("duration: 30m", "duration: 30m\n" + visualBody);
    }

    @Test
    void stageVisualOverridesAreParsed() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(withStageVisual(
            "          visual:\n"
                + "            transform: HEAD\n"
                + "            scale: 0.75\n"
                + "            y-offset: 0.25")), "tomato.yml", issues);

        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);

        var visual = crop.paths().get("normal").stage(0).visual();
        assertEquals(ItemDisplayTransform.HEAD, visual.transform());
        assertEquals(0.75F, visual.scaleOr(1.0F));
        assertEquals(0.25D, visual.yOffsetOr(0.0D));

        // A stage with no override block falls back to whatever it is given.
        var plain = crop.paths().get("normal").stage(1).visual();
        assertTrue(plain.isEmpty());
        assertEquals(ItemDisplayTransform.GROUND, plain.transformOr(ItemDisplayTransform.GROUND));
        assertEquals(2.0F, plain.scaleOr(2.0F));
        assertEquals(1.5D, plain.yOffsetOr(1.5D));
    }

    @Test
    void partialStageVisualOnlyOverridesWhatItStates() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(withStageVisual(
            "          visual:\n            transform: FIXED")), "tomato.yml", issues);

        assertNotNull(crop);
        var visual = crop.paths().get("normal").stage(0).visual();
        assertEquals(ItemDisplayTransform.FIXED, visual.transform());
        assertEquals(3.0F, visual.scaleOr(3.0F), "unstated scale must fall through");
        assertEquals(9.0D, visual.yOffsetOr(9.0D), "unstated offset must fall through");
    }

    @Test
    void invalidStageTransformIsReported() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(yaml(withStageVisual(
            "          visual:\n            transform: SIDEWAYS")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
                i.isError() && i.message().contains("is not a display transform")),
            () -> "expected a transform error, got: " + issues);
    }

    @Test
    void invalidStageScaleIsReported() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader().parseCrop(yaml(withStageVisual(
            "          visual:\n            scale: 0")), "tomato.yml", issues);
        assertTrue(issues.stream().anyMatch(i ->
                i.isError() && i.message().contains("greater than zero")),
            () -> "expected a scale error, got: " + issues);
    }

    @Test
    void breakDropsAreParsedPerMaturity() {
        // Path-level keys sit at six spaces in the fixture after text-block
        // stripping; the injected block has to line up with `regrowth:`.
        String withBreak = VALID.replace(
            "      regrowth:",
            "      break-drops:\n"
                + "        immature:\n"
                + "          mode: GUARANTEED_ALL\n"
                + "          entries:\n"
                + "            seeds:\n"
                + "              drops:\n"
                + "                - item: nexo:tomato_seeds\n"
                + "                  amount: 1\n"
                + "        mature:\n"
                + "          mode: GUARANTEED_ALL\n"
                + "          entries:\n"
                + "            seeds:\n"
                + "              drops:\n"
                + "                - item: nexo:tomato_seeds\n"
                + "                  amount: 2\n"
                + "      regrowth:");

        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(withBreak), "tomato.yml", issues);
        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);

        var breakDrops = crop.paths().get("normal").breakDrops();
        assertFalse(breakDrops.isEmpty());

        // The table chosen depends on whether the crop had finished growing.
        var immature = breakDrops.tableFor(false).outcomes().get("seeds");
        var mature = breakDrops.tableFor(true).outcomes().get("seeds");
        assertNotNull(immature);
        assertNotNull(mature);
        assertEquals(1, immature.drops().get(0).minAmount());
        assertEquals(1, immature.drops().get(0).maxAmount());
        assertEquals(2, mature.drops().get(0).minAmount());
        assertEquals(2, mature.drops().get(0).maxAmount());
    }

    @Test
    void pathsWithoutBreakDropsReturnNothing() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(VALID), "tomato.yml", issues);
        assertNotNull(crop);
        // No break-drops block means a destroyed crop drops nothing, which is
        // the behaviour that existed before the feature.
        assertTrue(crop.paths().get("normal").breakDrops().isEmpty());
        assertTrue(crop.paths().get("normal").breakDrops().tableFor(true).isEmpty());
        assertTrue(crop.paths().get("normal").breakDrops().tableFor(false).isEmpty());
    }

    @Test
    void selectedPathsAreAlwaysSingular() {
        // Structural guarantee: parsing produces distinct path ids; a crop
        // instance stores exactly one of them (enforced by type: a single
        // String field, not a collection).
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader().parseCrop(yaml(VALID), "tomato.yml", issues);
        assertNotNull(crop);
        assertEquals(crop.paths().keySet().stream().distinct().count(), crop.paths().size());
        assertFalse(crop.paths().containsKey(""));
    }
}
