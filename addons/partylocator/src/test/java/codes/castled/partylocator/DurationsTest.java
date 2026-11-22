package codes.castled.partylocator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DurationsTest {
    @Test
    void legacyFormatsParseToSeconds() {
        assertEquals(30, Durations.parse("30"));
        assertEquals(60, Durations.parse("1m"));
        assertEquals(7_200, Durations.parse("2H"));
        assertEquals(86_400, Durations.parse("1d"));
        for (String bad : new String[]{"", "0", "-5", "1w", "m", "99999999999999999999d"}) assertEquals(-1, Durations.parse(bad), bad);
    }

    @Test
    void durationsReadInTheLargestWholeUnit() {
        assertEquals("1 second", Durations.format(1));
        assertEquals("5 minutes", Durations.format(300));
        assertEquals("2 hours", Durations.format(7_200));
        assertEquals("1 day", Durations.format(86_400));
    }
}
