package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

/**
 * The tooltip style id and the name key.
 *
 * <p>These strings are a wire format, not a label. The client turns the style id
 * into an asset path verbatim and looks the name key up in the pack's lang file,
 * so a typo produces a card that silently shows vanilla's panel or a raw key —
 * no error anywhere. Everything worth asserting here is about the strings
 * matching what {@code tools/generate_card_glyphs.py} writes.
 */
class CardTooltipStyleTest {

    private static final Path GENERATOR = Path.of("tools/generate_card_glyphs.py");

    @Test
    void theStyleIdIsLowercaseSoTheAssetPathResolves() {
        var key = CardTooltipStyle.STYLE;
        assertEquals(CardTooltipStyle.NAMESPACE, key.namespace());
        assertEquals(key.value().toLowerCase(java.util.Locale.ROOT), key.value());
    }

    @Test
    void theStyleIdIsTheOneTheGeneratorWritesSpritesFor() throws Exception {
        assertEquals(CardTooltipStyle.STYLE.value(), generatorConstant("STYLE_ID"));
    }

    @Test
    void theNameColourIsReadOffTheTitleWhereverItIsSet() {
        // MiniMessage puts a line's colour on a child, not the root.
        Component title = Component.text().append(
            Component.text("Axolotl Trading Card", NamedTextColor.AQUA)).build();
        assertEquals(NamedTextColor.AQUA, CardTooltipStyle.colourOf(title));
        assertNull(CardTooltipStyle.colourOf(Component.text("plain")));
    }

    private static String generatorConstant(String name) throws Exception {
        var matcher = Pattern.compile("^" + name + " = \"([^\"]+)\"", Pattern.MULTILINE)
            .matcher(Files.readString(GENERATOR));
        if (!matcher.find()) {
            throw new AssertionError(name + " not found in " + GENERATOR);
        }
        return matcher.group(1);
    }
}
