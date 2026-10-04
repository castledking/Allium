package codes.castled.allium.frames;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * A GUI sprite and the scaling its {@code .mcmeta} declares, drawn the way the
 * client draws a tooltip sprite.
 *
 * <p>Copies {@code GuiGraphicsExtractor.blitNineSlicedSprite} from the 26.3
 * client: corners 1:1, borders clamped to half the drawn size, and the edges
 * and centre stretched when {@code stretch_inner} is set, tiled otherwise. An
 * animated sprite is drawn from its first frame, since a font glyph cannot
 * animate.
 */
final class FrameSprite {

    enum Type { STRETCH, TILE, NINE_SLICE }

    private final BufferedImage image;
    private final Type type;
    private final int left, top, right, bottom;
    private final boolean stretchInner;

    private FrameSprite(BufferedImage image, Type type, int left, int top, int right,
                        int bottom, boolean stretchInner) {
        this.image = image;
        this.type = type;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.stretchInner = stretchInner;
    }

    /**
     * Reads a sprite from a pack.
     *
     * @param path {@code namespace:path} under {@code textures/}, with or without
     *             {@code .png}; a bare path is in the minecraft namespace
     */
    static FrameSprite load(File packRoot, String path) throws IOException {
        File png = file(packRoot, path);
        if (!png.isFile()) {
            throw new IOException("no sprite at " + png);
        }
        BufferedImage full = ImageIO.read(png);
        if (full == null) {
            throw new IOException("not a PNG: " + png);
        }
        JsonObject meta = null;
        File mcmeta = new File(png.getPath() + ".mcmeta");
        if (mcmeta.isFile()) {
            meta = new JsonParser().parse(Files.readString(mcmeta.toPath())).getAsJsonObject();
        }
        // An animation stacks its frames vertically, square unless it says otherwise.
        int frameHeight = full.getHeight();
        if (meta != null && meta.has("animation")) {
            JsonObject animation = meta.getAsJsonObject("animation");
            frameHeight = animation.has("height") ? animation.get("height").getAsInt()
                : Math.min(full.getWidth(), full.getHeight());
        }
        BufferedImage image = copy(full, 0, 0, full.getWidth(), Math.min(frameHeight, full.getHeight()));

        if (meta == null || !meta.has("gui")) {
            return new FrameSprite(image, Type.STRETCH, 0, 0, 0, 0, true);
        }
        JsonObject scaling = meta.getAsJsonObject("gui").getAsJsonObject("scaling");
        String type = scaling.has("type") ? scaling.get("type").getAsString() : "stretch";
        return switch (type) {
            case "tile" -> new FrameSprite(image, Type.TILE, 0, 0, 0, 0, false);
            case "nine_slice" -> {
                int l, t, r, b;
                var border = scaling.get("border");
                if (border.isJsonObject()) {
                    JsonObject o = border.getAsJsonObject();
                    l = o.get("left").getAsInt();
                    t = o.get("top").getAsInt();
                    r = o.get("right").getAsInt();
                    b = o.get("bottom").getAsInt();
                } else {
                    l = t = r = b = border.getAsInt();
                }
                boolean stretch = scaling.has("stretch_inner") && scaling.get("stretch_inner").getAsBoolean();
                // The border is declared against the sprite's declared size, which
                // these sprites always match; scale it anyway if they do not.
                int declaredW = scaling.has("width") ? scaling.get("width").getAsInt() : image.getWidth();
                int declaredH = scaling.has("height") ? scaling.get("height").getAsInt() : image.getHeight();
                yield new FrameSprite(image, Type.NINE_SLICE,
                    l * image.getWidth() / declaredW, t * image.getHeight() / declaredH,
                    r * image.getWidth() / declaredW, b * image.getHeight() / declaredH, stretch);
            }
            default -> new FrameSprite(image, Type.STRETCH, 0, 0, 0, 0, true);
        };
    }

    static File file(File packRoot, String path) {
        String p = path.trim();
        String ns = "minecraft";
        int colon = p.indexOf(':');
        if (colon >= 0) {
            ns = p.substring(0, colon);
            p = p.substring(colon + 1);
        }
        if (!p.endsWith(".png")) {
            p = p + ".png";
        }
        return new File(packRoot, "assets/" + ns + "/textures/" + p);
    }

    /** Rows the top and bottom borders keep 1:1 when drawn at {@code height}. */
    int topBorder(int height) {
        return type == Type.NINE_SLICE ? Math.min(top, height / 2) : 0;
    }

    int bottomBorder(int height) {
        return type == Type.NINE_SLICE ? Math.min(bottom, height / 2) : 0;
    }

    /**
     * Draws the sprite at {@code width} by its own height, scaled across exactly
     * as the client would. Vertical scaling is done by {@link FrameBuilder},
     * which repeats one middle row so every body line can share a glyph.
     */
    BufferedImage drawAcross(int width) {
        int h = image.getHeight();
        BufferedImage out = new BufferedImage(width, h, BufferedImage.TYPE_INT_ARGB);
        switch (type) {
            case STRETCH -> scaleInto(image, 0, 0, image.getWidth(), h, out, 0, 0, width, h);
            case TILE -> tileInto(image, 0, 0, image.getWidth(), h, out, 0, 0, width, h);
            case NINE_SLICE -> {
                int l = Math.min(left, width / 2);
                int r = Math.min(right, width / 2);
                int sw = image.getWidth();
                blit(image, 0, 0, l, h, out, 0, 0);
                blit(image, sw - r, 0, r, h, out, width - r, 0);
                int inner = width - l - r;
                if (inner > 0) {
                    if (stretchInner) {
                        scaleInto(image, l, 0, sw - l - r, h, out, l, 0, inner, h);
                    } else {
                        tileInto(image, l, 0, sw - l - r, h, out, l, 0, inner, h);
                    }
                }
            }
        }
        return out;
    }

    private static BufferedImage copy(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        blit(src, x, y, w, h, out, 0, 0);
        return out;
    }

    private static void blit(BufferedImage src, int sx, int sy, int w, int h,
                             BufferedImage dst, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(dx + x, dy + y, src.getRGB(sx + x, sy + y));
            }
        }
    }

    private static void scaleInto(BufferedImage src, int sx, int sy, int sw, int sh,
                                  BufferedImage dst, int dx, int dy, int dw, int dh) {
        if (sw <= 0 || sh <= 0) return;
        for (int y = 0; y < dh; y++) {
            int py = sy + Math.min(sh - 1, y * sh / dh);
            for (int x = 0; x < dw; x++) {
                int px = sx + Math.min(sw - 1, x * sw / dw);
                dst.setRGB(dx + x, dy + y, src.getRGB(px, py));
            }
        }
    }

    private static void tileInto(BufferedImage src, int sx, int sy, int sw, int sh,
                                 BufferedImage dst, int dx, int dy, int dw, int dh) {
        if (sw <= 0 || sh <= 0) return;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                dst.setRGB(dx + x, dy + y, src.getRGB(sx + x % sw, sy + y % sh));
            }
        }
    }
}
