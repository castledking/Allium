package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.CardTooltipStyle;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The per-tier tooltip style ids.
 *
 * <p>These strings are a wire format, not a label. The client turns the id into
 * an asset path verbatim, so a capital letter or a missing underscore produces a
 * card that silently falls back to the default tooltip — no error anywhere, just
 * a card that looks plain. Everything worth asserting here is about the string
 * matching the file on disk.
 */
class CardTooltipStyleTest {

    @Test
    void theStyleIdIsLowercaseSoTheAssetPathResolves() {
        for (Tier tier : Tier.values()) {
            var key = CardTooltipStyle.styleFor(tier);
            assertEquals(key.namespace(), CardTooltipStyle.NAMESPACE);
            assertEquals(key.value(), key.value().toLowerCase(java.util.Locale.ROOT),
                tier + " style id must be lowercase; the client uses it as a path verbatim");
            assertEquals("card_" + tier.name().toLowerCase(java.util.Locale.ROOT),
                key.value());
        }
    }

    @Test
    void theStyleIdResolvesToTheSpritesThatWereWritten() {
        // The client maps ns:path to ns:tooltip/path_background and _frame, so the
        // file name has to be exactly the style id with the sprite kind appended.
        for (Tier tier : Tier.values()) {
            String style = CardTooltipStyle.styleFor(tier).value();
            for (String kind : new String[] {"background", "frame"}) {
                String file = "card_" + tier.name().toLowerCase(java.util.Locale.ROOT)
                    + "_" + kind + ".png";
                assertEquals(file, style + "_" + kind + ".png");
            }
        }
    }

    @Test
    void everyTierGetsItsOwnStyle() {
        Set<String> seen = new HashSet<>();
        for (Tier tier : Tier.values()) {
            assertTrue(seen.add(CardTooltipStyle.styleFor(tier).value()),
                tier + " reuses another tier's style, so two tiers look identical");
        }
        assertEquals(Tier.values().length, seen.size());
    }

    @Test
    void fabledIsNotTheSameStyleAsSimple() {
        assertNotEquals(CardTooltipStyle.styleFor(Tier.FABLED).value(),
            CardTooltipStyle.styleFor(Tier.SIMPLE).value());
    }

    @Test
    void aNullTierFallsBackToTheLowestRatherThanThrowing() {
        // A hand-edited card with no tier read should still get a panel.
        assertEquals(CardTooltipStyle.styleFor(Tier.SIMPLE).value(),
            CardTooltipStyle.styleFor(null).value());
    }
}
