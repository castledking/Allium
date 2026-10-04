package codes.castled.allium.frames;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Cuts sprite frames into the per-line glyphs a lore or hover frame is drawn
 * from, and writes them into the resource pack.
 *
 * <p>A tooltip sprite is drawn across a quad 12 pixels bigger than the text on
 * every side. Glyphs cannot stretch, so the panel is drawn here at the frame's
 * width for every line count until its middle repeats, and cut into strips, one
 * per line. A header strip, on the line above the first, holds everything above
 * the text. Identical strips share a glyph, so a long lore costs no more than a
 * short one: past a handful of lines it is the same top strips, one middle strip
 * repeated, and the same bottom strips.
 *
 * <p>Vertically the middle is one row repeated, where the client would stretch
 * or tile the sprite's middle. That is what lets every middle line share a glyph,
 * and it costs only a gradient, if the sprite had one.
 *
 * <p>Two layouts are cut, because the two places a frame is drawn differ:
 *
 * <ul>
 *   <li>Item lore: the title is the first line under the header, and a blank
 *       line after the last carries the bottom of the panel. The item's own
 *       tooltip panel is switched off, so only the art matters.
 *   <li>Chat hovers: vanilla's panel is always drawn and cannot be turned off,
 *       so the frame has to cover it. These sprites are made to sit 12 pixels
 *       around the text with their visible edge 8 pixels in, which is exactly
 *       vanilla's panel, so the first line carries the top of the panel itself
 *       and the last line the bottom. Anything the sprite leaves clear inside
 *       that box is filled with its background colour.
 * </ul>
 */
public final class FrameBuilder {

    static final int LINE = 10;
    /** Rows of the quad above the first text line: the client's own margin. */
    static final int OFFSET = 12;
    /** How far in from the quad's edge vanilla's panel, and these sprites, start. */
    static final int INSET = 8;
    /** Header rows below the first line's top, for clients that add 2px there. */
    static final int OVERHANG = 2;
    /** A glyph's top row lands at line top + 7 - ascent. */
    static final int ASCENT_BASE = 7;

    static final char IN = '\uE000';
    static final char HOVER_IN = '\uE001';

    public record Result(int frames, int glyphs, List<String> problems) {}

    private final Map<String, Integer> glyphIds = new LinkedHashMap<>();
    private final List<Glyph> glyphs = new ArrayList<>();
    private final Map<Integer, Character> outs = new LinkedHashMap<>();
    private char next = '\uE010';

    private record Glyph(char ch, int ascent, BufferedImage image, String file) {}

    /**
     * Builds every sprite frame in the config.
     *
     * @param packRoot the resource pack's root folder
     * @param manifest where the plugin's copy of the result is written
     */
    public static Result build(FramesConfig config, File packRoot, File manifest) throws IOException {
        return new FrameBuilder().run(config, packRoot, manifest);
    }

    private Result run(FramesConfig config, File packRoot, File manifest) throws IOException {
        List<String> problems = new ArrayList<>();
        JsonObject frames = new JsonObject();
        int built = 0;
        for (FramesConfig.Entry entry : config.spriteFrames()) {
            try {
                frames.add(entry.name(), frame(entry, packRoot));
                built++;
            } catch (IOException | RuntimeException e) {
                problems.add(entry.name() + ": " + e.getMessage());
            }
        }

        File textures = new File(packRoot, "assets/allium/textures/font/frames");
        deleteTree(textures);
        textures.mkdirs();
        JsonArray providers = new JsonArray();
        for (Glyph g : glyphs) {
            File png = new File(textures, g.file);
            png.getParentFile().mkdirs();
            ImageIO.write(g.image, "png", png);
            JsonObject p = new JsonObject();
            p.addProperty("type", "bitmap");
            p.addProperty("file", "allium:font/frames/" + g.file);
            p.addProperty("height", g.image.getHeight());
            p.addProperty("ascent", g.ascent);
            JsonArray chars = new JsonArray();
            chars.add(new com.google.gson.JsonPrimitive(String.valueOf(g.ch)));
            p.add("chars", chars);
            providers.add(p);
        }
        JsonObject advances = new JsonObject();
        // Items: from the text column back to the tooltip box's left edge, as the
        // card frames do. Hovers: back to the sprite quad's left edge, 12 pixels.
        advances.addProperty(String.valueOf(IN), -3);
        advances.addProperty(String.valueOf(HOVER_IN), -12);
        for (var out : outs.entrySet()) {
            advances.addProperty(String.valueOf(out.getValue()), 12 - (out.getKey() + 1));
        }
        JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        space.add("advances", advances);
        providers.add(space);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        File fontFile = new File(packRoot, "assets/allium/font/frames.json");
        fontFile.getParentFile().mkdirs();
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(fontFile.toPath(), gson.toJson(font));

        JsonObject root = new JsonObject();
        root.addProperty("font", "allium:frames");
        root.addProperty("in", String.valueOf(IN));
        root.addProperty("hoverIn", String.valueOf(HOVER_IN));
        root.add("frames", frames);
        manifest.getParentFile().mkdirs();
        Files.writeString(manifest.toPath(), gson.toJson(root));
        return new Result(built, glyphs.size(), problems);
    }

