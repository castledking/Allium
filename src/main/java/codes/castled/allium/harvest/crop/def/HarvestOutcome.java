package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.random.WeightedEntry;
import java.util.List;

/**
 * One entry in a harvest table: a quality tier, a bonus drop group, etc.
 *
 * @param weight relative weight used by weighted modes
 * @param chance independent probability used by INDEPENDENT / SEQUENCE modes
 * @param commands console commands run when this outcome fires;
 *                 {@code {player}} and {@code {crop}} are substituted
 */
public record HarvestOutcome(
    String id,
    double weight,
    double chance,
    List<DropSpec> drops,
    List<String> commands
) implements WeightedEntry {

    public HarvestOutcome {
        drops = List.copyOf(drops);
        commands = List.copyOf(commands);
    }

    @Override
    public double weight() {
        return weight;
    }
}
