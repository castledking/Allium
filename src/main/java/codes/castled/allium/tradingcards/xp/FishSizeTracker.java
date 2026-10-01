package codes.castled.allium.tradingcards.xp;

/**
 * Decides whether a caught fish counts as "large" for one species, from a
 * rolling sample of what that player has actually landed.
 *
 * <p>LiteFish exposes no such test. Its {@code WeightConfig} maps a weight to a
 * {@code [range_min, range_max)} band that is a <i>percentage within that
 * species' own range</i>, so a 12.4kg cod and a 12.4kg salmon are not the same
 * achievement, and the band boundaries live in a config file this plugin would
 * have to parse and re-parse to stay honest. There is no {@code isLarge()}
 * anywhere in it.
 *
 * <p>So this measures it instead. For each species, the median of a bounded
 * recent sample of that player's own catches is "normal" for them, and anything
 * at or above the configured percentile of that sample is large. It needs only
 * the two keys LiteFish already writes to the item — nothing from its API, no
 * config parsing, and no threshold table to maintain.
 *
 * <p>The cost is that "large" means large <i>for you</i>, not large absolutely.
 * A player who has only ever landed 1kg sardines will find 2kg sardines large.
 * That is the right trade here: the reward is for a player demonstrating
 * progress on their own fishing, not for clearing a leaderboard, and a fixed
 * table would make the early game unrewarding and the late game trivial.
 */
public final class FishSizeTracker {

    /**
     * @param maxSamples how many recent catches per species to remember. Bounded
     *                   so a player who fishes for a year does not grow an
     *                   unbounded list, and small enough that the median stays
     *                   responsive to a change in what they are landing.
     * @param percentile the share of a catch's sample it must reach to count,
     *                   in (0,1]. 0.75 means the top quarter.
     * @param minimumSamples catches needed before anything can be large. Below
     *                       this the first fish of a species would always look
     *                       enormous against a sample of one.
     */
    public record Rules(int maxSamples, double percentile, int minimumSamples) {
        public static Rules defaults() {
            return new Rules(40, 0.75, 8);
        }
    }

    /** One species' recent sample for one player. */
    private static final class Sample {
        private final double[] weights = new double[64];
        private int size;
        private int next;

        void add(double weight) {
            if (weight <= 0.0) {
                return;
            }
            if (size < weights.length) {
                weights[size++] = weight;
            } else {
                // Ring buffer: the oldest sample is overwritten, so the window
                // slides without a shift on every catch.
                weights[next] = weight;
                next = (next + 1) % weights.length;
            }
        }

        /** How many are actually held once the buffer has wrapped. */
        int held() {
            return Math.min(size, weights.length);
        }

        double[] snapshot(int maxSamples) {
            int count = held();
            if (count > maxSamples) {
                // Take the most recent maxSamples, in arrival order.
                double[] out = new double[maxSamples];
                int start = size < weights.length
                    ? 0
                    : next;
                for (int i = 0; i < maxSamples; i++) {
                    out[i] = weights[(start + i) % weights.length];
                }
                return out;
            }
            double[] out = new double[count];
            System.arraycopy(weights, 0, out, 0, count);
            return out;
        }
    }

    private final java.util.Map<String, java.util.Map<java.util.UUID, Sample>> samples =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final Rules rules;

    public FishSizeTracker(Rules rules) {
        this.rules = rules == null ? Rules.defaults() : rules;
    }

    /**
     * Records a catch and reports whether it counted as large.
     *
     * <p>Recorded before the verdict, so a fish is always part of the sample it
     * is judged against — including the large ones. A player who only ever
     * landed big fish would otherwise have no baseline to be big against.
     *
     * @param species the LiteFish drop id, e.g. {@code COD_1}
     * @return true when this catch is at or above the configured percentile
     */
    public boolean record(java.util.UUID player, String species, double weight) {
        if (species == null || species.isBlank() || weight <= 0.0) {
            return false;
        }
        Sample sample = samples
            .computeIfAbsent(species.toUpperCase(java.util.Locale.ROOT), k -> new java.util.concurrent.ConcurrentHashMap<>())
            .computeIfAbsent(player, k -> new Sample());
        double[] before = sample.snapshot(rules.maxSamples());
        sample.add(weight);
        if (before.length < rules.minimumSamples()) {
            // Not enough history to say anything honest. Returning false keeps
            // the first catches of a species from paying out purely because
            // there was nothing to compare them to.
            return false;
        }
        return percentileOf(before, weight) >= rules.percentile();
    }

    /** How many catches are held for a species and player. */
    public int sampleSize(java.util.UUID player, String species) {
        if (species == null) return 0;
        java.util.Map<java.util.UUID, Sample> bySpecies =
            samples.get(species.toUpperCase(java.util.Locale.ROOT));
        if (bySpecies == null) return 0;
        Sample sample = bySpecies.get(player);
        return sample == null ? 0 : sample.snapshot(rules.maxSamples()).length;
    }

    /**
     * The share of {@code sample} at or below {@code value}, in [0,1].
     *
     * <p>Uses the share of values strictly below, plus half the ties, which is
     * the standard mid-rank convention: with a sample of four, the smallest is
     * at 0.125 and the largest at 0.875, so a percentile can never be exactly
     * 0 or 1 and a "top 25%" cut always keeps roughly a quarter.
     */
    public static double percentileOf(double[] sample, double value) {
        if (sample == null || sample.length == 0) {
            return 0.0;
        }
        int below = 0;
        int equal = 0;
        for (double weight : sample) {
            if (weight < value) below++;
            else if (weight == value) equal++;
        }
        return (below + (equal / 2.0)) / sample.length;
    }

    /** The median of a sample, for a menu line showing what "large" means. */
    public double medianFor(java.util.UUID player, String species) {
        if (species == null) return 0.0;
        java.util.Map<java.util.UUID, Sample> bySpecies =
            samples.get(species.toUpperCase(java.util.Locale.ROOT));
        if (bySpecies == null) return 0.0;
        Sample sample = bySpecies.get(player);
        if (sample == null) return 0.0;
        double[] values = sample.snapshot(rules.maxSamples());
        if (values.length == 0) return 0.0;
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1
            ? sorted[mid]
            : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    /** Forgets a player's samples, for a data reset. */
    public void forget(java.util.UUID player) {
        samples.values().forEach(bySpecies -> bySpecies.remove(player));
        samples.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public Rules rules() {
        return rules;
    }

    /** How many species are being tracked, for diagnostics. */
    public int trackedSpecies() {
        return samples.size();
    }

    static double[] copy(double[] values) {
        return values == null ? new double[0] : values.clone();
    }
}
