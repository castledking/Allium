package codes.castled.allium.tool.powertool;

/**
 * Executes one {@link PowerToolAction} type.
 *
 * <p>Returns {@code true} when the action performed work (or dispatched), and {@code false} when
 * it was a no-op (for example, {@code disarm} on an unequipped mob, {@code obliterate} with no
 * targets in range, or {@code tree_fell} on a non-tree block) so the caller can avoid wasting
 * cooldowns or limited uses on no-ops.
 */
@FunctionalInterface
public interface PowerToolActionHandler {
    boolean execute(PowerToolContext context, PowerToolAction action);
}
