package codes.castled.allium.economy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepairReceiptsTest {

    @Test
    void receiptsRoundTripAndAnEmptySetRemovesTheKey() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Set<UUID> ids = new LinkedHashSet<>(List.of(a, b));
        String raw = RepairReceipts.format(ids);
        assertEquals(a + "," + b, raw);
        assertEquals(ids, RepairReceipts.parse(raw));
        assertNull(RepairReceipts.format(Set.of()));
        assertTrue(RepairReceipts.parse(null).isEmpty());
        assertTrue(RepairReceipts.parse("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RepairReceipts.parse(a + ",not-a-uuid"),
                "a damaged receipt is never read as 'absent'");
    }
}
