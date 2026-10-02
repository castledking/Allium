package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The action bar name shown while a card is held.
 *
 * <p>A card has no display name by design, so this text is the only place the
 * card is named. That makes two things worth pinning: the fallback spelling, and
 * the fact that the name comes off the card rather than being rebuilt.
 */
class HeldCardNameListenerTest {

    @Test
    void theFallbackNameIsTitleCasedFromTheEntityType() {
        // A card minted before names were stored still needs a name, and the
        // entity type is the only thing it carries.
        assertEquals("Magma Cube Trading Card",
            HeldCardNameListener.pretty("MAGMA_CUBE") + " Trading Card");
        assertEquals("Axolotl Trading Card",
            HeldCardNameListener.pretty("AXOLOTL") + " Trading Card");
        assertEquals("Wither Skeleton Trading Card",
            HeldCardNameListener.pretty("WITHER_SKELETON") + " Trading Card");
    }

    @Test
    void theFallbackSurvivesRubbishMobValues() {
        assertEquals("Card", HeldCardNameListener.pretty(null));
        assertEquals("Card", HeldCardNameListener.pretty(""));
        assertEquals("Card", HeldCardNameListener.pretty("   "));
    }

    @Test
    void aLowercaseEntityTypeIsAcceptedToo() {
        // PDC values are written by us, but a hand-edited or truncated card
        // should not produce a shouted name.
        assertEquals("Piglin Brute", HeldCardNameListener.pretty("piglin_brute"));
        assertEquals("Mooshroom", HeldCardNameListener.pretty("MOOSHROOM"));
    }

    @Test
    void theRefreshPeriodMatchesTheActionBarLifetime() {
        // Repainting faster than the bar fades costs a packet per player per tick
        // and buys nothing visible.
        assertEquals(40L, HeldCardNameListener.PERIOD_TICKS,
            "40 ticks is the two seconds the action bar stays up");
    }
}
