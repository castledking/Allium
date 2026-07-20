package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import java.util.Map;

/**
 * A fertilizer item and the effects it applies to a crop.
 *
 * <p>A fertilizer is not restricted to one kind of effect: a single definition
 * may carry any combination of quality, yield, speed, variation and soil
 * effects. "Levels" are not a built-in concept — a tier-3 speed fertilizer is
 * simply a separate definition with a stronger multiplier, so every tier's
 * numbers stay independently configurable rather than derived from a formula.
 *
 * <p>Effects apply at different moments in a crop's life, which determines
 * when the fertilizer has to be used:
 * <ul>
 *   <li>{@code variation} — at <em>plant</em> time, when the single weighted
 *       path roll happens. It must therefore be applied to the soil
 *       <em>before</em> the seed goes in; a growth path cannot be re-rolled
 *       once chosen.</li>
 *   <li>{@code growthSpeedMultiplier} — on every stage advance.</li>
 *   <li>{@code weightMultipliers} and {@code yield} — at harvest.</li>
 *   <li>{@code soilRetainMillis} — immediately, extending the soil's life.</li>
 * </ul>
 *
 * @param weightMultipliers   outcome id → multiplier applied to that outcome's
 *                            weight in the primary quality table
 * @param yield               drop-amount effect, never {@code null}
 * @param growthSpeedMultiplier multiplier on each stage duration; {@code 1.0}
 *                            is unchanged, {@code 0.5} grows twice as fast,
 *                            values above {@code 1.0} slow growth down
 * @param variation           path-selection effect, never {@code null}
 * @param soilRetainMillis    time added to the soil's remaining life, or
 *                            {@code 0} when this fertilizer does not feed soil
 * @param appliesToSoil       whether it can be applied to bare soil (required
 *                            for variation and soil effects to ever fire)
 * @param appliesToCrop       whether it can be applied to a growing crop
 */
public record FertilizerDefinition(
    String id,
    ItemRef item,
    String displayName,
    Map<String, Double> weightMultipliers,
    YieldEffect yield,
    double growthSpeedMultiplier,
    VariationEffect variation,
    long soilRetainMillis,
    boolean appliesToSoil,
    boolean appliesToCrop
) {

    public FertilizerDefinition {
        weightMultipliers = Map.copyOf(weightMultipliers);
        if (yield == null) yield = YieldEffect.NONE;
        if (variation == null) variation = VariationEffect.NONE;
        if (growthSpeedMultiplier <= 0.0D) growthSpeedMultiplier = 1.0D;
        if (soilRetainMillis < 0L) soilRetainMillis = 0L;
    }

    /** Whether this fertilizer changes anything about harvest drops. */
    public boolean affectsHarvest() {
        return !weightMultipliers.isEmpty() || !yield.isNone();
    }

    /**
     * Effect on the number of items a drop produces.
     *
     * <p>Applied as {@code round(amount * multiply) + bonus}, then clamped to
     * {@code maximum} when one is set. Keeping multiply and bonus separate lets
     * a config express "+1 tomato" and "50% more tomatoes" independently
     * instead of forcing everything through one number.
     *
     * @param maximum hard cap on the final amount, or {@code 0} for uncapped
     */
    public record YieldEffect(double multiply, int bonus, int maximum) {

        public static final YieldEffect NONE = new YieldEffect(1.0D, 0, 0);

        public YieldEffect {
            if (multiply < 0.0D) multiply = 0.0D;
            if (maximum < 0) maximum = 0;
        }

        public boolean isNone() {
            return multiply == 1.0D && bonus == 0 && maximum == 0;
        }

        /** Applies this effect to a rolled drop amount. */
        public int apply(int amount) {
            long scaled = Math.round(amount * multiply) + bonus;
            if (maximum > 0) {
                scaled = Math.min(scaled, maximum);
            }
            return (int) Math.max(0L, scaled);
        }
    }

    /**
     * Effect on the single weighted growth-path roll performed at plant time.
     *
     * <p>{@code pathMultipliers} targets named paths explicitly; {@code
     * specialMultiplier} is a convenience that scales every path <em>except</em>
     * the crop's most common one, which is the usual intent of a "variation"
     * fertilizer ("make rare outcomes likelier") without having to name each
     * rare path.
     *
     * @param pathMultipliers   path id → weight multiplier
     * @param specialMultiplier multiplier applied to every non-primary path
     */
    public record VariationEffect(Map<String, Double> pathMultipliers, double specialMultiplier) {

        public static final VariationEffect NONE = new VariationEffect(Map.of(), 1.0D);

        public VariationEffect {
            pathMultipliers = Map.copyOf(pathMultipliers);
            if (specialMultiplier <= 0.0D) specialMultiplier = 1.0D;
        }

        public boolean isNone() {
            return pathMultipliers.isEmpty() && specialMultiplier == 1.0D;
        }
    }
}
