package codes.castled.allium.tradingcards.item;

import codes.castled.allium.frames.FrameService;
import codes.castled.allium.frames.FramesConfig;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

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
 * text, so colour codes around it do not matter. It names a frame from
 * frames.yml or a trading card tier, in square or angle brackets.
 * {@code [frame:auto]}, or {@code [frame]} on its own, is the frame of the
 * player it is drawn for; a name nobody configured gets the fallback frame.
 */
public final class FrameMarker {

    private static final Pattern MARKER =
        Pattern.compile("^\\s*[\\[<]frame(?::\\s*([a-z0-9_\\-]+)\\s*)?[\\]>]\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** The font {@code /frame build} writes sprite frames into. */
    private static final Key FRAMES_FONT = Key.key("allium", "frames");

    private FrameMarker() {}

    /** The line a marker sits on, and the frame it names ({@code auto} included). */
    public record Match(int line, String name) {}

    /** The marker in this lore, or null when it has none. */
    public static Match find(List<Component> lore) {
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        // Already framed, as a trading card is: a second frame would wrap the
        // first one's header and bottom cap as if they were text.
        Key font = lore.get(0) == null ? null : lore.get(0).font();
        if (CardFrame.FONT.equals(font) || FRAMES_FONT.equals(font)) {
            return null;
        }
        for (int i = 0; i < lore.size(); i++) {
            Component line = lore.get(i);
            if (line == null) {
                continue;
            }
            String text = PLAIN.serialize(line);
            // Cheap reject before the regex: most lines on most items are not markers.
            if (text.indexOf('[') < 0 && text.indexOf('<') < 0) {
                continue;
            }
            Matcher m = MARKER.matcher(text);
            if (m.matches()) {
                return new Match(i, m.group(1) == null ? "auto" : FramesConfig.name(m.group(1)));
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
     * @param viewer whose frame {@code auto} means, or null for the fallback
     */
    public static List<Component> frame(Component title, List<Component> lore, Match match,
                                        Player viewer) {
        List<Component> lines = new ArrayList<>(lore.size());
        lines.add(title);
        for (int i = 0; i < lore.size(); i++) {
            if (i != match.line()) {
                lines.add(lore.get(i));
            }
        }
        return FrameService.get().frame(match.name(), viewer).item(lines);
    }

    // ==================== hovers ====================

    /**
     * Frames every hover in a message whose text carries a marker line.
     *
     * <p>Returns the message itself when nothing changed, so a caller can tell
     * cheaply whether there is anything to send differently.
     */
    public static Component frameHovers(Component message, Player viewer) {
        if (message == null) {
            return null;
        }
        Component out = message;
        HoverEvent<?> hover = message.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT
            && hover.value() instanceof Component text) {
            Component framed = hoverText(text, viewer);
            if (framed != text) {
                out = out.hoverEvent(HoverEvent.showText(framed));
            }
        }
        List<Component> children = message.children();
        List<Component> next = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component framed = frameHovers(child, viewer);
            if (framed != child) {
                if (next == null) {
                    next = new ArrayList<>(children);
                }
                next.set(i, framed);
            }
        }
        if (next != null) {
            out = out.children(next);
        }
        if (out instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
            List<ComponentLike> args = new ArrayList<>();
            boolean changed = false;
            for (TranslationArgument arg : translatable.arguments()) {
                if (arg.value() instanceof Component component) {
                    Component framed = frameHovers(component, viewer);
                    changed |= framed != component;
                    args.add(framed);
                } else {
                    args.add(arg);
                }
            }
            if (changed) {
                out = translatable.arguments(args);
            }
        }
        return out;
    }

    /**
     * A hover's text framed, or the text itself when it carries no marker.
     *
     * <p>Hover text is one component with newlines in it, so it is cut into lines
     * first. A hover has no name line, so the line after the marker is simply
     * the first line in the frame.
     */
    static Component hoverText(Component text, Player viewer) {
        List<Component> lines = lines(text);
        Match match = find(lines);
        if (match == null) {
            return text;
        }
        List<Component> rest = new ArrayList<>(lines);
        rest.remove(match.line());
        List<Component> framed = FrameService.get().frame(match.name(), viewer).hover(rest);
        var joined = Component.text();
        for (int i = 0; i < framed.size(); i++) {
            if (i > 0) {
                joined.append(Component.newline());
            }
            joined.append(framed.get(i));
        }
        return joined.build();
    }

    /**
     * Cuts a component into the lines its newlines make, each carrying the
     * styles it inherited, so a line moved into the frame looks as it did.
     */
    static List<Component> lines(Component text) {
        List<List<Component>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        split(text, Style.empty(), lines);
        List<Component> out = new ArrayList<>(lines.size());
        for (List<Component> runs : lines) {
            out.add(runs.size() == 1 ? runs.get(0) : Component.text().append(runs).build());
        }
        return out;
    }

    private static void split(Component component, Style inherited, List<List<Component>> lines) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            String[] parts = text.content().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    lines.add(new ArrayList<>());
                }
                if (!parts[i].isEmpty()) {
                    lines.get(lines.size() - 1).add(Component.text(parts[i], style));
                }
            }
        } else {
            // A translation, keybind or score cannot hold a newline of its own;
            // it moves into its line whole.
            lines.get(lines.size() - 1).add(component.children(List.of()).style(style));
        }
        for (Component child : component.children()) {
            split(child, style, lines);
        }
    }

    /** The marker line for a tier, as {@code /cards frame} writes it. */
    public static String marker(Tier tier) {
        return "[frame:" + tier.name().toLowerCase(Locale.ROOT) + "]";
    }

}
