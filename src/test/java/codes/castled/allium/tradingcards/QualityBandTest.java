package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.QualityBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class QualityBandTest {

    private static QualityBand band(String id, int min, int max) {
        return new QualityBand(id, min, max, 1, "white");
    }

    private static List<QualityBand> defaultLadder() {
        return List.of(
            band("rotten", 1, 5),
            band("damaged", 6, 15),
            band("torn", 16, 25),
            band("okay", 26, 45),
            band("fine", 46, 65),
            band("great", 66, 85),
            band("mint", 86, 99),
            band("emaculate", 100, 100));
    }

    @Test
    void completeLadderHasNoProblems() {
        assertTrue(QualityBand.validate(defaultLadder()).isEmpty(),
            "the shipped eight-band ladder must partition 1..100 exactly");
    }

    @Test
    void aGapIsReportedWithItsExactRange() {
        List<QualityBand> ladder = List.of(
            band("rotten", 1, 5),
            band("damaged", 6, 15),
            band("okay", 26, 100));
        List<String> problems = QualityBand.validate(ladder);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("16..25"),
            "the uncovered stretch should be named, got: " + problems.get(0));
    }

    @Test
    void anOverlapIsReportedWithBothBandNames() {
        List<QualityBand> ladder = List.of(
            band("rotten", 1, 10),
            band("damaged", 6, 20),
            band("okay", 21, 100));
        List<String> problems = QualityBand.validate(ladder);
        assertTrue(problems.stream().anyMatch(p ->
                p.contains("rotten") && p.contains("damaged")),
            "the overlap should name both bands, got: " + problems);
    }

    @Test
    void bothGapsAndOverlapsAreReportedTogether() {
        List<QualityBand> ladder = List.of(
            band("a", 1, 10),
            band("b", 5, 20),
            band("c", 50, 60));
        List<String> problems = QualityBand.validate(ladder);
        assertTrue(problems.size() >= 3,
            "expected an overlap plus both gaps, got: " + problems);
    }

    @Test
    void aBandOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> band("bad", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> band("bad", 90, 101));
        assertThrows(IllegalArgumentException.class, () -> band("bad", 50, 40));
    }

    @Test
    void aBandMustPayAtLeastOneHead() {
        assertThrows(IllegalArgumentException.class,
            () -> new QualityBand("bad", 1, 10, 0, "white"));
    }

    @Test
    void findResolvesEveryRolledValueToExactlyOneBand() {
        List<QualityBand> ladder = defaultLadder();
        for (int roll = QualityBand.ROLL_MIN; roll <= QualityBand.ROLL_MAX; roll++) {
            QualityBand found = QualityBand.find(ladder, roll);
            assertTrue(found != null, "roll " + roll + " resolved to no band");
            assertTrue(found.contains(roll),
                "band " + found.id() + " does not contain " + roll);
        }
    }

    @Test
    void findReturnsNullOutsideTheLadder() {
        assertNull(QualityBand.find(defaultLadder(), 0));
        assertNull(QualityBand.find(defaultLadder(), 101));
        assertNull(QualityBand.find(List.of(), 50));
    }

    @Test
    void unsortedBandsStillResolve() {
        // The loader preserves config order, which need not be ascending, and
        // find() must not depend on it.
        List<QualityBand> shuffled = List.of(
            band("great", 66, 85),
            band("rotten", 1, 5),
            band("emaculate", 100, 100));
        assertEquals("rotten", QualityBand.find(shuffled, 3).id());
        assertEquals("great", QualityBand.find(shuffled, 70).id());
        assertNull(QualityBand.find(shuffled, 50));
    }

    @Test
    void displayIdIsHumanised() {
        assertEquals("Rotten", QualityBand.displayId("rotten"));
        assertEquals("Ultra Rare", QualityBand.displayId("ultra_rare"));
        assertEquals("Very Rare", QualityBand.displayId("very-rare"));
        assertEquals("Unknown", QualityBand.displayId(""));
        assertEquals("Unknown", QualityBand.displayId(null));
    }
}
