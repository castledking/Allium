package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.soil.SoilState;
import codes.castled.allium.harvest.sprinkler.SprinklerDefinition;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Soil wear arithmetic and sprinkler coverage geometry. */
class SoilStateTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static SoilState soil(long establishedAt, long exhaustedAt) {
        return new SoilState(
            new BlockPositionKey(UUID.randomUUID(), 0, 64, 0),
            establishedAt, exhaustedAt, null);
    }

    // ==================== wear ====================

    @Test
    void soilIsHealthyUntilItsExpiryPasses() {
        SoilState state = soil(0, 7 * DAY);
        assertFalse(state.isExhausted(0));
        assertFalse(state.isExhausted(7 * DAY - 1));
        assertTrue(state.isExhausted(7 * DAY), "expiry is inclusive");
        assertTrue(state.isExhausted(30 * DAY));
    }

    @Test
    void zeroExpiryMeansSoilNeverWearsOut() {
        SoilState eternal = soil(0, 0);
        assertFalse(eternal.isExhausted(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, eternal.remainingMillis(1000));
        assertEquals(eternal, eternal.extendedBy(5 * DAY, 0),
            "extending unlimited soil is a no-op");
    }

    @Test
    void remainingTimeNeverGoesNegative() {
        SoilState state = soil(0, 2 * DAY);
        assertEquals(2 * DAY, state.remainingMillis(0));
        assertEquals(DAY, state.remainingMillis(DAY));
        assertEquals(0, state.remainingMillis(10 * DAY));
    }

    // ==================== retainers ====================

    @Test
    void feedingHealthySoilBanksTheTime() {
        // 5 days left, +7 days granted -> 12 days left, nothing wasted.
        SoilState state = soil(0, 7 * DAY).extendedBy(7 * DAY, 2 * DAY);
        assertEquals(14 * DAY, state.exhaustedAt());
        assertEquals(12 * DAY, state.remainingMillis(2 * DAY));
    }

    @Test
    void feedingDeadSoilRevivesItForTheFullDuration() {
        // Expired 3 days ago; a 7-day retainer must grant a full 7 days from
        // now, not 4 days of leftovers.
        SoilState revived = soil(0, 7 * DAY).extendedBy(7 * DAY, 10 * DAY);
        assertFalse(revived.isExhausted(10 * DAY));
        assertEquals(7 * DAY, revived.remainingMillis(10 * DAY));
    }

    @Test
    void extendingByNothingChangesNothing() {
        SoilState state = soil(0, 7 * DAY);
        assertEquals(state, state.extendedBy(0, DAY));
        assertEquals(state, state.extendedBy(-5 * DAY, DAY));
    }

    // ==================== fertilizer slot ====================

    @Test
    void fertilizerCanBeWorkedInAndCleared() {
        SoilState state = soil(0, 7 * DAY);
        assertNull(state.fertilizerId());
        SoilState fertilized = state.withFertilizer("variation_fertilizer_3");
        assertEquals("variation_fertilizer_3", fertilized.fertilizerId());
        assertEquals(state.exhaustedAt(), fertilized.exhaustedAt(),
            "working in a fertilizer must not silently change soil life");
        assertNull(fertilized.withFertilizer(null).fertilizerId());
    }

    // ==================== sprinkler coverage ====================

    private static SprinklerDefinition sprinkler(int radius, double speed) {
        ItemRef item = ItemRef.parse("minecraft:stone");
        return new SprinklerDefinition(
            "test", item, "Test", radius, speed, true, 0L, item);
    }

    @Test
    void coverageIsASquareRadius() {
        SprinklerDefinition radiusOne = sprinkler(1, 0.9D);
        assertTrue(radiusOne.covers(0, 0));
        assertTrue(radiusOne.covers(1, 1), "corners of the 3x3 are covered");
        assertTrue(radiusOne.covers(-1, 1));
        assertFalse(radiusOne.covers(2, 0));
        assertFalse(radiusOne.covers(0, -2));
    }

    @Test
    void largerTiersCoverMore() {
        assertTrue(sprinkler(3, 0.65D).covers(3, -3), "7x7 reaches its corner");
        assertFalse(sprinkler(3, 0.65D).covers(4, 0));
        assertTrue(sprinkler(0, 0.9D).covers(0, 0), "radius 0 covers only itself");
        assertFalse(sprinkler(0, 0.9D).covers(1, 0));
    }

    @Test
    void invalidSprinklerValuesAreNormalised() {
        SprinklerDefinition normalised = sprinkler(-4, -1.0D);
        assertEquals(0, normalised.radius());
        assertEquals(1.0D, normalised.speedMultiplier(), 1e-9,
            "a non-positive multiplier means unmodified growth, not instant crops");
    }
}
