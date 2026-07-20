package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.random.WeightedEntry;
import java.util.List;

/**
 * One mutually exclusive growth path of a crop (e.g. normal / golden /
 * gigantic). Exactly one path is selected per planted crop with a single
 * weighted roll; the selection is stored on the crop instance and never
 * changes. Different paths may reuse the same stage models — the hidden path
 * id stays distinct, which is what lets a golden crop look ordinary until
 * harvest.
 */
public record CropPathDefinition(
    String id,
    double weight,
    List<StageDefinition> stages,
    HarvestDefinition harvest,
    RegrowthDefinition regrowth,
    BreakDefinition breakDrops
) implements WeightedEntry {

    public CropPathDefinition {
        stages = List.copyOf(stages);
        if (breakDrops == null) {
            breakDrops = BreakDefinition.NONE;
        }
    }

    /** Convenience for paths that drop nothing when broken. */
    public CropPathDefinition(String id, double weight, List<StageDefinition> stages,
                              HarvestDefinition harvest, RegrowthDefinition regrowth) {
        this(id, weight, stages, harvest, regrowth, BreakDefinition.NONE);
    }

    @Override
    public double weight() {
        return weight;
    }

    public int matureStage() {
        return stages.size() - 1;
    }

    public StageDefinition stage(int index) {
        return stages.get(Math.max(0, Math.min(index, stages.size() - 1)));
    }

    public boolean isMature(int stageIndex) {
        return stageIndex >= matureStage();
    }
}
