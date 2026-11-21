package codes.castled.allium.essentials;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTimeParserTest {

    @Test
    void parsesNamedClockTwelveHourAndTicksSuffixFormats() {
        assertEquals(OptionalLong.of(1000L), WorldTimeParser.parseTimeTicks("day"));
        assertEquals(OptionalLong.of(13000L), WorldTimeParser.parseTimeTicks("night"));
        assertEquals(OptionalLong.of(23000L), WorldTimeParser.parseTimeTicks("dawn"));
        assertEquals(OptionalLong.of(11500L), WorldTimeParser.parseTimeTicks("17:30"));
        assertEquals(OptionalLong.of(10000L), WorldTimeParser.parseTimeTicks("4pm"));
        assertEquals(OptionalLong.of(10500L), WorldTimeParser.parseTimeTicks("4:30pm"));
        assertEquals(OptionalLong.of(0L), WorldTimeParser.parseTimeTicks("6am"));
        assertEquals(OptionalLong.of(6000L), WorldTimeParser.parseTimeTicks("12pm"));
        assertEquals(OptionalLong.of(18000L), WorldTimeParser.parseTimeTicks("12am"));
        assertEquals(OptionalLong.of(18500L), WorldTimeParser.parseTimeTicks("12:30am"));
        assertEquals(OptionalLong.of(4000L), WorldTimeParser.parseTimeTicks("4000ticks"));
        assertEquals(OptionalLong.of(4000L), WorldTimeParser.parseTimeTicks("4000tick"));
        assertEquals(OptionalLong.of(4000L), WorldTimeParser.parseTimeTicks("4000t"));
        assertEquals(OptionalLong.of(4000L), WorldTimeParser.parseTimeTicks("4000"));
        assertEquals(OptionalLong.of(4000L), WorldTimeParser.parseAddTicks("4000ticks"));
    }

    @Test
    void rejectsInvalidTwelveHourAndTickInputs() {
        assertTrue(WorldTimeParser.parseTimeTicks("0pm").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("13pm").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("4:60pm").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("am").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("ticks").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("-100").isEmpty());
    }
}
