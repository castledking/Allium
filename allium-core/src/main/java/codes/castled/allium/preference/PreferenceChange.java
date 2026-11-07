package codes.castled.allium.preference;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A patch of preference values. Each entry sets a value, or unsets it ({@link Optional#empty()})
 * so the configured default applies again. Fields not in the patch are never touched, so a patch
 * can never write defaults over stored data.
 */
public final class PreferenceChange {

    private final Map<PreferenceKey, Optional<Object>> entries;

    private PreferenceChange(Map<PreferenceKey, Optional<Object>> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static PreferenceChange set(PreferenceKey key, Object value) {
        return new PreferenceChange(new EnumMap<>(Map.of(key, Optional.of(key.validate(value)))));
    }

    public static PreferenceChange unset(PreferenceKey key) {
        return new PreferenceChange(new EnumMap<>(Map.of(key, Optional.empty())));
    }

    public PreferenceChange andSet(PreferenceKey key, Object value) {
        EnumMap<PreferenceKey, Optional<Object>> copy = new EnumMap<>(PreferenceKey.class);
        copy.putAll(entries);
        copy.put(key, Optional.ofNullable(key.validate(value)));
        return new PreferenceChange(copy);
    }

    public PreferenceChange andUnset(PreferenceKey key) {
        EnumMap<PreferenceKey, Optional<Object>> copy = new EnumMap<>(PreferenceKey.class);
        copy.putAll(entries);
        copy.put(key, Optional.empty());
        return new PreferenceChange(copy);
    }

    public Map<PreferenceKey, Optional<Object>> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return entries.toString();
    }
}
