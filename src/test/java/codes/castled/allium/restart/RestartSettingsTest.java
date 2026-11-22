package codes.castled.allium.restart;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestartSettingsTest {
    @Test
    void readsLegacyClockTimesAndSchedulesNextDayInConfiguredZone() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("timezone", "America/Los_Angeles");
        config.set("times", List.of("04:00", "20:00"));
        var settings = RestartSettings.read(config);
        assertEquals(Instant.parse("2026-11-22T12:00:00Z"), settings.next(Instant.parse("2026-11-22T06:00:00Z")));
        assertEquals(Instant.parse("2026-11-23T04:00:00Z"), settings.next(Instant.parse("2026-11-22T12:00:00Z")));
        config.set("enabled", false);
        assertNull(RestartSettings.read(config).next(Instant.EPOCH));
    }

    @Test
    void parsesLegacyDelaysAndRejectsMalformedOrOverflowingInputs() {
        assertEquals(90, AutoRestart.delaySeconds("90"));
        assertEquals(300, AutoRestart.delaySeconds("5m"));
        assertEquals(5400, AutoRestart.delaySeconds("1:30"));
        assertThrows(IllegalArgumentException.class, () -> AutoRestart.delaySeconds("24:00"));
        assertThrows(IllegalArgumentException.class, () -> AutoRestart.delaySeconds("123abc"));
        assertThrows(IllegalArgumentException.class, () -> AutoRestart.delaySeconds("9223372036854775807d"));
    }
}
