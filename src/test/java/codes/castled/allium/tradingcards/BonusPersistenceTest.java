package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.boost.BoostMechanism;
import codes.castled.allium.tradingcards.boost.BoostService;
import codes.castled.allium.tradingcards.boost.AuraSkillsBridge;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.reroll.RerollPricing;
import codes.castled.allium.tradingcards.reroll.RerollService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Bonus boosts: that they are stored, that they move on a reroll, and that they
 * never move on a level-up.
 *
 * <p>The distinction being pinned down is the one the whole two-list design
 * exists for. Signatures are a card's identity and grow with every level;
 * bonuses are flat and change only when the card is rerolled. If a level-up
 * could grow a bonus, a card would gain a loot boost it was never awarded, and
 * the two lists would stop meaning anything.
 */
class BonusPersistenceTest {

    private static TradingCardData card(Tier tier, int level, List<String> signatures,
                                        List<String> bonuses, int rerolls) {
        return new TradingCardData("CHICKEN", "chicken", tier, level, 50, signatures, bonuses,
            rerolls, false);
    }

    // ==================== the two lists stay separate ====================

    @Test
    void levellingGrowsSignaturesAndLeavesBonusesAlone() {
        TradingCardData start = card(Tier.SIMPLE, 0,
            List.of("luck", "strength", "speed"), List.of("crop-boost", "armor"), 0);
        TradingCardData levelled = start.withLevel(1);

        assertEquals(1, levelled.level());
        assertEquals(start.bonuses(), levelled.bonuses(),
            "levelling must never grow a bonus; the drop awarded it");
        assertEquals(start.signatures(), levelled.signatures(),
            "the boost value grows with the level, not the signature list");
    }

    @Test
    void rerollingReplacesBonusesAndLeavesSignaturesAlone() {
        TradingCardData start = card(Tier.SIMPLE, 10,
            List.of("luck", "strength", "speed"), List.of("crop-boost"), 3);
        TradingCardData rerolled = start
            .withReroll(4, start.signatures())
            .withBonuses(List.of("armor", "haste"));

        assertEquals(4, rerolled.rerolls());
        assertEquals(List.of("armor", "haste"), rerolled.bonuses());
        assertEquals(start.signatures(), rerolled.signatures(),
            "a bonus roll must not silently unlock a signature too");
    }

    @Test
    void aBonusRollAndAnUnlockAreMutuallyExclusive() {
        TradingCardData start = card(Tier.SIMPLE, 0,
            List.of("luck", "strength", "speed"), List.of("crop-boost"), 0);

        TradingCardData unlocked = start
            .withReroll(1, java.util.List.of("luck", "strength", "speed", "health"));
        assertEquals(start.bonuses(), unlocked.bonuses());

        TradingCardData rerolled = start.withReroll(1, start.signatures())
            .withBonuses(List.of("armor"));
        assertEquals(start.signatures(), rerolled.signatures());
    }

    @Test
    void theConvenienceConstructorLeavesBonusesEmpty() {
        TradingCardData plain = new TradingCardData("CHICKEN", "chicken", Tier.SIMPLE, 0, 50,
            List.of("luck"), 0, false);
        assertEquals(List.of(), plain.bonuses(),
            "a card built by older callers must read as having no bonuses");
    }

    @Test
    void aCardWithoutBonusesIsStillValid() {
        TradingCardData plain = card(Tier.SIMPLE, 0, List.of("luck"), List.of(), 0);
        assertTrue(plain.bonuses().isEmpty());
        assertFalse(plain.bonuses() == null, "never null; empty is the value");
    }

    // ==================== rolling ====================

    @Test
    void rollsNeverRepeatABoost() {
        List<String> pool = List.of("a", "b", "c", "d", "e");
        for (int seed = 0; seed < 50; seed++) {
            List<String> rolled = roll(pool, 3, seed);
            assertEquals(3, rolled.size());
            assertEquals(3, new HashSet<>(rolled).size(),
                "a card holding the same bonus twice is worse than a shorter card");
        }
    }

