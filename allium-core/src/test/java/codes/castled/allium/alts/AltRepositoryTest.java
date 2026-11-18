package codes.castled.allium.alts;

import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AltRepositoryTest {

    @TempDir
    Path dir;

    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private AltRepository repository() throws Exception {
        String file = dir.resolve("alts-" + System.nanoTime() + ".db").toString();
        AltRepository repo = new AltRepository(() -> DriverManager.getConnection("jdbc:sqlite:" + file), SqlDialect.SQLITE,
                System::currentTimeMillis);
        assertEquals(1, repo.migrate());
        assertEquals(1, repo.migrate(), "migrating again changes nothing");
        return repo;
    }

    @Test
    void theLaterAccountOnAnIpIsTheAlt() throws Exception {
        AltRepository repo = repository();
        repo.record(MAIN, "10.0.0.1", 1000);
        repo.record(ALT, "10.0.0.1", 2000);
        repo.record(OTHER, "10.0.0.9", 500);
        assertFalse(repo.isAlt(MAIN, "10.0.0.1"), "the first account is the main");
        assertTrue(repo.isAlt(ALT, "10.0.0.1"));
        assertFalse(repo.isAlt(OTHER, "10.0.0.9"));
        // Joining again later never changes who was first.
        repo.record(MAIN, "10.0.0.1", 9000);
        assertFalse(repo.isAlt(MAIN, "10.0.0.1"));
        assertTrue(repo.isAlt(ALT, "10.0.0.1"));
    }

    @Test
    void linkedAccountsSpanTheWholeIpHistory() throws Exception {
        AltRepository repo = repository();
        repo.record(MAIN, "10.0.0.1", 1000);
        repo.record(ALT, "10.0.0.1", 2000);
        repo.record(ALT, "10.0.0.2", 3000); // the alt moved
        repo.record(OTHER, "10.0.0.2", 4000);
        assertEquals(List.of(ALT), repo.linkedAccounts(MAIN, 10), "the old shared IP still links them");
        assertEquals(List.of(OTHER, MAIN), repo.linkedAccounts(ALT, 10), "most recently seen first");
        assertEquals(List.of(OTHER), repo.linkedAccounts(ALT, 1));
        assertEquals(Optional.of("10.0.0.2"), repo.lastIp(ALT));
        assertEquals(Optional.empty(), repo.lastIp(UUID.randomUUID()));
    }

    @Test
    void exemptionsCanBeAddedReplacedAndRemoved() throws Exception {
        AltRepository repo = repository();
        assertTrue(repo.exemption(ALT).isEmpty());
        repo.exempt(ALT, "Staff", "sibling", 100);
        repo.exempt(ALT, "Admin", "verified sibling", 200);
        AltRepository.Exemption e = repo.exemption(ALT).orElseThrow();
        assertEquals("Admin", e.actor());
        assertEquals("verified sibling", e.reason());
        assertTrue(repo.unexempt(ALT));
        assertFalse(repo.unexempt(ALT));
    }
}
