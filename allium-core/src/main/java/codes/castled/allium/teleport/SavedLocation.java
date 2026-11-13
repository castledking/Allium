package codes.castled.allium.teleport;

import java.util.Objects;
import java.util.UUID;

/** A platform-neutral location. The adapter resolves its world before teleporting. */
public record SavedLocation(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {

    public SavedLocation {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("location coordinates and angles must be finite");
        }
    }
}
