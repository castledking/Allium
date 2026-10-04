package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import codes.castled.allium.tradingcards.card.Tier;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        assertEquals("fabled", match.name());
        assertEquals(0, match.line());
    }

    @Test
    void aMarkerCanSitOnAnyLine() {
        var match = FrameMarker.find(List.of(
            LEGACY.deserialize("&7All purchasable perks."),
            LEGACY.deserialize("[frame:Legendary]")));
        assertNotNull(match);
        assertEquals("legendary", match.name());
        assertEquals(1, match.line());
    }

    @Test
    void aBareMarkerIsAutoAndAnUnknownNameFallsBackToSimple() {
        assertEquals("auto", FrameMarker.find(List.of(Component.text("[frame]"))).name());
        var gold = FrameMarker.find(List.of(Component.text("[frame:gold]")));
        assertEquals("gold", gold.name());
        // No frames.yml here, so the fallback is the simple card frame.
        Component title = Component.text("Perks");
        assertEquals(CardFrame.wrap(Tier.SIMPLE, List.of(title)),
            FrameMarker.frame(title, List.of(Component.text("[frame:gold]")), gold, null));
    }

    @Test
    void angleBracketsWorkLikeSquareOnes() {
        assertEquals("epic", FrameMarker.find(List.of(Component.text("<frame:epic>"))).name());
        assertEquals("auto", FrameMarker.find(List.of(Component.text("<frame>"))).name());
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
        List<Component> framed = FrameMarker.frame(title, lore, match, null);
        assertEquals(CardFrame.wrap(Tier.ULTIMATE,
            List.of(title, lore.get(1), lore.get(2))), framed);
        assertEquals("All purchasable perks.",
            ((TextComponent) framed.get(CardFrame.TITLE_LINE + 1).children().get(1)).content());
    }

    @Test
    void theCommandWritesAMarkerTheFinderReads() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier.name().toLowerCase(java.util.Locale.ROOT),
                FrameMarker.find(List.of(Component.text(FrameMarker.marker(tier)))).name());
        }
    }

    // ==================== hovers ====================

    @Test
    void hoverTextIsCutIntoLinesThatKeepTheirColour() {
        List<Component> lines = FrameMarker.lines(LEGACY.deserialize("&7{prefix} &bSteve\n&eClick to message"));
        assertEquals(2, lines.size());
        assertEquals("Click to message", PlainTextComponentSerializer.plainText().serialize(lines.get(1)));
        assertEquals(NamedTextColor.YELLOW, CardTooltipStyle.colourOf(lines.get(1)));
    }

    @Test
    void aMarkedHoverIsFramedWithNoBlankLineUnderTheCap() {
        Component hover = LEGACY.deserialize("&8[frame:elite]\n&7Rank: &bVIP\n&eClick to message");
        Component framed = FrameMarker.hoverText(hover, null);
        List<Component> lines = FrameMarker.lines(framed);
        // header, two text lines, bottom cap; nothing after it, or the box
        // would grow past the cap and vanilla's panel would show under it
        assertEquals(4, lines.size());
        assertEquals("Rank: VIP", PlainTextComponentSerializer.plainText().serialize(lines.get(1)).substring(3));
        String bottom = PlainTextComponentSerializer.plainText().serialize(lines.get(3));
        assertEquals(CardFrame.TRIM, bottom.substring(bottom.length() - 1));
        assertEquals(CardFrame.HOVER_IN, bottom.substring(0, 1));
    }

    @Test
    void anUnmarkedHoverAndAMessageWithoutOneComeBackUntouched() {
        Component hover = Component.text("Click to message Steve");
        assertSame(hover, FrameMarker.hoverText(hover, null));
        Component message = Component.text("hello").hoverEvent(HoverEvent.showText(hover));
        assertSame(message, FrameMarker.frameHovers(message, null));
    }

    @Test
    void aHoverDeepInAMessageIsFound() {
        Component name = Component.text("Steve")
            .hoverEvent(HoverEvent.showText(Component.text("[frame:fabled]\nClick to message")));
        Component message = Component.text("<").append(name).append(Component.text("> hi"));
        Component framed = FrameMarker.frameHovers(message, null);
        Component hover = (Component) framed.children().get(0).hoverEvent().value();
        assertEquals(3, FrameMarker.lines(hover).size());
        assertEquals("hi", ((TextComponent) framed.children().get(1)).content().substring(2));
    }
}
