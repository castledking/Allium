package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.managers.core.LocatorRadiusExtension;
import codes.castled.allium.tradingcards.boost.CardLocatorRadius;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The card's effect on the party manager's locator radius.
 *
 * <p>The arithmetic is the point: the base is a server-wide setting that
 * operators tune, and a card must widen it without ever narrowing it. A card
 * that could take reach away would be a downgrade dressed as a reward, and the
 * failure would be invisible until somebody noticed a player could no longer see
 * a landmark they used to.
 */
class CardLocatorRadiusTest {

    /** The shipped default in party-manager.show-non-party-members-radius. */
    private static final double BASE = 128.0;

    private static CardLocatorRadius radius(double multiplier, double flat) {
        UUID id = UUID.randomUUID();
        return new CardLocatorRadius(v -> multiplier, v -> flat);
    }

    @Test
    void noBoostsLeavesTheConfiguredRadiusAlone() {
        assertEquals(BASE, radius(1.0, 0.0).radiusFor(UUID.randomUUID(), BASE),
            "the card must be invisible when it grants nothing");
    }

    @Test
    void aFlatRangeAddsToTheBase() {
        assertEquals(BASE + 48.0, radius(1.0, 48.0).radiusFor(UUID.randomUUID(), BASE));
    }

    @Test
    void aMultiplierScalesTheBase() {
        assertEquals(BASE * 2.0, radius(2.0, 0.0).radiusFor(UUID.randomUUID(), BASE));
    }

    @Test
    void bothComposeRatherThanOneSwallowingTheOther() {
        // (base * multiplier) + flat. Taking the larger of the two instead
        // would silently discard a boost the player paid for.
        assertEquals((BASE * 2.0) + 48.0,
            radius(2.0, 48.0).radiusFor(UUID.randomUUID(), BASE));
        assertTrue(radius(2.0, 48.0).radiusFor(UUID.randomUUID(), BASE) > BASE * 2.0,
            "the flat bonus must add on top of the multiplier, not be rounded away");
    }

    @Test
    void aMultiplierBelowOneNeverShrinksTheRadius() {
        // Configured in error, or from a card rolling a discount. Either way the
        // player keeps the reach the server gave them.
        assertEquals(BASE, radius(0.5, 0.0).radiusFor(UUID.randomUUID(), BASE),
            "a card must never take visibility away");
        assertEquals(BASE + 48.0, radius(0.5, 48.0).radiusFor(UUID.randomUUID(), BASE),
            "the flat bonus still applies; only the shrink is refused");
    }

    @Test
    void aNegativeFlatBonusIsRefused() {
        assertEquals(BASE, radius(1.0, -500.0).radiusFor(UUID.randomUUID(), BASE));
    }

    @Test
    void garbageValuesFallBackToTheConfiguredRadius() {
        assertEquals(BASE, radius(Double.NaN, 10.0).radiusFor(UUID.randomUUID(), BASE));
        assertEquals(BASE, radius(Double.POSITIVE_INFINITY, 10.0)
            .radiusFor(UUID.randomUUID(), BASE));
        assertEquals(BASE, radius(1.0, Double.NaN).radiusFor(UUID.randomUUID(), BASE));
    }

    @Test
    void theNoOpExtensionIsExactlyTheConfiguredRadius() {
        assertEquals(BASE, LocatorRadiusExtension.NONE.radiusFor(UUID.randomUUID(), BASE));
    }

    @Test
    void aNullExtensionResolvesToTheNoOp() {
        // A reload or a shutdown can hand the setter null; the decision points
        // must never have to remember to check.
        assertEquals(BASE, LocatorRadiusExtension.orNone(null)
            .radiusFor(UUID.randomUUID(), BASE));
        assertFalse(LocatorRadiusExtension.orNone(null) == null);
    }

    @Test
    void twoCardsWidenFurtherThanOne() {
        // The multiplier path is geometric, so a second x2 card doubles again
        // rather than adding a fixed amount.
        UUID id = UUID.randomUUID();
        double one = radius(2.0, 0.0).radiusFor(id, BASE);
        double two = radius(4.0, 0.0).radiusFor(id, BASE);
        assertEquals(BASE * 4.0, two);
        assertTrue(two > one);
    }

    @Test
    void theCatalogAmountsProduceAReasonableReach() {
        // The shipped boosts.yml values, checked against the shipped base so a
        // rebalance that made these absurd fails here rather than on the server.
        double reach = radius(2.0, 48.0).radiusFor(UUID.randomUUID(), BASE);
        assertTrue(reach > BASE, "the card must actually do something");
        assertTrue(reach < BASE * 10,
            "reach should stay in a range a player can still use as a locator bar");
    }
}
