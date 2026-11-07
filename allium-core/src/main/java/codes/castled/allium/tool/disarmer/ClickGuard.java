package codes.castled.allium.tool.disarmer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Admits at most one committing attempt per player within a short window, so a burst of
 * duplicate clicks cannot spend more than one charge. A window of {@code 0} disables it.
 */
public final class ClickGuard {

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Map<UUID, Long> lastAccepted = new ConcurrentHashMap<>();

    public ClickGuard(long windowMillis, LongSupplier nanoClock) {
        if (windowMillis < 0L) {
            throw new IllegalArgumentException("windowMillis must be >= 0; got " + windowMillis);
        }
        this.windowNanos = windowMillis * 1_000_000L;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public boolean tryAcquire(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        long now = nanoClock.getAsLong();
        if (windowNanos == 0L) {
            return true;
        }
        Long previous = lastAccepted.get(playerId);
        if (previous != null && now - previous < windowNanos) {
            return false;
        }
        lastAccepted.put(playerId, now);
        return true;
    }

    public void forget(UUID playerId) {
        lastAccepted.remove(playerId);
    }
}
