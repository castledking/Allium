package codes.castled.allium.teleport;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeleportSettingsTest {

    @Test
    void defaultsAreConservativeAndDoNotLoadChunks() {
        TeleportSettings settings = TeleportSettings.read(new MemoryConfiguration());
        assertEquals(3000, settings.warmupMillis());
        assertEquals(60000, settings.requestExpiryMillis());
        assertEquals(0.1, settings.movementTolerance());
        assertTrue(settings.safety());
        assertTrue(settings.adjust());
        assertTrue(settings.lava());
        assertTrue(settings.voids());
        assertTrue(settings.suffocation());
        assertTrue(settings.falls());
        assertFalse(settings.loadChunk());
        assertFalse(settings.deathBack());
        assertEquals(3, settings.radius());
        assertEquals(4, settings.vertical());
        assertEquals(0, settings.cooldown("spawn"));
    }

    @Test
    void capturedSettingsSurviveReloadAndDefensivelyCopyTheCooldownMap() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("teleport.warmup-seconds", 1.25);
        config.set("teleport.request-expiry-seconds", 2.5);
        config.set("teleport.movement-tolerance", 0.25);
        config.set("teleport.safety.avoid-falls", false);
        config.set("teleport.safety.avoid-void", true);
        config.set("teleport.safety.load-destination-chunk", true);
        config.set("teleport.back.include-death", true);
        config.set("teleport.cooldowns.spawn", 4.5);
        config.set("teleport.cooldowns.back", 0);
        TeleportSettings captured = TeleportSettings.read(config);
        config.set("teleport.warmup-seconds", 10);
        config.set("teleport.safety.avoid-void", false);
        config.set("teleport.cooldowns.spawn", 20);
        assertEquals(1250, captured.warmupMillis());
        assertEquals(2500, captured.requestExpiryMillis());
        assertEquals(0.25, captured.movementTolerance());
        assertFalse(captured.falls());
        assertTrue(captured.voids());
        assertTrue(captured.loadChunk());
        assertTrue(captured.deathBack());
        assertEquals(Map.of("spawn", 4500L, "back", 0L), captured.cooldowns());
        assertThrows(UnsupportedOperationException.class, () -> captured.cooldowns().put("spawn", 1L));
        Map<String, Long> input = new HashMap<>(Map.of("spawn", 100L));
        TeleportSettings direct = direct(0, 0, 100, 0, 0, input);
        input.put("spawn", 900L);
        assertEquals(100, direct.cooldown("spawn"));
    }

    @Test
    void invalidNumericConfigCannotBeSilentlyDefaultedOrTruncated() {
        Map<String, Object[]> invalid = Map.of(
                "warmup-seconds", new Object[]{-1, 301, Double.NaN, Double.POSITIVE_INFINITY, "later"},
                "request-expiry-seconds", new Object[]{0, 0.099, 3601, Double.NaN, Double.NEGATIVE_INFINITY, "soon"},
                "movement-tolerance", new Object[]{-0.1, 5.1, Double.NaN, Double.POSITIVE_INFINITY, "small"},
                "safety.search-radius", new Object[]{-1, 9, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, "near"},
                "safety.vertical-search", new Object[]{-1, 17, 2.5, Double.NaN, Double.NEGATIVE_INFINITY, Long.MAX_VALUE, "up"},
                "cooldowns.spawn", new Object[]{-1, 86401, Double.NaN, Double.POSITIVE_INFINITY, "short"});
        invalid.forEach((path, values) -> {
            for (Object value : values) {
                MemoryConfiguration config = new MemoryConfiguration();
                config.set("teleport." + path, value);
                assertThrows(IllegalArgumentException.class, () -> TeleportSettings.read(config), path + "=" + value);
            }
        });
    }

    @Test
    void malformedSectionsBooleansAndCommandKeysAreRejected() {
        for (String path : new String[]{"teleport", "teleport.cooldowns"}) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set(path, "wrong type");
            assertThrows(IllegalArgumentException.class, () -> TeleportSettings.read(config), path);
        }
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("teleport.safety.avoid-falls", "false");
        assertThrows(IllegalArgumentException.class, () -> TeleportSettings.read(config));
        for (String command : new String[]{"Spawn", "/spawn", "tpa1", "tp-here"}) {
            MemoryConfiguration commandConfig = new MemoryConfiguration();
            commandConfig.set("teleport.cooldowns." + command, 1);
            assertThrows(IllegalArgumentException.class, () -> TeleportSettings.read(commandConfig), command);
        }
    }

    @Test
    void publicRecordConstructorEnforcesTheSameBoundsAsConfig() {
        assertThrows(IllegalArgumentException.class, () -> direct(-1, 0, 100, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(300001, 0, 100, 0, 0, Map.of()));
        for (double tolerance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 5.1}) {
            assertThrows(IllegalArgumentException.class, () -> direct(0, tolerance, 100, 0, 0, Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 99, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 3600001, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, -1, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 9, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 0, -1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 0, 17, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 0, 0, Map.of("Spawn", 1L)));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 0, 0, Map.of("spawn", -1L)));
        assertThrows(IllegalArgumentException.class, () -> direct(0, 0, 100, 0, 0, Map.of("spawn", 86400001L)));
        assertThrows(NullPointerException.class, () -> direct(0, 0, 100, 0, 0, null));
    }

    @Test
    void documentedMaximumsAndZeroCooldownsRemainUsable() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("teleport.warmup-seconds", 300);
        config.set("teleport.request-expiry-seconds", 3600);
        config.set("teleport.movement-tolerance", 5);
        config.set("teleport.safety.search-radius", 8);
        config.set("teleport.safety.vertical-search", 16);
        config.set("teleport.cooldowns.spawn", 86400);
        config.set("teleport.cooldowns.back", 0);
        TeleportSettings settings = TeleportSettings.read(config);
        assertEquals(300000, settings.warmupMillis());
        assertEquals(3600000, settings.requestExpiryMillis());
        assertEquals(8, settings.radius());
        assertEquals(16, settings.vertical());
        assertEquals(86400000, settings.cooldown("spawn"));
        assertEquals(0, settings.cooldown("back"));
    }

    private static TeleportSettings direct(long warmup, double movement, long expiry, int radius, int vertical,
                                           Map<String, Long> cooldowns) {
        return new TeleportSettings(warmup, movement, expiry, false, true, true, radius, vertical,
                true, true, true, true, false, cooldowns);
    }
}
