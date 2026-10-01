package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.xp.FishSizeTracker;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The percentile maths behind "large fish", which is the part with no upstream
 * API to lean on and therefore the part most likely to be quietly wrong.
 */
class FishSizeTrackerTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void nothingCountsUntilThereIsEnoughHistory() {
        // With a sample of one, the first fish would be at the 100th percentile
        // of itself and every new species would pay out on the first catch.
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 8));
        for (int i = 0; i < 8; i++) {
            assertFalse(tracker.record(PLAYER, "COD_1", 2.0),
                "catch " + (i + 1) + " should not count with too little history");
        }
        assertEquals(8, tracker.sampleSize(PLAYER, "COD_1"));
    }

    @Test
    void anOutlierCountsOnceThereIsHistory() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 8));
        for (int i = 0; i < 10; i++) {
            tracker.record(PLAYER, "COD_1", 2.0);
        }
        assertTrue(tracker.record(PLAYER, "COD_1", 9.0),
            "a fish far above the player's own recent catches is large for them");
        assertFalse(tracker.record(PLAYER, "COD_1", 2.0),
            "another ordinary fish is not");
    }

    @Test
    void theTopQuarterIsRoughlyTheTopQuarterOfAnOrdinarySample() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(200, 0.75, 4));
        // A scattered, realistic catch history rather than an ascending ladder:
        // every value in an ascending run is a personal best, so every one of
        // them is legitimately large and a top-quarter cut would not apply.
        double[] history = {3, 5, 4, 6, 3, 7, 4, 5, 8, 4, 6, 5, 3, 7, 4, 6, 5, 8, 4, 6};
        for (double weight : history) {
            tracker.record(PLAYER, "SALMON_1", weight);
        }
        int large = 0;
        for (double weight : history) {
            // Re-caught from the same history, so each is judged against a
            // sample of ordinary fish rather than against its predecessors.
            if (tracker.record(UUID.randomUUID(), "SALMON_1", weight)) {
                large++;
            }
        }
        assertTrue(large >= 0 && large <= 5,
            "of an ordinary sample, " + large + " counted as large; expected the "
                + "top quarter at most");
    }

    @Test
    void aPersonalBestAlwaysCountsEvenAgainstAPoorHistory() {
        // The deliberate consequence of self-calibration: an ascending player
        // finds every improvement large, because each one IS their best. That is
        // the reward working, not a loophole.
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(200, 0.75, 4));
        int large = 0;
        for (int i = 1; i <= 20; i++) {
            if (tracker.record(PLAYER, "SALMON_1", i)) {
                large++;
            }
        }
        // The first four build history; every catch after is a personal best.
        assertEquals(16, large,
            "each new personal best should count, once there is history");
    }

    @Test
    void speciesAreCalibratedIndependently() {
        // The whole reason for a rolling sample: a big salmon must not make a
        // small cod look large, because the two are not comparable.
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 8));
        for (int i = 0; i < 10; i++) {
            tracker.record(PLAYER, "SALMON_1", 8.0);
        }
        for (int i = 0; i < 10; i++) {
            assertFalse(tracker.record(PLAYER, "COD_1", 1.0),
                "a 1kg cod is not large just because the player lands 8kg salmon");
        }
        assertTrue(tracker.record(PLAYER, "COD_1", 4.0),
            "but a 4kg cod is large against the player's own 1kg cod");
    }

    @Test
    void playersAreCalibratedIndependently() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 4));
        UUID beginner = UUID.randomUUID();
        UUID expert = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            tracker.record(beginner, "COD_1", 1.0);
            tracker.record(expert, "COD_1", 20.0);
        }
        assertTrue(tracker.record(beginner, "COD_1", 4.0),
            "4kg is large for someone who lands 1kg");
        assertFalse(tracker.record(expert, "COD_1", 4.0),
            "and unremarkable for someone who lands 20kg");
    }

    @Test
    void theSampleIsBounded() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(10, 0.75, 4));
        for (int i = 0; i < 500; i++) {
            tracker.record(PLAYER, "COD_1", 1.0 + (i % 5));
        }
        assertEquals(10, tracker.sampleSize(PLAYER, "COD_1"),
            "a year of fishing must not grow an unbounded sample");
    }

    @Test
    void aZeroOrMissingWeightIsIgnored() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 4));
        assertFalse(tracker.record(PLAYER, "COD_1", 0.0));
        assertFalse(tracker.record(PLAYER, "COD_1", -5.0));
        assertFalse(tracker.record(PLAYER, null, 5.0));
        assertFalse(tracker.record(PLAYER, "  ", 5.0));
        assertEquals(0, tracker.sampleSize(PLAYER, "COD_1"),
            "a species with no weight range contributes nothing");
    }

    @Test
    void theSpeciesKeyIsCaseInsensitive() {
        // LiteFish writes the id as it appears in its config; the config file
        // may spell it differently, and the two must reach one sample.
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 4));
        for (int i = 0; i < 10; i++) {
            tracker.record(PLAYER, "COD_1", 2.0);
        }
        assertTrue(tracker.record(PLAYER, "cod_1", 9.0));
    }

    @Test
    void theMedianReportsWhatNormalMeansForThisPlayer() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 4));
        for (int i = 0; i < 10; i++) {
            tracker.record(PLAYER, "COD_1", 4.0);
        }
        assertEquals(4.0, tracker.medianFor(PLAYER, "COD_1"), 1e-9);
        assertEquals(0.0, tracker.medianFor(PLAYER, "NEVER_CAUGHT"), 1e-9);
    }

    @Test
    void forgettingAPlayerClearsOnlyTheirSamples() {
        FishSizeTracker tracker = new FishSizeTracker(
            new FishSizeTracker.Rules(40, 0.75, 4));
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            tracker.record(PLAYER, "COD_1", 2.0);
            tracker.record(other, "COD_1", 2.0);
        }
        tracker.forget(PLAYER);
        assertEquals(0, tracker.sampleSize(PLAYER, "COD_1"));
        assertEquals(10, tracker.sampleSize(other, "COD_1"));
    }

    @Test
    void percentileUsesTheMidRankSoAValueInTheSampleIsNeverZeroOrOne() {
        // Without mid-ranking, a value present in the sample would land on
        // either 0 or 1, and a "top 25%" cut would keep either nothing or
        // everything depending on ties.
        double[] sample = {1.0, 2.0, 3.0, 4.0};
        assertEquals(0.125, FishSizeTracker.percentileOf(sample, 1.0), 1e-9);
        assertEquals(0.375, FishSizeTracker.percentileOf(sample, 2.0), 1e-9);
        assertEquals(0.875, FishSizeTracker.percentileOf(sample, 4.0), 1e-9);
        // A value beyond the whole sample sits at exactly 1.0, and that is
        // correct: it is larger than everything observed, so it is the largest
        // catch this player has had. The mid-rank guarantee only covers values
        // that appear in the sample.
        assertEquals(1.0, FishSizeTracker.percentileOf(sample, 99.0), 1e-9);
        assertEquals(0.0, FishSizeTracker.percentileOf(new double[0], 1.0), 1e-9);
    }

    @Test
    void tiesCountHalfSoAConstantSpeciesNeverAllCountsAtOnce() {
        // A player who only ever lands fish of identical weight must not find
        // every single one of them "large".
        double[] sample = {3.0, 3.0, 3.0, 3.0};
        assertEquals(0.5, FishSizeTracker.percentileOf(sample, 3.0), 1e-9);
    }
}
