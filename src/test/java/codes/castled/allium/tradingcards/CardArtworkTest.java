package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The card artwork against the layout the frame glyphs are cut for.
 *
 * <p>{@code tools/generate_card_glyphs.py} slices this art into a header, a
 * one-line body slice and a bottom cap, and {@code CardFrame} puts them on fixed
 * lines. Neither notices if the art stops fitting: a taller cap is drawn
 * anyway, through the title or over the F3+H lines, and no server log reports
 * it. The generator refuses such art, but only when someone runs it, so the
 * same checks run here against the committed PNGs.
 *
 * <p>The caps are measured exactly as the generator measures them: the plain
 * panel ROW is the most common whole row, everything above its first occurrence
 * is the top cap and everything below its last is the bottom cap. Measuring
 * per-pixel colour instead gives a different answer, because the panel row
 * carries the 1px inset border lines and the last row does not.
 */
class CardArtworkTest {

    private static final File ART =
        new File("src/main/resources/tradingcards/tooltip-originals");

    private static final Path GENERATOR = Path.of("tools/generate_card_glyphs.py");

    @Test
    void theTopCapFitsInTheHeaderAboveTheTitle() throws Exception {
        int titleRow = generatorInt("TITLE_ROW");
        for (Tier tier : Tier.values()) {
            int top = caps(artFor(tier))[0];
            assertTrue(top <= titleRow,
                tier + " has a " + top + " row top cap, but the title starts at art row "
                    + titleRow + "; the cap would be drawn through it");
        }
    }

    @Test
    void theBottomCapFitsInTheLinesTheFrameLeavesForIt() throws Exception {
        // The cap starts on the bottom line, after BOTTOM_LEAD plain rows, and
        // the blank lines after it have to cover the rest of it.
        int line = generatorInt("LINE");
        int lead = generatorInt("BOTTOM_LEAD");
        int extra = generatorInt("BOTTOM_EXTRA_LINES");
        for (Tier tier : Tier.values()) {
            int bot = caps(artFor(tier))[1];
            assertEquals(extra, (lead + bot + line - 1) / line - 1,
                tier + " has a " + bot + " row bottom cap, which needs a different "
                    + "number of blank lines after it than CardFrame leaves");
        }
    }

    @Test
    void theMiddleBandIsUniformSoOneRowCanStandForIt() {
        // The body glyph is one plain panel row repeated, and every line between
        // the caps gets that glyph. If the art ever gains a rule or a flourish in
        // the middle, the cards lose it rather than repeating it.
        for (Tier tier : Tier.values()) {
            BufferedImage art = artFor(tier);
            List<List<Integer>> rows = rows(art);
            int[] caps = caps(art);
            List<Integer> panel = modalRow(rows);
            for (int y = caps[0]; y < art.getHeight() - caps[1]; y++) {
                assertEquals(panel, rows.get(y),
                    tier + " row " + y + " differs from the panel row, so the "
                        + "body glyph would drop it");
            }
        }
    }

    @Test
    void everyTierIsTheSameWidth() {
        // One space steps back from the end of the frame glyph to the text for
        // every tier, so a tier drawn at another width puts its text elsewhere.
        Set<Integer> widths = new HashSet<>();
        for (Tier tier : Tier.values()) {
            widths.add(artFor(tier).getWidth());
        }
        assertEquals(1, widths.size(), "tiers disagree on the frame width: " + widths);
    }

    @Test
    void theTopCapIsWiderThanZeroSoTheOrnamentIsNotLost() {
        for (Tier tier : Tier.values()) {
            assertTrue(caps(artFor(tier))[0] > 0,
                tier + " has no top ornament; the title would sit on the panel");
        }
    }

    private static int generatorInt(String name) throws Exception {
        var matcher = Pattern.compile("^" + name + " = (\\d+)", Pattern.MULTILINE)
            .matcher(Files.readString(GENERATOR));
        if (!matcher.find()) {
            throw new AssertionError(name + " not found in " + GENERATOR);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** Top cap then bottom cap, measured the way the generator measures them. */
    private static int[] caps(BufferedImage art) {
        List<List<Integer>> rows = rows(art);
        int h = rows.size();
        List<Integer> panel = modalRow(rows);

        List<List<Integer>> reversed = new ArrayList<>(rows);
        java.util.Collections.reverse(reversed);
        int first = rows.indexOf(panel);
        int last = h - 1 - reversed.indexOf(panel);
        return new int[] {first, h - 1 - last};
    }

    private static List<Integer> modalRow(List<List<Integer>> rows) {
        Map<List<Integer>, Integer> counts = new HashMap<>();
        for (List<Integer> row : rows) {
            counts.merge(row, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    private static List<List<Integer>> rows(BufferedImage art) {
        List<List<Integer>> rows = new ArrayList<>();
        for (int y = 0; y < art.getHeight(); y++) {
            List<Integer> row = new ArrayList<>(art.getWidth());
            for (int x = 0; x < art.getWidth(); x++) {
                row.add(art.getRGB(x, y));
            }
            rows.add(row);
        }
        return rows;
    }

    private static BufferedImage artFor(Tier tier) {
        File f = new File(ART, "card_" + tier.name().toLowerCase(java.util.Locale.ROOT)
            + "_frame.png");
        try {
            return ImageIO.read(f);
        } catch (Exception e) {
            throw new AssertionError("cannot read artwork " + f, e);
        }
    }
}