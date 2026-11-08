package codes.castled.allium.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiRangeTest {

    private static ApiVersion v(String s) {
        return ApiVersion.parse(s);
    }

    @Test
    void versionsParseAndOrderSemantically() {
        assertEquals(new ApiVersion(1, 2, 0), v("1.2"));
        assertEquals(new ApiVersion(1, 2, 3), v(" 1.2.3 "));
        assertTrue(v("1.10.0").compareTo(v("1.9.9")) > 0);
        assertEquals("1.0.0", v("1.0").toString());
        assertThrows(IllegalArgumentException.class, () -> v("1"));
        assertThrows(IllegalArgumentException.class, () -> v("1.x"));
        assertThrows(IllegalArgumentException.class, () -> v("v1.0"));
    }

    @Test
    void caretRangesAcceptLaterMinorsOfTheSameMajor() {
        ApiRange range = ApiRange.parse("^1.0");
        assertTrue(range.contains(v("1.0.0")));
        assertTrue(range.contains(v("1.7.2")));
        assertFalse(range.contains(v("2.0.0")));
        assertFalse(range.contains(v("0.9.0")));
        assertEquals(">=1.0.0 <2.0.0", range.describe());
        assertEquals(ApiRange.parse("1.0").describe(), range.describe(), "a bare version means the same as ^");
        assertFalse(ApiRange.parse("^1.1").contains(v("1.0.0")), "an addon needing 1.1 does not run on 1.0");
        assertFalse(ApiRange.parse("^2.0").contains(v("1.0.0")));
        assertTrue(ApiRange.parse("^1.0.3").contains(v("1.0.3")));
        assertFalse(ApiRange.parse("^1.0.3").contains(v("1.0.2")));
    }

    @Test
    void majorZeroFixesTheMinor() {
        ApiRange range = ApiRange.parse("^0.3");
        assertTrue(range.contains(v("0.3.9")));
        assertFalse(range.contains(v("0.4.0")));
    }

    @Test
    void explicitRangesIncludeTheLowerAndExcludeTheUpperBound() {
        ApiRange range = ApiRange.parse(">=1.2 <3.0");
        assertTrue(range.contains(v("1.2.0")));
        assertTrue(range.contains(v("2.9.9")));
        assertFalse(range.contains(v("3.0.0")));
        assertFalse(range.contains(v("1.1.9")));
        assertTrue(ApiRange.parse(">=1.0").contains(v("9.0.0")), "no upper bound when none is given");
        assertThrows(IllegalArgumentException.class, () -> ApiRange.parse(">=2.0 <1.0"));
        assertThrows(IllegalArgumentException.class, () -> ApiRange.parse(">=1.0 <=2.0"));
        assertThrows(IllegalArgumentException.class, () -> ApiRange.parse("latest"));
    }
}
