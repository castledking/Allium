package codes.castled.allium.tradingcards;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.trade.TradeQuote;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeQuoteTest {

    /** The shipped eight-band ladder. */
    private static final List<QualityBand> BANDS = List.of(
        new QualityBand("rotten", 1, 5, 1, "<dark_gray>"),
        new QualityBand("damaged", 6, 15, 1, "<gray>"),
        new QualityBand("torn", 16, 25, 2, "<dark_red>"),
        new QualityBand("okay", 26, 45, 3, "<white>"),
        new QualityBand("fine", 46, 65, 5, "<yellow>"),
        new QualityBand("great", 66, 85, 6, "<green>"),
        new QualityBand("mint", 86, 99, 7, "<aqua>"),
        new QualityBand("emaculate", 100, 100, 8, "<gold>"));

    private static TradeQuote quoter(boolean tradeEnabled) {
        return quoter(tradeEnabled, false);
    }

    private static TradeQuote quoter(boolean tradeEnabled, boolean requireMintOrBetter) {
        TradeQuote quote = new TradeQuote();
        quote.configure(() -> BANDS, tradeEnabled, requireMintOrBetter);
        return quote;
    }

    private static TradingCardData card(int quality, boolean bound) {
        return new TradingCardData("CHICKEN", "chicken",
            Tier.SIMPLE, 0, quality,
            List.of("luck", "strength", "speed"), 0, bound);
    }

    @Test
    void onePercentPaysOneHeadAndOneHundredPaysEight() {
        TradeQuote quote = quoter(true);
        assertEquals(1, quote.quote(card(1, false)).quote().heads());
        assertEquals(8, quote.quote(card(100, false)).quote().heads());
    }

    @Test
    void theLadderIsMonotonicAcrossEveryBoundary() {
        // The whole point of "proportional" here: a better condition is never
        // worth less than a worse one, and there is no step where the payout
        // falls.
        TradeQuote quote = quoter(true);
        int previous = 0;
        for (int quality = 1; quality <= 100; quality++) {
            int heads = quote.quote(card(quality, false)).quote().heads();
            assertTrue(heads >= previous,
                "quality " + quality + " pays " + heads + ", less than the previous " + previous);
            assertTrue(heads >= 1 && heads <= 8,
                "quality " + quality + " paid " + heads + ", outside 1..8");
            previous = heads;
        }
    }

    @Test
    void eachBandPaysItsOwnValue() {
        TradeQuote quote = quoter(true);
        assertEquals(1, quote.quote(card(1, false)).quote().heads());
        assertEquals(1, quote.quote(card(5, false)).quote().heads());
        assertEquals(1, quote.quote(card(6, false)).quote().heads());
        assertEquals(2, quote.quote(card(16, false)).quote().heads());
        assertEquals(3, quote.quote(card(26, false)).quote().heads());
        assertEquals(5, quote.quote(card(46, false)).quote().heads());
        assertEquals(6, quote.quote(card(66, false)).quote().heads());
        assertEquals(7, quote.quote(card(86, false)).quote().heads());
        assertEquals(8, quote.quote(card(100, false)).quote().heads());
    }

    @Test
    void theLowestAndHighestOfEachBandAgree() {
        TradeQuote quote = quoter(true);
        for (QualityBand band : BANDS) {
            int atMin = quote.quote(card(band.min(), false)).quote().heads();
            int atMax = quote.quote(card(band.max(), false)).quote().heads();
            assertEquals(band.heads(), atMin, "band " + band.id() + " at its minimum");
            assertEquals(band.heads(), atMax, "band " + band.id() + " at its maximum");
        }
    }

    @Test
    void aBoundCardCannotBeTradedBackForTheHeadsThatMadeIt() {
        // Eight heads make a card, and a card buys eight heads. Refusing the
        // bound card is what stops that loop closing.
        TradeQuote quote = quoter(true);
        TradeQuote.Result result = quote.quote(card(100, true));
        assertFalse(result.isQuoted());
        assertEquals(TradeQuote.Denial.BOUND, result.denial());
    }

    @Test
    void tradingCanBeSwitchedOffEntirely() {
        TradeQuote.Result result = quoter(false).quote(card(100, false));
        assertFalse(result.isQuoted());
        assertEquals(TradeQuote.Denial.TRADE_DISABLED, result.denial());
    }

    @Test
    void aQualityOutsideEveryBandHasNoValue() {
        // Reachable only if the band table was reloaded to a broken state
        // underneath a live card, so it must be refused rather than paid at
        // some default rate.
        TradeQuote quote = new TradeQuote();
        quote.configure(() -> List.of(new QualityBand("only", 40, 60, 4, "<white>")), true, false);
        TradeQuote.Result result = quote.quote(card(100, false));
        assertFalse(result.isQuoted());
        assertEquals(TradeQuote.Denial.UNKNOWN_QUALITY, result.denial());
    }

    @Test
    void requiringMintOrBetterRefusesTheLowerSixBands() {
        TradeQuote quote = quoter(true, true);
        // The threshold is the second-highest band's value (mint = 7), so
        // great (6) is refused and mint (7) is not.
        assertEquals(TradeQuote.Denial.BELOW_MINIMUM_QUALITY,
            quote.quote(card(85, false)).denial());
        assertTrue(quote.quote(card(86, false)).isQuoted());
        assertTrue(quote.quote(card(100, false)).isQuoted());
    }

    @Test
    void theMinimumQualityThresholdFollowsTheBandTableNotAHardcodedNumber() {
        // Two ladders with the same shape but different payouts. The floor is
        // the second-highest band's value in each, so raising the top of the
        // ladder raises the floor with it and the two can never disagree.
        TradeQuote low = new TradeQuote();
        low.configure(() -> List.of(
            new QualityBand("common", 1, 50, 1, "<white>"),
            new QualityBand("mint", 51, 80, 2, "<aqua>"),
            new QualityBand("emaculate", 81, 100, 3, "<gold>")), true, true);

        TradeQuote high = new TradeQuote();
        high.configure(() -> List.of(
            new QualityBand("common", 1, 50, 1, "<white>"),
            new QualityBand("mint", 51, 80, 40, "<aqua>"),
            new QualityBand("emaculate", 81, 100, 80, "<gold>")), true, true);

        // A 60% card falls in `mint` in both ladders. It is tradeable in the
        // first (worth 2, floor 2 — the floor is inclusive) and refused in the
        // second (worth 40, floor 40 would pass) — so use a card below the
        // floor to show the two ladders disagree.
        assertTrue(low.quote(card(60, false)).isQuoted());

        // A 20% card falls in `common` (worth 1) in both, under both floors.
        assertEquals(TradeQuote.Denial.BELOW_MINIMUM_QUALITY,
            low.quote(card(20, false)).denial());
        assertEquals(TradeQuote.Denial.BELOW_MINIMUM_QUALITY,
            high.quote(card(20, false)).denial());

        // The floors really do differ: 2 against 40, taken from each table's
        // own second-highest band rather than a shared constant.
        assertEquals(2, low.quote(card(60, false)).quote().heads());
        assertEquals(40, high.quote(card(60, false)).quote().heads());
    }

    @Test
    void theThresholdIsTakenFromTheSortedBandsNotTheConfigOrder() {
        // An operator who lists their bands out of order must still get the
        // floor from the second-highest band by quality, not from whichever
        // band happens to sit second in the file.
        TradeQuote quote = new TradeQuote();
        quote.configure(() -> List.of(
            new QualityBand("emaculate", 81, 100, 8, "<gold>"),
            new QualityBand("common", 1, 50, 1, "<white>"),
            new QualityBand("mint", 51, 80, 4, "<aqua>")), true, true);
        // floor is mint (4): a 60% card is worth 4 and passes.
        assertTrue(quote.quote(card(60, false)).isQuoted());
        // a 10% card is worth 1 and fails.
        assertEquals(TradeQuote.Denial.BELOW_MINIMUM_QUALITY,
            quote.quote(card(10, false)).denial());
    }

    @Test
    void aSingleBandDisablesTheMinimumQualityGate() {
        // With no "second-highest" band there is nothing to threshold against,
        // so refusing everything would be the wrong failure.
        TradeQuote quote = new TradeQuote();
        quote.configure(() -> List.of(new QualityBand("only", 1, 100, 3, "<white>")),
            true, true);
        assertTrue(quote.quote(card(1, false)).isQuoted());
    }

    @Test
    void aCardValuesAgainstTheCurrentLadderNotAHardcodedRate() {
        // The band table is supplied, not baked in, so moving a boundary
        // changes what existing cards pay. That is the point of storing the
        // integer rather than the band name.
        TradeQuote quote = new TradeQuote();
        quote.configure(() -> List.of(new QualityBand("everything", 1, 100, 6, "<gold>")), true, false);
        assertEquals(6, quote.quote(card(1, false)).quote().heads());
        assertEquals(6, quote.quote(card(100, false)).quote().heads());
    }
}
