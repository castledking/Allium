package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.HarvestOutcome;
import codes.castled.allium.harvest.crop.def.HarvestTable;
import codes.castled.allium.harvest.crop.def.LootMode;
import codes.castled.allium.harvest.random.MutableWeightedTable;
import codes.castled.allium.harvest.random.WeightedSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Pure resolution of a harvest table into the set of outcomes that fire.
 * The weighted modes guarantee mutual exclusivity through a single roll;
 * INDEPENDENT/SEQUENCE roll per-entry chances. Weight modifiers (fertilizer,
 * permissions, …) are applied to the mutable table before selection and only
 * affect weighted modes.
 */
public final class LootResolver {

    private LootResolver() {}

    /**
     * @param modifier adjusts weights before weighted selection; may be null
     * @return the outcomes that fired, in table order for non-weighted modes
     */
    public static List<HarvestOutcome> resolve(
        HarvestTable table,
        Consumer<MutableWeightedTable<HarvestOutcome>> modifier,
        RandomGenerator random
    ) {
        if (table == null || table.isEmpty()) {
            return List.of();
        }
        return switch (table.mode()) {
            case WEIGHTED_ONE -> weighted(table, modifier, 1, random);
            case WEIGHTED_MULTIPLE_WITHOUT_REPLACEMENT ->
                weighted(table, modifier, Math.max(1, table.picks()), random);
            case INDEPENDENT -> {
                List<HarvestOutcome> fired = new ArrayList<>();
                for (HarvestOutcome outcome : table.outcomes().values()) {
                    if (random.nextDouble() < outcome.chance()) {
                        fired.add(outcome);
                    }
                }
                yield fired;
            }
            case GUARANTEED_ALL -> List.copyOf(table.outcomes().values());
            case SEQUENCE -> {
                List<HarvestOutcome> fired = new ArrayList<>();
                for (HarvestOutcome outcome : table.outcomes().values()) {
                    if (random.nextDouble() < outcome.chance()) {
                        fired.add(outcome);
                    } else if (table.stopOnFail()) {
                        break;
                    }
                }
                yield fired;
            }
        };
    }

    private static List<HarvestOutcome> weighted(
        HarvestTable table,
        Consumer<MutableWeightedTable<HarvestOutcome>> modifier,
        int picks,
        RandomGenerator random
    ) {
        MutableWeightedTable<HarvestOutcome> weighted = new MutableWeightedTable<>();
        for (HarvestOutcome outcome : table.outcomes().values()) {
            weighted.put(outcome.id(), outcome.weight(), outcome);
        }
        if (modifier != null) {
            modifier.accept(weighted);
        }
        if (table.mode() == LootMode.WEIGHTED_ONE) {
            return weighted.selectOne(random)
                .map(entry -> List.of(entry.value()))
                .orElse(List.of());
        }
        return WeightedSelector.selectWithoutReplacement(weighted.entries().values(), picks, random)
            .stream()
            .map(MutableWeightedTable.Entry::value)
            .toList();
    }
}
