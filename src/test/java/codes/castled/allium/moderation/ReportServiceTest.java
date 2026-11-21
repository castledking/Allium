package codes.castled.allium.moderation;

import codes.castled.allium.naming.NameCache;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportServiceTest {

    @Test
    void aKnownFirstWordIsTheReportedPlayerAndTheRestIsWhatHappened() {
        NameCache.Entry steve = new NameCache.Entry(UUID.randomUUID(), "Steve", 0L);
        ReportService.Parsed report = ReportService.parse(new String[]{"steve", "griefed", "my", " house"}, steve);
        assertEquals("Steve", report.player());
        assertEquals(steve.uuid(), report.playerId());
        assertEquals("griefed my house", report.text());
        assertNull(ReportService.parse(new String[]{"steve"}, steve), "a player report needs what happened");

        ReportService.Parsed general = ReportService.parse(new String[]{"the", "spawn", "§cshop", "is", "broken"}, null);
        assertNull(general.player());
        assertEquals("the spawn cshop is broken", general.text());
        assertNull(ReportService.parse(new String[0], null));
        assertEquals(ReportService.MAX_LENGTH, ReportService.parse(new String[]{"x".repeat(400)}, null).text().length());
    }
}
