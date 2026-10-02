package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.managers.core.LocatorRadiusExtension;
import java.util.UUID;
import java.util.function.Function;

/**
 * Widens the locator-bar radius for players wearing a card that grants transmit
 * range or transmit reach.
 *
 * <p>Built on Allium's existing party visibility rather than introducing a second
 * reachability system: the party manager already decides who appears on a
 * viewer's locator bar from a configured radius, and already pushes the result
 * into the locator packets. Extending that number is the whole integration — a
 * card reaches exactly as far as the party system says it does, and nothing has
 * to be taught about cards twice.
 *
 * <h2>Why the two boosts are separate</h2>
 *
 * <p>A flat bonus and a multiplier are separate purchases because they help
 * different players. A flat bonus helps someone who is slightly too short of a
 * landmark; a multiplier does nothing for them, because doubling a range they
 * cannot see is still one they cannot see. Combined as
 * {@code (base * multiplier) + flat}, a card rolling both produces a reach
 * somebody actually configured, rather than the larger of the two swallowing the
 * smaller.
 *
 * <p>Multipliers compose multiplicatively and flat ranges add, which is what
 * {@link BoostService#product} and {@link BoostService#total} already do. Two
 * cards therefore widen further than one, without stacking to an absurd number:
 * the multiplier path is geometric.
 */
public class CardLocatorRadius implements LocatorRadiusExtension {

    private final Function<UUID, Double> multiplierFor;
    private final Function<UUID, Double> flatFor;

    /**
     * @param multiplierFor supplies the player's total transmit-reach multiplier,
     *                      as a product. 1.0 when nothing is equipped.
     * @param flatFor       supplies the player's total flat transmit range, in
     *                      blocks. 0 when nothing is equipped.
     */
    public CardLocatorRadius(Function<UUID, Double> multiplierFor, Function<UUID, Double> flatFor) {
        this.multiplierFor = multiplierFor;
        this.flatFor = flatFor;
    }

    @Override
    public double radiusFor(UUID viewer, double base) {
        double multiplier = multiplierFor.apply(viewer);
        double flat = flatFor.apply(viewer);
        if (!Double.isFinite(multiplier) || !Double.isFinite(flat)) {
            return base;
        }
        // A multiplier at or below 1 is not a downgrade, it is simply nothing:
        // clamped rather than allowed to shrink the configured radius.
        double multiplierPart = base * Math.max(1.0, multiplier);
        return multiplierPart + Math.max(0.0, flat);
    }
}
