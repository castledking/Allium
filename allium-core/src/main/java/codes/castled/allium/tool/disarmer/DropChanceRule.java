package codes.castled.allium.tool.disarmer;

/**
 * The slot-chance roll used by {@link DisarmDropRule#SLOT_CHANCE}.
 *
 * <p>Each removed piece is rolled exactly once, against the drop chance its slot had
 * immediately before removal: a chance of {@code 1.0} or more (vanilla uses {@code 2.0} for
 * picked-up items) always drops, a chance of {@code 0} or less (or NaN) never drops, and
 * anything between drops when a uniform roll in {@code [0, 1)} is below it. Pieces that do not
 * drop are destroyed. The slot's chance is then set to {@code 0}, so the mob's death cannot
 * roll the same slot again.
 */
public final class DropChanceRule {

    private DropChanceRule() {
    }

    public static boolean drops(float dropChance, float roll) {
        if (Float.isNaN(dropChance) || dropChance <= 0.0F) {
            return false;
        }
        if (dropChance >= 1.0F) {
            return true;
        }
        return roll < dropChance;
    }
}
