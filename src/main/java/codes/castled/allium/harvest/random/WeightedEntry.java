package codes.castled.allium.harvest.random;

/** An entry that can participate in a single-roll weighted selection. */
public interface WeightedEntry {

    /**
     * Relative weight of this entry. Weights do not need to sum to any
     * particular total; only the ratio between entries matters. Entries with a
     * weight of zero or below are never selected.
     */
    double weight();
}
