package codes.castled.allium.tfly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TFlyFormatTest {

    @Test
    void shortKeepsEveryUnitByDefault() {
        assertEquals("1d 12h 30m 45s", TFlyManager.formatTime(131445));
    }

    @Test
    void shortKeepsAtMostTwoUnits() {
        assertEquals("1d 12h", TFlyManager.formatTimeShort(131400));
        assertEquals("10d 10h", TFlyManager.formatTimeShort(900000));
        assertEquals("23h 59m", TFlyManager.formatTimeShort(86399));
    }

    @Test
    void shortKeepsFewerThanTwoUnitsWhenThatIsAllThereIs() {
        assertEquals("1h", TFlyManager.formatTimeShort(3600));
        assertEquals("10m", TFlyManager.formatTimeShort(600));
        assertEquals("1m 30s", TFlyManager.formatTimeShort(90));
        assertEquals("30s", TFlyManager.formatTimeShort(30));
    }

    @Test
    void shortFallsBackToZeroSeconds() {
        assertEquals("0s", TFlyManager.formatTimeShort(0));
        assertEquals("0s", TFlyManager.formatTimeShort(-5));
    }
}