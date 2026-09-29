package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.kitchen.KitchenConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class KitchenConfigTest {

    /** Minecraft always resolves; Nexo ids exist when the predicate says so. */
    private static KitchenConfig.LoadResult load(YamlConfiguration yaml, Predicate<String> nexoExists) {
        return KitchenConfig.load(yaml,
            ns -> ns.equals("minecraft") || ns.equals("nexo"),
            ref -> ref.isVanilla()
                ? Material.matchMaterial(ref.id().toUpperCase()) != null
                : nexoExists.test(ref.id()),
            ref -> Optional.empty());
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

    private static YamlConfiguration shipped() {
        var stream = KitchenConfigTest.class.getResourceAsStream("/harvest/kitchen.yml");
        assertNotNull(stream, "kitchen.yml is packaged");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    private static final String MINIMAL = """
        pies:
          crust: { item: nexo:pie_crust, model: nexo:pie_crust_placed }
          steps:
            - item: minecraft:egg
            - filling: true
              amount: 2
          types:
            blackberry:
              filling: [nexo:blackberries, nexo:blackberries_golden_star]
        """;

    @Test
    void shippedConfigLoadsCleanlyWhenEveryItemExists() {
        KitchenConfig.LoadResult result = load(shipped(), id -> true);
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());

        KitchenConfig config = result.config();
        assertTrue(config.enabled());
        KitchenConfig.Bag bag = config.bags().get(new ItemRef("nexo", "flour_bag"));
        assertEquals(256, bag.capacity());
        assertEquals(new ItemRef("nexo", "flour"), bag.stores());

        assertEquals(Set.of(Material.CAULDRON, Material.WATER_CAULDRON), config.kneading().blocks());
        assertEquals(3, config.kneading().waterRequired());

        KitchenConfig.Pies pies = config.pies();
        assertEquals(4, pies.steps().size());
        assertTrue(pies.steps().get(2).isFilling());
        assertEquals("Egg", pies.steps().get(0).name());
        assertEquals(9, pies.types().size());
        assertEquals(600_000L, pies.cooling().millis());
        assertEquals(40L, pies.hologram().cycleTicks());
    }

    @Test
    void modelIdsFollowThePatterns() {
        KitchenConfig.Pies pies = load(shipped(), id -> true).config().pies();
        KitchenConfig.PieType blackberry = pies.type("blackberry").orElseThrow();
        assertEquals(new ItemRef("nexo", "cold_blackberry_pie"), blackberry.coldItem());
        assertEquals(new ItemRef("nexo", "baked_blackberry_pie"), blackberry.bakedItem());
        assertEquals(new ItemRef("nexo", "blackberry_pie_filled"), blackberry.filledModel());
        assertEquals(new ItemRef("nexo", "cold_blackberry_pie_placed"), blackberry.coldModels().get(0));
        assertEquals(new ItemRef("nexo", "baked_blackberry_pie_placed_slice3"), blackberry.bakedModels().get(3));
    }

    @Test
    void everyQualityOfAFillingPicksTheSamePie() {
        KitchenConfig.Pies pies = load(shipped(), id -> true).config().pies();
        assertEquals("blackberry", pies.typeByFilling(new ItemRef("nexo", "blackberries_silver_star"))
            .orElseThrow().id());
        assertEquals("apple", pies.typeByFilling(new ItemRef("minecraft", "apple")).orElseThrow().id());
        assertTrue(pies.typeByFilling(new ItemRef("minecraft", "dirt")).isEmpty());
        assertEquals(Boolean.TRUE, pies.byItem(new ItemRef("nexo", "baked_pumpkin_pie")).orElseThrow().getValue());
    }

    @Test
    void aMissingModelDropsOnlyThatPieType() {
        KitchenConfig.LoadResult result = load(shipped(),
            id -> !id.equals("baked_apple_pie_placed_slice2"));
        KitchenConfig.Pies pies = result.config().pies();
        assertTrue(pies.type("apple").isEmpty());
        assertEquals(8, pies.types().size());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.isError()
            && issue.path().equals("pies.types.apple.baked-models[2]")));
    }

    @Test
    void missingCrustDisablesPiesButKeepsTheRest() {
        KitchenConfig.LoadResult result = load(shipped(), id -> !id.equals("pie_crust"));
        assertNull(result.config().pies());
        assertNotNull(result.config().kneading());
        assertFalse(result.config().bags().isEmpty());
    }

    @Test
    void exactlyOneFillingStepIsRequired() {
        KitchenConfig.LoadResult result = load(yaml("""
            pies:
              crust: { item: nexo:pie_crust, model: nexo:pie_crust_placed }
              steps:
                - item: minecraft:egg
              types:
                blackberry: { filling: nexo:blackberries }
            """), id -> true);
        assertNull(result.config().pies());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals("pies.steps")));
    }

    @Test
    void aFillingCanOnlyBelongToOnePie() {
        KitchenConfig.LoadResult result = load(yaml(MINIMAL + """
                other:
                  filling: nexo:blackberries_golden_star
            """), id -> true);
        assertEquals(Set.of("blackberry"), result.config().pies().types().keySet());
        assertTrue(result.issues().stream().anyMatch(ValidationIssue::isError));
    }

    @Test
    void aCauldronCannotNeedMoreThanThreeLevels() {
        KitchenConfig.LoadResult result = load(yaml("""
            kneading:
              stations: { blocks: [CAULDRON] }
              water: { required: 4 }
              flour: { item: nexo:flour }
              result: { item: nexo:dough }
            """), id -> true);
        assertNull(result.config().kneading());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals("kneading.water.required")));
    }

    @Test
    void chocolateAndPumpkinSkipTheDoughTop() {
        KitchenConfig.Pies pies = load(shipped(), id -> true).config().pies();
        assertTrue(pies.type("chocolate").orElseThrow().skips(3));
        assertTrue(pies.type("pumpkin").orElseThrow().skips(3));
        assertFalse(pies.type("apple").orElseThrow().skips(3));
    }

    @Test
    void stepsBeforeTheFillingCannotBeSkipped() {
        KitchenConfig.LoadResult result = load(yaml(MINIMAL + """
                  skip-steps: [1]
            """), id -> true);
        assertTrue(result.config().pies().type("blackberry").orElseThrow().skipSteps().isEmpty());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().endsWith(".skip-steps")));
    }

    @Test
    void theKitchenStoveIsAFurnace() {
        KitchenConfig config = load(shipped(), id -> true).config();
        KitchenConfig.Stove stove = config.furnaces().get("kitchen_stove");
        assertEquals(KitchenConfig.StoveType.FURNACE, stove.type());
        assertEquals(1.0D, stove.speed());
    }

    @Test
    void aStoveNeedsAKnownTypeAndAPositiveSpeed() {
        KitchenConfig.LoadResult result = load(yaml("""
            furnaces:
              stations:
                grill: { type: GRILL }
                oven: { speed: 0 }
                smoker: { type: smoker, speed: 0.5 }
            """), id -> true);
        assertEquals(Set.of("smoker"), result.config().furnaces().keySet());
        assertEquals(2, result.issues().size());
    }

    @Test
    void disabledFileLoadsNothing() {
        KitchenConfig.LoadResult result = load(yaml("enabled: false"), id -> true);
        assertFalse(result.config().enabled());
        assertTrue(result.issues().isEmpty());
    }
}
