package codes.castled.allium.item.delivery;

import java.util.List;
import java.util.Objects;

/**
 * What a delivery did.
 *
 * @param requested items requested (sum of stack amounts)
 * @param given     items placed in the target's inventory or equipment
 * @param dropped   items dropped at the target's feet ({@link OverflowPolicy#DROP})
 * @param leftover  items neither given nor dropped
 * @param warnings  non-fatal problems, for example an unresolved skull profile
 */
public record DeliveryOutcome(Status status, int requested, int given, int dropped, int leftover, List<String> warnings) {

    public enum Status {
        /** Everything was given (some may have been dropped under {@link OverflowPolicy#DROP}). */
        DELIVERED,
        /**
         * Everything was given, but at least one item fell back (a player head without its skin
         * because the profile lookup timed out, failed or was rate-limited).
         */
        DEGRADED,
        /** {@link OverflowPolicy#PARTIAL}: what fit was given, the rest was not. */
        PARTIAL,
        /** {@link OverflowPolicy#REFUSE}: it did not all fit, so nothing was given. */
        REFUSED_FULL,
        /** {@link SkullFallback#REFUSE}: a skull's skin could not be resolved, so nothing was given. */
        REFUSED_UNRESOLVED,
        /** The target left before the items were ready; nothing was given. */
        TARGET_OFFLINE,
        /** A listener cancelled {@code ItemGiveEvent}; nothing was given. */
        CANCELLED_BY_EVENT,
        /** Preparation failed; nothing was given. */
        FAILED
    }

    public DeliveryOutcome {
        Objects.requireNonNull(status, "status");
        warnings = List.copyOf(warnings);
    }

    public static DeliveryOutcome nothing(Status status, int requested, List<String> warnings) {
        return new DeliveryOutcome(status, requested, 0, 0, requested, warnings);
    }
}
