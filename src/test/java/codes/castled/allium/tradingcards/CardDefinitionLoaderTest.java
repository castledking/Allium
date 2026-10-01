package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.tradingcards.card.CardDefinition;
import codes.castled.allium.tradingcards.card.CardDefinitionLoader;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CardDefinitionLoaderTest {

    private static final Set<String> KNOWN_NEXO_ITEMS = Set.of(
        "chicken_trading_card", "elite_chicken_trading_card",
        "cow_trading_card", "elite_cow_trading_card");

    private static CardDefinitionLoader loader() {
        return new CardDefinitionLoader(
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
            throw new AssertionError("test yaml is invalid", e);
        }
        return configuration;
    }

    private static final String FULL_CARD = """
        cards:
          chicken:
            mob: CHICKEN
            chance: 0.0015
            tiers:
              SIMPLE:
                weight: 60.0
                item: nexo:chicken_trading_card
              ELITE:
                weight: 25.0
                item: nexo:elite_chicken_trading_card
        """;

    @Test
    void loadsAWellFormedCard() {
        CardDefinitionLoader.LoadResult result = loader().load(yaml(FULL_CARD));
        assertFalse(result.hasErrors(), () -> "unexpected errors: " + result.issues());

        CardDefinition chicken = result.cards().get("chicken");
        assertEquals("CHICKEN", chicken.mob());
        assertEquals(0.0015, chicken.chance(), 1e-9);
        assertEquals(60.0, chicken.weightOf(Tier.SIMPLE), 1e-9);
        assertEquals(25.0, chicken.weightOf(Tier.ELITE), 1e-9);
        assertEquals(ItemRef.parse("nexo:elite_chicken_trading_card"),
            chicken.itemFor(Tier.ELITE));
        assertEquals(List.of(Tier.SIMPLE, Tier.ELITE), chicken.droppableTiers());
    }

    @Test
    void aMissingFileIsAnErrorNotAnException() {
        CardDefinitionLoader.LoadResult result = loader().load(null);
        assertTrue(result.hasErrors());
        assertTrue(result.cards().isEmpty());
    }

    @Test
    void aMissingCardsSectionIsReported() {
        CardDefinitionLoader.LoadResult result = loader().load(yaml("other: true"));
        assertTrue(result.hasErrors());
        assertTrue(result.cards().isEmpty());
    }

    @Test
    void aTierWithPositiveWeightAndAMissingItemDropsTheWholeCard() {
        // A card that drops an item nobody can hold is worse than no card, so
        // the card is skipped rather than registered half-configured.
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
                  ELITE:
                    weight: 25.0
                    item: nexo:does_not_exist
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.hasErrors());
        assertTrue(result.cards().isEmpty(),
            "the card should be skipped entirely, not registered without ELITE");
    }

    @Test
    void aZeroWeightTierMayOmitItsItem() {
        // A tier nobody can roll needs no item; requiring one would mean
        // retyping the file to disable a tier.
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
                  FABLED:
                    weight: 0.0
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertFalse(result.hasErrors(), () -> "unexpected errors: " + result.issues());
        assertEquals(List.of(Tier.SIMPLE), result.cards().get("chicken").droppableTiers());
    }

    @Test
    void aBareNumberIsAcceptedAsAWeight() {
        // A bare number is shorthand for a weight, usable where the tier is
        // switched off. A bare POSITIVE weight reports the missing-item error,
        // because a tier with weight but no item block would drop something
        // nobody can hold.
        String off = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
                  ELITE: 0.0
            """;
        CardDefinitionLoader.LoadResult disabled = loader().load(yaml(off));
        assertFalse(disabled.hasErrors(),
            () -> "a bare 0.0 is a valid switched-off weight: " + disabled.issues());
        assertEquals(0.0, disabled.cards().get("chicken").weightOf(Tier.ELITE), 1e-9);

        String on = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
                  ELITE: 25.0
            """;
        CardDefinitionLoader.LoadResult enabled = loader().load(yaml(on));
        assertTrue(enabled.issues().stream()
                .anyMatch(i -> i.path().endsWith("tiers.ELITE.item")),
            () -> "expected the missing-item error: " + enabled.issues());
    }

    @Test
    void anUnknownTierIsRejectedByName() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  MYTHIC:
                    weight: 10.0
                    item: nexo:chicken_trading_card
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.hasErrors());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("Unknown tier")),
            () -> "expected an unknown-tier error: " + result.issues());
    }

    @Test
    void aCardWithNoMobIsRejected() {
        String content = """
            cards:
              chicken:
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.hasErrors());
        assertTrue(result.cards().isEmpty());
    }

    @Test
    void anOutOfRangeChanceIsRejected() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 5.0
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().endsWith(".chance")));
    }

    @Test
    void oneBrokenCardDoesNotTakeTheOthersDown() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:chicken_trading_card
              cow:
                mob: COW
                chance: 0.002
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: nexo:does_not_exist
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.hasErrors());
        assertEquals(Set.of("chicken"), result.cards().keySet());
    }

    @Test
    void aVanillaItemReferenceIsAccepted() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                head: minecraft:chicken_skull
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: minecraft:paper
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertFalse(result.hasErrors(), () -> "unexpected errors: " + result.issues());
        assertEquals(ItemRef.parse("minecraft:chicken_skull"),
            result.cards().get("chicken").head());
    }

    @Test
    void anUnknownNamespaceIsRejected() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 60.0
                    item: mythmod:chicken_card
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.message().contains("Unknown item namespace")));
    }

    @Test
    void aCardWithNoPositiveWeightIsReportedAsUndroppable() {
        String content = """
            cards:
              chicken:
                mob: CHICKEN
                chance: 0.0015
                tiers:
                  SIMPLE:
                    weight: 0.0
            """;
        CardDefinitionLoader.LoadResult result = loader().load(yaml(content));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.message().contains("no tier with a positive weight")),
            () -> "expected the no-positive-weight error: " + result.issues());
    }

    @Test
    void chanceOfSumsToTheDropChance() {
        CardDefinitionLoader.LoadResult result = loader().load(yaml(FULL_CARD));
        CardDefinition chicken = result.cards().get("chicken");
        Map<Tier, Double> all = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            all.put(tier, chicken.chanceOf(tier));
        }
        double total = all.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(chicken.chance(), total, 1e-9);
    }

    @Test
    void theShippedRerollLadderIsProgressiveAndRounded() {
        RerollProbe probe = RerollProbe.fromShippedConfig();
        double previous = 0.0;
        for (int attempt = 1; attempt <= 9; attempt++) {
            double cost = probe.cost(attempt);
            assertTrue(cost > previous,
                "reroll " + attempt + " (" + cost + ") is not dearer than " + previous);
            assertEquals(0.0, cost % 100.0, 1e-9, "cost should be rounded to 100");
            previous = cost;
        }
        // The ladder documented in config.yml: 2k 3k 5k 8k 13k 21k 33k 53k 85k
        assertEquals(2000.0, probe.cost(1), 1e-9);
        assertEquals(3200.0, probe.cost(2), 1e-9);
        assertEquals(5100.0, probe.cost(3), 1e-9);
        assertEquals(8200.0, probe.cost(4), 1e-9);
        assertEquals(13100.0, probe.cost(5), 1e-9);
    }

    @Test
    void rerollPriceIsCappedSoItNeverBecomesAbsurd() {
        RerollProbe probe = RerollProbe.fromShippedConfig();
        assertEquals(500000.0, probe.cost(50), 1e-9);
    }

    @Test
    void aTierMultiplierMakesAFabledRerollDearerThanASimpleOne() {
        RerollProbe probe = RerollProbe.fromShippedConfig();
        assertTrue(probe.cost(1, Tier.FABLED) > probe.cost(1, Tier.SIMPLE));
    }

    @Test
    void theShippedQualityLadderIsAccepted() {
        TradingCardsConfig.LoadResult result = TradingCardsConfig.load(
            yaml("""
                enabled: true
                quality:
                  bands:
                    rotten:    { min: 1,   max: 5,   heads: 1, colour: "<dark_gray>" }
                    damaged:   { min: 6,   max: 15,  heads: 1, colour: "<gray>" }
                    torn:      { min: 16,  max: 25,  heads: 2, colour: "<dark_red>" }
                    okay:      { min: 26,  max: 45,  heads: 3, colour: "<white>" }
                    fine:      { min: 46,  max: 65,  heads: 5, colour: "<yellow>" }
                    great:     { min: 66,  max: 85,  heads: 6, colour: "<green>" }
                    mint:      { min: 86,  max: 99,  heads: 7, colour: "<aqua>" }
                    emaculate: { min: 100, max: 100, heads: 8, colour: "<gold>" }
                """));
        assertFalse(result.hasErrors(), () -> "unexpected errors: " + result.issues());
        assertEquals(8, result.config().quality().size());
        assertEquals(8, result.config().quality().get(7).heads());
    }

    @Test
    void aQualityGapIsAnErrorNotAWarning() {
        TradingCardsConfig.LoadResult complete = TradingCardsConfig.load(
            yaml("""
                enabled: true
                quality:
                  bands:
                    rotten: { min: 1,  max: 5,  heads: 1, colour: "gray" }
                    fine:   { min: 6,  max: 100, heads: 5, colour: "yellow" }
                """));
        assertFalse(complete.hasErrors(), "a complete ladder should be clean");

        TradingCardsConfig.LoadResult gapped = TradingCardsConfig.load(
            yaml("""
                enabled: true
                quality:
                  bands:
                    rotten: { min: 1,  max: 5,  heads: 1, colour: "gray" }
                    fine:   { min: 30, max: 100, heads: 5, colour: "yellow" }
                """));
        assertTrue(gapped.issues().stream().anyMatch(i -> i.isError()
                && i.message().contains("6..29")),
            () -> "the gap should be an error naming its range: " + gapped.issues());
    }

    @Test
    void craftingEnabledWithUnboundCardsIsWarnedAbout() {
        // Heads buy cards and cards buy heads; allowing both unbounded is a
        // money printer, so it is called out loudly.
        TradingCardsConfig.LoadResult result = TradingCardsConfig.load(
            yaml("""
                enabled: true
                quality:
                  bands:
                    only: { min: 1, max: 100, heads: 1, colour: "gray" }
                crafting:
                  enabled: true
                  bound: false
                """));
        assertTrue(result.issues().stream().anyMatch(i -> i.path().contains("crafting.bound")),
            () -> "expected a bound warning: " + result.issues());
    }

    @Test
    void anEscalationBelowOneFallsBackToFlat() {
        TradingCardsConfig.LoadResult result = TradingCardsConfig.load(
            yaml("""
                enabled: true
                quality:
                  bands:
                    only: { min: 1, max: 100, heads: 1, colour: "gray" }
                reroll:
                  escalation: 0.5
                """));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().contains("reroll.escalation")));
        assertEquals(1.0, result.config().reroll().escalation(), 1e-9);
    }

    /**
     * Reads the reroll ladder out of the real shipped config, not out of
     * {@code Reroll.defaults()}.
     *
     * <p>The defaults are a flat ladder, so testing against them would only
     * ever prove the formula works — never that the numbers an operator will
     * actually ship are progressive and tier-scaled. This loads the resource
     * the plugin writes to disk, so a bad edit there fails the build.
     */
    static final class RerollProbe {

        private final TradingCardsConfig.Reroll reroll;

        private RerollProbe(TradingCardsConfig.Reroll reroll) {
            this.reroll = reroll;
        }

        static RerollProbe fromShippedConfig() {
            YamlConfiguration shipped = new YamlConfiguration();
            try (java.io.InputStream in = CardDefinitionLoaderTest.class
                    .getResourceAsStream("/tradingcards/config.yml")) {
                assertTrue(in != null, "tradingcards/config.yml is not on the classpath");
                shipped.loadFromString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new AssertionError("could not read the shipped config", e);
            }
            TradingCardsConfig.LoadResult result = TradingCardsConfig.load(shipped);
            assertFalse(result.hasErrors(),
                () -> "the shipped config must load cleanly: " + result.issues());
            return new RerollProbe(result.config().reroll());
        }

        double cost(int attempt) {
            return cost(attempt, Tier.SIMPLE);
        }

        double cost(int attempt, Tier tier) {
            return reroll.costFor(attempt, tier);
        }
    }
}
