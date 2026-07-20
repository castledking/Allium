package codes.castled.allium.harvest.random;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A weighted table whose weights can be adjusted by modifiers (fertilizer,
 * permissions, skills…) before the final single-roll selection. The configured
 * base weights stay visible next to the modified weights for debug output.
 *
 * @param <T> the entry payload type
 */
public final class MutableWeightedTable<T> {

    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();

    public static final class Entry<T> implements WeightedEntry {
        private final String id;
        private final double baseWeight;
        private double weight;
        private final T value;

        Entry(String id, double baseWeight, T value) {
            this.id = id;
            this.baseWeight = baseWeight;
            this.weight = baseWeight;
            this.value = value;
        }

        public String id() { return id; }
        public double baseWeight() { return baseWeight; }
        @Override public double weight() { return weight; }
        public T value() { return value; }
    }

    public void put(String id, double baseWeight, T value) {
        entries.put(id, new Entry<>(id, baseWeight, value));
    }

    public boolean has(String id) {
        return entries.containsKey(id);
    }

    public void remove(String id) {
        entries.remove(id);
    }

    /** Multiplies the current weight of {@code id}; unknown ids are ignored. */
    public void multiplyWeight(String id, double factor) {
        Entry<T> entry = entries.get(id);
        if (entry != null) {
            entry.weight = Math.max(0.0D, entry.weight * factor);
        }
    }

    /** Adds to the current weight of {@code id}; unknown ids are ignored. */
    public void addWeight(String id, double delta) {
        Entry<T> entry = entries.get(id);
        if (entry != null) {
            entry.weight = Math.max(0.0D, entry.weight + delta);
        }
    }

    /** Sets the current weight of {@code id}; unknown ids are ignored. */
    public void setWeight(String id, double weight) {
        Entry<T> entry = entries.get(id);
        if (entry != null) {
            entry.weight = Math.max(0.0D, weight);
        }
    }

    public Map<String, Entry<T>> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** Single-roll mutually exclusive selection over the modified weights. */
    public Optional<Entry<T>> selectOne(RandomGenerator random) {
        return WeightedSelector.selectOne(entries.values(), random);
    }
}
