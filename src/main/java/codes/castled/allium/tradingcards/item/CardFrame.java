package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Wraps a card's lore in the panel drawn by the pack's {@code sf:card_frame} font.
 *
 * <p>The panel is text. Every lore line starts with a frame glyph that is a
 * full-width slice of the card art, stepped back to the tooltip's left edge by a
 * negative space and followed by one that returns to the text column, so it
 * sits behind the line without moving it. The client draws a tooltip's glyphs
 * in the order it is given them, so each line's text lands on its own slice.
 * The assets, and the arithmetic behind every number here, come from
 * {@code tools/generate_card_glyphs.py}.
 *
 * <p>The framed lore is laid out as:
 *
 * <pre>
 *   (item name)   blank, see {@link CardTooltipStyle#name}
 *   header        the art's top cap, ornament included, and no text
 *   title         {@link #TITLE_LINE}
 *   body ...      one slice per line
 *   bottom        the bottom cap
 *   (blank)       {@link #BOTTOM_EXTRA_LINES} of them
 * </pre>
 *
 * <p>The header line is there to make room. The client positions a tooltip from
 * its line count alone and draws nothing above the box that count reserves, and
 * the blank name slot leaves 15px of it above the first lore line. The top cap
 * is 19 rows before the title even starts, so with the title as the first lore
 * line the ornament either collided with the name or rose out of the box and
 * was cut off at the top of the screen. One more line puts the whole cap inside.
 *
 * <p>The blank lines after the bottom cap do the same at the other end: the cap
 * is taller than a line, and without them it overhangs the box and the F3+H
 * lines that vanilla appends print on top of it.
 */
public final class CardFrame {

    /** The font the frame glyphs and spaces live in. */
    public static final Key FONT = Key.key("sf", "card_frame");

    /** Where the title lands in framed lore, after the header line. */
    public static final int TITLE_LINE = 1;

    /**
     * Blank lines after the bottom cap. The generator works this out from the
     * art and refuses to write assets if it disagrees.
     */
    static final int BOTTOM_EXTRA_LINES = 1;

    /** Steps from the text column back to the box's left edge. */
    static final String IN = "\uE000";

    /** Steps from the end of a frame glyph forward to the text column. */
    static final String OUT = "\uE001";

    /**
     * IN for a hover: one pixel further left, because vanilla's panel, which a
     * hover cannot turn off, is drawn from there.
     */
    static final String HOVER_IN = "\uE002";

    /** Ends a hover's bottom line so the tooltip box is the frame's width. */
    static final String TRIM = "\uE003";

    private static final int HEADER = 0;
    private static final int BODY = 1;
    private static final int BOTTOM = 2;

    /**
     * The frame's own style: no lore colour, no italic and no shadow.
     *
     * <p>White because a glyph's colour multiplies its texture, and lore
     * defaults to dark purple. Italic would shear the art, bold would draw it
     * twice offset by a pixel, and a shadow would put a dark copy of the panel
     * one pixel down and right of it.
     */
    private static final Style FRAME_STYLE = Style.style()
        .font(FONT)
        .color(NamedTextColor.WHITE)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, false)
        .shadowColor(ShadowColor.none())
        .build();

    private CardFrame() {}

    /**
     * Frames rendered lore.
     *
     * @param lines the lore, title first, already parsed
     */
    public static List<Component> wrap(Tier tier, List<Component> lines) {
        Tier t = tier == null ? Tier.SIMPLE : tier;
        List<Component> out = new ArrayList<>(lines.size() + 2 + BOTTOM_EXTRA_LINES);
        out.add(frame(IN + glyph(t, HEADER) + OUT));
        for (Component line : lines) {
            out.add(Component.text().append(frame(IN + glyph(t, BODY) + OUT)).append(line).build());
        }
        // No OUT: the line's width is then the frame's, which is what vanilla
        // measures the tooltip by when it keeps it on screen.
        out.add(frame(IN + glyph(t, BOTTOM)));
        for (int i = 0; i < BOTTOM_EXTRA_LINES; i++) {
            out.add(Component.empty());
        }
        return out;
    }

    /**
     * Frames the lines of a hover tooltip.
     *
     * <p>A hover has no tooltip style, so vanilla's panel is drawn behind it
     * whatever the item or text asks for, and the frame hides it by covering it.
     * The lines step one pixel further left than a tooltip's, the bottom line is
     * trimmed so the box ends at the frame's right edge, and no blank line
     * follows the bottom cap, since the box would grow past the cap to hold it.
     * A line wider than the frame still widens the box, and the panel shows
     * beside it.
     *
     * @param lines the hover's lines, first at the top
     */
    public static List<Component> wrapHover(Tier tier, List<Component> lines) {
        Tier t = tier == null ? Tier.SIMPLE : tier;
        List<Component> out = new ArrayList<>(lines.size() + 2);
        out.add(frame(HOVER_IN + glyph(t, HEADER) + OUT));
        for (Component line : lines) {
            out.add(Component.text().append(frame(HOVER_IN + glyph(t, BODY) + OUT)).append(line).build());
        }
        out.add(frame(HOVER_IN + glyph(t, BOTTOM) + TRIM));
        return out;
    }

    /**
     * A card's lore swapped for other lines, keeping its title and its panel.
     *
     * <p>For menus that show a copy of a card with their own description. The
     * copy keeps the card's tooltip style and blank name, so unframed lines on
     * it would print with no panel behind them and nothing naming the card.
     *
     * @param current the card's lore as it stands, framed or not
     * @param lines   what to show under the title, already parsed
     */
    public static List<Component> rewrap(Tier tier, List<Component> current, List<Component> lines) {
        List<Component> out = new ArrayList<>(lines.size() + 1);
        Component title = title(current);
        if (title != null) {
            out.add(title);
        }
        out.addAll(lines);
        return wrap(tier, out);
    }

    /**
     * The title line of a card's lore, frame removed, or null for no lore.
     *
     * <p>Also reads lore written before the frame, where the title was line 0:
     * a card is only re-rendered when something writes it, so both layouts are
     * in circulation.
     */
    public static Component title(List<Component> lore) {
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        if (FONT.equals(lore.get(0).font()) && lore.size() > TITLE_LINE) {
            return unwrap(lore.get(TITLE_LINE));
        }
        return lore.get(0);
    }

    /**
     * A framed line with its frame taken off, or the line itself if it has none.
     *
     * <p>For anything that shows a lore line somewhere other than the tooltip,
     * where the glyphs would print as a run of boxes.
     */
    public static Component unwrap(Component line) {
        if (line == null) {
            return Component.empty();
        }
        List<Component> children = line.children();
        if (children.isEmpty() || !FONT.equals(children.get(0).font())) {
            return line;
        }
        return Component.text().append(children.subList(1, children.size())).build();
    }

    /**
     * The glyph for one tier's slice: {@code 0xE100 + ordinal * 0x10 + kind}, the
     * same allocation the generator uses. Keyed on the ordinal, so a reordered
     * {@link Tier} re-points every card at another tier's art.
     */
    private static char glyph(Tier tier, int kind) {
        return (char) (0xE100 + tier.ordinal() * 0x10 + kind);
    }

    private static Component frame(String chars) {
        return Component.text(chars, FRAME_STYLE);
    }
}
