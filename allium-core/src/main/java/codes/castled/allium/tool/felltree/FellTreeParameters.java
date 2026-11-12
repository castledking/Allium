package codes.castled.allium.tool.felltree;

import codes.castled.allium.tool.definition.ToolParameters;

import java.util.List;
import java.util.Objects;

/**
 * The {@code allium:fell_tree} parameters one definition supplies. They tune the search and the
 * pace; the steps (vanilla breaks the first log, detection, a per-block protection check and
 * recheck, the re-entry guard) belong to {@code FellTreeBehavior}.
 *
 * @param breakAsPlayer   break each log with the server's player-break path, so every plugin sees
 *                        a real {@code BlockBreakEvent}; otherwise break it directly
 * @param stopBeforeBreak stop felling with one durability left instead of breaking the axe
 * @param sneak           {@code disables}, {@code required} or {@code ignored}
 */
public record FellTreeParameters(
        List<TreeFamily> families,
        boolean stripped,
        int maxBlocks,
        int maxRadius,
        int maxHeight,
        int minNaturalLeaves,
        int blocksPerTick,
        boolean breakAsPlayer,
        boolean stopBeforeBreak,
        String sneak,
        long cooldownMillis,
        boolean importLegacy
) implements ToolParameters {

    public FellTreeParameters {
        families = List.copyOf(families);
        Objects.requireNonNull(sneak, "sneak");
    }

    public TreeScanner.Limits limits() {
        return new TreeScanner.Limits(maxBlocks, maxRadius, maxHeight, minNaturalLeaves, stripped);
    }

    /** Whether a player sneaking (or not) may fell. */
    public boolean fellsWhileSneaking(boolean sneaking) {
        return switch (sneak) {
            case "disables" -> !sneaking;
            case "required" -> sneaking;
            default -> true;
        };
    }
}
