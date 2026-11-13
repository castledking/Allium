package codes.castled.allium.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MailReceiptsTest {

    @Test
    void sendAndClaimReceiptsRoundTripAndUnknownEntriesAreKept() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        List<MailReceipts.Entry> entries = List.of(new MailReceipts.Sent(a), new MailReceipts.Claimed(b, token, List.of(64, 0, 12)),
                new MailReceipts.Unknown("X/future-format"));
        String raw = MailReceipts.format(entries);
        assertEquals(entries, MailReceipts.parse(raw));
        assertEquals(List.of(), MailReceipts.parse(null));
        assertEquals(List.of(), MailReceipts.parse(" "));
        assertInstanceOf(MailReceipts.Unknown.class, MailReceipts.parse("C/not-a-uuid/x/1").get(0), "a damaged entry is kept, not dropped");
        assertThrows(IllegalArgumentException.class, () -> new MailReceipts.Claimed(a, token, List.of(-1)));
    }
}
