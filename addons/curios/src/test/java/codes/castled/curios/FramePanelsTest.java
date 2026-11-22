package codes.castled.curios;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FramePanelsTest {

    @Test
    void everyConfiguredFrameCanBeDrawnAsALorePanel() throws Exception {
        YamlConfiguration config;
        try (var in = FramePanelsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> frames = config.getConfigurationSection("frames").getKeys(false);
        assertEquals(57, frames.size());
        for (String frame : frames) {
            assertTrue(FramePanels.bundled().draws(frame), frame);
            assertNotNull(FramePanels.bundled().panel(frame, null, 22, 0, 0), frame);
        }
        assertEquals(52, FramePanels.bundled().spriteFrames().size());
    }

    /** Pixel advance of a panel string, from the generated layout: pieces advance width + 1, spaces their own. */
    private static int advance(String text) throws Exception {
        com.google.gson.JsonObject layout;
        try (var in = FramePanelsTest.class.getClassLoader().getResourceAsStream("card-panels.json")) {
            layout = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        java.util.Map<Integer, Integer> advances = new java.util.HashMap<>();
        advances.put(layout.get("in").getAsString().codePointAt(0), -3);
        layout.getAsJsonObject("negative").entrySet().forEach(e -> advances.put(e.getValue().getAsString().codePointAt(0), -Integer.parseInt(e.getKey())));
        for (var tier : layout.getAsJsonObject("tiers").entrySet()) {
            for (var part : tier.getValue().getAsJsonObject().entrySet()) {
                var strip = part.getValue().getAsJsonObject();
                for (String piece : new String[]{"left", "centre", "right"}) {
                    if (strip.has(piece)) advances.put(strip.getAsJsonArray(piece).get(0).getAsString().codePointAt(0), strip.getAsJsonArray(piece).get(1).getAsInt() + 1);
                }
                strip.getAsJsonObject("fills").entrySet().forEach(e -> advances.put(e.getValue().getAsString().codePointAt(0), Integer.parseInt(e.getKey()) + 1));
            }
        }
        int total = 0;
        for (int cp : text.codePoints().toArray()) total += java.util.Objects.requireNonNull(advances.get(cp), "unknown glyph " + Integer.toHexString(cp));
        return total;
    }

    @Test
    void tierPanelsAreDrawnAtTheWidthOfTheirWidestLine() throws Exception {
        for (CardRules.Tier tier : CardRules.Tier.values()) {
            for (int text : new int[]{0, 40, 176, 230}) {
                var panel = FramePanels.bundled().panel(tier.name(), null, 3, text, 0);
                int width = FramePanels.bundled().panelWidth(tier, text, 0);
                assertEquals("curios:card_panel", panel.font());
                assertEquals(3, panel.prefixes().size());
                assertTrue(panel.blankAfter());
                // Text starts MARGIN - 3 = 9 pixels right of where it would without the panel.
                assertEquals(FramePanels.MARGIN - 3, advance(panel.header()), tier + " header at " + text);
                for (String prefix : panel.prefixes()) assertEquals(FramePanels.MARGIN - 3, advance(prefix));
                // The last line is the panel's full width, back to the tooltip's edge.
                assertEquals(width - 3, advance(panel.last()), tier + " bottom at " + text);
                if (text > 100) assertEquals(text + 2 * FramePanels.MARGIN, width, "a wide line widens the panel");
            }
            assertTrue(FramePanels.bundled().panelWidth(tier, 0, 0) >= 30, "never narrower than the art's caps");
            assertEquals(FramePanels.CARD_WIDTH, FramePanels.bundled().panelWidth(tier, 40, FramePanels.CARD_WIDTH));
        }
    }

    @Test
    void textIsMeasuredWithTheClientsDefaultFont() {
        assertEquals(44, TextWidth.of("ᴛʀᴀᴅᴇ ɪɴ"));
        assertEquals(34, TextWidth.of("\ue151"), "the elite label glyph is 33 pixels plus spacing");
        assertEquals(9, TextWidth.of("\uD83D\uDD12"), "unifont emoji fall back to a wide advance");
    }

    @Test
    void spriteFramesFollowSfsBuiltCutsThenRepeatTheirMiddleStrip() {
        // anniversary_nxny's cut for two lines, from SF's frames-built.json.
        var two = FramePanels.bundled().panel("anniversary_nxny", null, 2, 0, 0);
        assertEquals("curios:frames", two.font());
        assertEquals("", two.header());
        assertEquals(java.util.List.of("", ""), two.prefixes());
        assertEquals("", two.last());
        assertFalse(two.blankAfter());

        var tall = FramePanels.bundled().panel("anniversary_nxny", null, 30, 0, 0);
        assertEquals(30, tall.prefixes().size());
        assertEquals(tall.prefixes().get(10), tall.prefixes().get(20));
        assertNotEquals(tall.prefixes().get(0), tall.prefixes().get(15));
    }

    @Test
    void unknownFramesFallBackAndNothingIsDrawnWithoutLines() {
        assertEquals("curios:card_panel", FramePanels.bundled().panel("no_such_frame", "SIMPLE", 4, 0, 0).font());
        assertNull(FramePanels.bundled().panel("no_such_frame", "also_missing", 4, 0, 0));
        assertNull(FramePanels.bundled().panel("red", null, 0, 0, 0));
        assertFalse(FramePanels.bundled().draws("no_such_frame"));
    }
}
