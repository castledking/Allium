package codes.castled.allium.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cutting a nine-slice tooltip sprite into per-line glyphs.
 *
 * <p>Built from a made-up sprite with a distinct top, middle and bottom, so
 * the test can tell which part of the panel each glyph came from.
 */
class FrameBuilderTest {

    @TempDir
    File dir;

    @Test
    void aSpriteFrameFramesAnyNumberOfLinesAndReusesTheMiddle() throws Exception {
        File pack = new File(dir, "pack");
        sprite(pack, "tooltip/test_background", 30);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("frames:\n  test:\n    background: minecraft:tooltip/test_background\n");
        FramesConfig config = FramesConfig.load(yaml, new ArrayList<>());
        File manifest = new File(dir, "manifest.json");

        var result = FrameBuilder.build(config, pack, manifest);
        assertEquals(1, result.frames(), result.problems().toString());
        assertTrue(new File(pack, "assets/allium/font/frames.json").isFile());

        JsonObject root = new JsonParser().parse(Files.readString(manifest.toPath())).getAsJsonObject();
        SpriteFrame frame = new SpriteFrame(root, root.getAsJsonObject("frames").getAsJsonObject("test"));

        for (int n : new int[] {1, 2, 5, 40}) {
            List<Component> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) lines.add(Component.text("line " + i));
            // items: a header line, every line, and the bottom of the panel
            assertEquals(n + 2, frame.item(lines).size(), "item lines for " + n);
            // hovers: exactly the hover's own lines, so vanilla's panel fits them
            assertEquals(n, frame.hover(lines).size(), "hover lines for " + n);
        }

        // A long lore costs no more glyphs than a short one: the middle repeats.
        List<Component> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) forty.add(Component.text("x"));
        var framed = frame.item(forty);
        String middle = frameChars(framed.get(20));
        assertEquals(middle, frameChars(framed.get(21)));
    }

    @Test
    void everyGlyphIsAsTallAsItReachesAndNoHigherThanTheClientAllows() throws Exception {
        File pack = new File(dir, "pack");
        sprite(pack, "tooltip/test_background", 45);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("frames:\n  test:\n    background: minecraft:tooltip/test_background\n    width: 180\n");
        FrameBuilder.build(FramesConfig.load(yaml, new ArrayList<>()), pack, new File(dir, "m.json"));
        var font = new JsonParser().parse(Files.readString(
            new File(pack, "assets/allium/font/frames.json").toPath())).getAsJsonObject();
        for (var p : font.getAsJsonArray("providers")) {
            JsonObject o = p.getAsJsonObject();
            if (!o.get("type").getAsString().equals("bitmap")) continue;
            // The client and Nexo both reject a font whose glyph rises above its height.
            assertTrue(o.get("ascent").getAsInt() <= o.get("height").getAsInt(), o.toString());
            BufferedImage img = ImageIO.read(new File(pack, "assets/allium/textures/"
                + o.get("file").getAsString().substring("allium:".length())));
            assertEquals(180, img.getWidth());
            // The rightmost column is never fully clear, or the client would
            // advance the glyph by less than the frame's width.
            boolean visible = false;
            for (int y = 0; y < img.getHeight(); y++) visible |= (img.getRGB(179, y) >>> 24) != 0;
            assertTrue(visible, o.get("file").getAsString());
        }
    }

    private static String frameChars(Component line) {
        return ((TextComponent) line.children().get(0)).content();
    }

    /** A 100x100 nine-slice sprite: red top border, green middle, blue bottom border. */
    private static void sprite(File pack, String path, int border) throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) {
            int c = y < border ? 0xFFCC2222 : y >= 100 - border ? 0xFF2222CC : 0xFF22AA22;
            for (int x = 8; x < 92; x++) img.setRGB(x, y, c);
        }
        File png = new File(pack, "assets/minecraft/textures/" + path + ".png");
        png.getParentFile().mkdirs();
        ImageIO.write(img, "png", png);
        Files.writeString(new File(png.getPath() + ".mcmeta").toPath(),
            "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":100,\"height\":100,"
                + "\"border\":" + border + ",\"stretch_inner\":true}}}");
    }
}
