package codes.castled.allium.tradingcards.gui;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardMenuLayoutTest {
    @Test
    void homeButtonsCoverTheBottomRowWithoutOverlapping() {
        var used = new HashSet<Integer>();
        int[] starts = {CardMenuGui.SLOT_BONUSES, CardMenuGui.SLOT_MERGE,
            CardMenuGui.SLOT_TRADE, CardMenuGui.SLOT_EXIT};
        int[] widths = {3, 2, 2, 2};
        for (int i = 0; i < starts.length; i++) {
            for (int n = 0; n < widths[i]; n++) {
                int slot = starts[i] + n;
                assertTrue(slot >= 36 && slot < 45);
                assertTrue(used.add(slot), "overlapping button at " + slot);
            }
        }
        assertEquals(9, used.size());
        assertEquals(13, CardMenuGui.SLOT_CARD);
    }

    @Test
    void contentNeverCollidesWithTheSharedNavigation() {
        for (int content : new int[] {CardBonusGui.SLOT_FIRST, CardBonusGui.SLOT_FIRST + 4,
                CardBonusGui.SLOT_CARD, CardMergeGui.SLOT_FIRST, CardMergeGui.SLOT_SECOND,
                CardMergeGui.SLOT_RESULT, TradeInGui.SLOT_CARD, TradeInGui.SLOT_CONFIRM}) {
            assertTrue(content >= 0 && content < 36);
        }
        assertEquals(38, CardGui.SLOT_BACK);
        assertEquals(40, CardGui.SLOT_CLOSE);
        assertEquals(41, CardGui.SLOT_NEXT);
    }
}
