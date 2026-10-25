package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.item.ItemRef;
import java.util.Map;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.jetbrains.annotations.Nullable;

/**
 * One growth stage of one path.
 *
 * <p>A stage renders in exactly one of two ways: as an item model shown on a
 * display entity, or as a real block standing in the crop's cell. A block
 * stage names the block ({@code block}) and the block state it should be in
 * ({@code state}); an item stage names the model ({@code model}). Exactly one
 * of the two is set — the loader rejects a stage that claims both or neither.
 *
 * @param model item model shown for this stage, or {@code null} for a block stage
 * @param block block shown for this stage, or {@code null} for an item stage
 * @param state block state properties of the block, never {@code null}
 * @param durationMs how long the crop stays in this stage before advancing;
 *                   the final stage's duration is ignored (the crop is mature)
 * @param footprint cells occupied while in this stage (usually single-cell)
 * @param visual per-stage rendering overrides, never {@code null}
 */
public record StageDefinition(
    int index,
    @Nullable ItemRef model,
    @Nullable BlockRef block,
    Map<String, String> state,
    long durationMs,
    FootprintDefinition footprint,
    VisualOverride visual
) {

    public StageDefinition {
        if (footprint == null) {
            footprint = FootprintDefinition.SINGLE;
        }
        if (visual == null) {
            visual = VisualOverride.NONE;
        }
        if (state == null) {
            state = Map.of();
        } else {
            state = Map.copyOf(state);
        }
    }

    /** True when this stage is drawn as a real block instead of a model. */
    public boolean isBlockStage() {
        return block != null;
    }

    /** Whether this stage shows a model, whether on its own or with a block. */
    public boolean hasModel() {
        return model != null;
    }

    /** Convenience for stages that render with the global visual settings. */
    public StageDefinition(int index, ItemRef model, long durationMs, FootprintDefinition footprint) {
        this(index, model, null, Map.of(), durationMs, footprint, VisualOverride.NONE);
    }

    /** Convenience for item stages that override their rendering. */
    public StageDefinition(int index, ItemRef model, long durationMs,
                           FootprintDefinition footprint, VisualOverride visual) {
        this(index, model, null, Map.of(), durationMs, footprint, visual);
    }

    /**
     * Per-stage rendering overrides.
     *
     * <p>Models within one crop are not always authored the same way — a
     * sprout drawn as a flat cross and a mature plant drawn as a small block
     * want different display contexts, and often different sizes. Any field
     * left null falls back to the global {@code crop-visuals} setting, so a
     * stage only has to state what it needs to differ on.
     */
    public record VisualOverride(
        @Nullable ItemDisplayTransform transform,
        @Nullable Float scale,
        @Nullable Double yOffset
    ) {

        public static final VisualOverride NONE = new VisualOverride(null, null, null);

        public boolean isEmpty() {
            return transform == null && scale == null && yOffset == null;
        }

        public ItemDisplayTransform transformOr(ItemDisplayTransform fallback) {
            return transform == null ? fallback : transform;
        }

        public float scaleOr(float fallback) {
            return scale == null ? fallback : scale;
        }

        public double yOffsetOr(double fallback) {
            return yOffset == null ? fallback : yOffset;
        }
    }
}
