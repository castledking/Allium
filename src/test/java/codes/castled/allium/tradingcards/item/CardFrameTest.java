package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The panel drawn around a card's lore.
 *
 * <p>Two files describe one layout here: this class says which glyph goes on
 * which line, and {@code tools/generate_card_glyphs.py} draws the glyphs to land
 * where those lines are. Nothing at runtime notices if they drift — the client
 * draws whatever art sits at a codepoint, wherever its metrics put it — so the
 * shared constants are read out of the generator and compared.
 */
class CardFrameTest {

    private static final Path GENERATOR = Path.of("tools/generate_card_glyphs.py");

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final List<Component> LORE = List.of(
        Component.text("Axolotl Trading Card", NamedTextColor.AQUA),
        Component.text("Level: 3"),
        Component.text("Right-Click to Open Menu"));

    @Test
    void theTitleSitsUnderAHeaderLineAndTheBottomCapHasRoomAfterIt() {
        List<Component> framed = CardFrame.wrap(Tier.FABLED, LORE);
        // header + lore + bottom + the blank lines that keep F3+H off the cap
        assertEquals(1 + LORE.size() + 1 + CardFrame.BOTTOM_EXTRA_LINES, framed.size());
        assertEquals(CardFrame.IN + glyph(Tier.FABLED, 0) + CardFrame.OUT, text(framed.get(0)));
        assertEquals(LORE.get(0), framed.get(CardFrame.TITLE_LINE).children().get(1));
        int bottom = 1 + LORE.size();
        // No OUT on the bottom line: its width is the frame's.
        assertEquals(CardFrame.IN + glyph(Tier.FABLED, 2), text(framed.get(bottom)));
        for (int i = bottom + 1; i < framed.size(); i++) {
            assertEquals(Component.empty(), framed.get(i));
        }
    }

    @Test
    void everyTextLineCarriesABodySliceAheadOfItsText() {
        // Ahead, because the client draws a line's glyphs in order: a slice after
        // the text would paint the panel over it.
        List<Component> framed = CardFrame.wrap(Tier.ELITE, LORE);
        for (int i = 0; i < LORE.size(); i++) {
            Component line = framed.get(CardFrame.TITLE_LINE + i);
            assertEquals(CardFrame.IN + glyph(Tier.ELITE, 1) + CardFrame.OUT,
                ((TextComponent) line.children().get(0)).content());
            assertEquals(LORE.get(i), line.children().get(1));
        }
    }

    @Test
    void theFrameIsNeitherTintedNorSlantedNorShadowed() {
        for (Component line : CardFrame.wrap(Tier.SIMPLE, LORE)) {
            if (line.equals(Component.empty())) continue;
            Component frame = line.children().isEmpty() ? line : line.children().get(0);
            assertEquals(CardFrame.FONT, frame.font());
            assertEquals(NamedTextColor.WHITE, frame.color(), "a colour multiplies the art");
            assertEquals(TextDecoration.State.FALSE, frame.decoration(TextDecoration.ITALIC),
                "lore is italic unless told otherwise, which shears the art");
            assertEquals(TextDecoration.State.FALSE, frame.decoration(TextDecoration.BOLD));
            assertEquals(ShadowColor.none(), frame.shadowColor(),
                "a shadow draws a dark copy of the panel a pixel off");
        }
    }

    @Test
    void everyTierHasItsOwnArt() {
        Set<String> seen = new HashSet<>();
        for (Tier tier : Tier.values()) {
            assertTrue(seen.add(text(CardFrame.wrap(tier, LORE).get(0))),
                tier + " reuses another tier's header");
        }
    }

    @Test
    void aNullTierFallsBackToTheLowestRatherThanThrowing() {
        assertEquals(CardFrame.wrap(Tier.SIMPLE, LORE), CardFrame.wrap(null, LORE));
    }

    @Test
    void theTitleReadsBackWithoutItsFrame() {
        assertEquals(LORE.get(0), CardFrame.title(CardFrame.wrap(Tier.LEGENDARY, LORE)).children().get(0));
    }

