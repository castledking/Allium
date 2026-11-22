package codes.castled.curios;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lays out the font-glyph panel a frame draws behind tooltip lines, so frames show on every
 * client version, including servers without item tooltip styles (before 1.21.2).
 *
 * <p>The five card tiers use the ornate card art, cut by the pack builder into caps, centre
 * ornaments and fill strips ({@code curios:card_panel}, laid out by {@code card-panels.json}) so a
 * panel can be drawn at any width: as wide as its widest line plus a margin. Every other frame uses the
 * strips cut from its tooltip sprites into {@code curios:frames}, described by the bundled
 * {@code frame-glyphs.json}: SF's {@code frames-built.json} item layouts, whose glyphs match the
 * bundled {@code font/frames.json} exactly.
 *
 * <p>A panel is a header line above the first text line, one prefix glyph per text line, and a
 * last line below them. Each prefix steps back to the tooltip's left edge, draws its strip, and
 * steps forward to the text column again, so it sits behind the line without moving it.
 */
final class FramePanels {

    static final String CARD_FONT = "curios:card_frame";
    /** Space a panel leaves on each side of its widest line. */
    static final int MARGIN = 12;
    /** The width the card art was drawn at; cards are never narrower, as in legacy. */
    static final int CARD_WIDTH = 200;
    private static final int MAX_WIDTH = 1000;

    /** The glyphs for one framed block of text. Every string is drawn in {@link #font}. */
    record Panel(String font, String header, List<String> prefixes, String last, boolean blankAfter) {}

    private static final class Holder {
        static final FramePanels BUNDLED = load();
    }

    private final String font;
    private final String in;
    private final Map<String, Sprite> sprites;
    private final CardArt cards;

    private FramePanels(JsonObject manifest, JsonObject cardPanels) {
        this.cards = new CardArt(cardPanels);
        this.font = manifest.get("font").getAsString();
        this.in = manifest.get("in").getAsString();
        Map<String, Sprite> frames = new LinkedHashMap<>();
        for (var entry : manifest.getAsJsonObject("frames").entrySet()) {
            JsonObject frame = entry.getValue().getAsJsonObject();
            frames.put(entry.getKey(), new Sprite(frame.get("out").getAsString(), frame.getAsJsonObject("item")));
        }
        this.sprites = Collections.unmodifiableMap(frames);
    }

    static FramePanels bundled() {
        return Holder.BUNDLED;
    }

    private static FramePanels load() {
        return new FramePanels(resource("frame-glyphs.json"), resource("card-panels.json"));
    }

    private static JsonObject resource(String name) {
        try (InputStream in = FramePanels.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is missing from the Curios jar");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Set<String> spriteFrames() {
        return sprites.keySet();
    }

    /** True when {@code frame} is a card tier or a sprite frame this panel set can draw. */
    boolean draws(String frame) {
        return tier(frame) != null || frame != null && sprites.containsKey(frame.toLowerCase(Locale.ROOT));
    }

    /**
     * The panel for {@code lines} text lines in {@code frame}, or in {@code fallback} when this set
     * cannot draw {@code frame}. Returns null only when neither can be drawn or there are no lines.
     *
     * @param textWidth the widest line, in pixels; tier panels grow to fit it, sprite panels are fixed
     * @param minWidth  the narrowest a tier panel may be
     */
    Panel panel(String frame, String fallback, int lines, int textWidth, int minWidth) {
        if (lines <= 0) return null;
        for (String name : new String[]{frame, fallback}) {
            if (name == null) continue;
            CardRules.Tier tier = tier(name);
            if (tier != null) return cards.panel(tier, lines, panelWidth(tier, textWidth, minWidth));
            Sprite sprite = sprites.get(name.toLowerCase(Locale.ROOT));
            if (sprite != null) return sprite.panel(font, in, lines);
        }
        return null;
    }

    /** The width a tier panel is drawn at: its widest line plus a margin each side, within the art's limits. */
    int panelWidth(CardRules.Tier tier, int textWidth, int minWidth) {
        return cards.width(tier, Math.max(minWidth, textWidth + 2 * MARGIN));
    }

    /** The resizable tier panels: each strip is a left cap, fill, optional centre ornament, fill, right cap. */
    private static final class CardArt {
        private final String font;
        private final String in;
        private final Map<Integer, String> negative = new TreeMap<>(Comparator.reverseOrder());
        private final Map<CardRules.Tier, Strip[]> tiers = new EnumMap<>(CardRules.Tier.class);

        CardArt(JsonObject manifest) {
            font = manifest.get("font").getAsString();
            in = manifest.get("in").getAsString();
            manifest.getAsJsonObject("negative").entrySet()
                    .forEach(e -> negative.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()));
            JsonObject all = manifest.getAsJsonObject("tiers");
            for (CardRules.Tier tier : CardRules.Tier.values()) {
                JsonObject parts = all.getAsJsonObject(tier.name().toLowerCase(Locale.ROOT));
                tiers.put(tier, new Strip[]{new Strip(parts.getAsJsonObject("header")),
                        new Strip(parts.getAsJsonObject("body")), new Strip(parts.getAsJsonObject("bottom"))});
            }
        }

        int width(CardRules.Tier tier, int requested) {
            int width = requested;
            for (Strip strip : tiers.get(tier)) width = Math.max(width, strip.fixed());
            return Math.min(width, MAX_WIDTH);
        }

        Panel panel(CardRules.Tier tier, int lines, int width) {
            Strip[] strips = tiers.get(tier);
            String joiner = negative.get(1);
            // Back from the text column to the tooltip's edge, the strip, then forward to the text
            // column again, MARGIN pixels in from where the strip started.
            String out = advance(width - MARGIN);
            String body = in + strips[1].draw(width, joiner) + out;
            // The bottom cap is taller than a line; the blank line after it keeps the tooltip box
            // (and vanilla's advanced-tooltip lines) below it. It has no OUT, so the line is the
            // panel's full width and the client sizes the tooltip by it.
            return new Panel(font, in + strips[0].draw(width, joiner) + out, Collections.nCopies(lines, body),
                    in + strips[2].draw(width, joiner), true);
        }

        /** Space characters moving back {@code pixels}, largest steps first. */
        private String advance(int pixels) {
            StringBuilder out = new StringBuilder();
            int left = pixels;
            for (var step : negative.entrySet()) {
                while (left >= step.getKey()) {
                    out.append(step.getValue());
                    left -= step.getKey();
                }
            }
            return out.toString();
        }
    }

