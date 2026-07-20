package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.HarvestOutcome;
import codes.castled.allium.harvest.random.MutableWeightedTable;

/**
 * Adjusts the weights of the mutually exclusive primary-outcome table before
 * the single selection roll. Built-in modifiers cover fertilizers and
 * permission boosts; external plugins can register their own through the
 * harvest API (skills, seasons, boosters, …). Weights are only normalized at
 * selection time, so base and modified values both stay inspectable.
 */
@FunctionalInterface
public interface HarvestWeightModifier {

    void modify(HarvestContext context, MutableWeightedTable<HarvestOutcome> table);
}
