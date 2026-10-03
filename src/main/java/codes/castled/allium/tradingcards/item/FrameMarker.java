package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * A card frame asked for by any item, through a marker line in its lore.
 *
 * <p>Other plugins cannot write the frame themselves: each parses its own config
 * text, most only understand {@code &} codes, and none can set a tooltip style
 * or blank an item's name. A lore line reading {@code [frame:fabled]} is
 * something any of them can carry, so the frame is applied on the way to the
 * player instead, to the copy the client draws. The item on the server is never
 * touched, so the plugin that owns it sees exactly what it wrote.
 *
 * <p>The marker can sit on any lore line and is matched on the line's plain
 * text, so colour codes around it do not matter. {@code [frame]} on its own is
 * the simple frame, and so is a tier this does not know.
 */
public final class FrameMarker {

    private static final Pattern MARKER =
        Pattern.compile("^\\s*\\[frame(?::\\s*([a-z_]+)\\s*)?]\\s*$", Pattern.CASE_INSENSITIVE);

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private FrameMarker() {}

    /** The line a marker sits on, and the frame it asks for. */
    public record Match(int line, Tier tier) {}

    /** The marker in this lore, or null when it has none. */
    public static Match find(List<Component> lore) {
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        // Already framed, as a trading card is: a second frame would wrap the
        // first one's header and bottom cap as if they were text.
        if (CardFrame.FONT.equals(lore.get(0).font())) {
            return null;
        }
        for (int i = 0; i < lore.size(); i++) {
            Component line = lore.get(i);
            if (line == null) {
                continue;
            }
            String text = PLAIN.serialize(line);
            // Cheap reject before the regex: most lines on most items are not markers.
            if (text.indexOf('[') < 0) {
                continue;
            }
            Matcher m = MARKER.matcher(text);
            if (m.matches()) {
                return new Match(i, tier(m.group(1)));
            }
        }
        return null;
    }

    /**
     * The framed lore for an item whose lore carries a marker: the title on top,
     * then every other line, in order, without the marker.
     *
     * @param title what the item's name line would have shown, styled as it would
     *              have been drawn there, since the name line itself is blanked
     */
    public static List<Component> frame(Component title, List<Component> lore, Match match) {
        List<Component> lines = new ArrayList<>(lore.size());
        lines.add(title);
        for (int i = 0; i < lore.size(); i++) {
            if (i != match.line()) {
                lines.add(lore.get(i));
            }
        }
        return CardFrame.wrap(match.tier(), lines);
    }

    /** The marker line for a tier, as {@code /cards frame} writes it. */
    public static String marker(Tier tier) {
        return "[frame:" + tier.name().toLowerCase(Locale.ROOT) + "]";
    }

    private static Tier tier(String name) {
        if (name == null) {
            return Tier.SIMPLE;
        }
        try {
            return Tier.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Tier.SIMPLE;
        }
    }
}
