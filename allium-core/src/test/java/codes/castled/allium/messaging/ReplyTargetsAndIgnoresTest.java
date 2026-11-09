package codes.castled.allium.messaging;

import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplyTargetsAndIgnoresTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @TempDir
    Path dir;

    @Test
    void theMostRecentPartnerWinsWhicheverWayTheMessageWent() {
        ReplyTargets targets = new ReplyTargets();
        targets.delivered(A, B);
        assertEquals(Optional.of(B), targets.target(A));
        assertEquals(Optional.of(A), targets.target(B));
        targets.delivered(C, A); // C messaged A more recently than A messaged B
        assertEquals(Optional.of(C), targets.target(A));
        assertEquals(Optional.of(A), targets.target(B), "B's last partner is still A");
    }

    @Test
    void goingOfflineClearsEveryTargetPointingAtThem() {
        ReplyTargets targets = new ReplyTargets();
        targets.delivered(A, B);
        targets.delivered(C, B);
        targets.forget(B);
        assertEquals(Optional.empty(), targets.target(A));
        assertEquals(Optional.empty(), targets.target(C));
        assertEquals(Optional.empty(), targets.target(B));
    }

    @Test
    void theConsoleTakesPart() {
        ReplyTargets targets = new ReplyTargets();
        targets.delivered(ReplyTargets.CONSOLE, A);
        assertEquals(Optional.of(ReplyTargets.CONSOLE), targets.target(A));
        assertEquals(Optional.of(A), targets.target(ReplyTargets.CONSOLE));
    }

    @Test
    void ignoreListsRoundTripInTheirOwnTable() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("ignores.db");
        // A ticking clock: two adds inside one millisecond would otherwise tie on created_at.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        IgnoreRepository repository = new IgnoreRepository(() -> DriverManager.getConnection(url), SqlDialect.SQLITE, clock::incrementAndGet);
        assertEquals(1, repository.migrate());
        assertEquals(1, repository.migrate(), "idempotent");
        assertTrue(repository.add(A, B));
        assertFalse(repository.add(A, B), "adding twice keeps one entry");
        assertTrue(repository.add(A, C));
        assertEquals(List.of(B, C), List.copyOf(repository.load(A)));
        assertEquals(Set.of(), repository.load(B), "lists are per owner");
        assertTrue(repository.remove(A, B));
        assertFalse(repository.remove(A, B));
        IgnoreRepository restarted = new IgnoreRepository(() -> DriverManager.getConnection(url), SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(Set.of(C), restarted.load(A));
    }
}
