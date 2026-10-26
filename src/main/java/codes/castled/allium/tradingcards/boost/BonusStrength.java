package codes.castled.allium.tradingcards.boost;

import java.util.random.RandomGenerator;

/**
 * How hard a rolled bonus landed, and what that makes it worth.
 *
 * <p>A bonus slot rolls two things: which boost, and its strength, a whole
 * number from {@link #MIN} to {@link #MAX}. The strength is a position through
 * the configured range, so 0 is the weakest a boost can roll and 100 the
 * strongest, and the menu's bar is just that position drawn. Storing the
 * position rather than the value means a rebalanced {@code boosts.yml} moves
 * every card with it, the way a rebalanced amount already does.
 *
 * <p>The range is a factor on the boost's configured amount, centred on 1 by
 * default so the configured amount stays the typical roll. A card that holds a
 * bonus from before strengths existed reads as {@link #unrolled()}, the strength
 * at which the factor is 1, so it keeps exactly the value it always had.
 *
 * <p>Player scale is the exception: it is a size, so its strength picks the
 * size itself, from the boost's {@code minimum} to its {@code maximum}
 * ({@link #SCALE_MIN} to {@link #SCALE_MAX} unless configured). Its value is the
 * whole multiplier, 0.6x to 2.0x, not an amount to add.
 *
 * @param low  the factor a strength of 0 applies
 * @param high the factor a strength of 100 applies
 */
public record BonusStrength(double low, double high) {

    public static final int MIN = 0;
    public static final int MAX = 100;

    /** The smallest and largest a player can be made, and the scale roll's default ends. */
    public static final double SCALE_MIN = 0.6;
    public static final double SCALE_MAX = 2.0;

    public BonusStrength {
        low = Math.max(0.0, low);
        high = Math.max(low, high);
    }

    public static BonusStrength defaults() {
        return new BonusStrength(0.75, 1.25);
    }

    /** Whether a boost has a magnitude to roll. A permission does not. */
    public static boolean rolls(BoostMechanism mechanism) {
        return mechanism != null && mechanism != BoostMechanism.CARD_PERMISSION;
    }

    /** A fresh strength, every value equally likely. */
    public int roll(RandomGenerator random) {
        return MIN + random.nextInt(MAX - MIN + 1);
    }

    /** The strength a bonus rolled before strengths existed stands at. */
    public int unrolled() {
        if (high <= low) return MAX;
        double position = (1.0 - low) / (high - low);
        return clamp((int) Math.round(position * MAX));
    }

    /**
     * The strength a slot holds. An absent one is the strength that reproduces
     * the value the bonus had before strengths: {@link #unrolled()}, or for a
     * scale bonus the size its amount used to add up to.
     */
    public int resolve(BoostDefinition boost, Integer strength) {
        if (strength != null) return clamp(strength);
        if (boost != null && boost.mechanism() == BoostMechanism.CARD_SCALE) {
            // Scale was applied as "+amount" on top of the player's own size.
            return scaleStrength(boost, 1.0 + boost.amount());
        }
        return unrolled();
    }

    public double factor(int strength) {
        return low + (high - low) * clamp(strength) / (double) MAX;
    }

    /**
     * A bonus boost's value at a strength.
     *
     * <p>A multiplier rolls only its gain over 1: an x1.25 rolled at a 0.75
     * factor is x1.19, where scaling the whole number would hand out x0.94 and
     * make a weak roll a penalty.
     */
    public double valueOf(BoostDefinition boost, Integer strength) {
        double amount = boost.amount();
        if (!rolls(boost.mechanism())) return amount;
        int resolved = resolve(boost, strength);
        if (boost.mechanism() == BoostMechanism.CARD_SCALE) {
            double low = scaleLow(boost);
            return low + (scaleHigh(boost) - low) * resolved / (double) MAX;
        }
        double factor = factor(resolved);
        return boost.mechanism().growsFromOne()
            ? 1.0 + (amount - 1.0) * factor
            : amount * factor;
    }

    private static double scaleLow(BoostDefinition boost) {
        return boost.optionDouble("minimum", SCALE_MIN);
    }

    private static double scaleHigh(BoostDefinition boost) {
        return Math.max(scaleLow(boost), boost.optionDouble("maximum", SCALE_MAX));
    }

    /** The strength at which a scale bonus is this size. */
    private static int scaleStrength(BoostDefinition boost, double size) {
        double low = scaleLow(boost);
        double high = scaleHigh(boost);
        if (high <= low) return MAX;
        return clamp((int) Math.round((size - low) / (high - low) * MAX));
    }

    private static int clamp(int strength) {
        return Math.max(MIN, Math.min(MAX, strength));
    }
}
