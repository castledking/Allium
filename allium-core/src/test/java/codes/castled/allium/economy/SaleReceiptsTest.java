package codes.castled.allium.economy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaleReceiptsTest {

    @Test
    void receiptsRoundTripInOrder() {
        Map<UUID, Money> sales = new LinkedHashMap<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        sales.put(first, new Money(150));
        sales.put(second, new Money(65_000));
        String text = SaleReceipts.format(sales);
        assertEquals(first + ":150," + second + ":65000", text);
        assertEquals(sales, SaleReceipts.parse(text));
        assertTrue(SaleReceipts.parse(null).isEmpty());
        assertTrue(SaleReceipts.parse("").isEmpty());
        assertNull(SaleReceipts.format(Map.of()));
    }

    @Test
    void aMalformedEntrySpoilsTheWholeReceipt() {
        UUID sale = UUID.randomUUID();
        for (String bad : new String[]{sale.toString(), sale + ":0", sale + ":-5", sale + ":x", "nope:5", sale + ":5,", ","}) {
            assertThrows(IllegalArgumentException.class, () -> SaleReceipts.parse(bad), bad);
        }
    }

    @Test
    void theDepositOperationIsFixedPerSale() {
        UUID sale = UUID.randomUUID();
        assertEquals(SaleReceipts.operation(sale), SaleReceipts.operation(UUID.fromString(sale.toString())));
        assertNotEquals(SaleReceipts.operation(sale), SaleReceipts.operation(UUID.randomUUID()));
        assertNotEquals(sale, SaleReceipts.operation(sale));
    }
}
