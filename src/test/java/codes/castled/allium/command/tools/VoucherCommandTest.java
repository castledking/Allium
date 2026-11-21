package codes.castled.allium.command.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VoucherCommandTest {

    @Test
    void aVoucherIsNamedAsInVouchersYmlOrAsItsTool() {
        Set<String> tools = Set.of("voucher_tfly10m", "voucher_randomglow", "lazy_axe");
        assertEquals("voucher_tfly10m", VoucherCommand.resolve("TFly10m", tools::contains));
        assertEquals("voucher_tfly10m", VoucherCommand.resolve("voucher_tfly10m", tools::contains));
        assertEquals("voucher_randomglow", VoucherCommand.resolve("RandomGlow", tools::contains));
        assertNull(VoucherCommand.resolve("lazy_axe", tools::contains), "only voucher_ tools are vouchers");
        assertNull(VoucherCommand.resolve("missing", tools::contains));
    }
}
