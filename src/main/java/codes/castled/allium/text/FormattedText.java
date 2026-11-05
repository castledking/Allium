package codes.castled.allium.text;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral intermediate representation of formatted text.
 *
 * <p>Contains no Paper or Adventure types in its signatures or fields, allowing
 * shared services to pass formatted text safely across Spigot and Paper runtimes.
 */
public final class FormattedText {

    public static final byte DEC_OBFUSCATED = 1;
    public static final byte DEC_BOLD = 1 << 1;
    public static final byte DEC_STRIKETHROUGH = 1 << 2;
    public static final byte DEC_UNDERLINED = 1 << 3;
    public static final byte DEC_ITALIC = 1 << 4;

    public static final FormattedText EMPTY = new FormattedText(Collections.emptyList());

    private final List<StyledGlyph> glyphs;

    public FormattedText(List<StyledGlyph> glyphs) {
        this.glyphs = glyphs.isEmpty() ? Collections.emptyList() : List.copyOf(glyphs);
    }

    public List<StyledGlyph> glyphs() {
        return glyphs;
    }

    public boolean isEmpty() {
        return glyphs.isEmpty();
    }

    public int length() {
        return glyphs.size();
    }

    /**
     * Renders this formatted text to a legacy Bukkit/Spigot string using {@code §} codes
     * and {@code §x§r§r§g§g§b§b} hex sequences.
     */
    public String toLegacy() {
        if (glyphs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(glyphs.size() * 4);
        int prevRgb = -1;
        char prevLegacyChar = '\0';
        byte prevDec = 0;

        for (StyledGlyph g : glyphs) {
            boolean colorRemoved = (prevRgb != -1 || prevLegacyChar != '\0')
                    && (g.rgb == -1 && g.legacyColorChar == '\0');
            boolean decRemoved = (prevDec & ~g.decorations) != 0;
            boolean colorChanged = (prevRgb != g.rgb) || (prevLegacyChar != g.legacyColorChar);

            if (colorRemoved || (decRemoved && g.rgb == -1 && g.legacyColorChar == '\0')) {
                sb.append('§').append('r');
                prevRgb = -1;
                prevLegacyChar = '\0';
                prevDec = 0;
                colorChanged = (g.rgb != -1 || g.legacyColorChar != '\0');
            }

            if (colorChanged || (decRemoved && (g.rgb != -1 || g.legacyColorChar != '\0'))) {
                appendColor(sb, g.rgb, g.legacyColorChar);
                prevRgb = g.rgb;
                prevLegacyChar = g.legacyColorChar;
                // Emitting a color code in Minecraft resets client decorations
                prevDec = 0;
            }

            byte addedDec = (byte) (g.decorations & ~prevDec);
            if (addedDec != 0) {
                appendDecorations(sb, addedDec);
                prevDec = g.decorations;
            }

            sb.appendCodePoint(g.codePoint);
        }

        return sb.toString();
    }

    /**
     * Returns the plain visible text with all colors and decorations stripped.
     */
    public String toPlainText() {
        if (glyphs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(glyphs.size());
        for (StyledGlyph g : glyphs) {
            sb.appendCodePoint(g.codePoint);
        }
        return sb.toString();
    }

    public static void appendHexColor(StringBuilder sb, int rgb) {
        sb.append('§').append('x');
        for (int shift = 20; shift >= 0; shift -= 4) {
            int nibble = (rgb >> shift) & 0xF;
            sb.append('§').append(Character.forDigit(nibble, 16));
        }
    }

    private static void appendColor(StringBuilder sb, int rgb, char legacyColorChar) {
        if (legacyColorChar != '\0') {
            sb.append('§').append(legacyColorChar);
        } else if (rgb != -1) {
            appendHexColor(sb, rgb);
        }
    }

    private static void appendDecorations(StringBuilder sb, byte dec) {
        if ((dec & DEC_OBFUSCATED) != 0) sb.append('§').append('k');
        if ((dec & DEC_BOLD) != 0) sb.append('§').append('l');
        if ((dec & DEC_STRIKETHROUGH) != 0) sb.append('§').append('m');
        if ((dec & DEC_UNDERLINED) != 0) sb.append('§').append('n');
        if ((dec & DEC_ITALIC) != 0) sb.append('§').append('o');
    }

    public static final class StyledGlyph {
        private final int codePoint;
        private final int rgb;
        private final char legacyColorChar;
        private final byte decorations;

        public StyledGlyph(int codePoint, int rgb, char legacyColorChar, byte decorations) {
            this.codePoint = codePoint;
            this.rgb = rgb;
            this.legacyColorChar = legacyColorChar;
            this.decorations = decorations;
        }

        public int codePoint() {
            return codePoint;
        }

        /**
         * Returns the 24-bit RGB color ({@code 0x000000..0xFFFFFF}), or {@code -1} if uncolored.
         */
        public int rgb() {
            return rgb;
        }

        /**
         * Returns the legacy color character ({@code '0'..'9'}, {@code 'a'..'f'}), or {@code '\0'} if hex/uncolored.
         */
        public char legacyColorChar() {
            return legacyColorChar;
        }

        public byte decorations() {
            return decorations;
        }

        public boolean hasDecoration(byte flag) {
            return (decorations & flag) != 0;
        }

        public boolean hasSameStyle(StyledGlyph other) {
            return other != null
                    && this.rgb == other.rgb
                    && this.legacyColorChar == other.legacyColorChar
                    && this.decorations == other.decorations;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StyledGlyph that)) return false;
            return codePoint == that.codePoint
                    && rgb == that.rgb
                    && legacyColorChar == that.legacyColorChar
                    && decorations == that.decorations;
        }

        @Override
        public int hashCode() {
            return Objects.hash(codePoint, rgb, legacyColorChar, decorations);
        }
    }
}
