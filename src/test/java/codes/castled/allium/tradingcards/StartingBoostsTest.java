package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.boost.StartingBoosts;
import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a starting boost gets its number.
 *
 * <p>Nothing about these numbers is stored on the card: they are derived from its
 * identity every time they are read. That is what makes them safe to re-render,
 * and it is also the thing that would break silently if the derivation were not
 * stable — a card whose numbers changed on every read would show a player
 * different luck each time they hovered it, and nothing would error.
 */
class StartingBoostsTest {

    private static final List<QualityBand> BANDS = List.of(
        new QualityBand("rotten", 1, 5, 1, "<dark_gray>", 1, 2),
        new QualityBand("okay", 26, 45, 3, "<white>", 4, 5),
        new QualityBand("emaculate", 100, 100, 8, "<gold>", 7, 11));

    private static StartingBoosts boosts() {
        return new StartingBoosts(BANDS, new StartingBoosts.Rules(1.0, 1));
    }

    private static TradingCardData card(Tier tier, int level, int quality, String id) {
        return new TradingCardData("ALLAY", id, tier, level, quality,
            List.of("luck", "strength", "speed"), List.of(), 0.0, level, false);
    }

    @Test
    void theSameCardAlwaysDerivesTheSameNumbers() {
        var rules = boosts();
        var first = rules.valuesFor(card(Tier.ULTIMATE, 5, 100, "allay"), BANDS);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, rules.valuesFor(card(Tier.ULTIMATE, 5, 100, "allay"), BANDS),
                "derivation drifted between reads at attempt " + i);
        }
    }

    @Test
    void aBetterQualityStartsHigher() {
        // The ladder is the point: a rotten card starts near nothing and an
        // immaculate one starts high, on the same tier.
        double rotten = averageBase(1, "rotten");
        double emaculate = averageBase(100, "emaculate");
        assertTrue(emaculate > rotten,
            "emaculate averaged " + emaculate + " against rotten's " + rotten);
    }

    @Test
    void aRarerTierStartsHigher() {
        double simple = averageBase(100, Tier.SIMPLE);
        double fabled = averageBase(100, Tier.FABLED);
        assertTrue(fabled > simple,
            "FABLED averaged " + fabled + " against SIMPLE's " + simple);
    }

    @Test
    void everyBaseLandsInsideItsBandsRange() {
        var rules = boosts();
        for (Tier tier : Tier.values()) {
            for (int quality : new int[] {1, 30, 100}) {
                QualityBand band = rules.bandFor(quality, BANDS);
                for (var v : rules.valuesFor(card(tier, 0, quality, "allay"), BANDS)) {
                    // The tier bonus lands on top, so the floor is the band's
                    // minimum and the ceiling is its maximum plus the bonus.
                    assertTrue(v.base() >= band.boostMin(),
                        "base " + v.base() + " below " + band.id() + "'s floor");
                    assertTrue(v.base() <= band.boostMax()
                            + StartingBoosts.tierBonus(tier),
                        "base " + v.base() + " above " + band.id() + "'s ceiling");
                }
            }
        }
    }

    @Test
    void levellingAddsPointsToOneBoostAtATime() {
        var rules = boosts();
        var none = rules.valuesFor(card(Tier.SIMPLE, 0, 100, "allay"), BANDS);
        int before = none.stream().mapToInt(StartingBoosts.Value::points).sum();
        var some = rules.valuesFor(card(Tier.SIMPLE, 4, 100, "allay"), BANDS);
        int after = some.stream().mapToInt(StartingBoosts.Value::points).sum();
        assertEquals(before, 0, "a fresh card has no level points");
        assertEquals(4, after, "each level puts one point on one boost");
    }

    @Test
    void oneLevelNeverPilesOntoASingleBoost() {
        // Without replacement within a level, a level cannot put several points
        // on the same boost, which would make the (+N) suffix lopsided.
        var rules = boosts();
        var one = rules.valuesFor(card(Tier.SIMPLE, 1, 100, "allay"), BANDS);
        assertEquals(1, one.stream().mapToInt(StartingBoosts.Value::points).sum());
        assertEquals(1, one.stream().filter(v -> v.points() > 0).count(),
            "a single level touched more than one boost");
    }

    @Test
    void thePointsSuffixOnlyAppearsOnceSomethingHasLevelled() {
        assertEquals("", new StartingBoosts.Value(6.0, 0).pointsSuffix());
        assertTrue(new StartingBoosts.Value(6.0, 2).pointsSuffix().contains("+2"));
    }

    @Test
    void twoCardsOfTheSameTierDoNotReadIdentically() {
        // The tier bonus lands at random, so two cards of one tier differ. If
        // they did not, the bonus would just be a flat tier multiplier and the
        // roll would be doing nothing.
        var rules = boosts();
        var a = rules.valuesFor(card(Tier.LEGENDARY, 3, 100, "allay_a"), BANDS);
        var b = rules.valuesFor(card(Tier.LEGENDARY, 3, 100, "allay_b"), BANDS);
        assertNotEquals(a, b, "two cards of one tier derived identical numbers");
    }

    @Test
    void aMergeCarriesTheHigherTiersBonus() {
        // Derived from identity, so raising the tier raises the numbers — which
        // is what someone who merged two cards expects.
        var rules = boosts();
        double simple = rules.valuesFor(card(Tier.SIMPLE, 0, 100, "allay"), BANDS)
            .stream().mapToDouble(StartingBoosts.Value::base).sum();
        double fabled = rules.valuesFor(card(Tier.FABLED, 0, 100, "allay"), BANDS)
            .stream().mapToDouble(StartingBoosts.Value::base).sum();
        assertTrue(fabled > simple + 3,
            "FABLED total " + fabled + " is not meaningfully above SIMPLE's " + simple);
    }

    private static double averageBase(int quality, String unusedId) {
        return averageBase(quality, Tier.SIMPLE);
    }

    private static double averageBase(int quality, Tier tier) {
        var rules = boosts();
        double total = 0.0;
        int n = 40;
        for (int i = 0; i < n; i++) {
            for (var v : rules.valuesFor(card(tier, 0, quality, "allay_" + i), BANDS)) {
                total += v.base();
            }
        }
        return total / (n * 3.0);
    }
}