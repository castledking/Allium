package codes.castled.curios;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a GUI tooltip sprite and its optional {@code .mcmeta} scaling metadata
 * ({@code stretch}, {@code tile}, {@code nine_slice}, and animated first-frame slicing)
 * from a resource-pack folder.
 */
final class CustomFrameSprite {

    enum Type { STRETCH, TILE, NINE_SLICE }

    private final BufferedImage image;
    private final Type type;
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;
    private final boolean stretchInner;

    private CustomFrameSprite(BufferedImage image, Type type, int left, int top, int right,
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
     * Reads a sprite from {@code packRoot}.
     *
     * @param path {@code namespace:path} under {@code textures/}, with or without {@code .png};
     *             a bare path defaults to the {@code minecraft} namespace.
     */
    static CustomFrameSprite load(File packRoot, String path) throws IOException {
        File png = resolveTextureFile(packRoot, path);
        if (!png.isFile()) {
            throw new IOException("no sprite at " + png.getPath());
        }
        BufferedImage full = ImageIO.read(png);
        if (full == null) {
            throw new IOException("not a PNG: " + png.getPath());
        }
        JsonObject meta = null;
        File mcmeta = new File(png.getPath() + ".mcmeta");
        if (mcmeta.isFile()) {
            meta = JsonParser.parseString(Files.readString(mcmeta.toPath())).getAsJsonObject();
        }
        int frameHeight = full.getHeight();
        if (meta != null && meta.has("animation")) {
            JsonObject animation = meta.getAsJsonObject("animation");
            frameHeight = animation.has("height") ? animation.get("height").getAsInt()
                    : Math.min(full.getWidth(), full.getHeight());
        }
        BufferedImage image = copy(full, 0, 0, full.getWidth(), Math.max(1, Math.min(frameHeight, full.getHeight())));

        if (meta == null || !meta.has("gui")) {
            return new CustomFrameSprite(image, Type.STRETCH, 0, 0, 0, 0, true);
        }
        JsonObject gui = meta.getAsJsonObject("gui");
        if (!gui.has("scaling")) {
            return new CustomFrameSprite(image, Type.STRETCH, 0, 0, 0, 0, true);
        }
        JsonObject scaling = gui.getAsJsonObject("scaling");
        String scalingType = scaling.has("type") ? scaling.get("type").getAsString() : "stretch";
        return switch (scalingType) {
            case "tile" -> new CustomFrameSprite(image, Type.TILE, 0, 0, 0, 0, false);
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
                int declaredW = Math.max(1, scaling.has("width") ? scaling.get("width").getAsInt() : image.getWidth());
                int declaredH = Math.max(1, scaling.has("height") ? scaling.get("height").getAsInt() : image.getHeight());
                yield new CustomFrameSprite(image, Type.NINE_SLICE,
                        l * image.getWidth() / declaredW,
                        t * image.getHeight() / declaredH,
                        r * image.getWidth() / declaredW,
                        b * image.getHeight() / declaredH,
                        stretch);
            }
            default -> new CustomFrameSprite(image, Type.STRETCH, 0, 0, 0, 0, true);
        };
    }

    static File resolveTextureFile(File packRoot, String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("empty sprite path");
        }
        String p = path.trim();
        String ns = "minecraft";
        int colon = p.indexOf(':');
        if (colon >= 0) {
            ns = p.substring(0, colon);
            p = p.substring(colon + 1);
        }
        if (!ns.matches("[a-z0-9_.-]+")) {
            throw new IOException("invalid sprite namespace: " + ns);
        }
        if (p.startsWith("/") || p.contains("\\") || p.contains("..")) {
            throw new IOException("invalid sprite path: " + path);
        }
        if (!p.endsWith(".png")) {
            p = p + ".png";
        }
        Path root = packRoot.toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve("assets").resolve(ns).resolve("textures").resolve(p).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("sprite path escapes pack directory: " + path);
        }
        return resolved.toFile();
    }

    int topBorder(int height) {
        return type == Type.NINE_SLICE ? Math.min(top, height / 2) : 0;
    }

    int bottomBorder(int height) {
        return type == Type.NINE_SLICE ? Math.min(bottom, height / 2) : 0;
    }

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