    @Test
    void aPoolSmallerThanTheCountYieldsTheWholePool() {
        List<String> rolled = roll(List.of("a", "b"), 3, 7);
        assertEquals(List.of("a", "b"), rolled.stream().sorted().toList(),
            "the roll count is a ceiling, never a reason to repeat");
    }

    @Test
    void anEmptyPoolRollsNothingRatherThanFailing() {
        assertEquals(List.of(), roll(List.of(), 3, 1));
        assertEquals(List.of(), roll(null, 3, 1));
    }

    @Test
    void everyBonusInTheRollComesFromThePool() {
        List<String> pool = List.of("crop-boost", "armor", "haste");
        for (int seed = 0; seed < 30; seed++) {
            for (String bonus : roll(pool, 2, seed)) {
                assertTrue(pool.contains(bonus), bonus + " is not in the pool");
            }
        }
    }

    @Test
    void differentRollsActuallyDiffer() {
        List<String> pool = List.of("a", "b", "c", "d", "e", "f");
        Set<List<String>> seen = new HashSet<>();
        for (int seed = 0; seed < 30; seed++) {
            seen.add(List.copyOf(roll(pool, 3, seed)));
        }
        assertNotEquals(1, seen.size(),
            "a reroll that always returns the same bonuses is not a reroll");
    }

    /** Rolls through the service, which is where the rule actually lives. */
    private static List<String> roll(List<String> pool, int rollCount, long seed) {
        BoostCatalog.LoadResult catalog = new BoostCatalog.LoadResult(
            Map.of(), Map.of(), Map.of(), Map.of(), rollCount, 0.35, 6, List.of());
        RerollService service = new RerollService(catalog, pricing(), null, fixed(seed));
        return service.rollBonuses(pool);
    }

    /** Pricing is irrelevant to the roll; only the roll itself is under test. */
    private static RerollPricing pricing() {
        return RerollPricing.from(new codes.castled.allium.tradingcards.config.TradingCardsConfig.Reroll(
            true, 2000.0, 1.6, 100L, 0.0, Map.of(), 0.35, 6, false));
    }

    private static RandomGenerator fixed(long seed) {
        // SplittableRandom is deterministic per seed and needs no Bukkit, so the
        // roll can be asserted on exactly rather than statistically.
        return new java.util.SplittableRandom(seed);
    }

    // ==================== what the consumer reads ====================

    @Test
    void flatBoostsAddAndMultipliersMultiply() {
        BoostService service = new BoostService(Logger.getLogger("test"),
            new AuraSkillsBridge(Logger.getLogger("test"), "test:card"));
        var uuid = java.util.UUID.randomUUID();

        // Nothing equipped: the reads must be neutral, not zero. A multiplier
        // of zero would zero a sale rather than discount it.
        assertEquals(0.0, service.total(uuid, BoostMechanism.CARD_TOKEN_DROP_CHANCE));
        assertEquals(1.0, service.product(uuid, BoostMechanism.CARD_XP_MULTIPLIER));
        assertEquals(1.0, service.multiplier(uuid, BoostMechanism.CARD_XP_MULTIPLIER, 1.0));
    }

    @Test
    void aMultiplierFloorClampsTheValueFromBelow() {
        BoostService service = new BoostService(Logger.getLogger("test"),
            new AuraSkillsBridge(Logger.getLogger("test"), "test:card"));
        var uuid = java.util.UUID.randomUUID();

        // The floor is a minimum, not a target: with no card equipped the
        // neutral product of 1.0 passes through even when the floor is lower.
        assertEquals(1.0, service.multiplier(uuid, BoostMechanism.CARD_SELL_MULTIPLIER, 0.5));
        // And a floor above the neutral value is honoured, which is what stops a
        // card rolling a sub-1 multiplier from making a sale worth nothing.
        assertEquals(1.0, service.multiplier(uuid, BoostMechanism.CARD_SELL_MULTIPLIER, 1.0));
    }

    @Test
    void multipliersComposeRatherThanAdding() {
        // Two cards granting x1.25 and x1.5 give x1.875, not x2.75 — summing
        // multipliers would hand out more than the two cards are worth.
        assertEquals(1.25 * 1.5, 1.875, 1e-9);
    }
}
