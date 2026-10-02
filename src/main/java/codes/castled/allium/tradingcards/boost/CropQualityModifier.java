package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.harvest.crop.HarvestContext;
import codes.castled.allium.harvest.crop.HarvestWeightModifier;
import codes.castled.allium.harvest.crop.def.HarvestOutcome;
import codes.castled.allium.harvest.random.MutableWeightedTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;

/**
 * Biases Allium's crop quality tables toward the rarer outcomes while a card
 * granting {@code crop-boost} is equipped.
 *
 * <p>Registered through the harvest API rather than by editing crop files, so
 * the effect is global while equipped and leaves every config untouched when the
 * card comes off.
 *
 * <h2>How "better quality" is decided</h2>
 *
 * <p>Crop files name their quality tiers freely — {@code regular}, {@code
 * silver}, {@code golden-star} — so there is no column to sort on. Weight is
 * the one thing every quality entry has, and a weighted-one table already
 * encodes quality as "rarer means better": a golden tomato is worth more
 * precisely because it comes out less often.
 *
 * <p>So the rarest entry is the best one, and the bias scales with how far up
 * the rarity order an entry sits. This cannot invent an outcome the crop file
 * did not define, and it cannot make a common outcome rarer — it only makes
 * better ones likelier, which is what a crop boost is asking for.
 */
public class CropQualityModifier implements HarvestWeightModifier {

    private final java.util.function.Function<UUID, Double> bonusFor;

    /**
     * @param bonusFor supplies a player's total crop-quality bonus in the
     *                 catalog's own units: 0.25 is a 25% bias. Returning 0
     *                 leaves the table untouched, so a player with no card pays
     *                 nothing beyond the lookup.
     */
    public CropQualityModifier(java.util.function.Function<UUID, Double> bonusFor) {
        this.bonusFor = bonusFor;
    }

    @Override
    public void modify(HarvestContext context, MutableWeightedTable<HarvestOutcome> table) {
        if (context == null || context.player() == null || table == null) {
            return;
        }
        double bonus = bonusFor.apply(context.player().getUniqueId());
        if (bonus <= 0.0) {
            return;
        }
        List<MutableWeightedTable.Entry<HarvestOutcome>> entries =
            new ArrayList<>(table.entries().values());
        if (entries.size() < 2) {
            // One outcome means there is no choice to bias, and ranking a single
            // entry at the top would apply the full bonus for no reason.
            return;
        }
        // Ascending by base weight: index 0 is the rarest, and therefore the best
        // quality. Ties break on id so two equal-weight entries still produce a
        // stable ranking rather than an arbitrary one.
        entries.sort(Comparator
            .comparingDouble(
                (MutableWeightedTable.Entry<HarvestOutcome> e) -> e.baseWeight())
            .thenComparing(MutableWeightedTable.Entry<HarvestOutcome>::id));

        int n = entries.size();
        for (int rank = 0; rank < n; rank++) {
            MutableWeightedTable.Entry<HarvestOutcome> entry = entries.get(rank);
            if (entry.weight() <= 0.0) {
                // An outcome nobody can roll must stay unrollable: a card may
                // bias the table, never unlock an outcome the config disabled.
                continue;
            }
            // Rarest gets the whole bonus, commonest none, tapering linearly in
            // between so the boost is strongest where it is meaningful and does
            // not fall off a cliff between neighbours.
            double share = 1.0 - ((double) rank / (n - 1));
            table.multiplyWeight(entry.id(), 1.0 + (bonus * share));
        }
    }
}
