package codes.castled.allium.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fault points for live validation harnesses on disposable test servers.
 *
 * <p>Inert unless the JVM is started with {@code -Dallium.fault-injection=true}. No config
 * value, command or permission can turn it on, and when it is off {@link #trip} is a constant
 * {@code false}. When it is on, Allium logs a warning at startup, and a harness can arm a point
 * to fail its next passes.
 */
public final class FaultInjection {

    public static final String PROPERTY = "allium.fault-injection";

    /** {@code ItemService#updateState} rejects without writing, exactly like a failed verification. */
    public static final String ITEM_STATE_WRITE = "item.state-write";

    /** A skull profile lookup answers after {@code SkullProfiles#SLOW_FAULT_MILLIS} with a name-only profile. */
    public static final String SKULL_LOOKUP_SLOW = "skull.lookup-slow";

    /** A skull profile lookup never answers, so the lookup timeout fires. */
    public static final String SKULL_LOOKUP_HANG = "skull.lookup-hang";

    /** A preference load waits {@code PreferencesModule#SLOW_LOAD_FAULT_MILLIS} before reading. */
    public static final String PREFERENCES_LOAD_SLOW = "preferences.load-slow";

    /** A preference load fails as if the database were unreachable. */
    public static final String PREFERENCES_LOAD_FAIL = "preferences.load-fail";

    private static final FaultInjection GLOBAL = new FaultInjection(Boolean.getBoolean(PROPERTY));

    private final boolean enabled;
    private final Map<String, AtomicInteger> armed = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tripped = new ConcurrentHashMap<>();

    FaultInjection(boolean enabled) {
        this.enabled = enabled;
    }

    public static boolean enabled() {
        return GLOBAL.isEnabled();
    }

    /**
     * Makes the next {@code count} passes through {@code point} fail and resets its trip count.
     *
     * @throws IllegalStateException when fault injection is off
     */
    public static void arm(String point, int count) {
        GLOBAL.armPoint(point, count);
    }

    /** Clears every armed point and trip count. */
    public static void reset() {
        GLOBAL.resetAll();
    }

    /** How many passes through {@code point} failed since it was last armed. */
    public static int tripped(String point) {
        return GLOBAL.trippedCount(point);
    }

    /** Remaining armed failures for {@code point}. */
    public static int armed(String point) {
        return GLOBAL.armedCount(point);
    }

    /** Called at a fault point; {@code true} means the caller must fail as if rejected. */
    public static boolean trip(String point) {
        return GLOBAL.tripPoint(point);
    }

    boolean isEnabled() {
        return enabled;
    }

    void armPoint(String point, int count) {
        Objects.requireNonNull(point, "point");
        if (!enabled) {
            throw new IllegalStateException("fault injection is off; start the JVM with -D" + PROPERTY + "=true");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be 0 or more");
        }
        armed.put(point, new AtomicInteger(count));
        tripped.put(point, new AtomicInteger());
    }

    void resetAll() {
        armed.clear();
        tripped.clear();
    }

    int trippedCount(String point) {
        AtomicInteger count = tripped.get(point);
        return count == null ? 0 : count.get();
    }

    int armedCount(String point) {
        AtomicInteger count = armed.get(point);
        return count == null ? 0 : count.get();
    }

    boolean tripPoint(String point) {
        if (!enabled) {
            return false;
        }
        AtomicInteger remaining = armed.get(point);
        if (remaining == null || remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) <= 0) {
            return false;
        }
        tripped.computeIfAbsent(point, p -> new AtomicInteger()).incrementAndGet();
        return true;
    }
}
