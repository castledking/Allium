package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.CatchUp;
import codes.castled.allium.harvest.crop.StageDuration;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.FootprintDefinition;
import codes.castled.allium.harvest.crop.def.HarvestDefinition;
import codes.castled.allium.harvest.crop.def.RegrowthDefinition;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.item.ItemRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Growth jitter. The load-bearing property is not the distribution but the
 * <em>reproducibility</em>: the growth engine advancing a crop one stage at a
 * time and {@link CatchUp} replaying those stages in bulk after offline time
 * must agree exactly, or a crop's timing would shift every time its chunk
 * unloaded.
 */
class StageDurationTest {

    private static final long STAGE_MS = 30 * 60_000L;

    private static CropPathDefinition path(long... durations) {
        StageDefinition[] stages = new StageDefinition[durations.length];
        for (int i = 0; i < durations.length; i++) {
            stages[i] = new StageDefinition(
                i, new ItemRef("nexo", "stage_" + i), durations[i], FootprintDefinition.SINGLE);
        }
        return new CropPathDefinition("normal", 85, List.of(stages),
            HarvestDefinition.empty(), RegrowthDefinition.DISABLED);
    }

    @Test
    void zeroRandomnessLeavesDurationsExact() {
        UUID id = UUID.randomUUID();
        assertEquals(STAGE_MS, StageDuration.jitter(id, 0, STAGE_MS, 0.0D));
        assertEquals(STAGE_MS, StageDuration.forStage(id, 0, STAGE_MS, 1.0D, 0.0D));
    }

    @Test
    void jitterStaysWithinTheConfiguredSpread() {
        for (int i = 0; i < 2000; i++) {
            long rolled = StageDuration.jitter(UUID.randomUUID(), 0, STAGE_MS, 0.25D);
            assertTrue(rolled >= Math.round(STAGE_MS * 0.75D) && rolled <= Math.round(STAGE_MS * 1.25D),
                () -> "out of range: " + rolled);
        }
    }

    @Test
    void jitterIsStableForTheSameCropAndStage() {
        UUID id = UUID.randomUUID();
        long first = StageDuration.jitter(id, 3, STAGE_MS, 0.25D);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, StageDuration.jitter(id, 3, STAGE_MS, 0.25D));
        }
    }

    /**
     * Without this, every stage of a crop would be jittered the same direction
     * and the crop would simply be uniformly fast or slow — which is a much
     * less interesting outcome than staggered stages.
     */
    @Test
    void adjacentStagesOfOneCropRollIndependently() {
        UUID id = UUID.randomUUID();
        int distinct = 0;
        long previous = StageDuration.jitter(id, 0, STAGE_MS, 0.25D);
        for (int stage = 1; stage < 8; stage++) {
            long current = StageDuration.jitter(id, stage, STAGE_MS, 0.25D);
            if (current != previous) distinct++;
            previous = current;
        }
        assertTrue(distinct >= 6, "stages should not share a roll, got " + distinct + "/7 changes");
    }

    @Test
    void differentCropsGetDifferentRolls() {
        assertNotEquals(
            StageDuration.jitter(UUID.randomUUID(), 0, STAGE_MS, 0.25D),
            StageDuration.jitter(UUID.randomUUID(), 0, STAGE_MS, 0.25D));
    }

    /**
     * The whole reason the roll is derived rather than stored: stepping stage
     * by stage (what the growth engine does while loaded) and replaying in one
     * call (what catch-up does on chunk load) must land on the same timings.
     */
    @Test
    void catchUpReproducesTheEnginesJitteredTimings() {
        UUID id = UUID.randomUUID();
        double randomness = 0.25D;
        CropPathDefinition path = path(STAGE_MS, STAGE_MS, STAGE_MS, 0L);
        long plantedAt = 1_000_000L;

        // What the engine would have produced, one stage at a time.
        long stepwiseStart = plantedAt;
        for (int stage = 0; stage < path.matureStage(); stage++) {
            stepwiseStart += StageDuration.forStage(
                id, stage, path.stage(stage).durationMs(), 1.0D, randomness);
        }

        // What catch-up computes after the same span of offline time.
        CatchUp.Result result = CatchUp.advance(
            path, 0, plantedAt, stepwiseStart, 10, 1.0D, id, randomness);

        assertTrue(result.mature(), "crop should have matured");
        assertEquals(path.matureStage(), result.stage());
        assertEquals(stepwiseStart, result.stageStartedAt());
    }

    /** A jittered crop must not mature earlier than its unjittered floor. */
    @Test
    void catchUpRespectsJitterWhenDecidingMaturity() {
        UUID id = UUID.randomUUID();
        CropPathDefinition path = path(STAGE_MS, STAGE_MS, 0L);
        long plantedAt = 0L;
        long exactTotal = 2 * STAGE_MS;

        CatchUp.Result atExactTotal = CatchUp.advance(
            path, 0, plantedAt, exactTotal, 10, 1.0D, id, 0.9D);
        long jitteredTotal =
            StageDuration.jitter(id, 0, STAGE_MS, 0.9D) + StageDuration.jitter(id, 1, STAGE_MS, 0.9D);

        // Mature at the exact total only if this crop's own roll came in under it.
        assertEquals(jitteredTotal <= exactTotal, atExactTotal.mature());
    }

    @Test
    void speedMultiplierScalesTheJitteredDuration() {
        UUID id = UUID.randomUUID();
        long full = StageDuration.forStage(id, 0, STAGE_MS, 1.0D, 0.25D);
        long halved = StageDuration.forStage(id, 0, STAGE_MS, 0.5D, 0.25D);
        assertEquals(Math.round(full * 0.5D), halved, 1L);
    }

    @Test
    void matureStageHasNoDurationToJitter() {
        assertEquals(0L, StageDuration.jitter(UUID.randomUUID(), 4, 0L, 0.25D));
    }
}
