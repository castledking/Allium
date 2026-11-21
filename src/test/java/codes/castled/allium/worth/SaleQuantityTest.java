package codes.castled.allium.worth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SaleQuantityTest {

    private static SaleQuantity.Result resolve(String text, int available, boolean stacksOnly) {
        return SaleQuantity.parse(text).resolve(available, 64, stacksOnly);
    }

    @Test
    void readsEssentialsAmounts() {
        assertEquals(new SaleQuantity(0, false), SaleQuantity.parse(null));
        assertEquals(new SaleQuantity(10, false), SaleQuantity.parse("10"));
        assertEquals(new SaleQuantity(-10, false), SaleQuantity.parse("-10"));
        assertEquals(new SaleQuantity(2, true), SaleQuantity.parse("2s"));
        assertEquals(new SaleQuantity(-1, true), SaleQuantity.parse("-1S"));
        for (String bad : new String[]{"", "s", "-", "ten", "1.5", "12345678", "--1", "1ss"}) {
            assertThrows(IllegalArgumentException.class, () -> SaleQuantity.parse(bad), bad);
        }
    }

    @Test
    void allAllButAndStacksResolveAgainstWhatIsHeld() {
        assertEquals(new SaleQuantity.Sell(100), resolve(null, 100, false));
        assertEquals(new SaleQuantity.Sell(100), resolve("0", 100, false));
        assertEquals(new SaleQuantity.Sell(10), resolve("10", 100, false));
        assertEquals(new SaleQuantity.Sell(90), resolve("-10", 100, false));
        assertEquals(new SaleQuantity.Sell(64), resolve("1s", 100, false));
        assertEquals(new SaleQuantity.Sell(36), resolve("-1s", 100, false));
        assertEquals(new SaleQuantity.NotEnough(100), resolve("101", 100, false));
        assertEquals(new SaleQuantity.NotEnough(100), resolve("-100", 100, false));
        assertEquals(new SaleQuantity.NotEnough(100), resolve("2s", 100, false));
        assertEquals(new SaleQuantity.NotEnough(0), resolve(null, 0, false));
    }

    @Test
    void tradeInStacksItemsSellOnlyWholeStacks() {
        assertEquals(new SaleQuantity.Sell(128), resolve(null, 150, true));
        assertEquals(new SaleQuantity.Sell(64), resolve("1s", 150, true));
        assertEquals(new SaleQuantity.Sell(64), resolve("-1s", 150, true));
        assertEquals(new SaleQuantity.MustStack(64), resolve("10", 150, true));
        assertEquals(new SaleQuantity.NotEnough(40), resolve(null, 40, true));
    }
}
