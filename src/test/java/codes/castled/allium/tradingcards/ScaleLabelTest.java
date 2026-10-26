package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScaleLabelTest {

    @Test
    void aSizeReadsAsAMultiplierWithItsDecimal() {
        assertEquals("0.6x", TradingCardsModule.size(0.6));
        assertEquals("2.0x", TradingCardsModule.size(2.0));
        assertEquals("1.37x", TradingCardsModule.size(1.372));
        assertEquals("0.61x", TradingCardsModule.size(0.614));
    }
}
