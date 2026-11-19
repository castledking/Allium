package codes.castled.allium.placeholder;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderFormatsTest {

    @Test
    void amountsMatchLegacyAlliumsFormats() {
        BigDecimal amount = new BigDecimal("12345.678");
        assertEquals("12345.68", PlaceholderFormats.plain(amount));
        assertEquals("10000000.00", PlaceholderFormats.plain(new BigDecimal("1E7")), "never an exponent");
        assertEquals("12345", PlaceholderFormats.fixed(amount));
        assertEquals("12,345.68", PlaceholderFormats.commas(amount));
        assertEquals("12.3k", PlaceholderFormats.shortened(amount));
        assertEquals("1.5M", PlaceholderFormats.shortened(new BigDecimal("1500000")));
        assertEquals("2.3B", PlaceholderFormats.shortened(new BigDecimal("2300000000")));
        assertEquals("999", PlaceholderFormats.shortened(new BigDecimal("999.99")));
    }

    @Test
    void ranksTakeEnglishOrdinals() {
        assertEquals("1st", PlaceholderFormats.ordinal(1));
        assertEquals("2nd", PlaceholderFormats.ordinal(2));
        assertEquals("3rd", PlaceholderFormats.ordinal(3));
        assertEquals("11th", PlaceholderFormats.ordinal(11));
        assertEquals("13th", PlaceholderFormats.ordinal(13));
        assertEquals("22nd", PlaceholderFormats.ordinal(22));
        assertEquals("112th", PlaceholderFormats.ordinal(112));
    }

    @Test
    void theWorldClockStartsAtSixInTheMorningAndTheDateCountsDays() {
        assertEquals("6:00 AM", PlaceholderFormats.clock12(0));
        assertEquals("12:00 PM", PlaceholderFormats.clock12(6_000));
        assertEquals("12:00 AM", PlaceholderFormats.clock12(18_000));
        assertEquals("06:30", PlaceholderFormats.clock24(500));
        assertEquals("18:00", PlaceholderFormats.clock24(12_000));
        assertEquals("Jan 1, 0", PlaceholderFormats.date(0));
        assertEquals("Feb 1, 0", PlaceholderFormats.date(31L * 24_000));
        assertEquals("Jan 1, 1", PlaceholderFormats.date(365L * 24_000));
    }

    @Test
    void durationsLeaveOutZeroParts() {
        assertEquals("0s", PlaceholderFormats.duration(0));
        assertEquals("45s", PlaceholderFormats.duration(45));
        assertEquals("10m 30s", PlaceholderFormats.duration(630));
        assertEquals("1h", PlaceholderFormats.duration(3_600));
        assertEquals("1d 2h 30m 45s", PlaceholderFormats.duration(86_400 + 7_200 + 1_800 + 45));
    }
}
