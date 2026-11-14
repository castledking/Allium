package codes.castled.allium.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /reply} targets: each participant's target is the last person they messaged or who
 * messaged them, whichever was most recent. Only delivered messages count. When a participant goes
 * offline, their own target and every target pointing at them are cleared. The console takes part
 * as {@link #CONSOLE}.
 */
public final class ReplyTargets {

    public static final UUID CONSOLE = new UUID(0L, 0L);

    private final Map<UUID, UUID> targets = new ConcurrentHashMap<>();

    /** A message from {@code from} to {@code to} was delivered. */
    public void delivered(UUID from, UUID to) {
        targets.put(from, to);
        targets.put(to, from);
    }

    public Optional<UUID> target(UUID participant) {
        return Optional.ofNullable(targets.get(participant));
    }

    /** Clears only {@code participant}'s own saved reply target. */
    public void clear(UUID participant) {
        targets.remove(participant);
    }

    /** {@code participant} went offline. */
    public void forget(UUID participant) {
        targets.remove(participant);
        targets.values().removeIf(participant::equals);
    }
}
