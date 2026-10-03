package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import codes.castled.allium.tradingcards.card.Tier;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The {@code [frame:...]} lore marker, which any plugin's item can carry.
 *
 * <p>The marker is typed by hand into other plugins' configs, most of which only
 * know {@code &} codes, so it has to be found however it was coloured and
 * wherever it was put.
 */
class FrameMarkerTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    @Test
    void aMarkerIsFoundThroughTheColourCodesAroundIt() {
        var match = FrameMarker.find(List.of(LEGACY.deserialize("&8&o[frame:fabled]")));
        assertNotNull(match);
        assertEquals(Tier.FABLED, match.tier());
        assertEquals(0, match.line());
    }

    @Test
    void aMarkerCanSitOnAnyLine() {
        var match = FrameMarker.find(List.of(
            LEGACY.deserialize("&7All purchasable perks."),
            LEGACY.deserialize("[frame:Legendary]")));
        assertNotNull(match);
        assertEquals(Tier.LEGENDARY, match.tier());
        assertEquals(1, match.line());
    }

    @Test
    void aBareOrUnknownMarkerIsTheSimpleFrame() {
        assertEquals(Tier.SIMPLE, FrameMarker.find(List.of(Component.text("[frame]"))).tier());
        assertEquals(Tier.SIMPLE, FrameMarker.find(List.of(Component.text("[frame:gold]"))).tier());
    }

    @Test
    void textThatOnlyMentionsAFrameIsNotAMarker() {
        assertNull(FrameMarker.find(List.of(Component.text("Buy a [frame:fabled] upgrade"))));
        assertNull(FrameMarker.find(List.of(Component.text("Picture frame"))));
        assertNull(FrameMarker.find(List.of()));
        assertNull(FrameMarker.find(null));
    }

    @Test
    void alreadyFramedLoreIsLeftAlone() {
        // A trading card's lore starts with its header line; framing it again
        // would wrap the frame as if it were text.
        var framed = CardFrame.wrap(Tier.ELITE, List.of(Component.text("[frame:fabled]")));
        assertNull(FrameMarker.find(framed));
    }

    @Test
    void theTitleGoesOnTopAndTheMarkerLineIsDropped() {
        List<Component> lore = List.of(
            Component.text("[frame:ultimate]"),
            Component.text("All purchasable perks."),
            Component.text("[Click to view]"));
        Component title = Component.text("Perks", NamedTextColor.AQUA);
        var match = FrameMarker.find(lore);
        List<Component> framed = FrameMarker.frame(title, lore, match);
        assertEquals(CardFrame.wrap(Tier.ULTIMATE,
            List.of(title, lore.get(1), lore.get(2))), framed);
        assertEquals("All purchasable perks.",
            ((TextComponent) framed.get(CardFrame.TITLE_LINE + 1).children().get(1)).content());
    }

    @Test
    void theCommandWritesAMarkerTheFinderReads() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier, FrameMarker.find(List.of(Component.text(FrameMarker.marker(tier)))).tier());
        }
    }
}
