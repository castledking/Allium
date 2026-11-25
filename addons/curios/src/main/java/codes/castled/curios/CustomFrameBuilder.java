package codes.castled.curios;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import javax.imageio.ImageIO;
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

/**
 * Cuts custom sprite frames declared in {@code frames.yml} into per-line bitmap font glyphs
 * under {@code assets/curios/textures/font/custom_frames/}, writes {@code assets/curios/font/custom_frames.json},
 * and emits {@code frames-built.json} for {@link FramePanels}.
 */
final class CustomFrameBuilder {

    static final String CUSTOM_FONT = "curios:custom_frames";
    static final int LINE = 10;
    static final int OFFSET = 12;
    static final int INSET = 8;
    static final int OVERHANG = 2;
    static final int ASCENT_BASE = 7;

    static final char IN = '\uE000';
    static final char HOVER_IN = '\uE001';

    record Result(int frames, int glyphs, List<String> problems) {}

    private final Map<String, Integer> glyphIds = new LinkedHashMap<>();
    private final List<Glyph> glyphs = new ArrayList<>();
    private final Map<Integer, Character> outs = new LinkedHashMap<>();
    private char next = '\uE010';

    private record Glyph(char ch, int ascent, BufferedImage image, String file) {}

    private record Cut(char header, List<Character> lines, char last) {}

    static Result build(CustomFramesConfig config, File packRoot, File manifest) throws IOException {
        return new CustomFrameBuilder().run(config, packRoot, manifest);
    }

    private Result run(CustomFramesConfig config, File packRoot, File manifest) throws IOException {
        List<String> problems = new ArrayList<>();
        JsonObject frames = new JsonObject();
        int built = 0;
        for (CustomFramesConfig.Entry entry : config.spriteFrames()) {
            try {
                frames.add(entry.name(), buildFrame(entry, packRoot));
                built++;
            } catch (IOException | RuntimeException e) {
                problems.add(entry.name() + ": " + e.getMessage());
            }
        }

        File textures = new File(packRoot, "assets/curios/textures/font/custom_frames");
        deleteTree(textures);
        if (!textures.mkdirs() && !textures.isDirectory()) {
            throw new IOException("Could not create " + textures.getPath());
        }
        JsonArray providers = new JsonArray();
        for (Glyph g : glyphs) {
            File png = new File(textures, g.file);
            File parent = png.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Could not create " + parent.getPath());
            }
            ImageIO.write(g.image, "png", png);
            JsonObject p = new JsonObject();
            p.addProperty("type", "bitmap");
            p.addProperty("file", "curios:font/custom_frames/" + g.file);
            p.addProperty("height", g.image.getHeight());
            p.addProperty("ascent", g.ascent);
            JsonArray chars = new JsonArray();
            chars.add(new JsonPrimitive(String.valueOf(g.ch)));
            p.add("chars", chars);
            providers.add(p);
        }

        JsonObject advances = new JsonObject();
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
        File fontFile = new File(packRoot, "assets/curios/font/custom_frames.json");
        File fontParent = fontFile.getParentFile();
        if (fontParent != null && !fontParent.mkdirs() && !fontParent.isDirectory()) {
            throw new IOException("Could not create " + fontParent.getPath());
        }
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(fontFile.toPath(), gson.toJson(font));

        JsonObject root = new JsonObject();
        root.addProperty("font", CUSTOM_FONT);
        root.addProperty("in", String.valueOf(IN));
        root.addProperty("hoverIn", String.valueOf(HOVER_IN));
        root.add("frames", frames);
        File manifestParent = manifest.getParentFile();
        if (manifestParent != null && !manifestParent.mkdirs() && !manifestParent.isDirectory()) {
            throw new IOException("Could not create " + manifestParent.getPath());
        }
        Files.writeString(manifest.toPath(), gson.toJson(root));
        return new Result(built, glyphs.size(), problems);
    }

    private JsonObject buildFrame(CustomFramesConfig.Entry entry, File packRoot) throws IOException {
        List<CustomFrameSprite> sprites = new ArrayList<>();
        if (entry.background() != null) sprites.add(CustomFrameSprite.load(packRoot, entry.background()));
        if (entry.frame() != null) sprites.add(CustomFrameSprite.load(packRoot, entry.frame()));
        if (sprites.isEmpty()) {
            throw new IOException("no background or frame sprite configured");
        }
        int width = entry.width();
        List<BufferedImage> across = new ArrayList<>(sprites.size());
        for (CustomFrameSprite s : sprites) across.add(s.drawAcross(width));
        int border = 0;
        for (CustomFrameSprite s : sprites) {
            border = Math.max(border, Math.max(s.topBorder(10_000), s.bottomBorder(10_000)));
        }
        int max = (2 * border) / LINE + 3;

        char out = outs.computeIfAbsent(width, w -> next++);
        JsonObject json = new JsonObject();
        json.addProperty("width", width);
        json.addProperty("out", String.valueOf(out));
        json.add("item", layout(entry.name() + "/item", sprites, across, width, max, false));
        json.add("hover", layout(entry.name() + "/hover", sprites, across, width, max, true));
        return json;
    }

    private JsonObject layout(String name, List<CustomFrameSprite> sprites, List<BufferedImage> across,
                              int width, int max, boolean hover) {
        JsonObject fixed = new JsonObject();
        List<Character> lastLines = null;
        char lastHeader = 0;
        char lastFinal = 0;
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
        int mid = lastLines.size() / 2;
        char body = lastLines.get(mid);
        int top = mid;
        int bottom = mid;
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

    private Cut cut(String name, List<CustomFrameSprite> sprites, List<BufferedImage> across,
                    int width, int n, boolean hover) {
        int height = OFFSET + LINE * n + LINE;
        BufferedImage quad = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < sprites.size(); i++) {
            over(quad, tall(sprites.get(i), across.get(i), height));
        }
        if (!hover) {
            char header = glyph(name, strip(quad, 0, OFFSET + OVERHANG, width), ASCENT_BASE + OFFSET - LINE);
            List<Character> lines = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                lines.add(glyph(name, strip(quad, OFFSET + LINE * k, OFFSET + LINE * (k + 1), width), ASCENT_BASE));
            }
            char last = glyph(name, strip(quad, OFFSET + LINE * n, height, width), ASCENT_BASE);
            return new Cut(header, lines, last);
        }
        underlay(quad, across);
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

    private static BufferedImage tall(CustomFrameSprite sprite, BufferedImage across, int height) {
        int w = across.getWidth();
        int sh = across.getHeight();
        int top = sprite.topBorder(height);
        int bottom = sprite.bottomBorder(height);
        int middleRow = Math.min(sh - 1, top + Math.max(0, sh - top - bottom) / 2);
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