    @Test
    void loreFromBeforeTheFrameStillHasItsTitleOnLineZero() {
        // Cards are re-rendered only when written, so unframed lore is still out
        // there, and its line 1 is the tier badge rather than the title.
        assertEquals(LORE.get(0), CardFrame.title(LORE));
    }

    @Test
    void aMenuCopyKeepsTheTitleAndFramesItsOwnLines() {
        // The copy keeps the card's blank name and empty tooltip style, so lines
        // put on it without a frame would print with no panel and no name.
        List<Component> menu = List.of(Component.text("Quality: Mint (92%)"));
        for (List<Component> current : List.of(CardFrame.wrap(Tier.ULTIMATE, LORE), LORE)) {
            List<Component> shown = CardFrame.rewrap(Tier.ULTIMATE, current, menu);
            assertEquals("Axolotl Trading Card", PLAIN.serialize(CardFrame.title(shown)));
            assertEquals(menu.get(0), shown.get(CardFrame.TITLE_LINE + 1).children().get(1));
            assertEquals(1 + 2 + 1 + CardFrame.BOTTOM_EXTRA_LINES, shown.size());
        }
    }

    @Test
    void aLineWithNoFrameUnwrapsToItself() {
        assertEquals(LORE.get(1), CardFrame.unwrap(LORE.get(1)));
    }

    // ==================== agreement with the generator ====================

    @Test
    void theGeneratorDrawsTheTiersInEnumOrder() throws Exception {
        // Glyphs are allocated by ordinal on both sides, so a reorder on one side
        // shows every card in another tier's colours.
        var matcher = Pattern.compile("^TIERS = \\(([^)]*)\\)", Pattern.MULTILINE)
            .matcher(Files.readString(GENERATOR));
        assertTrue(matcher.find(), "TIERS not found in " + GENERATOR);
        List<String> generator = Arrays.stream(matcher.group(1).split(","))
            .map(s -> s.strip().replace("\"", ""))
            .filter(s -> !s.isEmpty())
            .toList();
        List<String> plugin = Arrays.stream(Tier.values())
            .map(t -> t.name().toLowerCase(Locale.ROOT))
            .toList();
        assertEquals(plugin, generator);
    }

    @Test
    void theGeneratorWritesTheFontThisReads() throws Exception {
        assertEquals(CardFrame.FONT.namespace(), constant("NS"));
        assertEquals(CardFrame.FONT.value(), constant("FONT_NAME"));
    }

    @Test
    void theGeneratorLeavesTheSameRoomAfterTheBottomCap() throws Exception {
        assertEquals(String.valueOf(CardFrame.BOTTOM_EXTRA_LINES), constant("BOTTOM_EXTRA_LINES"));
    }

    @Test
    void theSpacesAreTheOnesTheGeneratorDefines() throws Exception {
        var matcher = Pattern.compile("^SPACE_IN, SPACE_OUT = \"\\\\u(\\w{4})\", \"\\\\u(\\w{4})\"",
            Pattern.MULTILINE).matcher(Files.readString(GENERATOR));
        assertTrue(matcher.find(), "SPACE_IN, SPACE_OUT not found in " + GENERATOR);
        assertEquals((int) CardFrame.IN.charAt(0), Integer.parseInt(matcher.group(1), 16));
        assertEquals((int) CardFrame.OUT.charAt(0), Integer.parseInt(matcher.group(2), 16));
        assertNotEquals(CardFrame.IN, CardFrame.OUT);
    }

    private static String glyph(Tier tier, int kind) {
        return String.valueOf((char) (0xE100 + tier.ordinal() * 0x10 + kind));
    }

    private static String text(Component line) {
        return ((TextComponent) line).content();
    }

    private static String constant(String name) throws Exception {
        var matcher = Pattern.compile("^" + name + " = \"?([^\"\\s#]+)\"?", Pattern.MULTILINE)
            .matcher(Files.readString(GENERATOR));
        if (!matcher.find()) {
            throw new AssertionError(name + " not found in " + GENERATOR);
        }
        return matcher.group(1);
    }
}
