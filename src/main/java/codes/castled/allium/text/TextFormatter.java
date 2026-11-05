package codes.castled.allium.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Parses and formats Allium text strings containing legacy {@code &}/{@code §} codes,
 * hex colors ({@code &#RRGGBB}, {@code #RRGGBB}, {@code §x§r§r§g§g§b§b}, {@code <#RRGGBB>}),
 * multi-stop {@code <gradient:#c1:#c2[:#c3...]>...</gradient>} tags, and
 * {@code <rainbow[:phase]>...</rainbow>} tags.
 */
public final class TextFormatter {

    private static final int[] LEGACY_RGB = new int[]{
            0x000000, // 0: BLACK
            0x0000AA, // 1: DARK_BLUE
            0x00AA00, // 2: DARK_GREEN
            0x00AAAA, // 3: DARK_AQUA
            0xAA0000, // 4: DARK_RED
            0xAA00AA, // 5: DARK_PURPLE
            0xFFAA00, // 6: GOLD
            0xAAAAAA, // 7: GRAY
            0x555555, // 8: DARK_GRAY
            0x5555FF, // 9: BLUE
            0x55FF55, // a: GREEN
            0x55FFFF, // b: AQUA
            0xFF5555, // c: RED
            0xFF55FF, // d: LIGHT_PURPLE
            0xFFFF55, // e: YELLOW
            0xFFFFFF  // f: WHITE
    };

    private TextFormatter() {
    }

    /**
     * Parses a formatted string into a platform-neutral {@link FormattedText}.
     */
    public static FormattedText parse(String input) {
        return parse(input, null);
    }

    /**
     * Parses a formatted string with an optional root {@link GlyphColorSource}
     * (used by S01.5b animated effects to supply {@code color(glyphIndex, length, timeMs, seed)}).
     */
    public static FormattedText parse(String input, GlyphColorSource rootColorSource) {
        if (input == null || input.isEmpty()) {
            return FormattedText.EMPTY;
        }

        List<MutableGlyph> mutableGlyphs = new ArrayList<>(input.length());
        Deque<SpanFrame> stack = new ArrayDeque<>();
        stack.push(new SpanFrame("root", rootColorSource, -1, '\0', (byte) 0));

        int len = input.length();
        int i = 0;
        while (i < len) {
            char c = input.charAt(i);

            // 1. Check for angle-bracket tags: <gradient:...>, </gradient>, <rainbow...>, </rainbow>, <#rrggbb>, <reset>, etc.
            if (c == '<') {
                int closeIdx = input.indexOf('>', i + 1);
                if (closeIdx > i + 1) {
                    String tagContent = input.substring(i + 1, closeIdx);
                    if (tryApplyTag(tagContent, stack)) {
                        i = closeIdx + 1;
                        continue;
                    }
                }
            }

            // 2. Check for & or § legacy / hex escape sequences
            if ((c == '&' || c == '§') && i + 1 < len) {
                char next = input.charAt(i + 1);

                // &#RRGGBB or §#RRGGBB
                if (next == '#' && i + 8 <= len && isHexRun(input, i + 2, 6)) {
                    int rgb = Integer.parseInt(input.substring(i + 2, i + 8), 16);
                    SpanFrame top = stack.peek();
                    top.setInlineHex(rgb);
                    i += 8;
                    continue;
                }

                // &x&R&R&G&G&B&B or §x§R§R§G§G§B§B (14 chars total)
                if ((next == 'x' || next == 'X') && i + 14 <= len) {
                    int rgb = tryParseBungeeHex(input, i + 2);
                    if (rgb != -1) {
                        SpanFrame top = stack.peek();
                        top.setInlineHex(rgb);
                        i += 14;
                        continue;
                    }
                }

                // Standard legacy color or decoration code
                char lower = Character.toLowerCase(next);
                int colorIdx = legacyColorIndex(lower);
                if (colorIdx != -1) {
                    SpanFrame top = stack.peek();
                    top.setInlineLegacy(lower, LEGACY_RGB[colorIdx]);
                    i += 2;
                    continue;
                }

                byte decFlag = decorationFlag(lower);
                if (decFlag != 0) {
                    SpanFrame top = stack.peek();
                    top.decorations |= decFlag;
                    i += 2;
                    continue;
                }

                if (lower == 'r') {
                    resetAll(stack);
                    i += 2;
                    continue;
                }
            }

            // 3. Check for standalone #RRGGBB hex code
            if (c == '#' && i + 7 <= len && isHexRun(input, i + 1, 6)) {
                int rgb = Integer.parseInt(input.substring(i + 1, i + 7), 16);
                SpanFrame top = stack.peek();
                top.setInlineHex(rgb);
                i += 7;
                continue;
            }

            // 4. Ordinary visible code point
            int cp = input.codePointAt(i);
            SpanFrame top = stack.peek();
            MutableGlyph glyph = new MutableGlyph(cp, top.inlineRgb, top.inlineLegacyChar, top.decorations);
            if (top.inlineRgb == -1 && top.inlineLegacyChar == '\0' && top.tagColorSource != null) {
                top.ownedGlyphs.add(glyph);
            }
            mutableGlyphs.add(glyph);
            i += Character.charCount(cp);
        }

        // Flush any remaining open frames on the stack
        while (!stack.isEmpty()) {
            stack.pop().resolveOwnedGlyphs();
        }

        if (mutableGlyphs.isEmpty()) {
            return FormattedText.EMPTY;
        }

        List<FormattedText.StyledGlyph> result = new ArrayList<>(mutableGlyphs.size());
        for (MutableGlyph mg : mutableGlyphs) {
            result.add(new FormattedText.StyledGlyph(mg.codePoint, mg.rgb, mg.legacyColorChar, mg.decorations));
        }
        return new FormattedText(result);
    }

    /**
     * Convenience method to format a string directly to legacy {@code §} + {@code §x} format.
     */
    public static String toLegacy(String input) {
        return parse(input).toLegacy();
    }

    /**
     * Convenience method to strip all formatting and tags from a string.
     */
    public static String stripFormatting(String input) {
        return parse(input).toPlainText();
    }

    private static boolean tryApplyTag(String rawTag, Deque<SpanFrame> stack) {
        String lower = rawTag.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty()) {
            return false;
        }

        // Reset tag
        if (lower.equals("reset") || lower.equals("r") || lower.equals("/reset")) {
            resetAll(stack);
            return true;
        }

        // Closing tags
        if (lower.startsWith("/")) {
            String closingName = lower.substring(1);
            if (closingName.startsWith("gradient")) {
                popMatchingFrame(stack, "gradient");
                return true;
            }
            if (closingName.startsWith("rainbow")) {
                popMatchingFrame(stack, "rainbow");
                return true;
            }
            if (closingName.startsWith("#") && closingName.length() == 7 && isHexRun(closingName, 1, 6)) {
                popMatchingFrame(stack, "hex");
                return true;
            }
            byte dec = tagDecorationFlag(closingName);
            if (dec != 0) {
                SpanFrame top = stack.peek();
                top.decorations &= ~dec;
                return true;
            }
            return false;
        }

        // Opening <gradient:#c1:#c2[:#c3...]>
        if (lower.startsWith("gradient:")) {
            String argsPart = lower.substring("gradient:".length());
            String[] parts = argsPart.split(":");
            if (parts.length < 2) {
                return false;
            }
            int[] stops = new int[parts.length];
            for (int idx = 0; idx < parts.length; idx++) {
                int parsed = parseHexStop(parts[idx]);
                if (parsed == -1) {
                    return false;
                }
                stops[idx] = parsed;
            }
            SpanFrame parent = stack.peek();
            stack.push(new SpanFrame("gradient", GlyphColorSource.gradient(stops), -1, '\0', parent.decorations));
            return true;
        }

        // Opening <rainbow> or <rainbow:phase>
        if (lower.equals("rainbow") || lower.startsWith("rainbow:")) {
            double phase = 0.0;
            boolean reversed = false;
            if (lower.startsWith("rainbow:")) {
                String arg = lower.substring("rainbow:".length()).trim();
                if (arg.startsWith("!")) {
                    reversed = true;
                    arg = arg.substring(1).trim();
                }
                if (!arg.isEmpty()) {
                    try {
                        double val = Double.parseDouble(arg);
                        // Support both normalized [0..1] and integer/degree phases
                        phase = (Math.abs(val) > 1.0) ? (val / 360.0) : val;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            }
            SpanFrame parent = stack.peek();
            stack.push(new SpanFrame("rainbow", GlyphColorSource.rainbow(phase, reversed), -1, '\0', parent.decorations));
            return true;
        }

        // Opening <#RRGGBB>
        if (lower.startsWith("#") && lower.length() == 7 && isHexRun(lower, 1, 6)) {
            int rgb = Integer.parseInt(lower.substring(1, 7), 16);
            SpanFrame parent = stack.peek();
            stack.push(new SpanFrame("hex", GlyphColorSource.solid(rgb), -1, '\0', parent.decorations));
            return true;
        }

        // Decoration tags (<bold>, <b>, <italic>, <i>, <underlined>, <u>, <strikethrough>, <st>, <obfuscated>, <obf>)
        byte dec = tagDecorationFlag(lower);
        if (dec != 0) {
            SpanFrame top = stack.peek();
            top.decorations |= dec;
            return true;
        }

        return false;
    }

    private static void popMatchingFrame(Deque<SpanFrame> stack, String expectedType) {
        if (stack.size() <= 1) {
            return;
        }
        for (SpanFrame frame : stack) {
            if (frame.tagType.equals(expectedType)) {
                while (stack.size() > 1) {
                    SpanFrame popped = stack.pop();
                    popped.resolveOwnedGlyphs();
                    if (popped.tagType.equals(expectedType)) {
                        break;
                    }
                }
                return;
            }
        }
    }

    private static void resetAll(Deque<SpanFrame> stack) {
        while (!stack.isEmpty()) {
            stack.pop().resolveOwnedGlyphs();
        }
        stack.push(new SpanFrame("root", null, -1, '\0', (byte) 0));
    }

    private static int parseHexStop(String token) {
        String s = token.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6 && isHexRun(s, 0, 6)) {
            return Integer.parseInt(s, 16);
        }
        return -1;
    }

    private static int tryParseBungeeHex(String s, int offset) {
        int rgb = 0;
        for (int k = 0; k < 6; k++) {
            char prefix = s.charAt(offset + k * 2);
            char hexChar = s.charAt(offset + k * 2 + 1);
            if (prefix != '&' && prefix != '§') {
                return -1;
            }
            int digit = Character.digit(hexChar, 16);
            if (digit == -1) {
                return -1;
            }
            rgb = (rgb << 4) | digit;
        }
        return rgb;
    }

    private static boolean isHexRun(String s, int start, int count) {
        if (start + count > s.length()) {
            return false;
        }
        for (int idx = 0; idx < count; idx++) {
            if (Character.digit(s.charAt(start + idx), 16) == -1) {
                return false;
            }
        }
        return true;
    }

    public static int legacyColorIndex(char lowerChar) {
        if (lowerChar >= '0' && lowerChar <= '9') {
            return lowerChar - '0';
        }
        if (lowerChar >= 'a' && lowerChar <= 'f') {
            return 10 + (lowerChar - 'a');
        }
        return -1;
    }

    public static int legacyColorRgb(char lowerChar) {
        int idx = legacyColorIndex(lowerChar);
        return idx != -1 ? LEGACY_RGB[idx] : -1;
    }

    private static byte decorationFlag(char lowerChar) {
        return switch (lowerChar) {
            case 'k' -> FormattedText.DEC_OBFUSCATED;
            case 'l' -> FormattedText.DEC_BOLD;
            case 'm' -> FormattedText.DEC_STRIKETHROUGH;
            case 'n' -> FormattedText.DEC_UNDERLINED;
            case 'o' -> FormattedText.DEC_ITALIC;
            default -> 0;
        };
    }

    private static byte tagDecorationFlag(String lowerTag) {
        return switch (lowerTag) {
            case "bold", "b" -> FormattedText.DEC_BOLD;
            case "italic", "i", "em" -> FormattedText.DEC_ITALIC;
            case "underlined", "u" -> FormattedText.DEC_UNDERLINED;
            case "strikethrough", "st" -> FormattedText.DEC_STRIKETHROUGH;
            case "obfuscated", "obf" -> FormattedText.DEC_OBFUSCATED;
            default -> 0;
        };
    }

    private static final class MutableGlyph {
        final int codePoint;
        int rgb;
        char legacyColorChar;
        final byte decorations;

        MutableGlyph(int codePoint, int rgb, char legacyColorChar, byte decorations) {
            this.codePoint = codePoint;
            this.rgb = rgb;
            this.legacyColorChar = legacyColorChar;
            this.decorations = decorations;
        }
    }

    private static final class SpanFrame {
        final String tagType;
        final GlyphColorSource tagColorSource;
        int inlineRgb;
        char inlineLegacyChar;
        byte decorations;
        final List<MutableGlyph> ownedGlyphs = new ArrayList<>();

        SpanFrame(String tagType, GlyphColorSource tagColorSource, int inlineRgb, char inlineLegacyChar, byte decorations) {
            this.tagType = tagType;
            this.tagColorSource = tagColorSource;
            this.inlineRgb = inlineRgb;
            this.inlineLegacyChar = inlineLegacyChar;
            this.decorations = decorations;
        }

        void setInlineHex(int rgb) {
            this.inlineRgb = rgb & 0xFFFFFF;
            this.inlineLegacyChar = '\0';
            // In Minecraft, inline color codes reset active decorations
            this.decorations = 0;
        }

        void setInlineLegacy(char legacyChar, int rgb) {
            this.inlineRgb = rgb & 0xFFFFFF;
            this.inlineLegacyChar = legacyChar;
            // In Minecraft, inline color codes reset active decorations
            this.decorations = 0;
        }

        void resolveOwnedGlyphs() {
            if (tagColorSource == null || ownedGlyphs.isEmpty()) {
                return;
            }
            int total = ownedGlyphs.size();
            for (int idx = 0; idx < total; idx++) {
                MutableGlyph glyph = ownedGlyphs.get(idx);
                glyph.rgb = tagColorSource.colorAt(idx, total) & 0xFFFFFF;
                glyph.legacyColorChar = '\0';
            }
            ownedGlyphs.clear();
        }
    }
}
