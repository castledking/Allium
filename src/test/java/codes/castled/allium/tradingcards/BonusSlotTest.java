package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.bonus.BonusSlot;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bonus slot state: what a slot remembers between clicks.
 *
 * <p>A slot is bought repeatedly, so it has to remember how many times it has
 * been rolled (which sets the next price), how much has been spent (which the
 * card shows back) and whether it is locked. That state is written into a single
 * compact string on the item, so the encoding is a wire format: a slot that
 * decoded wrong would silently reset a player's roll count and hand them a
 * cheaper next roll.
 */
class BonusSlotTest {

    @Test
    void anUntouchedSlotEncodesToNothing() {
        // A card nobody has rolled must not grow a state blob for five default
        // slots; empty means "write no key at all".
        assertEquals("", BonusSlot.EMPTY.encode());
    }

    @Test
    void aRolledSlotRoundTripsItsCountersAndSpend() {
        var slot = new BonusSlot("crop_yield", 3, 8400.0, false);
        var back = BonusSlot.decode(slot.encode());
        assertEquals(3, back.rolls());
        assertEquals(8400.0, back.spent(), 0.001);
        assertFalse(back.locked());
    }

    @Test
    void rollingASlotCountsTheRollAndTheMoney() {
        var slot = BonusSlot.EMPTY.rolled("crop_yield", 2000.0);
        assertEquals(1, slot.rolls());
        assertEquals(2000.0, slot.spent(), 0.001);
        assertEquals("crop_yield", slot.id());
    }

    @Test
    void aLockedSlotRoundTrips() {
        var back = BonusSlot.decode(new BonusSlot("luck", 1, 2000.0, true).encode());
        assertTrue(back.locked());
        assertEquals(1, back.rolls());
    }

    @Test
    void theBoostItselfIsNotInThisEncoding() {
        // Deliberate: the boost id lives in its own component, which predates
        // slots being rolled individually and is read by cards already in
        // circulation. Encoding it twice would let the two disagree.
        assertEquals("", BonusSlot.decode(new BonusSlot("luck", 1, 0.0, false).encode()).id());
    }

    @Test
    void clearingKeepsTheRollCountAndTheSpend() {
        // The player paid for those rolls. Showing the slot as never rolled
        // would misrepresent what happened, and would make clearing a way to
        // resell a slot at the first-roll price.
        var slot = new BonusSlot("luck", 4, 20000.0, false).cleared();
        assertTrue(slot.isEmpty());
        assertEquals(4, slot.rolls());
        assertEquals(20000.0, slot.spent(), 0.001);
    }

    @Test
    void malformedStateCostsTheStateNotTheSlot() {
        // The boost lives in a separate component, so a corrupt state blob must
        // not take the bonus with it.
        assertEquals(BonusSlot.EMPTY, BonusSlot.decode("not:a:number"));
        assertEquals(BonusSlot.EMPTY, BonusSlot.decode(null));
        assertEquals(BonusSlot.EMPTY, BonusSlot.decode(""));
    }

    @Test
    void theTierDecidesHowManySlotsOpen() {
        var pricing = TradingCardsConfig.BonusSlotRoll.defaults();
        assertEquals(2000.0, pricing.costFor(0, Tier.SIMPLE), 0.01);
        // Escalating, so the next roll always costs more than the last.
        assertTrue(pricing.costFor(1, Tier.SIMPLE) > pricing.costFor(0, Tier.SIMPLE),
            "a flat per-slot fee makes rolling until satisfied free of friction");
    }

    @Test
    void aHigherTierPaysMoreForTheSameSlot() {
        var pricing = TradingCardsConfig.BonusSlotRoll.defaults();
        assertTrue(pricing.costFor(0, Tier.FABLED) > pricing.costFor(0, Tier.SIMPLE),
            "the ladder has to be worth climbing, or the top tier buys nothing");
    }

    @Test
    void pricesRoundToSomethingAPlayerCanRead() {
        var pricing = TradingCardsConfig.BonusSlotRoll.defaults();
        for (int rolls = 0; rolls < 6; rolls++) {
            for (Tier tier : Tier.values()) {
                double cost = pricing.costFor(rolls, tier);
                assertEquals(0.0, cost % 100, 0.01,
                    tier + " roll " + rolls + " costs " + cost + ", which is not a round figure");
            }
        }
    }

    @Test
    void aNegativeEscalationIsRejectedRatherThanRewardingThePlayer() {
        var issues = new java.util.ArrayList<codes.castled.allium.tradingcards.config.ValidationIssue>();
        var section = new org.bukkit.configuration.MemoryConfiguration();
        section.set("bonus-slot-roll.escalation", 0.5);
        // Loaded through the public path so the clamp is exercised, not asserted
        // against the record directly.
        var loaded = TradingCardsConfig.load(section);
        assertTrue(loaded.issues().stream().anyMatch(i -> i.path() != null
                && i.path().contains("bonus-slot-roll.escalation")),
            "a sub-1 escalation means each roll costs less than the last; expected a warning, got "
                + loaded.issues());
    }

    @Test
    void anAbsentBlockLoadsTheDefaultsRatherThanFailing() {
        var loaded = TradingCardsConfig.load(new org.bukkit.configuration.MemoryConfiguration());
        assertFalse(loaded.config().bonusSlotRoll() == null,
            "a config predating the block must still load");
        assertEquals(2000.0, loaded.config().bonusSlotRoll().costFor(0, Tier.SIMPLE), 0.01);
    }

    @Test
    void slotsArePaddedSoTheLastOneIsReachable() {
        // A slot is identified by index. A list shorter than the slot count would
        // make the tail unreachable rather than empty.
        var card = new codes.castled.allium.tradingcards.item.TradingCardData(
            "ALLAY", "allay", Tier.FABLED, 1, 90, List.of("luck"),
            List.of("haste"), 0.0, 0, false);
        assertEquals("haste", card.bonusAt(0));
        assertEquals("", card.bonusAt(1));
        assertEquals("", card.bonusAt(4), "a slot beyond the list must read empty, not throw");
        assertEquals("", card.bonusAt(-1));
    }

    @Test
    void settingOneSlotLeavesTheOthersAlone() {
        var card = new codes.castled.allium.tradingcards.item.TradingCardData(
            "ALLAY", "allay", Tier.FABLED, 1, 90, List.of("luck"),
            List.of("haste", ""), 0.0, 0, false);
        var next = card.withBonusAt(2, "scale", 5);
        assertEquals("haste", next.bonusAt(0));
        assertEquals("", next.bonusAt(1));
        assertEquals("scale", next.bonusAt(2));
        assertEquals("", next.bonusAt(4), "the tail must stay addressable after a write");
    }
}