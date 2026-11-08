package codes.castled.allium.tool.definition;

import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Strict, path-aware reader over one parsed YAML document: nested {@code Map}s, {@code List}s
 * and scalars, with no Bukkit types. Every problem is recorded as {@code <path>: <message>} in
 * one shared list, so a whole file is checked in one pass. Values of the wrong type are errors,
 * never silently replaced by a default. {@link #rejectUnknown()} reports keys nothing asked for,
 * so a misspelled setting cannot fall back to its default unnoticed.
 */
public final class ConfigReader {

    private final String path;
    private final Map<String, Object> values;
    private final List<String> errors;
    private final Set<String> known = new LinkedHashSet<>();
    private final Map<String, ConfigReader> children = new LinkedHashMap<>();

    private ConfigReader(String path, Map<String, Object> values, List<String> errors) {
        this.path = path;
        this.values = values;
        this.errors = errors;
    }

    public static ConfigReader of(Map<?, ?> document) {
        return new ConfigReader("", normalize(document), new ArrayList<>());
    }

    /** The full path of a key in this section, e.g. {@code charges.max}. */
    public String path(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public String path() {
        return path;
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void error(String key, String message) {
        errors.add(path(key) + ": " + message);
    }

    /** An error about this section as a whole (or about the document, at the root). */
    public void errorHere(String message) {
        errors.add((path.isEmpty() ? "(file)" : path) + ": " + message);
    }

    /** Keys present in this section, in file order. Listing them marks them as known. */
    public Set<String> keys() {
        known.addAll(values.keySet());
        return Collections.unmodifiableSet(values.keySet());
    }

    public boolean has(String key) {
        known.add(key);
        return values.containsKey(key);
    }

    /** Marks a key as handled without reading it. */
    public void accept(String key) {
        known.add(key);
    }

    @Nullable
    private Object value(String key) {
        known.add(key);
        if (!values.containsKey(key)) {
            return null;
        }
        Object value = values.get(key);
        if (value == null) {
            error(key, "has no value; set one or remove the line");
        }
        return value;
    }

    /** A required text value; records an error and returns {@code null} when missing or invalid. */
    @Nullable
    public String requiredString(String key) {
        if (!values.containsKey(key)) {
            known.add(key);
            error(key, "is required");
            return null;
        }
        return string(key, null);
    }

    @Nullable
    public String string(String key, @Nullable String fallback) {
        Object value = value(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String text) {
            return text;
        }
        error(key, "must be text, not " + describe(value));
        return fallback;
    }

    public boolean bool(String key, boolean fallback) {
        Object value = value(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        error(key, "must be true or false, not " + describe(value));
        return fallback;
    }

    public int integer(String key, int fallback, int min, int max) {
        return (int) whole(key, fallback, min, max);
    }

    public long whole(String key, long fallback, long min, long max) {
        Object value = value(key);
        if (value == null) {
            return fallback;
        }
        Long number = asWhole(value);
        if (number == null) {
            error(key, "must be a whole number, not " + describe(value));
            return fallback;
        }
        if (number < min || number > max) {
            error(key, "must be between " + min + " and " + max + " (got " + number + ")");
            return fallback;
        }
        return number;
    }

    public double decimal(String key, double fallback, double min, double max) {
        Object value = value(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            error(key, "must be a number, not " + describe(value));
            return fallback;
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) {
            error(key, "must be between " + format(min) + " and " + format(max) + " (got " + value + ")");
            return fallback;
        }
        return result;
    }

    /** One of {@code choices}, compared case-insensitively with {@code _} accepted for {@code -}. */
    public String choice(String key, String fallback, List<String> choices) {
        String raw = string(key, null);
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (choices.contains(normalized)) {
            return normalized;
        }
        error(key, "must be one of " + String.join(", ", choices) + " (got '" + raw + "')");
        return fallback;
    }

    /** A list of text values; each bad element is reported with its index. */
    public List<String> stringList(String key, List<String> fallback) {
        Object value = value(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof List<?> list)) {
            error(key, "must be a list, not " + describe(value));
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof String text && !text.isBlank()) {
                out.add(text.trim());
            } else {
                errors.add(path(key) + "[" + i + "]: must be non-empty text, not " + describe(element));
            }
        }
        return out;
    }

    /**
     * The nested section at {@code key}, or an empty section when absent. A non-section value is
     * an error and also yields an empty section, so callers can keep reading defaults.
     */
    public ConfigReader section(String key) {
        ConfigReader cached = children.get(key);
        if (cached != null) {
            return cached;
        }
        Object value = value(key);
        Map<String, Object> map = Map.of();
        if (value instanceof Map<?, ?> raw) {
            map = normalize(raw);
        } else if (value != null) {
            error(key, "must be a section of settings, not " + describe(value));
        }
        ConfigReader child = new ConfigReader(path(key), map, errors);
        children.put(key, child);
        return child;
    }

    public Optional<Object> raw(String key) {
        return Optional.ofNullable(value(key));
    }

    /** Reports every key, here and in nested sections read so far, that nothing asked for. */
    public void rejectUnknown() {
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                error(key, "unknown setting" + (known.isEmpty() ? "" : "; expected one of " + String.join(", ", known)));
            }
        }
        for (ConfigReader child : children.values()) {
            child.rejectUnknown();
        }
    }

    @Nullable
    private static Long asWhole(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big && big.bitLength() < 64) {
            return big.longValue();
        }
        return null;
    }

    private static String describe(@Nullable Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof String text) {
            return "'" + text + "'";
        }
        if (value instanceof Map<?, ?>) {
            return "a section";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        return value.toString();
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    private static Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
