package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rolled strength of a bonus: stored beside it, and drawn as a bar under it. */
class BonusStrengthLoreTest {


    private static CardLore lore() {
        return new CardLore(new CardLore.LoreData(
            List.of(new QualityBand("worn", 1, 100, 1, "<gray>")),
            (id, level) -> id, "heads", 100, 5, "", level -> 25.0, mob -> "aqua", null,
            (id, value) -> id + " " + value, id -> id, 10,
            (id, strength) -> id + " @" + strength,
            // "token" stands in for a permission boost: nothing to roll, no bar.
            (id, strength) -> "token".equals(id) ? null : strength == null ? 50 : strength));
    }

    private static int segments(String bar, String colour) {
        int at = bar.indexOf("<" + colour + "><strikethrough>");
        if (at < 0) return 0;
        String rest = bar.substring(at + colour.length() + 17);
        return rest.substring(0, rest.indexOf("</strikethrough>")).length();
    }

    @Test
    void theWeakestRollIsOneDarkRedSegment() {
        String bar = lore().strengthBarFor("money-quest", 0);
        assertEquals(1, segments(bar, "dark_red"));
        assertEquals(9, segments(bar, "dark_gray"));
        assertTrue(bar.endsWith("<gray>0%</gray>"));
    }

    @Test
    void onlyAPerfectRollFillsTheBarGreen() {
        String best = lore().strengthBarFor("money-quest", 100);
        assertEquals(10, segments(best, "green"));
        assertEquals(0, segments(best, "dark_gray"));
        String nearly = lore().strengthBarFor("money-quest", 99);
        assertEquals(1, segments(nearly, "dark_gray"),
            "a 99 that drew a full bar would read as the best roll possible");
    }

    @Test
    void theBarWarmsThroughTheLevelBarsColours() {
        assertTrue(lore().strengthBarFor("money-quest", 30).contains("<red>"));
        assertTrue(lore().strengthBarFor("money-quest", 50).contains("<gold>"));
        assertTrue(lore().strengthBarFor("money-quest", 70).contains("<yellow>"));
        assertTrue(lore().strengthBarFor("money-quest", 85).contains("<dark_green>"));
    }

    @Test
    void aBoostWithNothingToRollGetsNoBar() {
        assertEquals("", lore().strengthBarFor("token", 80));
    }

    @Test
    void theCardsBonusLineReadsTheStrengthItsSlotRolled() {
        var card = new TradingCardData("AXOLOTL", "axolotl", Tier.ELITE, 0, 60,
            List.of("luck", "strength", "speed"), List.of("money-quest", "armor"), 0.0, 0, false,
            Arrays.asList(87, null));
        var slots = lore().bonusSlots(card);
        assertEquals("money-quest @87", slots.get(0));
        assertEquals("armor @null", slots.get(1), "a bonus from before strengths has none stored");
    }

    @Test
    void strengthsReadBackInSlotOrderWithGapsKept() {
        assertEquals(Arrays.asList(87, null, 42), TradingCardData.readStrengths("87,-,42"));
        assertEquals(List.of(), TradingCardData.readStrengths(null));
    }

    @Test
    void settingASlotDropsTheStrengthOfTheBonusItReplaces() {
        var card = new TradingCardData("AXOLOTL", "axolotl", Tier.ELITE, 0, 60,
            List.of("luck"), List.of("money-quest", "armor"), 0.0, 0, false, List.of(87, 12));
        var replaced = card.withBonusAt(0, "haste", 5);
        assertNull(replaced.bonusStrengthAt(0));
        assertEquals(12, replaced.bonusStrengthAt(1));
        assertFalse(replaced.bonusStrengths().isEmpty());
    }
}
