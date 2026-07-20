package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import java.util.random.RandomGenerator;

/**
 * One item drop inside a harvest outcome.
 *
 * @param chance probability (0..1] that this drop line yields at all; 1.0 for
 *               unconditional drops
 */
public record DropSpec(ItemRef item, int minAmount, int maxAmount, double chance) {

    public DropSpec {
        if (minAmount < 1 || maxAmount < minAmount) {
            throw new IllegalArgumentException(
                "Invalid drop amount range " + minAmount + ".." + maxAmount);
        }
        if (chance <= 0.0D || chance > 1.0D) {
            throw new IllegalArgumentException("Drop chance must be in (0,1], got " + chance);
        }
    }

    /** Rolls the concrete amount for this drop, 0 if the chance roll fails. */
    public int rollAmount(RandomGenerator random) {
        if (chance < 1.0D && random.nextDouble() >= chance) {
            return 0;
        }
        return minAmount == maxAmount
            ? minAmount
            : minAmount + random.nextInt(maxAmount - minAmount + 1);
    }
}
