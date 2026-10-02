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
            var key = CardTooltipStyle.styleFor(tier, 21);
            assertEquals(key.namespace(), CardTooltipStyle.NAMESPACE);
            assertEquals(key.value(), key.value().toLowerCase(java.util.Locale.ROOT),
                tier + " style id must be lowercase; the client uses it as a path verbatim");
            assertEquals("card_" + tier.name().toLowerCase(java.util.Locale.ROOT) + "_21",
                key.value());
        }
    }

    @Test
    void theStyleIdResolvesToTheSpritesThatWereWritten() {
        // The client maps ns:path to ns:tooltip/path_background and _frame, so the
        // file name has to be exactly the style id with the sprite kind appended.
        for (Tier tier : Tier.values()) {
            String style = CardTooltipStyle.styleFor(tier, 21).value();
            for (String kind : new String[] {"background", "frame"}) {
                String file = "card_" + tier.name().toLowerCase(java.util.Locale.ROOT)
                    + "_21_" + kind + ".png";
                assertEquals(file, style + "_" + kind + ".png");
            }
        }
    }

    @Test
    void everyTierGetsItsOwnStyle() {
        Set<String> seen = new HashSet<>();
        for (Tier tier : Tier.values()) {
            assertTrue(seen.add(CardTooltipStyle.styleFor(tier, 21).value()),
                tier + " reuses another tier's style, so two tiers look identical");
        }
        assertEquals(Tier.values().length, seen.size());
    }

    @Test
    void fabledIsNotTheSameStyleAsSimple() {
        assertNotEquals(CardTooltipStyle.styleFor(Tier.FABLED, 21).value(),
            CardTooltipStyle.styleFor(Tier.SIMPLE, 21).value());
    }

    @Test
    void aNullTierFallsBackToTheLowestRatherThanThrowing() {
        // A hand-edited card with no tier read should still get a panel.
        assertEquals(CardTooltipStyle.styleFor(Tier.SIMPLE, 21).value(),
            CardTooltipStyle.styleFor(null, 21).value());
    }

    @Test
    void theFrameHeightFollowsTheLoreLength() {
        // Lore length varies per card, so the style id carries the line count and
        // a longer card must not reuse a shorter card's frame.
        for (Tier tier : Tier.values()) {
            assertNotEquals(CardTooltipStyle.styleFor(tier, 20).value(),
                CardTooltipStyle.styleFor(tier, 21).value(),
                tier + " frames must differ between lore lengths");
        }
    }

    @Test
    void anAbsurdLoreLengthClampsInsteadOfMissingTheSprite() {
        // A style id with no sprite behind it falls back to the default tooltip,
        // silently. Clamping keeps a card that somehow grew too many lines on the
        // card frame rather than on vanilla's.
        var tiny = CardTooltipStyle.styleFor(Tier.FABLED, 2).value();
        var huge = CardTooltipStyle.styleFor(Tier.FABLED, 900).value();
        assertEquals(CardTooltipStyle.styleFor(Tier.FABLED, 15).value(), tiny);
        assertEquals(CardTooltipStyle.styleFor(Tier.FABLED, 30).value(), huge);
    }
}
