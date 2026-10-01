package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.tradingcards.gui.CardWorkshopGui;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workshop lays out slots in one window, and a collision between them is
 * invisible until a player is stuck: a staged card sitting on a tab button
 * swallows the tab click and strands them in a tab they cannot leave.
 */
class CardWorkshopLayoutTest {

    @Test
    void noTwoInteractiveSlotsShareAnIndex() {
        Set<Integer> seen = new HashSet<>();
        int[] slots = {
            CardWorkshopGui.SLOT_TAB_REROLL,
            CardWorkshopGui.SLOT_TAB_MERGE,
            CardWorkshopGui.SLOT_TAB_TRADE,
            CardWorkshopGui.SLOT_CARD,
            CardWorkshopGui.SLOT_ACTION,
            CardWorkshopGui.SLOT_MERGE_A,
            CardWorkshopGui.SLOT_MERGE_B,
        };
        for (int slot : slots) {
            assertTrue(seen.add(slot), "slot " + slot + " is used twice");
        }
        assertEquals(slots.length, seen.size());
    }

    @Test
    void theMergeSlotsAreNotOnTheTabRow() {
        // The specific collision this layout exists to avoid.
        assertTrue(CardWorkshopGui.SLOT_MERGE_A > 20,
            "the first merge slot must be below the tab row");
        assertTrue(CardWorkshopGui.SLOT_MERGE_B > 20,
            "the second merge slot must be below the tab row");
        for (int tab : new int[] {CardWorkshopGui.SLOT_TAB_REROLL,
            CardWorkshopGui.SLOT_TAB_MERGE, CardWorkshopGui.SLOT_TAB_TRADE}) {
            assertTrue(CardWorkshopGui.SLOT_MERGE_A != tab && CardWorkshopGui.SLOT_MERGE_B != tab,
                "a merge slot collides with a tab button at " + tab);
        }
    }

    @Test
    void everySlotIsInsideAFiveRowWindow() {
        int[] slots = {
            CardWorkshopGui.SLOT_TAB_REROLL, CardWorkshopGui.SLOT_TAB_MERGE,
            CardWorkshopGui.SLOT_TAB_TRADE, CardWorkshopGui.SLOT_CARD,
            CardWorkshopGui.SLOT_ACTION, CardWorkshopGui.SLOT_MERGE_A,
            CardWorkshopGui.SLOT_MERGE_B,
        };
        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < 45,
                "slot " + slot + " falls outside a 45-slot window");
        }
    }
}
