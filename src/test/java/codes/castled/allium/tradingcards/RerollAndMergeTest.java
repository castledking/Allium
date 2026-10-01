package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.merge.MergeRules;
import codes.castled.allium.tradingcards.reroll.RerollPricing;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RerollAndMergeTest {

    private static RerollPricing shipped() {
        // Read from the real config so the ladder under test is the one an
        // operator ships, not a fixture that cannot drift.
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = RerollAndMergeTest.class
                .getResourceAsStream("/tradingcards/config.yml")) {
            assertTrue(in != null, "tradingcards/config.yml is not on the classpath");
            yaml.loadFromString(new String(in.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped config", e);
        }
        var config = codes.castled.allium.tradingcards.config.TradingCardsConfig.load(yaml).config();
        assertFalse(config.reroll().escalation() < 1.0, "escalation must be at least 1");
        return RerollPricing.from(config.reroll());
    }

    private static codes.castled.allium.tradingcards.item.TradingCardData card(
            Tier tier, int level, List<String> signatures, int rerolls) {
        return new codes.castled.allium.tradingcards.item.TradingCardData(
            "CHICKEN", "chicken", tier, level, 50, signatures, rerolls, false);
    }

    // ==================== pricing ====================

    @Test
    void theLadderIsProgressive() {
        RerollPricing pricing = shipped();
        assertTrue(pricing.isProgressive(),
            "each reroll must cost more than the last, or the optimum is to reroll forever");
    }

    @Test
    void theShippedLadderMatchesWhatTheConfigDocuments() {
        RerollPricing pricing = shipped();
        // 2k base, x1.6, rounded to 100: 2k 3k 5k 8k 13k 21k 33k 53k 85k
        assertEquals(2000.0, pricing.costFor(1, Tier.SIMPLE), 1e-9);
        assertEquals(3200.0, pricing.costFor(2, Tier.SIMPLE), 1e-9);
        assertEquals(5100.0, pricing.costFor(3, Tier.SIMPLE), 1e-9);
        assertEquals(8200.0, pricing.costFor(4, Tier.SIMPLE), 1e-9);
        assertEquals(13100.0, pricing.costFor(5, Tier.SIMPLE), 1e-9);
    }

    @Test
    void everyPriceIsRoundedSoTheNextOneIsPredictable() {
        RerollPricing pricing = shipped();
        for (int attempt = 1; attempt <= 30; attempt++) {
            double cost = pricing.costFor(attempt, Tier.SIMPLE);
            assertEquals(0.0, cost % 100.0, 1e-9,
                "attempt " + attempt + " cost " + cost + " is not a round number");
        }
    }

    @Test
    void thePriceIsCappedSoItNeverBecomesAbsurd() {
        RerollPricing pricing = shipped();
        assertEquals(500000.0, pricing.costFor(100, Tier.SIMPLE), 1e-9);
    }

    @Test
    void aTierMultiplierMakesAFabledRerollDearerThanASimpleOne() {
        RerollPricing pricing = shipped();
        assertTrue(pricing.costFor(1, Tier.FABLED) > pricing.costFor(1, Tier.SIMPLE));
        assertEquals(5.0, pricing.costFor(1, Tier.FABLED) / pricing.costFor(1, Tier.SIMPLE), 0.01);
    }

    @Test
    void aFlatEscalationGivesAFlatLadder() {
        // Documented alternative: escalation 1.0 for a casual sink.
        Map<Tier, Double> flat = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) flat.put(t, 1.0);
        RerollPricing flatLadder = new RerollPricing(true, 2000, 1.0, 100, 500000,
            flat, 0.35, 6, true);
        assertEquals(2000.0, flatLadder.costFor(1, Tier.SIMPLE), 1e-9);
        assertEquals(2000.0, flatLadder.costFor(9, Tier.SIMPLE), 1e-9);
    }

    @Test
    void theNextPriceAccountsForRerollsAlreadySpent() {
        RerollPricing pricing = shipped();
        var card = card(Tier.SIMPLE, 0, List.of("luck"), 0);
        assertEquals(pricing.costFor(1, Tier.SIMPLE), pricing.nextCost(0, Tier.SIMPLE), 1e-9);
        assertEquals(pricing.costFor(4, Tier.SIMPLE), pricing.nextCost(3, Tier.SIMPLE), 1e-9);
        assertTrue(pricing.costFor(1, Tier.SIMPLE) < pricing.costFor(4, Tier.SIMPLE),
            "a card that has already been rerolled three times must be dearer to reroll");
    }

    @Test
    void aFixedBudgetBuysFewerRerollsThanAFlatLadderWould() {
        // The whole point of the escalation: the same money buys ten first
        // rerolls under a flat fee and fewer than ten on a rising ladder, so
        // the cost of wanting "one more" keeps going up.
        RerollPricing pricing = shipped();
        double budget = pricing.costFor(1, Tier.SIMPLE) * 10;
        int affordable = pricing.rerollsAffordable(budget, Tier.SIMPLE);
        assertTrue(affordable < 10,
            "twenty thousand bought " + affordable + " rerolls; a rising ladder must buy fewer than ten");
        assertTrue(affordable > 0, "the first reroll must be affordable");
    }

    // ==================== merge ====================

    private static MergeRules rules() {
        return new MergeRules(true, true, 100, 0);
    }

    @Test
    void twoMaxedCardsOfTheSameTierMergeIntoTheNextTierAtLevelZero() {
        var verdict = rules().check(
            card(Tier.SIMPLE, 100, List.of("luck", "strength", "speed"), 0),
            card(Tier.SIMPLE, 100, List.of("luck", "strength", "speed"), 0));
        assertTrue(verdict.allowed());
        assertEquals(Tier.ELITE, verdict.resultTier());
        assertEquals(0, rules().targetLevel(),
            "a merged card must start where a fresh drop starts, or merging is a shortcut");
    }

    @Test
    void aMergeNeverProducesAFabledCardBecauseThereIsNothingAboveIt() {
        var verdict = rules().check(
            card(Tier.FABLED, 100, List.of("luck"), 0),
            card(Tier.FABLED, 100, List.of("luck"), 0));
        assertFalse(verdict.allowed());
        assertEquals(MergeRules.Denial.TOP_TIER, verdict.denial());
    }

    @Test
    void differentMobsAreRefusedByName() {
        var a = card(Tier.SIMPLE, 100, List.of("luck"), 0);
        var b = new codes.castled.allium.tradingcards.item.TradingCardData(
            "COW", "cow", Tier.SIMPLE, 100, 50, List.of("luck"), 0, false);
        var verdict = rules().check(a, b);
        assertFalse(verdict.allowed());
        assertEquals(MergeRules.Denial.DIFFERENT_MOBS, verdict.denial());
        assertTrue(verdict.message().contains("Chicken") && verdict.message().contains("Cow"),
            "the refusal should name both mobs, got: " + verdict.message());
    }

    @Test
    void differentTiersAreRefusedByName() {
        var verdict = rules().check(
            card(Tier.SIMPLE, 100, List.of("luck"), 0),
            card(Tier.ELITE, 100, List.of("luck"), 0));
        assertFalse(verdict.allowed());
        assertEquals(MergeRules.Denial.DIFFERENT_TIERS, verdict.denial());
        assertTrue(verdict.message().contains("SIMPLE") && verdict.message().contains("ELITE"),
            "the refusal should name both tiers, got: " + verdict.message());
    }

    @Test
    void aCardBelowMaximumIsRefusedWithBothLevels() {
        var verdict = rules().check(
            card(Tier.SIMPLE, 100, List.of("luck"), 0),
            card(Tier.SIMPLE, 73, List.of("luck"), 0));
        assertFalse(verdict.allowed());
        assertEquals(MergeRules.Denial.NOT_MAXIMUM, verdict.denial());
        assertTrue(verdict.message().contains("100") && verdict.message().contains("73"),
            "the refusal should show both levels, got: " + verdict.message());
    }

    @Test
    void allThreeConditionsAreCheckedIndependently() {
        // Each condition alone must be enough to refuse; a card that only
        // checked two would let one of these through.
        var maxedSimple = card(Tier.SIMPLE, 100, List.of("luck"), 0);
        assertFalse(rules().check(maxedSimple,
            card(Tier.SIMPLE, 50, List.of("luck"), 0)).allowed(), "level check");
        assertFalse(rules().check(maxedSimple,
            card(Tier.ELITE, 100, List.of("luck"), 0)).allowed(), "tier check");
        assertFalse(rules().check(maxedSimple,
            new codes.castled.allium.tradingcards.item.TradingCardData(
                "COW", "cow", Tier.SIMPLE, 100, 50, List.of("luck"), 0, false)).allowed(),
            "mob check");
    }

    @Test
    void theMergedCardStartsWithTheThreeDefaultSignatures() {
        // Not the union of both inputs: a merge produces a card of a higher
        // tier, and carrying a FABLED unlock into an ELITE card would give it a
        // signature its own tier's pool could not roll.
        var rules = rules();
        assertEquals(List.of("luck", "strength", "speed"), rules.mergedSignatures());
    }

    @Test
    void aDisabledMergeRefusesEverything() {
        var disabled = new MergeRules(false, true, 100, 0);
        var verdict = disabled.check(
            card(Tier.SIMPLE, 100, List.of("luck"), 0),
            card(Tier.SIMPLE, 100, List.of("luck"), 0));
        assertFalse(verdict.allowed());
        assertEquals(MergeRules.Denial.DISABLED, verdict.denial());
    }

    @Test
    void theTierLadderIsExactlyTheFiveTiersInOrder() {
        var rules = rules();
        assertEquals(Tier.ELITE, rules.nextTier(Tier.SIMPLE).orElseThrow());
        assertEquals(Tier.ULTIMATE, rules.nextTier(Tier.ELITE).orElseThrow());
        assertEquals(Tier.LEGENDARY, rules.nextTier(Tier.ULTIMATE).orElseThrow());
        assertEquals(Tier.FABLED, rules.nextTier(Tier.LEGENDARY).orElseThrow());
        assertTrue(rules.nextTier(Tier.FABLED).isEmpty());
    }
}
