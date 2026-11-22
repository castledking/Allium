package codes.castled.curios;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    @Test
    void awardsAcrossMultipleLevelsAndKeepsRemainingXp() {
        assertEquals(new Progression(2, 20), new Progression(0, 0).award(320, 100, 100));
    }

    @Test
    void mergingUsesTheDonorsEarnedProgressWithoutExceedingTheCap() {
        assertEquals(new Progression(3, 0), new Progression(2, 150)
                .merge(new Progression(2, 200), 3, 100));
    }

    @Test
    void migratedOverCapLevelIsRetainedAndCannotAdvance() {
        var imported = new Progression(155, 60);
        assertEquals(imported, imported.award(10, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> imported.award(Double.NaN, 100, 100));
    }
}
