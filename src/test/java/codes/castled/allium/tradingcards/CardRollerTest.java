package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.CardDefinition;
import codes.castled.allium.tradingcards.card.CardRoller;
import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.item.ItemRef;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class CardRollerTest {

    private static final List<QualityBand> BANDS = List.of(
        new QualityBand("rotten", 1, 25, 1, "gray"),
        new QualityBand("fine", 26, 75, 5, "yellow"),
        new QualityBand("emaculate", 76, 100, 8, "gold"));

    private static CardDefinition chicken(double chance, Map<Tier, Double> weights) {
        Map<Tier, ItemRef> items = new EnumMap<>(Tier.class);
        for (Tier tier : weights.keySet()) {
            items.put(tier, ItemRef.parse("nexo:" + tier.name().toLowerCase() + "_card"));
        }
        return new CardDefinition("chicken", "CHICKEN", chance, weights, items, null);
    }

    private static CardRoller rollerFor(CardDefinition card) {
        return new CardRoller(Map.of("chicken", card), BANDS);
    }

    /** A generator that always returns the same value, for boundary checks. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override public double nextDouble() { return value; }
            @Override public long nextLong() { return (long) (value * Long.MAX_VALUE); }
            @Override public int nextInt(int bound) { return (int) (value * bound); }
        };
    }

    @Test
    void theShippedWeightsResolveInTheDocumentedProportions() {
        // config.yml: SIMPLE 60, ELITE 25, ULTIMATE 10, LEGENDARY 4.5, FABLED 0.5
        CardDefinition card = chicken(1.0, weights(60, 25, 10, 4.5, 0.5));
        CardRoller roller = rollerFor(card);
        RandomGenerator zero = fixed(0.0);
        assertEquals(Tier.SIMPLE, roller.rollTier(card, zero).orElseThrow());
        // 0.6 of the way through the total (100) lands in ELITE's span.
        assertEquals(Tier.ELITE,
            roller.rollTier(card, fixed(0.70)).orElseThrow());
        assertEquals(Tier.ULTIMATE,
            roller.rollTier(card, fixed(0.92)).orElseThrow());
        assertEquals(Tier.LEGENDARY,
            roller.rollTier(card, fixed(0.985)).orElseThrow());
        assertEquals(Tier.FABLED,
            roller.rollTier(card, fixed(0.999)).orElseThrow());
    }

    @Test
    void exactlyOneTierEverWins() {
        CardDefinition card = chicken(1.0, weights(60, 25, 10, 4.5, 0.5));
        CardRoller roller = rollerFor(card);
        for (int i = 0; i <= 1000; i++) {
            double roll = i / 1000.0;
            Optional<Tier> tier = roller.rollTier(card, fixed(roll));
            assertTrue(tier.isPresent(), "no tier at roll " + roll);
        }
    }

    @Test
    void aTierAlwaysRollsEvenAtTheVeryTopOfTheRange() {
        // Guards the float-accumulation case: a roll marginally above the
        // summed weight must still produce the last tier, not silently drop
        // the card.
        CardDefinition card = chicken(1.0, weights(0.1, 0.2, 0.3, 0.0, 0.0));
        CardRoller roller = rollerFor(card);
        assertEquals(Tier.ULTIMATE,
            roller.rollTier(card, fixed(0.9999999)).orElseThrow());
    }

    @Test
    void zeroWeightTiersAreNeverRolled() {
        CardDefinition card = chicken(1.0, weights(100, 0, 0, 0, 0));
        CardRoller roller = rollerFor(card);
        for (int i = 0; i < 500; i++) {
            assertEquals(Tier.SIMPLE,
                roller.rollTier(card, fixed(i / 500.0)).orElseThrow());
        }
    }

    @Test
    void anAllZeroCardIsNotDroppableAndRollsNothing() {
        CardDefinition card = chicken(1.0, weights(0, 0, 0, 0, 0));
        CardRoller roller = rollerFor(card);
        assertFalse(card.isDroppable());
        assertTrue(roller.rollTier(card, fixed(0.5)).isEmpty());
        assertTrue(roller.roll("CHICKEN", fixed(0.0)).isEmpty());
    }

    @Test
    void aZeroChanceMobNeverDrops() {
        CardDefinition card = chicken(0.0, weights(100, 0, 0, 0, 0));
        CardRoller roller = rollerFor(card);
        assertTrue(roller.roll("CHICKEN", fixed(0.0)).isEmpty(),
            "nextDouble() of 0 is below every chance, so a zero chance must still refuse");
    }

    @Test
    void anUnconfiguredMobNeverDrops() {
        CardRoller roller = rollerFor(chicken(1.0, weights(100, 0, 0, 0, 0)));
        assertTrue(roller.roll("CREEPER", fixed(0.0)).isEmpty());
        assertTrue(roller.roll(null, fixed(0.0)).isEmpty());
    }

    @Test
    void aSuccessfulDropAlwaysResolvesToABand() {
        CardDefinition card = chicken(1.0, weights(100, 0, 0, 0, 0));
        CardRoller roller = rollerFor(card);
        for (int i = 0; i < 400; i++) {
            CardRoller.RolledCard rolled = roller.roll("CHICKEN", seeded(i)).orElseThrow();
            assertTrue(rolled.band().contains(rolled.quality()),
                "quality " + rolled.quality() + " fell outside band " + rolled.band().id());
        }
    }

    @Test
    void qualityIsUniformAcrossTheWholeRange() {
        // 400 rolls over three bands of 25/50/25 should land in roughly that
        // shape. Generous bounds, because this is a distribution smoke test
        // rather than a statistical one.
        CardDefinition card = chicken(1.0, weights(100, 0, 0, 0, 0));
        CardRoller roller = rollerFor(card);
        int rotten = 0;
        int fine = 0;
        int emaculate = 0;
        for (int i = 0; i < 3000; i++) {
            CardRoller.RolledCard rolled = roller.roll("CHICKEN", seeded(i)).orElseThrow();
            switch (rolled.band().id()) {
                case "rotten" -> rotten++;
                case "fine" -> fine++;
                default -> emaculate++;
            }
        }
        assertTrue(rotten > 500 && rotten < 1000, "rotten was " + rotten);
        assertTrue(fine > 1200 && fine < 1800, "fine was " + fine);
        assertTrue(emaculate > 500 && emaculate < 1000, "emaculate was " + emaculate);
    }

    @Test
    void theDropChanceOfASpecificOutcomeIsChanceTimesWeightShare() {
        CardDefinition card = chicken(0.0015, weights(60, 25, 10, 4.5, 0.5));
        assertEquals(0.0015 * 0.6, card.chanceOf(Tier.SIMPLE), 1e-9);
        assertEquals(0.0015 * 0.005, card.chanceOf(Tier.FABLED), 1e-9);
        assertEquals(0.0015, card.chanceOf(Tier.SIMPLE) + card.chanceOf(Tier.ELITE)
            + card.chanceOf(Tier.ULTIMATE) + card.chanceOf(Tier.LEGENDARY)
            + card.chanceOf(Tier.FABLED), 1e-9);
    }

    @Test
    void tierOrderingDrivesEveryThresholdGate() {
        assertTrue(Tier.FABLED.atLeast(Tier.SIMPLE));
        assertTrue(Tier.SIMPLE.atLeast(Tier.SIMPLE));
        assertFalse(Tier.SIMPLE.atLeast(Tier.ELITE));
        assertTrue(Tier.ULTIMATE.atLeast(null),
            "an unset threshold should not block anything");
    }

    @Test
    void pipsFillLeftToRight() {
        assertEquals("◆◇◇◇◇", Tier.SIMPLE.pips());
        assertEquals("◆◆◆◆◆", Tier.FABLED.pips());
        assertEquals("◆◇◇◇◇  (1/5)", Tier.SIMPLE.pipsWithPosition());
        assertEquals("◆◆◆◇◇  (3/5)", Tier.ULTIMATE.pipsWithPosition());
    }

    private static Map<Tier, Double> weights(double simple, double elite,
                                             double ultimate, double legendary,
                                             double fabled) {
        Map<Tier, Double> weights = new EnumMap<>(Tier.class);
        weights.put(Tier.SIMPLE, simple);
        weights.put(Tier.ELITE, elite);
        weights.put(Tier.ULTIMATE, ultimate);
        weights.put(Tier.LEGENDARY, legendary);
        weights.put(Tier.FABLED, fabled);
        return weights;
    }

    /** A deterministic generator so a failure is reproducible. */
    private static RandomGenerator seeded(long seed) {
        return new java.util.Random(seed);
    }
}