    /** One strip of a tier panel. */
    private static final class Strip {
        private final String left, centre, right;
        private final int leftWidth, centreWidth, rightWidth, bias;
        private final TreeMap<Integer, String> fills = new TreeMap<>(Comparator.reverseOrder());

        Strip(JsonObject json) {
            left = json.getAsJsonArray("left").get(0).getAsString();
            leftWidth = json.getAsJsonArray("left").get(1).getAsInt();
            right = json.getAsJsonArray("right").get(0).getAsString();
            rightWidth = json.getAsJsonArray("right").get(1).getAsInt();
            boolean hasCentre = json.has("centre");
            centre = hasCentre ? json.getAsJsonArray("centre").get(0).getAsString() : null;
            centreWidth = hasCentre ? json.getAsJsonArray("centre").get(1).getAsInt() : 0;
            bias = json.has("bias") ? json.get("bias").getAsInt() : 0;
            json.getAsJsonObject("fills").entrySet().forEach(e -> fills.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()));
        }

        int fixed() {
            return leftWidth + centreWidth + rightWidth;
        }

        /** The strip {@code width} pixels wide. A bitmap glyph advances its width plus one; the joiner takes that one back. */
        String draw(int width, String joiner) {
            int fill = Math.max(0, width - fixed());
            StringBuilder out = new StringBuilder(left).append(joiner);
            if (centre != null) {
                int before = Math.min(fill, Math.max(0, fill / 2 + bias));
                fill(out, before, joiner);
                out.append(centre).append(joiner);
                fill(out, fill - before, joiner);
            } else {
                fill(out, fill, joiner);
            }
            return out.append(right).append(joiner).toString();
        }

        private void fill(StringBuilder out, int pixels, String joiner) {
            for (var size : fills.entrySet()) {
                while (pixels >= size.getKey()) {
                    out.append(size.getValue()).append(joiner);
                    pixels -= size.getKey();
                }
            }
        }
    }

    private static CardRules.Tier tier(String name) {
        if (name == null) return null;
        for (CardRules.Tier tier : CardRules.Tier.values()) {
            if (tier.name().equalsIgnoreCase(name)) return tier;
        }
        return null;
    }

    /** One sprite frame's item layout, cut for every line count (legacy {@code SpriteFrame.item}). */
    private record Sprite(String out, JsonObject item) {
        Panel panel(String font, String in, int n) {
            int max = item.get("max").getAsInt();
            char header;
            String strips;
            char last;
            if (n <= max) {
                JsonObject fixed = item.getAsJsonObject("fixed").getAsJsonObject(Integer.toString(n));
                header = fixed.get("header").getAsString().charAt(0);
                strips = fixed.get("lines").getAsString();
                last = fixed.get("final").getAsString().charAt(0);
            } else {
                // Past max lines the panel's middle row repeats between fixed top and bottom strips.
                String top = item.get("top").getAsString();
                String bottom = item.get("bottom").getAsString();
                char body = item.get("body").getAsString().charAt(0);
                int middle = Math.max(0, n - top.length() - bottom.length());
                header = item.get("header").getAsString().charAt(0);
                strips = top + String.valueOf(body).repeat(middle) + bottom;
                last = item.get("final").getAsString().charAt(0);
            }
            List<String> prefixes = new ArrayList<>(n);
            for (int k = 0; k < n; k++) prefixes.add(in + strips.charAt(k) + out);
            // The last line has no OUT, so the line is the frame's full width and the client keeps the
            // tooltip on screen by it.
            return new Panel(font, in + header + out, List.copyOf(prefixes), in + last, false);
        }
    }
}
