package codes.castled.allium.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SavedLocationTest {

    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void preservesWorldIdentityCoordinatesAndUnwrappedAngles() {
        SavedLocation location = new SavedLocation(WORLD, "world_雪", -30_000_000.25, -64.5, 0.125, 450.5f, -100.25f);
        assertEquals(WORLD, location.worldId());
        assertEquals("world_雪", location.worldName());
        assertEquals(-30_000_000.25, location.x());
        assertEquals(-64.5, location.y());
        assertEquals(0.125, location.z());
        assertEquals(450.5f, location.yaw());
        assertEquals(-100.25f, location.pitch());
    }

    @Test
    void requiresWorldIdentityAndName() {
        assertThrows(NullPointerException.class, () -> new SavedLocation(null, "world", 0, 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new SavedLocation(WORLD, null, 0, 0, 0, 0, 0));
    }

    @Test
    void rejectsEveryNonfiniteCoordinateAndAngle() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new SavedLocation(WORLD, "world", value, 0, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new SavedLocation(WORLD, "world", 0, value, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new SavedLocation(WORLD, "world", 0, 0, value, 0, 0));
        }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new SavedLocation(WORLD, "world", 0, 0, 0, value, 0));
            assertThrows(IllegalArgumentException.class, () -> new SavedLocation(WORLD, "world", 0, 0, 0, 0, value));
        }
    }
}
