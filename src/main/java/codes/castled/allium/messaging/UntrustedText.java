package codes.castled.allium.messaging;

import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Formats text a player typed (a private message body) without trusting it.
 *
 * <ul>
 *   <li>{@code §} and control characters (including newlines) are always removed: on Spigot a
 *       {@code §} would become real formatting, and a newline could fake another chat line.</li>
 *   <li>Each kind of formatting needs its own permission; without it the sequence is shown
 *       literally, exactly as typed: {@link #COLOR} ({@code &} colour and formatting codes,
 *       {@code <reset>} and decoration tags such as {@code <b>}),
 *       {@link #HEX} ({@code &#RRGGBB}, {@code &x&R&R…}, a bare {@code #RRGGBB} and
 *       {@code <#RRGGBB>}) and {@link #GRADIENT} ({@code <gradient:…>} and {@code <rainbow>}).</li>
 *   <li>Allium's formatter has no click, hover or other interactive tags, so text can only ever be
 *       styled, never made interactive; JSON or other markup is just text.</li>
 * </ul>
 *
 * <p>How: each disallowed sequence's opening character is swapped for a private-use placeholder
 * the formatter treats as an ordinary character, the formatter runs, and the placeholders are
 * turned back into the literal characters.
 */
public final class UntrustedText {

    public static final String COLOR = "allium.msg.color";
    public static final String HEX = "allium.msg.hex";
    public static final String GRADIENT = "allium.msg.gradient";

    private static final char AMP = '';
    private static final char HASH = '';
    private static final char LT = '';
    private static final char SLOT = '';

    public record Allow(boolean color, boolean hex, boolean gradient) {
        public static final Allow NONE = new Allow(false, false, false);

        public static Allow of(Predicate<String> permission) {
            return new Allow(permission.test(COLOR), permission.test(HEX), permission.test(GRADIENT));
        }
    }

    private UntrustedText() {
    }

    /** Removes {@code §}, control characters and the private-use placeholders this class relies on. */
    public static String clean(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' || Character.isISOControl(c) || (c >= '' && c <= '')) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static FormattedText parse(String raw, Allow allow) {
        String neutral = neutralize(clean(raw), allow);
        FormattedText parsed = TextFormatter.parse(neutral);
        List<FormattedText.StyledGlyph> glyphs = new ArrayList<>(parsed.glyphs().size());
        for (FormattedText.StyledGlyph g : parsed.glyphs()) {
            int cp = switch (g.codePoint()) {
                case AMP -> '&';
                case HASH -> '#';
                case LT -> '<';
                default -> g.codePoint();
            };
            glyphs.add(cp == g.codePoint() ? g : new FormattedText.StyledGlyph(cp, g.rgb(), g.legacyColorChar(), g.decorations()));
        }
        return new FormattedText(glyphs);
    }

    /** The cleaned text with every sequence the sender may not use made literal. */
    static String neutralize(String s, Allow allow) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && hexRun(s, i + 2, 6)) {
                    i = keepOrNeutralize(s, i, 8, allow.hex(), out);
                    continue;
                }
                if ((next == 'x' || next == 'X') && bungeeHex(s, i + 2)) {
                    i = keepOrNeutralize(s, i, 14, allow.hex(), out);
                    continue;
                }
                if (TextFormatter.legacyColorIndex(Character.toLowerCase(next)) != -1 || "klmnor".indexOf(Character.toLowerCase(next)) != -1) {
                    i = keepOrNeutralize(s, i, 2, allow.color(), out);
                    continue;
                }
            }
            if (c == '#' && hexRun(s, i + 1, 6)) {
                i = keepOrNeutralize(s, i, 7, allow.hex(), out);
                continue;
            }
            if (c == '<') {
                int close = s.indexOf('>', i + 1);
                if (close > i + 1) {
                    boolean allowed = tagAllowed(s.substring(i + 1, close).trim().toLowerCase(Locale.ROOT), allow);
                    i = keepOrNeutralize(s, i, close + 1 - i, allowed, out);
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Gradient and rainbow tags need {@link #GRADIENT}, {@code #hex} tags {@link #HEX}, and every other
     * tag (reset and the decoration tags such as {@code <b>}) {@link #COLOR}. A tag the formatter does
     * not apply comes out unchanged either way.
     */
    private static boolean tagAllowed(String tag, Allow allow) {
        String name = tag.startsWith("/") ? tag.substring(1) : tag;
        if (name.startsWith("gradient") || name.startsWith("rainbow")) {
            return allow.gradient();
        }
        if (name.startsWith("#")) {
            return allow.hex();
        }
        return allow.color();
    }

    /** Copies {@code s[i, i+length)} as is, or with every formatting trigger in it made literal. */
    private static int keepOrNeutralize(String s, int i, int length, boolean allowed, StringBuilder out) {
        String part = s.substring(i, Math.min(s.length(), i + length));
        if (allowed) {
            out.append(part);
        } else {
            out.append(part.replace('&', AMP).replace('#', HASH).replace('<', LT));
        }
        return i + part.length();
    }

    private static boolean hexRun(String s, int from, int count) {
        if (from + count > s.length()) {
            return false;
        }
        for (int k = from; k < from + count; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean bungeeHex(String s, int from) {
        if (from + 12 > s.length()) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            if (s.charAt(from + 2 * k) != '&' || Character.digit(s.charAt(from + 2 * k + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts {@code body} into a trusted template at {@code {message}}. Body characters without their
     * own colour take the template's colour and decorations at that point.
     */
    public static FormattedText insert(String template, FormattedText body) {
        String marked = template.replace("{message}", String.valueOf(SLOT));
        FormattedText parsed = TextFormatter.parse(marked);
        List<FormattedText.StyledGlyph> out = new ArrayList<>(parsed.glyphs().size() + body.glyphs().size());
        for (FormattedText.StyledGlyph g : parsed.glyphs()) {
            if (g.codePoint() != SLOT) {
                out.add(g);
                continue;
            }
            for (FormattedText.StyledGlyph b : body.glyphs()) {
                boolean ownColor = b.rgb() != -1 || b.legacyColorChar() != '\0';
                out.add(new FormattedText.StyledGlyph(b.codePoint(), ownColor ? b.rgb() : g.rgb(), ownColor ? b.legacyColorChar() : g.legacyColorChar(),
                        b.decorations() != 0 ? b.decorations() : g.decorations()));
            }
        }
        return new FormattedText(out);
    }
}
