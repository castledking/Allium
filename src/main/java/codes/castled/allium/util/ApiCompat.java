package codes.castled.allium.util;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;

import java.lang.reflect.Field;

/**
 * Version-safe lookups for registry entries that do not exist on every supported server.
 *
 * <p>Allium compiles against a newer Paper API than the oldest server it runs on, so naming a
 * constant like {@code GameRule.LOCATOR_BAR} directly compiles cleanly and then throws
 * {@link NoSuchFieldError} at runtime on an older server. Because those references usually sit in
 * static initialisers or scheduled tasks, a single missing entry can disable an unrelated feature
 * or the whole plugin. Resolve by name here instead.
 */
public final class ApiCompat {

    private ApiCompat() {}

    /** {@code locatorBar} game rule, added in 1.21.6. */
    private static final GameRule<Boolean> LOCATOR_BAR = booleanGameRule("locatorBar");

    @SuppressWarnings("unchecked")
    private static GameRule<Boolean> booleanGameRule(String name) {
        GameRule<?> rule = GameRule.getByName(name);
        if (rule == null || rule.getType() != Boolean.class) return null;
        return (GameRule<Boolean>) rule;
    }

    /**
     * Whether the locator bar is enabled in the given world.
     * Servers older than 1.21.6 have no such game rule and report {@code true}, matching the
     * previous default for a world that could not be queried.
     */
    public static boolean isLocatorBarEnabled(World world) {
        if (LOCATOR_BAR == null || world == null) return true;
        return !Boolean.FALSE.equals(world.getGameRuleValue(LOCATOR_BAR));
    }

    /** Renamed from {@code GENERIC_MAX_HEALTH} in 1.21.3. */
    public static final Attribute MAX_HEALTH = attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH");

    /** Renamed from {@code GENERIC_MOVEMENT_SPEED} in 1.21.3. */
    public static final Attribute MOVEMENT_SPEED = attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");

    /** Added in 1.21.6; {@code null} on older servers, where callers should skip the work. */
    public static final Attribute WAYPOINT_TRANSMIT_RANGE = attribute("WAYPOINT_TRANSMIT_RANGE");

    /**
     * First attribute constant that exists on this server, or {@code null} if none do.
     * Reflection rather than a direct reference because the same attribute is spelled
     * differently across versions and a missing one must not be fatal.
     */
    private static Attribute attribute(String... names) {
        for (String name : names) {
            try {
                Field field = Attribute.class.getField(name);
                Object value = field.get(null);
                if (value instanceof Attribute found) return found;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next spelling
            }
        }
        return null;
    }
}
