package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.def.BlockRef;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropDefinitionLoader;
import codes.castled.allium.harvest.crop.def.InteractionSettings;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.item.ItemRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Stages that are drawn as real blocks rather than as models.
 *
 * <p>A block stage names the block and the state it should be in; the loader
 * has to accept it, keep the model optional, and refuse a stage that claims
 * both or neither.</p>
 */
class CropBlockStageTest {

    private static final Set<String> KNOWN_BLOCKS = Set.of("tomato", "tomato_ripe");

    /** A loader that knows one CraftEngine pack namespace and its blocks. */
    private static CropDefinitionLoader loader(boolean blocksKnown) {
        return new CropDefinitionLoader(
            ns -> ns.equals("minecraft") || ns.equals("default"),
            ref -> ref.isVanilla()
                ? org.bukkit.Material.matchMaterial(ref.id().toUpperCase()) != null
                : ref.id().equals("tomato_seeds") || ref.id().equals("tomato"),
            InteractionSettings.DEFAULT,
            0.0D,
            ref -> KNOWN_BLOCKS.contains(ref.id()),
            () -> blocksKnown);
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

    private static final String BLOCK_CROP = """
        id: tomato
        display-name: "<red>Tomato"
        seed:
          item: default:tomato_seeds
        placement:
          valid-soils: [FARMLAND]
        paths:
          entries:
            normal:
              weight: 100
              stages:
                - block: default:tomato
                  state:
                    age: 0
                  duration: 30m
                - block: default:tomato_ripe
                  state:
                    age: 3
              mature-harvest:
                primary:
                  mode: GUARANTEED_ALL
                  entries:
                    fruit:
                      drops:
                        - item: default:tomato
                          amount: 1
        """;

    /*
     * The fixture is a text block, so its incidental indentation is stripped:
     * the stage list sits at eight spaces, its keys at ten, and their values
     * at twelve.
     */
    private static final String FIRST_STAGE = "        - block: default:tomato\n";
    private static final String FIRST_STAGE_FULL =
        "        - block: default:tomato\n"
            + "          state:\n"
            + "            age: 0\n"
            + "          duration: 30m\n";
    private static final String SECOND_STAGE_FULL =
        "        - block: default:tomato_ripe\n"
            + "          state:\n"
            + "            age: 3\n";

    @Test
    void blockStageParsesWithoutAModel() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader(true).parseCrop(yaml(BLOCK_CROP), "tomato.yml", issues);

        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);

        StageDefinition stage = crop.paths().get("normal").stage(0);
        assertTrue(stage.isBlockStage());
        assertNull(stage.model());
        assertEquals(new BlockRef("default", "tomato"), stage.block());
        assertEquals(Map.of("age", "0"), stage.state());
        assertEquals(30 * 60_000L, stage.durationMs());
    }

    @Test
    void blockStateValuesAreStrings() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader(true).parseCrop(yaml(BLOCK_CROP), "tomato.yml", issues);

        assertNotNull(crop);
        StageDefinition stage = crop.paths().get("normal").stage(1);
        assertEquals("3", stage.state().get("age"));
        assertEquals(0L, stage.durationMs(), "the mature stage carries no duration");
    }

    @Test
    void unknownBlockIsReportedWhenTheProviderIsReady() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader(true).parseCrop(
            yaml(BLOCK_CROP.replace(FIRST_STAGE, "        - block: default:carrot\n")),
            "tomato.yml", issues);

        assertTrue(issues.stream().anyMatch(i ->
                i.isError() && i.message().contains("does not exist")),
            () -> "expected a missing-block error, got: " + issues);
    }

    @Test
    void blockIsNotJudgedWhileTheProviderIsStillLoading() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader(false).parseCrop(
            yaml(BLOCK_CROP.replace(FIRST_STAGE, "        - block: default:carrot\n")),
            "tomato.yml", issues);

        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);
    }

    @Test
    void aStageCanBeABlockWithAModelInIt() {
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader(true).parseCrop(
            yaml(BLOCK_CROP.replace(FIRST_STAGE,
                "        - block: default:tomato\n"
                    + "          model: default:tomato_seeds\n")),
            "tomato.yml", issues);

        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);
        StageDefinition stage = crop.paths().get("normal").stage(0);
        assertTrue(stage.isBlockStage());
        assertTrue(stage.hasModel());
        assertEquals(new BlockRef("default", "tomato"), stage.block());
        assertEquals(new ItemRef("default", "tomato_seeds"), stage.model());
    }


    @Test
    void aStageMustBeEitherAModelOrABlock() {
        List<ValidationIssue> issues = new ArrayList<>();
        loader(true).parseCrop(
            yaml(BLOCK_CROP.replace(FIRST_STAGE_FULL, "        - duration: 30m\n")),
            "tomato.yml", issues);

        assertTrue(issues.stream().anyMatch(i ->
                i.isError() && i.message().contains("neither")),
            () -> "expected a neither-defined error, got: " + issues);
    }

    @Test
    void modelStagesKeepWorking() {
        String modelCrop = BLOCK_CROP
            .replace(FIRST_STAGE_FULL,
                "        - model: default:tomato_seeds\n          duration: 30m\n")
            .replace(SECOND_STAGE_FULL,
                "        - model: default:tomato_seeds\n");
        List<ValidationIssue> issues = new ArrayList<>();
        CropDefinition crop = loader(true).parseCrop(yaml(modelCrop), "tomato.yml", issues);

        assertNotNull(crop);
        assertTrue(issues.stream().noneMatch(ValidationIssue::isError),
            () -> "unexpected errors: " + issues);
        StageDefinition stage = crop.paths().get("normal").stage(0);
        assertFalse(stage.isBlockStage());
        assertEquals(new ItemRef("default", "tomato_seeds"), stage.model());
        assertTrue(stage.state().isEmpty());
    }

    @Test
    void blockReferencesParseNamespacedAndVanilla() {
        assertEquals(new BlockRef("default", "tomato"), BlockRef.parse("default:tomato"));
        assertEquals(new BlockRef("minecraft", "wheat"), BlockRef.parse("wheat"));
        assertEquals(new BlockRef("minecraft", "wheat"), BlockRef.parse("minecraft:wheat"));
        assertEquals("default:tomato", BlockRef.parse("default:tomato").toString());
    }
}
