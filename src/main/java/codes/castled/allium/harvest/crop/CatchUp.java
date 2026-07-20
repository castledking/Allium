package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.StageDefinition;

/**
 * Pure catch-up math for crops whose chunk was unloaded while real time
 * passed. Advances through the stage durations of the crop's stored path,
 * capped by the configured maximum, and reports the resulting stage plus the
 * timestamp bookkeeping — without touching Bukkit, so it is unit-testable.
 */
public final class CatchUp {

    /**
     * @param stage resulting stage index
     * @param stageStartedAt when the resulting stage logically began
     * @param nextGrowthAt when the next advance is due (0 when mature)
     * @param mature whether the crop reached its final stage
     * @param stagesAdvanced how many stages were advanced
     */
    public record Result(int stage, long stageStartedAt, long nextGrowthAt, boolean mature, int stagesAdvanced) {}

    private CatchUp() {}

    /**
     * Advances a crop along its path given the wall-clock now.
     *
     * @param path the crop's selected path definition
     * @param currentStage current stage index
     * @param stageStartedAt epoch millis the current stage began
     * @param now current epoch millis
     * @param maxStages cap on stages advanced in one catch-up (0 = none)
     */
    public static Result advance(
        CropPathDefinition path,
        int currentStage,
        long stageStartedAt,
        long now,
        int maxStages
    ) {
        return advance(path, currentStage, stageStartedAt, now, maxStages, 1.0D);
    }

    /**
     * Advances a crop along its path, scaling every stage duration by a growth
     * speed multiplier (see {@link GrowthSpeed}).
     *
     * @param speedMultiplier {@code 1.0} for unmodified growth
     */
    public static Result advance(
        CropPathDefinition path,
        int currentStage,
        long stageStartedAt,
        long now,
        int maxStages,
        double speedMultiplier
    ) {
        int stage = Math.min(currentStage, path.matureStage());
        long startedAt = stageStartedAt;
        int advanced = 0;

        while (stage < path.matureStage() && advanced < maxStages) {
            StageDefinition current = path.stage(stage);
            long dueAt = startedAt + GrowthSpeed.apply(current.durationMs(), speedMultiplier);
            if (now < dueAt) {
                break;
            }
            stage++;
            advanced++;
            startedAt = dueAt;
        }

        boolean mature = stage >= path.matureStage();
        long nextGrowthAt;
        long currentStageMs = mature ? 0L
            : GrowthSpeed.apply(path.stage(stage).durationMs(), speedMultiplier);
        if (mature) {
            nextGrowthAt = 0L;
        } else if (advanced == maxStages && maxStages > 0 && now >= startedAt + currentStageMs) {
            // Cap reached with time still owed: resume promptly rather than
            // pretending the elapsed time never happened.
            nextGrowthAt = now;
        } else {
            nextGrowthAt = startedAt + currentStageMs;
        }
        return new Result(stage, startedAt, nextGrowthAt, mature, advanced);
    }
}
