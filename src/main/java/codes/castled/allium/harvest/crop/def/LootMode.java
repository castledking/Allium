package codes.castled.allium.harvest.crop.def;

/** How the entries of a harvest table are resolved into outcomes. */
public enum LootMode {

    /**
     * Exactly one eligible entry is chosen with a single roll across the
     * total weight. Mutually exclusive — use this for quality tiers.
     */
    WEIGHTED_ONE,

    /** A configured number of unique entries are chosen without replacement. */
    WEIGHTED_MULTIPLE_WITHOUT_REPLACEMENT,

    /**
     * Each entry rolls its own {@code chance} independently. Multiple entries
     * may fire together — never use this for mutually exclusive groups.
     */
    INDEPENDENT,

    /** All eligible entries execute. */
    GUARANTEED_ALL,

    /**
     * Entries are processed in configuration order; each rolls its
     * {@code chance}, and processing stops at the first failed roll when
     * {@code stop-on-fail} is enabled (the default).
     */
    SEQUENCE
}
