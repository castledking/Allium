package codes.castled.allium.tradingcards.card;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardCraftListenerTest {

    private static TradingCardData card(String mob) {
        return new TradingCardData(mob, mob.toLowerCase(), Tier.SIMPLE, 0, 32,
            List.of("luck", "strength", "speed"), 0, false);
    }

    @Test
    void anUpperCasedCardIsTheResultForItsMob() {
        // Cards are written with the loader's upper-cased mob. Missing this sent
        // every click to vanilla, which handed over the preview without spending
        // the held roll, so each craft repeated the same quality.
        assertTrue(CardCraftListener.isCardOf(card("ZOMBIE"), "ZOMBIE"));
        assertTrue(CardCraftListener.isCardOf(card("ZOMBIE"), "zombie"));
    }

    @Test
    void anotherMobsCardIsNot() {
        assertFalse(CardCraftListener.isCardOf(card("SKELETON"), "ZOMBIE"));
    }
}
