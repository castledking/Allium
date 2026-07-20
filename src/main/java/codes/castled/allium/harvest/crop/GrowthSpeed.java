package codes.castled.allium.harvest.crop;

/**
 * Combines the growth-speed multipliers that can act on one crop.
 *
 * <p>A multiplier scales a stage's configured duration: {@code 1.0} leaves it
 * alone, {@code 0.5} makes the stage take half as long, {@code 2.0} twice as
 * long. Sources stack multiplicatively, so a 0.8 fertilizer under a 0.75
 * sprinkler yields 0.6 — each source keeps its own meaning ("20% faster",
 * "25% faster") instead of the combined number needing a lookup table.
 *
 * <p>The result is clamped so no combination can drive a stage to zero
 * duration, which would make the growth engine advance a crop every pass.
 */
public final class GrowthSpeed {

    /** Never let stacked multipliers collapse a stage below this fraction. */
    public static final double MINIMUM_MULTIPLIER = 0.05D;

    private GrowthSpeed() {
    }

    /** Multiplies the given factors together and clamps the result. */
    public static double combine(double... multipliers) {
        double combined = 1.0D;
        for (double multiplier : multipliers) {
            if (multiplier > 0.0D) {
                combined *= multiplier;
            }
        }
        return clamp(combined);
    }

    public static double clamp(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
            return 1.0D;
        }
        return Math.max(MINIMUM_MULTIPLIER, multiplier);
    }

    /**
     * Applies a speed multiplier to a stage duration.
     *
     * <p>A non-zero duration never becomes zero: an extremely fast crop still
     * takes at least a millisecond per stage, so the engine keeps advancing it
     * one stage per pass rather than looping.
     */
    public static long apply(long durationMs, double multiplier) {
        if (durationMs <= 0L) {
            return durationMs;
        }
        long scaled = Math.round(durationMs * clamp(multiplier));
        return Math.max(1L, scaled);
    }
}
