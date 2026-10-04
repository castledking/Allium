package codes.castled.allium.frames;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A frame built from pack sprites by {@link FrameBuilder}, read back from the
 * manifest it wrote.
 *
 * <p>The glyphs for a given line count are the builder's cut for that count, up
 * to the count where the panel's middle starts repeating; past it, the top
 * strips, the middle strip as often as needed, and the bottom strips.
 */
final class SpriteFrame implements LineFrame {

    private final Style style;
    private final String in;
    private final String hoverIn;
    private final String out;
    private final Layout item;
    private final Layout hover;

    SpriteFrame(JsonObject manifest, JsonObject frame) {
        this.style = Style.style()
            .font(Key.key(manifest.get("font").getAsString()))
            .color(NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, false)
            .shadowColor(ShadowColor.none())
            .build();
        this.in = manifest.get("in").getAsString();
        this.hoverIn = manifest.get("hoverIn").getAsString();
        this.out = frame.get("out").getAsString();
        this.item = new Layout(frame.getAsJsonObject("item"));
        this.hover = new Layout(frame.getAsJsonObject("hover"));
    }

    @Override
    public List<Component> item(List<Component> lines) {
        int n = lines.size();
        if (n == 0) return lines;
        Layout.Cut cut = item.cut(n, n);
        List<Component> result = new ArrayList<>(n + 2);
        result.add(frame(in + cut.header() + out));
        for (int k = 0; k < n; k++) {
            result.add(Component.text().append(frame(in + cut.lines().charAt(k) + out))
                .append(lines.get(k)).build());
        }
        // The panel's last rows, on a blank line of their own. No OUT, so the
        // line is the frame's width and vanilla keeps the tooltip on screen by it.
        result.add(frame(in + cut.last()));
        return result;
    }

    @Override
    public List<Component> hover(List<Component> lines) {
        int n = lines.size();
        if (n == 0) return lines;
        Layout.Cut cut = hover.cut(n, Math.max(0, n - 2));
        List<Component> result = new ArrayList<>(n);
        // No line of its own for the top or bottom of the panel: vanilla draws
        // its panel around exactly these lines, and the sprite is made to cover
        // that panel when it sits 12 pixels around them.
        for (int k = 0; k < n; k++) {
            char strip = k == 0 ? cut.header() : k < n - 1 ? cut.lines().charAt(k - 1) : cut.last();
            result.add(Component.text().append(frame(hoverIn + strip + out))
                .append(lines.get(k)).build());
        }
        return result;
    }

    private Component frame(String chars) {
        return Component.text(chars, style);
    }

    /** The glyphs of one layout, for any line count. */
    private static final class Layout {
        private final JsonObject json;
        private final int max;

        Layout(JsonObject json) {
            this.json = json;
            this.max = json.get("max").getAsInt();
        }

        record Cut(char header, String lines, char last) {}

        /**
         * @param n     text lines
         * @param strips how many of them take a middle strip rather than the last
         */
        Cut cut(int n, int strips) {
            if (n <= max) {
                JsonObject one = json.getAsJsonObject("fixed").getAsJsonObject(String.valueOf(n));
                return new Cut(one.get("header").getAsString().charAt(0),
                    one.get("lines").getAsString(), one.get("final").getAsString().charAt(0));
            }
            String top = json.get("top").getAsString();
            String bottom = json.get("bottom").getAsString();
            char body = json.get("body").getAsString().charAt(0);
            int middle = Math.max(0, strips - top.length() - bottom.length());
            return new Cut(json.get("header").getAsString().charAt(0),
                top + String.valueOf(body).repeat(middle) + bottom,
                json.get("final").getAsString().charAt(0));
        }
    }
}
