package codes.castled.allium.travel;

import java.util.Collection;
import java.util.Objects;

/** Permission adapters supply granted limits; multiple grants select their maximum, never a sum. */
public final class HomePolicy {
    public static final int HARD_CAP = 1000;

    private HomePolicy() {
    }

    public static int maximum(int baseLimit, Collection<Integer> grantedLimits, int staffOverride) {
        Objects.requireNonNull(grantedLimits, "grantedLimits");
        int maximum = Math.max(baseLimit, staffOverride);
        for (Integer granted : grantedLimits) {
            maximum = Math.max(maximum, Objects.requireNonNull(granted, "granted limit"));
        }
        return bounded(maximum);
    }

    /** A staff bypass permits up to the durable hard cap, including when the base limit is zero. */
    public static int maximum(int baseLimit, Collection<Integer> grantedLimits, boolean staffBypass) {
        return maximum(baseLimit, grantedLimits, staffBypass ? HARD_CAP : 0);
    }

    public static int bounded(int limit) {
        return Math.max(0, Math.min(HARD_CAP, limit));
    }
}
