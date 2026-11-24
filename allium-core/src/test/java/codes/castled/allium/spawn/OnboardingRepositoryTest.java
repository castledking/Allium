package codes.castled.allium.spawn;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingRepositoryTest {

    @TempDir Path dir;

    @Test
    void ledgerPersistsClaimsReleasesAndIdempotencyAcrossReopen() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("onboarding.db"));
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        OnboardingRepository repo = new OnboardingRepository(cs, SqlDialect.SQLITE, clock::get);
        assertEquals(1, repo.migrate());
        assertEquals(1, repo.migrate());

        UUID player = UUID.randomUUID();
        assertTrue(repo.load(player).isEmpty());

        OnboardingRepository.State initial = repo.begin(player);
        assertFalse(initial.announced());
        assertFalse(initial.placed());
        assertFalse(initial.kitGranted());
        assertFalse(initial.completed());

        // Atomic announce claim succeeds once only.
        assertTrue(repo.claimAnnounce(player));
        assertFalse(repo.claimAnnounce(player));

        // Kit claim can be released on pre-delivery outage, then re-claimed cleanly.
        assertTrue(repo.claimKit(player, "starter"));
        assertFalse(repo.claimKit(player, "starter"));
        assertTrue(repo.releaseKit(player));
        assertTrue(repo.claimKit(player, "starter"));

        assertTrue(repo.markPlaced(player));
        assertFalse(repo.markPlaced(player));
        assertTrue(repo.markCompleted(player));
        assertFalse(repo.markCompleted(player));

        // Release after completion is a no-op.
        assertFalse(repo.releaseKit(player));

        // Reopen repository and verify durable state.
        OnboardingRepository reopened = new OnboardingRepository(cs, SqlDialect.SQLITE, clock::get);
        OnboardingRepository.State reloaded = reopened.begin(player);
        assertTrue(reloaded.announced());
        assertTrue(reloaded.placed());
        assertTrue(reloaded.kitGranted());
        assertEquals("starter", reloaded.kitName());
        assertTrue(reloaded.completed());
    }
}
