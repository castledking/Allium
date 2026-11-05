package codes.castled.allium.text;

import java.util.Arrays;

/**
 * Computes the 24-bit RGB color ({@code 0xRRGGBB}) for a visible glyph at
 * {@code glyphIndex} within a span of {@code length} visible glyphs.
 *
 * <p>Designed so S01.5b animated effects can bind
 * {@code color(glyphIndex, length, timeMs, seed) -> RGB} to this interface
 * without rewriting the parser or platform renderers.
 */
@FunctionalInterface
public interface GlyphColorSource {

    /**
     * Returns the 24-bit RGB color ({@code 0xRRGGBB}) for the given glyph position.
     *
     * @param glyphIndex zero-based index of the visible glyph within the span
     * @param length     total number of visible glyphs in the span ({@code >= 1})
     * @return 24-bit RGB color value in {@code 0x000000..0xFFFFFF}
     */
    int colorAt(int glyphIndex, int length);

    /**
     * Creates a static solid color source.
     */
    static GlyphColorSource solid(int rgb) {
        int masked = rgb & 0xFFFFFF;
        return (index, length) -> masked;
    }

    /**
     * Creates a multi-stop linear RGB gradient color source.
     *
     * @param stops two or more 24-bit RGB color stops
     */
    static GlyphColorSource gradient(int... stops) {
        if (stops == null || stops.length < 2) {
            throw new IllegalArgumentException("Gradient requires at least 2 color stops");
        }
        int[] copy = Arrays.copyOf(stops, stops.length);
        for (int i = 0; i < copy.length; i++) {
            copy[i] &= 0xFFFFFF;
        }
        return (glyphIndex, length) -> {
            if (length <= 1 || glyphIndex <= 0) {
                return copy[0];
            }
            if (glyphIndex >= length - 1) {
                return copy[copy.length - 1];
            }
            double t = (double) glyphIndex / (double) (length - 1);
            int segments = copy.length - 1;
            double scaled = t * segments;
            int segment = Math.min((int) Math.floor(scaled), segments - 1);
            double localT = scaled - segment;
            return lerpRgb(copy[segment], copy[segment + 1], localT);
        };
    }

    /**
     * Creates a static rainbow color source across the HSV hue wheel.
     *
     * @param phase    normalized phase offset (e.g. {@code 0.0..1.0})
     * @param reversed whether hue progresses in reverse order
     */
    static GlyphColorSource rainbow(double phase, boolean reversed) {
        double normalizedPhase = phase - Math.floor(phase);
        return (glyphIndex, length) -> {
            double step = (length <= 1) ? 0.0 : (double) glyphIndex / (double) length;
            if (reversed) {
                step = 1.0 - step;
            }
            double hue = (step + normalizedPhase) % 1.0;
            if (hue < 0.0) {
                hue += 1.0;
            }
            return hsvToRgb(hue, 1.0, 1.0);
        };
    }

    /**
     * Linearly interpolates between two 24-bit RGB colors with clamped weight in {@code [0, 1]}.
     */
    static int lerpRgb(int rgbA, int rgbB, double weight) {
        double w = Math.max(0.0, Math.min(1.0, weight));
        int rA = (rgbA >> 16) & 0xFF;
        int gA = (rgbA >> 8) & 0xFF;
        int bA = rgbA & 0xFF;

        int rB = (rgbB >> 16) & 0xFF;
        int gB = (rgbB >> 8) & 0xFF;
        int bB = rgbB & 0xFF;

        int r = (int) Math.round(rA + (rB - rA) * w);
        int g = (int) Math.round(gA + (gB - gA) * w);
        int b = (int) Math.round(bA + (bB - bA) * w);

        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Converts HSV values in {@code [0, 1]} to a 24-bit RGB integer ({@code 0xRRGGBB}).
     */
    static int hsvToRgb(double h, double s, double v) {
        double hue = (h - Math.floor(h)) * 6.0;
        int sector = (int) Math.floor(hue);
        double frac = hue - sector;
        double p = v * (1.0 - s);
        double q = v * (1.0 - s * frac);
        double t = v * (1.0 - s * (1.0 - frac));

        double r;
        double g;
        double b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = (int) Math.round(r * 255.0);
        int gi = (int) Math.round(g * 255.0);
        int bi = (int) Math.round(b * 255.0);
        return ((ri & 0xFF) << 16) | ((gi & 0xFF) << 8) | (bi & 0xFF);
    }
}
