package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.CardTooltipStyle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The frame constants against the artwork they describe.
 *
 * <p>{@code CardTooltipStyle.FRAME_BASE} and
 * {@code tools/generate_tooltip_sprites.py} both have to agree on the same
 * numbers, in two files that cannot see each other. When they disagreed the
 * bottom cap's top rule landed on the last lore line — the frame still drew, it
 * was just drawn through the text, which no compiler or server log reports.
 *
 * <p>So the artwork is the source of truth here. The caps are measured from the
 * committed PNGs exactly as the generator measures them: the plain panel ROW is
 * the most common whole row, everything above its first occurrence is the top
 * cap and everything below its last is the bottom cap. Measuring per-pixel
 * colour instead gives a different answer, because the panel row carries the
 * 1px inset border lines and the last row does not.
 */
class CardArtworkTest {

    private static final File ART =
        new File("src/main/resources/tradingcards/tooltip-originals");

    @Test
    void theBottomCapMatchesWhatThePluginAssumes() {
        for (Tier tier : Tier.values()) {
            int[] caps = caps(artFor(tier));
            assertEquals(CardTooltipStyle.BOTTOM_CAP, caps[1],
                tier + " artwork has a " + caps[1] + " row bottom cap but "
                    + "CardTooltipStyle assumes " + CardTooltipStyle.BOTTOM_CAP
                    + "; regenerate the sprites and update BOTTOM_CAP together");
        }
    }

    @Test
    void theMiddleBandIsUniformSoTheShaderCanRepeatIt() {
        // The shader fills the space between the caps by repeating one plain
        // panel row. If the art ever gains a rule or a flourish in the middle,
        // that row stops being plain and the repeat smears it down the panel.
        for (Tier tier : Tier.values()) {
            BufferedImage art = artFor(tier);
            List<List<Integer>> rows = rows(art);
            int[] caps = caps(art);
            List<Integer> panel = modalRow(rows);
            for (int y = caps[0]; y < art.getHeight() - caps[1]; y++) {
                assertEquals(panel, rows.get(y),
                    tier + " row " + y + " differs from the panel row, so the "
                        + "shader's middle repeat would smear it");
            }
        }
    }

    @Test
    void everyTierProducesTheSameFrameHeight() {
        // CardTooltipStyle carries one FRAME_BASE for all tiers, so a tier whose
        // art measured differently would be drawn at the wrong height.
        Map<Tier, Integer> bases = new HashMap<>();
        for (Tier tier : Tier.values()) {
            int bot = caps(artFor(tier))[1];
            bases.put(tier, CardTooltipStyle.TEXT_TOP + bot + CardTooltipStyle.CAP_GAP);
        }
        assertEquals(1, new HashSet<>(bases.values()).size(),
            "tiers disagree on the frame base: " + bases);
        assertEquals(CardTooltipStyle.FRAME_BASE, bases.values().iterator().next());
    }

    @Test
    void theTopCapIsWiderThanZeroSoTheOrnamentIsNotLost() {
        for (Tier tier : Tier.values()) {
            assertTrue(caps(artFor(tier))[0] > 0,
                tier + " has no top ornament; the title would sit on the panel");
        }
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