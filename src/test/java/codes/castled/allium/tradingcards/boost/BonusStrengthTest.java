package codes.castled.allium.tradingcards.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BonusStrengthTest {

    private static final BonusStrength RANGE = BonusStrength.defaults();

    private static BoostDefinition boost(BoostMechanism mechanism, double amount) {
        return new BoostDefinition("test", "Test", mechanism, amount, 0.0, false, Map.of());
    }

    @Test
    void theWeakestAndStrongestRollsSitAtTheEndsOfTheRange() {
        var bounty = boost(BoostMechanism.CARD_MONEY_ON_QUEST, 250.0);
        assertEquals(187.5, RANGE.valueOf(bounty, 0), 1e-9);
        assertEquals(312.5, RANGE.valueOf(bounty, 100), 1e-9);
    }

    @Test
    void aBonusRolledBeforeStrengthsKeepsExactlyTheValueItHad() {
        var bounty = boost(BoostMechanism.CARD_MONEY_ON_QUEST, 250.0);
        assertEquals(250.0, RANGE.valueOf(bounty, null), 1e-9);
        assertEquals(50, RANGE.unrolled());
    }

    @Test
    void aMultiplierRollsOnlyItsGainSoAWeakRollIsNeverAPenalty() {
        var xp = boost(BoostMechanism.CARD_XP_MULTIPLIER, 1.25);
        assertEquals(1.1875, RANGE.valueOf(xp, 0), 1e-9);
        assertEquals(1.3125, RANGE.valueOf(xp, 100), 1e-9);
        var wide = new BonusStrength(0.0, 2.0);
        assertEquals(1.0, wide.valueOf(xp, 0), 1e-9,
            "the weakest roll of a multiplier is no change, not less than none");
    }

    @Test
    void aPermissionHasNoMagnitudeToRoll() {
        assertFalse(BonusStrength.rolls(BoostMechanism.CARD_PERMISSION));
        assertTrue(BonusStrength.rolls(BoostMechanism.ATTRIBUTE));
        assertTrue(BonusStrength.rolls(BoostMechanism.CARD_SCALE));
        var token = boost(BoostMechanism.CARD_PERMISSION, 1.0);
        assertEquals(1.0, RANGE.valueOf(token, 0), 1e-9);
    }

    @Test
    void playerScaleRollsTheWholeSizeFromPointSixToTwo() {
        var scale = boost(BoostMechanism.CARD_SCALE, 1.0);
        assertEquals(0.6, RANGE.valueOf(scale, 0), 1e-9);
        assertEquals(1.3, RANGE.valueOf(scale, 50), 1e-9);
        assertEquals(2.0, RANGE.valueOf(scale, 100), 1e-9);
    }

    @Test
    void aScaleBonusFromBeforeStrengthsKeepsTheDoubleSizeItGave() {
        // It was applied as +amount on the player's own size: 1 + 1.0 = x2.0.
        var scale = boost(BoostMechanism.CARD_SCALE, 1.0);
        assertEquals(100, RANGE.resolve(scale, null));
        assertEquals(2.0, RANGE.valueOf(scale, null), 1e-9);
    }

    @Test
    void theScaleRangeComesFromTheBoostsOwnMinimumAndMaximum() {
        var scale = new BoostDefinition("scale", "Scale", BoostMechanism.CARD_SCALE, 1.0, 0.0,
            false, Map.of("minimum", "0.8", "maximum", "1.6"));
        assertEquals(0.8, RANGE.valueOf(scale, 0), 1e-9);
        assertEquals(1.6, RANGE.valueOf(scale, 100), 1e-9);
    }

    @Test
    void everyStrengthFromZeroToOneHundredCanRoll() {
        var random = new Random(7);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 20_000; i++) {
            int strength = RANGE.roll(random);
            assertTrue(strength >= BonusStrength.MIN && strength <= BonusStrength.MAX);
            sawMin |= strength == BonusStrength.MIN;
            sawMax |= strength == BonusStrength.MAX;
        }
        assertTrue(sawMin && sawMax, "both ends of the bar must be reachable");
    }

    @Test
    void aStoredStrengthOutsideTheScaleIsClampedRatherThanTrusted() {
        var bounty = boost(BoostMechanism.CARD_MONEY_ON_QUEST, 250.0);
        assertEquals(312.5, RANGE.valueOf(bounty, 900), 1e-9);
        assertEquals(187.5, RANGE.valueOf(bounty, -4), 1e-9);
    }
}
