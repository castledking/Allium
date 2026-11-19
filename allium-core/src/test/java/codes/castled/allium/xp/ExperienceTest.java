package codes.castled.allium.xp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceTest {

    @Test
    void totalsMatchTheVanillaCurveAtEveryBoundary() {
        long total = 0;
        for (int level = 0; level <= 200; level++) {
            assertEquals(total, Experience.totalForLevel(level), "level " + level);
            total += Experience.pointsToNextLevel(level);
        }
        assertEquals(352, Experience.totalForLevel(16));
        assertEquals(1395, Experience.totalForLevel(30));
        assertEquals(7, Experience.pointsToNextLevel(0));
        assertEquals(37, Experience.pointsToNextLevel(15));
        assertEquals(112, Experience.pointsToNextLevel(30));
        assertTrue(Experience.MAX_POINTS <= Integer.MAX_VALUE);
        assertTrue(Experience.totalForLevel(Experience.MAX_LEVEL + 1) > Integer.MAX_VALUE);
    }

    @Test
    void stateRoundTripsBetweenTotalsAndTheBar() {
        Experience.State state = Experience.fromTotal(400);
        assertEquals(17, state.level());
        assertEquals(6, state.points());
        assertEquals(400, state.total());
        assertEquals(state, Experience.fromBar(state.level(), state.progress()));
        assertEquals(new Experience.State(0, 0), Experience.fromTotal(-5));
        assertEquals(Experience.MAX_LEVEL, Experience.fromTotal(Long.MAX_VALUE).level());
        assertEquals(new Experience.State(3, 0), Experience.fromBar(3, Float.NaN));
    }

    @Test
    void pointsAndLevelsParseWithAnOptionalUnitWord() {
        assertEquals(new Experience.Amount(250, false), Experience.parse("250", null).orElseThrow());
        assertEquals(new Experience.Amount(5, true), Experience.parse("5L", null).orElseThrow());
        assertEquals(new Experience.Amount(-3, true), Experience.parse("-3l", null).orElseThrow());
        assertEquals(new Experience.Amount(5, true), Experience.parse("5", "levels").orElseThrow());
        assertEquals(new Experience.Amount(5, false), Experience.parse("5", "points").orElseThrow());
        assertTrue(Experience.parse("5L", "points").isEmpty());
        assertTrue(Experience.parse("5", "bananas").isEmpty());
        assertTrue(Experience.parse("five", null).isEmpty());
        assertTrue(Experience.parse("99999999999", null).isEmpty());
        assertTrue(Experience.parse("1.5", null).isEmpty());
    }

    @Test
    void levelsKeepTheBarAndPointsCarryAcrossLevels() {
        Experience.State half = Experience.fromBar(10, 0.5f);
        Experience.State more = Experience.add(half, new Experience.Amount(5, true));
        assertEquals(15, more.level());
        assertEquals(0.5f, more.progress(), 0.05f);

        Experience.State taken = Experience.add(Experience.fromTotal(10), new Experience.Amount(-100, false));
        assertEquals(new Experience.State(0, 0), taken);
        assertEquals(1, Experience.add(Experience.fromTotal(0), new Experience.Amount(7, false)).level());
        assertEquals(new Experience.State(0, 0), Experience.set(half, new Experience.Amount(0, false)));
        assertEquals(30, Experience.set(half, new Experience.Amount(30, true)).level());
        assertEquals(Experience.MAX_LEVEL, Experience.add(half, new Experience.Amount(Integer.MAX_VALUE, true)).level());
    }
}