    private JsonObject frame(FramesConfig.Entry entry, File packRoot) throws IOException {
        List<FrameSprite> sprites = new ArrayList<>();
        if (entry.background() != null) sprites.add(FrameSprite.load(packRoot, entry.background()));
        if (entry.frame() != null) sprites.add(FrameSprite.load(packRoot, entry.frame()));
        int width = entry.width();
        List<BufferedImage> across = new ArrayList<>();
        for (FrameSprite s : sprites) across.add(s.drawAcross(width));
        int border = 0;
        for (FrameSprite s : sprites) border = Math.max(border, Math.max(s.topBorder(10_000), s.bottomBorder(10_000)));
        // Enough lines that the top and bottom borders no longer meet, with
        // middle lines to spare between them.
        int max = (2 * border) / LINE + 3;

        char out = outs.computeIfAbsent(width, w -> next++);
        JsonObject json = new JsonObject();
        json.addProperty("width", width);
        json.addProperty("out", String.valueOf(out));
        json.add("item", layout(entry.name() + "/item", sprites, across, width, max, false));
        json.add("hover", layout(entry.name() + "/hover", sprites, across, width, max, true));
        return json;
    }

    /**
     * Cuts one layout for every line count up to {@code max}, and the repeating
     * pattern for any count beyond it.
     */
    private JsonObject layout(String name, List<FrameSprite> sprites, List<BufferedImage> across,
                              int width, int max, boolean hover) {
        JsonObject fixed = new JsonObject();
        List<Character> lastLines = null;
        char lastHeader = 0, lastFinal = 0;
        for (int n = 1; n <= max; n++) {
            Cut cut = cut(name, sprites, across, width, n, hover);
            JsonObject one = new JsonObject();
            one.addProperty("header", String.valueOf(cut.header));
            one.addProperty("lines", chars(cut.lines));
            one.addProperty("final", String.valueOf(cut.last));
            fixed.add(String.valueOf(n), one);
            lastLines = cut.lines;
            lastHeader = cut.header;
            lastFinal = cut.last;
        }
        // At max lines the middle line repeats; the lines before it are the top
        // of the panel and the lines after it the bottom.
        int mid = lastLines.size() / 2;
        char body = lastLines.get(mid);
        int top = mid, bottom = mid;
        while (top > 0 && lastLines.get(top - 1) == body) top--;
        while (bottom < lastLines.size() - 1 && lastLines.get(bottom + 1) == body) bottom++;
        JsonObject json = new JsonObject();
        json.addProperty("max", max);
        json.add("fixed", fixed);
        json.addProperty("header", String.valueOf(lastHeader));
        json.addProperty("top", chars(lastLines.subList(0, top)));
        json.addProperty("body", String.valueOf(body));
        json.addProperty("bottom", chars(lastLines.subList(bottom + 1, lastLines.size())));
        json.addProperty("final", String.valueOf(lastFinal));
        return json;
    }

    private record Cut(char header, List<Character> lines, char last) {}

