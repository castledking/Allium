package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.jetbrains.annotations.Nullable;

/**
 * One growth stage of one path.
 *
 * @param durationMs how long the crop stays in this stage before advancing;
 *                   the final stage's duration is ignored (the crop is mature)
 * @param footprint cells occupied while in this stage (usually single-cell)
 * @param visual per-stage rendering overrides, never {@code null}
 */
public record StageDefinition(
    int index,
    ItemRef model,
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
    }

    /** Convenience for stages that render with the global visual settings. */
    public StageDefinition(int index, ItemRef model, long durationMs, FootprintDefinition footprint) {
        this(index, model, durationMs, footprint, VisualOverride.NONE);
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
