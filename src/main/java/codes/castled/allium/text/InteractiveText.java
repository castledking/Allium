package codes.castled.allium.text;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.chat.ComponentSerializer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Operator-written text with click and hover events, for {@code customtext.txt}. Two forms:
 * <ul>
 *   <li>MiniMessage-style tags inside ordinary Allium formatting, as the Essentials fork's text
 *       files take them: {@code <click:run_command:/spawn>}, {@code suggest_command},
 *       {@code open_url} (http/https only) and {@code copy_to_clipboard}, closed by
 *       {@code </click>}; {@code <hover:show_text:'&aText'>} closed by {@code </hover>}. A value
 *       may be quoted with {@code '} or {@code "}, and {@code \n} in hover text is a new line.
 *       Colours and decorations carry across tag boundaries.</li>
 *   <li>A whole line of vanilla JSON text, as {@code /tellraw} takes it
 *       ({@code {"text":"Vote","clickEvent":{...}}} or a {@code [...]} array).</li>
 * </ul>
 * Anything else, including unknown tags, is formatted as usual.
 */
public final class InteractiveText {

    /** Region boundaries become supplementary private-use code points the formatter keeps as glyphs. */
    private static final int MARKER_BASE = 0xF0000;
    private static final int MAX_TAGS = 0xFFFD;

    private InteractiveText() {
    }

    private record Tag(boolean open, boolean click, ClickEvent clickEvent, HoverEvent hoverEvent) {
    }

    private record Scan(String marked, List<Tag> tags) {
    }

    /** Components for a player: the JSON line itself, or the formatted line with its click and hover regions. */
    public static BaseComponent[] components(String line) {
        BaseComponent[] json = json(line);
        if (json != null) {
            return json;
        }
        Scan scan = scan(line);
        FormattedText text = TextFormatter.parse(scan.marked());
        List<BaseComponent> out = new ArrayList<>();
        Deque<ClickEvent> clicks = new ArrayDeque<>();
        Deque<HoverEvent> hovers = new ArrayDeque<>();
        List<FormattedText.StyledGlyph> run = new ArrayList<>();
        for (FormattedText.StyledGlyph glyph : text.glyphs()) {
            int index = glyph.codePoint() - MARKER_BASE;
            if (index < 0 || index >= scan.tags().size()) {
                run.add(glyph);
                continue;
            }
            flush(out, run, clicks.peek(), hovers.peek());
            Tag tag = scan.tags().get(index);
            if (tag.click()) {
                if (tag.open()) clicks.push(tag.clickEvent());
                else if (!clicks.isEmpty()) clicks.pop();
            } else {
                if (tag.open()) hovers.push(tag.hoverEvent());
                else if (!hovers.isEmpty()) hovers.pop();
            }
        }
        flush(out, run, clicks.peek(), hovers.peek());
        return out.toArray(new BaseComponent[0]);
    }

    /** The visible text with its formatting, for the console, which cannot click. */
    public static FormattedText formatted(String line) {
        BaseComponent[] json = json(line);
        if (json != null) {
            StringBuilder legacy = new StringBuilder();
            for (BaseComponent component : json) {
                legacy.append(component.toLegacyText());
            }
            return TextFormatter.parse(legacy.toString().replace('§', '&'));
        }
        Scan scan = scan(line);
        List<FormattedText.StyledGlyph> kept = new ArrayList<>();
        for (FormattedText.StyledGlyph glyph : TextFormatter.parse(scan.marked()).glyphs()) {
            int index = glyph.codePoint() - MARKER_BASE;
            if (index < 0 || index >= scan.tags().size()) {
                kept.add(glyph);
            }
        }
        return new FormattedText(kept);
    }

    private static void flush(List<BaseComponent> out, List<FormattedText.StyledGlyph> run, ClickEvent click, HoverEvent hover) {
        if (run.isEmpty()) {
            return;
        }
        for (BaseComponent component : SpigotComponents.of(new FormattedText(run))) {
            if (click != null) component.setClickEvent(click);
            if (hover != null) component.setHoverEvent(hover);
            out.add(component);
        }
        run.clear();
    }

    private static BaseComponent[] json(String line) {
        String trimmed = line == null ? "" : line.trim();
        boolean object = trimmed.startsWith("{") && trimmed.endsWith("}");
        boolean array = false;
        if (trimmed.length() > 1 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // "[Rules]" is text; a JSON array starts with a component, a string or another array.
            String inner = trimmed.substring(1).trim();
            array = !inner.isEmpty() && "{[\"".indexOf(inner.charAt(0)) >= 0;
        }
        if (!object && !array) {
            return null;
        }
        try {
            BaseComponent[] parsed = ComponentSerializer.parse(trimmed);
            return parsed == null || parsed.length == 0 ? null : parsed;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** Replaces each click/hover tag with a marker and records what it opens or closes. */
    private static Scan scan(String line) {
        String text = line == null ? "" : line;
        StringBuilder out = new StringBuilder(text.length());
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<' && tags.size() < MAX_TAGS) {
                int consumed = tag(text, i, tags);
                if (consumed > 0) {
                    out.appendCodePoint(MARKER_BASE + tags.size() - 1);
                    i += consumed;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new Scan(out.toString(), tags);
    }

    /** Length of the tag at {@code start} when it is a click/hover tag (recorded in {@code tags}), else 0. */
    private static int tag(String text, int start, List<Tag> tags) {
        String rest = text.substring(start);
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.startsWith("</click>")) {
            tags.add(new Tag(false, true, null, null));
            return 8;
        }
        if (lower.startsWith("</hover>")) {
            tags.add(new Tag(false, false, null, null));
            return 8;
        }
        boolean click = lower.startsWith("<click:");
        boolean hover = lower.startsWith("<hover:");
        if (!click && !hover) {
            return 0;
        }
        int colon = rest.indexOf(':', 7);
        if (colon < 0) {
            return 0;
        }
        String action = rest.substring(7, colon).trim().toLowerCase(Locale.ROOT);
        int valueStart = colon + 1;
        String value;
        int end;
        if (valueStart < rest.length() && (rest.charAt(valueStart) == '\'' || rest.charAt(valueStart) == '"')) {
            char quote = rest.charAt(valueStart);
            int close = rest.indexOf(quote, valueStart + 1);
            if (close < 0 || close + 1 >= rest.length() || rest.charAt(close + 1) != '>') {
                return 0;
            }
            value = rest.substring(valueStart + 1, close);
            end = close + 2;
        } else {
            int close = rest.indexOf('>', valueStart);
            if (close < 0) {
                return 0;
            }
            value = rest.substring(valueStart, close);
            end = close + 1;
        }
        if (click) {
            ClickEvent.Action kind = switch (action) {
                case "run_command" -> ClickEvent.Action.RUN_COMMAND;
                case "suggest_command" -> ClickEvent.Action.SUGGEST_COMMAND;
                case "open_url" -> ClickEvent.Action.OPEN_URL;
                case "copy_to_clipboard" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
                default -> null;
            };
            if (kind == null || kind == ClickEvent.Action.OPEN_URL && !value.matches("(?i)https?://\\S+")) {
                return 0;
            }
            tags.add(new Tag(true, true, new ClickEvent(kind, value), null));
            return end;
        }
        if (!action.equals("show_text")) {
            return 0;
        }
        @SuppressWarnings("deprecation")
        HoverEvent event = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                SpigotComponents.of(TextFormatter.parse(value.replace("\\n", "\n"))));
        tags.add(new Tag(true, false, null, event));
        return end;
    }
}
