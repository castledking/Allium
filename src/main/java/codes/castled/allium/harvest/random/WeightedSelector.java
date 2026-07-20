package codes.castled.allium.harvest.random;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Mutually exclusive weighted selection. A single random roll is made across
 * the total positive weight, so exactly one entry can win — entries are never
 * rolled independently. This is the mechanism that guarantees a crop belongs
 * to exactly one growth path and a harvest yields exactly one quality tier.
 */
public final class WeightedSelector {

    private WeightedSelector() {}

    /**
     * Selects exactly one entry using one roll over the summed positive
     * weights. Entries with zero or negative weight are excluded.
     *
     * @return the selected entry, or empty if no entry has positive weight
     */
    public static <T extends WeightedEntry> Optional<T> selectOne(
        Collection<T> entries,
        RandomGenerator random
    ) {
        double total = 0.0D;
        for (T entry : entries) {
            double w = entry.weight();
            if (w > 0.0D && Double.isFinite(w)) {
                total += w;
            }
        }
        if (total <= 0.0D) {
            return Optional.empty();
        }
        double roll = random.nextDouble(total);
        double cursor = 0.0D;
        T last = null;
        for (T entry : entries) {
            double w = entry.weight();
            if (w <= 0.0D || !Double.isFinite(w)) continue;
            cursor += w;
            last = entry;
            if (roll < cursor) {
                return Optional.of(entry);
            }
        }
        // Floating point edge: roll landed exactly on the accumulated total.
        return Optional.ofNullable(last);
    }

    /**
     * Selects up to {@code count} unique entries without replacement. Each
     * pick is a fresh single roll over the remaining entries, so the result
     * contains no duplicates.
     */
    public static <T extends WeightedEntry> List<T> selectWithoutReplacement(
        Collection<T> entries,
        int count,
        RandomGenerator random
    ) {
        List<T> pool = new ArrayList<>(entries);
        List<T> picked = new ArrayList<>(Math.min(count, pool.size()));
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            Optional<T> selected = selectOne(pool, random);
            if (selected.isEmpty()) break;
            picked.add(selected.get());
            pool.remove(selected.get());
        }
        return picked;
    }
}
