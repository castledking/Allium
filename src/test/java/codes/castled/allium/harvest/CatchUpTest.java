package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.CatchUp;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.FootprintDefinition;
import codes.castled.allium.harvest.crop.def.HarvestDefinition;
import codes.castled.allium.harvest.crop.def.RegrowthDefinition;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.item.ItemRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatchUpTest {

    /** 4 stages: 10s, 20s, 30s, mature. */
    private static CropPathDefinition path() {
        ItemRef model = ItemRef.parse("minecraft:wheat_seeds");
        return new CropPathDefinition("normal", 85, List.of(
            new StageDefinition(0, model, 10_000, FootprintDefinition.SINGLE),
            new StageDefinition(1, model, 20_000, FootprintDefinition.SINGLE),
            new StageDefinition(2, model, 30_000, FootprintDefinition.SINGLE),
            new StageDefinition(3, model, 0, FootprintDefinition.SINGLE)),
            HarvestDefinition.empty(), RegrowthDefinition.DISABLED);
    }

    @Test
    void noTimeElapsedMeansNoAdvance() {
        CatchUp.Result result = CatchUp.advance(path(), 0, 1_000_000, 1_000_000, 10);
        assertEquals(0, result.stage());
        assertEquals(0, result.stagesAdvanced());
        assertFalse(result.mature());
        assertEquals(1_010_000, result.nextGrowthAt());
    }

    @Test
    void partialElapsedAdvancesOnlyEarnedStages() {
        // 25s elapsed: stage 0 (10s) done, stage 1 (20s) not yet.
        CatchUp.Result result = CatchUp.advance(path(), 0, 0, 25_000, 10);
        assertEquals(1, result.stage());
        assertEquals(1, result.stagesAdvanced());
        assertEquals(10_000, result.stageStartedAt());
        assertEquals(30_000, result.nextGrowthAt());
    }

    @Test
    void longOfflineTimeReachesMaturityExactlyOnce() {
        // Days elapsed: catches up to the mature stage and stops there.
        CatchUp.Result result = CatchUp.advance(path(), 0, 0, 86_400_000, 10);
        assertEquals(3, result.stage());
        assertTrue(result.mature());
        assertEquals(0, result.nextGrowthAt());
        assertEquals(3, result.stagesAdvanced());
    }

    @Test
    void catchUpIsCappedAndOwedTimeResumesImmediately() {
        CatchUp.Result result = CatchUp.advance(path(), 0, 0, 86_400_000, 2);
        assertEquals(2, result.stage());
        assertEquals(2, result.stagesAdvanced());
        assertFalse(result.mature());
        assertEquals(86_400_000, result.nextGrowthAt(),
            "capped catch-up must resume promptly, not restart the stage timer");
    }

    @Test
    void zeroCapFreezesProgress() {
        CatchUp.Result result = CatchUp.advance(path(), 1, 0, 86_400_000, 0);
        assertEquals(1, result.stage());
        assertEquals(0, result.stagesAdvanced());
    }

    @Test
    void matureCropStaysMature() {
        CatchUp.Result result = CatchUp.advance(path(), 3, 0, 999_999_999, 10);
        assertEquals(3, result.stage());
        assertTrue(result.mature());
        assertEquals(0, result.stagesAdvanced());
    }
}
