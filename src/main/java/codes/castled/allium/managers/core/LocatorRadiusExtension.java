package codes.castled.allium.managers.core;

import java.util.UUID;

/**
 * Lets something extend the radius at which a viewer sees other players on their
 * locator bar.
 *
 * <p>The base radius is a server-wide setting in
 * {@code party-manager.show-non-party-members-radius}. This hook exists so a
 * per-player source — an equipped card, say — can widen it for the players
 * holding that thing, without the party system knowing anything about cards.
 *
 * <p>Kept as an interface rather than a direct call into the trading card module
 * for two reasons: the party system must keep working with that module disabled,
 * and the radius arithmetic is worth testing without a server.
 *
 * <p>Only ever widens. A card that computes a smaller radius than the configured
 * base must not narrow what a player can already see — that would be a card
 * taking a capability away rather than granting one.
 */
@FunctionalInterface
public interface LocatorRadiusExtension {

    /** Leaves the radius exactly as configured. */
    LocatorRadiusExtension NONE = (viewer, base) -> base;

    /**
     * The radius this viewer should use.
     *
     * @param viewer the player whose own reach is being widened — the one
     *               holding the card, not the one being looked at
     * @param base   the configured server radius
     * @return the radius to use, in blocks; never less than {@code base}
     */
    double radiusFor(UUID viewer, double base);

    /**
     * The extension as configured, never null.
     *
     * <p>Null is a natural thing for a setter to receive on reload or shutdown,
     * and a null that has to be checked at every decision point is a null that
     * will eventually be forgotten.
     */
    static LocatorRadiusExtension orNone(LocatorRadiusExtension extension) {
        return extension == null ? NONE : extension;
    }
}