    /**
     * The glyphs for {@code n} text lines.
     *
     * <p>Items: a header on the line above the title, one strip per text line,
     * and a final strip on the blank line after them. Hovers: the first line's
     * strip carries the top of the panel too, the middle lines get one each, and
     * the last line's reaches down to the bottom; a hover has no line to spare,
     * and vanilla's panel is drawn around exactly these lines.
     */
    private Cut cut(String name, List<FrameSprite> sprites, List<BufferedImage> across,
                    int width, int n, boolean hover) {
        int height = OFFSET + LINE * n + LINE;
        BufferedImage quad = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < sprites.size(); i++) {
            over(quad, tall(sprites.get(i), across.get(i), height));
        }
        if (!hover) {
            char header = glyph(name, strip(quad, 0, OFFSET + OVERHANG, width),
                ASCENT_BASE + OFFSET - LINE);
            List<Character> lines = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                lines.add(glyph(name, strip(quad, OFFSET + LINE * k, OFFSET + LINE * (k + 1), width), ASCENT_BASE));
            }
            char last = glyph(name, strip(quad, OFFSET + LINE * n, height, width), ASCENT_BASE);
            return new Cut(header, lines, last);
        }
        underlay(quad, across);
        // The first line's strip reaches up over the top of the panel, and on
        // clients before 26.3, which put 2px after a hover's first line, its
        // overhang covers the gap.
        int firstEnd = n == 1 ? height : OFFSET + LINE + OVERHANG;
        char first = glyph(name, strip(quad, 0, firstEnd, width), ASCENT_BASE + OFFSET);
        List<Character> lines = new ArrayList<>();
        for (int k = 1; k < n - 1; k++) {
            lines.add(glyph(name, strip(quad, OFFSET + LINE * k, OFFSET + LINE * (k + 1), width), ASCENT_BASE));
        }
        char last = n == 1 ? first
            : glyph(name, strip(quad, OFFSET + LINE * (n - 1), height, width), ASCENT_BASE);
        return new Cut(first, lines, last);
    }

    /** The sprite drawn {@code height} tall: borders 1:1, one middle row repeated. */
    private static BufferedImage tall(FrameSprite sprite, BufferedImage across, int height) {
        int w = across.getWidth();
        int sh = across.getHeight();
        int top = sprite.topBorder(height);
        int bottom = sprite.bottomBorder(height);
        int middleRow = top + Math.max(0, sh - top - bottom) / 2;
        middleRow = Math.min(sh - 1, middleRow);
        BufferedImage out = new BufferedImage(w, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int sy;
            if (y < top) sy = y;
            else if (y >= height - bottom) sy = sh - (height - y);
            else sy = middleRow;
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, across.getRGB(x, sy));
            }
        }
        return out;
    }

    /**
     * Fills what the sprite leaves clear inside vanilla's hover panel, which is
     * the quad inset by 8 on every side, with the background's own colour.
     */
    private static void underlay(BufferedImage quad, List<BufferedImage> across) {
        int fill = 0xFF100010;
        if (!across.isEmpty()) {
            BufferedImage bg = across.get(0);
            int c = bg.getRGB(bg.getWidth() / 2, bg.getHeight() / 2);
            if ((c >>> 24) != 0) fill = c | 0xFF000000;
        }
        for (int y = INSET; y < quad.getHeight() - INSET; y++) {
            for (int x = INSET; x < quad.getWidth() - INSET; x++) {
                quad.setRGB(x, y, blend(quad.getRGB(x, y), fill));
            }
        }
    }

    private static void over(BufferedImage dst, BufferedImage src) {
        for (int y = 0; y < dst.getHeight(); y++) {
            for (int x = 0; x < dst.getWidth(); x++) {
                dst.setRGB(x, y, blend(src.getRGB(x, y), dst.getRGB(x, y)));
            }
        }
    }

    /** {@code top} over {@code below}, straight alpha. */
    static int blend(int top, int below) {
        int ta = top >>> 24;
        if (ta == 255) return top;
        if (ta == 0) return below;
        int ba = below >>> 24;
        int oa = ta + ba * (255 - ta) / 255;
        if (oa == 0) return 0;
        int r = (((top >> 16) & 255) * ta + ((below >> 16) & 255) * ba * (255 - ta) / 255) / oa;
        int g = (((top >> 8) & 255) * ta + ((below >> 8) & 255) * ba * (255 - ta) / 255) / oa;
        int b = ((top & 255) * ta + (below & 255) * ba * (255 - ta) / 255) / oa;
        return (oa << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage strip(BufferedImage quad, int from, int to, int width) {
        BufferedImage out = new BufferedImage(width, to - from, BufferedImage.TYPE_INT_ARGB);
        for (int y = from; y < to; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y - from, y < quad.getHeight() ? quad.getRGB(x, y) : 0);
            }
        }
        // The client advances a glyph by its rightmost visible column, and a
        // sprite's right margin is transparent. One pixel at alpha 1 cannot be
        // seen and keeps every glyph the frame's full width.
        if ((out.getRGB(width - 1, 0) >>> 24) == 0) {
            out.setRGB(width - 1, 0, 0x01000000);
        }
        return out;
    }

    private char glyph(String name, BufferedImage image, int ascent) {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        String key = ascent + ":" + image.getWidth() + "x" + image.getHeight() + ":" + Arrays.hashCode(pixels);
        Integer existing = glyphIds.get(key);
        if (existing != null && Arrays.equals(pixels, glyphs.get(existing).image.getRGB(
                0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))) {
            return glyphs.get(existing).ch;
        }
        char ch = next++;
        glyphIds.put(key, glyphs.size());
        glyphs.add(new Glyph(ch, ascent, image, name + "/" + Integer.toHexString(ch) + ".png"));
        return ch;
    }

    private static String chars(List<Character> list) {
        StringBuilder sb = new StringBuilder(list.size());
        for (char c : list) sb.append(c);
        return sb.toString();
    }

    private static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) return;
        try (var walk = Files.walk(dir.toPath())) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
