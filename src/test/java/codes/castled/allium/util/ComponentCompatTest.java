package codes.castled.allium.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The component shapes {@link ComponentCompat} has to produce.
 *
 * <p>These are not cosmetic. The frame draws a glyph in its own font immediately before a
 * line of text in the line's own styles, so the two have to stay separate children: collapse
 * them and the text takes the frame's font and the card prints as a run of boxes. A component
 * tree that merely renders the same string is not enough, which is why each case below counts
 * children and checks a font rather than comparing rendered text.
 *
 * <p>The single-child case is the reason {@code ComponentCompat} exists at all — Adventure's
 * {@code join} hands a lone part straight back, and callers that strip a prefix off a line need
 * the remainder to stay a line.
 */
class ComponentCompatTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final Key FRAME_FONT = Key.key("sf", "card_frame");

    private static Component frameGlyph(String content) {
        return Component.text(content, Style.style().font(FRAME_FONT).build());
    }

    private static Component text(String content) {
        return Component.text(content, NamedTextColor.AQUA);
    }

    @Test
    void joinKeepsBothPartsAsSeparateChildrenSoEachKeepsItsOwnFont() {
        Component glyph = frameGlyph("");
        Component line = text("Axolotl Trading Card");

        Component joined = ComponentCompat.join(glyph, line);

        assertEquals("Axolotl Trading Card", PLAIN.serialize(joined));
        assertEquals(2, joined.children().size(), "the glyph must stay its own child");
        assertEquals(FRAME_FONT, joined.children().get(0).font());
        assertEquals(null, joined.children().get(1).font(), "the line must not inherit the frame font");
    }

    @Test
    void joinOfManyPartsKeepsThemAll() {
        Component joined = ComponentCompat.join(List.of(text("a"), text("b"), text("c")));
        assertEquals("abc", PLAIN.serialize(joined));
        assertEquals(3, joined.children().size());
    }

    @Test
    void joinOfOnePartHandsItStraightBackRatherThanWrappingIt() {
        // A line made of a single run needs no parent, and callers that build a list of lines
        // rely on getting the run itself so they can compare or reuse it.
        Component only = text("Level: 3");
        assertSame(only, ComponentCompat.join(List.of(only)));
    }

    @Test
    void wrapKeepsTheParentForASingleChild() {
        // The case join cannot do. CardFrame#unwrap strips the frame glyph off a lore line and
        // hands the rest back; a bare child would arrive with no children to read a title from.
        Component only = text("Axolotl Trading Card");

        Component wrapped = ComponentCompat.wrap(List.of(only));

        assertEquals("Axolotl Trading Card", PLAIN.serialize(wrapped));
        assertEquals(1, wrapped.children().size());
        assertSame(only, wrapped.children().get(0));
    }

    @Test
    void wrapKeepsEveryChildInOrder() {
        List<Component> children = List.of(text("a"), text("b"), text("c"));
        Component wrapped = ComponentCompat.wrap(children);
        assertEquals("abc", PLAIN.serialize(wrapped));
        assertEquals(3, wrapped.children().size());
        for (int i = 0; i < children.size(); i++) {
            assertSame(children.get(i), wrapped.children().get(i));
        }
    }

    @Test
    void wrapOfNothingIsEmptyRatherThanNull() {
        assertEquals(Component.empty(), ComponentCompat.wrap(List.of()));
    }

    @Test
    void joinWithNewlinesBreaksBetweenPartsAndNotAfterTheLast() {
        Component joined =
            ComponentCompat.join(JoinConfiguration.newlines(), List.of(text("a"), text("b"), text("c")));
        assertEquals(5, joined.children().size(), "three lines and two breaks between them");
        assertEquals("a", PLAIN.serialize(joined.children().get(0)));
        assertEquals("\n", PLAIN.serialize(joined.children().get(1)));
        assertEquals("b", PLAIN.serialize(joined.children().get(2)));
        assertEquals("\n", PLAIN.serialize(joined.children().get(3)));
        assertEquals("c", PLAIN.serialize(joined.children().get(4)));
    }
}