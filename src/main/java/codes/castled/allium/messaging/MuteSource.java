package codes.castled.allium.messaging;

import codes.castled.allium.moderation.MuteRecord;

import java.util.Optional;
import java.util.UUID;

/** Whether moderation has muted a player. Moderation plugs in here; the default mutes no one. */
@FunctionalInterface
public interface MuteSource {

    MuteSource NONE = player -> false;

    boolean isMuted(UUID player);

    /**
     * Whether {@code player}'s moderation mute state has loaded for the current session. Defaults to
     * {@code true} for no-op sources; real moderation implementations return {@code false} while
     * loading or after a load failure so speech fails closed.
     */
    default boolean isMuteStateReady(UUID player) {
        return true;
    }

    /** Returns the active mute record (including reason and remaining duration), if available. */
    default Optional<MuteRecord> activeMute(UUID player) {
        return Optional.empty();
    }

    /** Current epoch milliseconds from the moderation clock (or system clock by default). */
    default long nowMillis() {
        return System.currentTimeMillis();
    }
}
