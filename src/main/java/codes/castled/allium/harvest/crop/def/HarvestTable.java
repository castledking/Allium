package codes.castled.allium.harvest.crop.def;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ordered table of harvest outcomes plus the mode used to resolve them.
 *
 * @param picks number of entries chosen in
 *              {@link LootMode#WEIGHTED_MULTIPLE_WITHOUT_REPLACEMENT}
 * @param stopOnFail whether {@link LootMode#SEQUENCE} stops at the first
 *                   failed chance roll
 */
public record HarvestTable(
    LootMode mode,
    int picks,
    boolean stopOnFail,
    Map<String, HarvestOutcome> outcomes
) {

    public HarvestTable {
        outcomes = new LinkedHashMap<>(outcomes);
    }

    public static HarvestTable empty() {
        return new HarvestTable(LootMode.GUARANTEED_ALL, 1, true, Map.of());
    }

    public boolean isEmpty() {
        return outcomes.isEmpty();
    }
}
