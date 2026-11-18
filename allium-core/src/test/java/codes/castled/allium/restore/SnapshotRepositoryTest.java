package codes.castled.allium.restore;

import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotRepositoryTest {

    @TempDir
    Path dir;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private SnapshotRepository repository() throws Exception {
        String file = dir.resolve("restore-" + System.nanoTime() + ".db").toString();
        SnapshotRepository repo = new SnapshotRepository(() -> DriverManager.getConnection("jdbc:sqlite:" + file), SqlDialect.SQLITE,
                System::currentTimeMillis);
        assertEquals(1, repo.migrate());
        assertEquals(1, repo.migrate());
        return repo;
    }

    private static SnapshotRepository.Snapshot snap(UUID player, long at, String reason) {
        return new SnapshotRepository.Snapshot(UUID.randomUUID(), player, at, reason, "world", 1.5, 64, -2.5, 90f, 10f, 30, 0.5f,
                "inventory-payload", "ender-payload", "Allium");
    }

    @Test
    void pagesAreNewestFirstAndTheHistoryIsCapped() throws Exception {
        SnapshotRepository repo = repository();
        for (int i = 0; i < 7; i++) {
            repo.save(snap(ALICE, 1000 + i, "AUTOSAVE"), 5);
        }
        repo.save(snap(BOB, 500, "DEATH"), 5);
        assertEquals(5, repo.count(ALICE), "only the newest five are kept");
        List<SnapshotRepository.Snapshot> first = repo.page(ALICE, 0, 3);
        assertEquals(List.of(1006L, 1005L, 1004L), first.stream().map(SnapshotRepository.Snapshot::createdAt).toList());
        assertEquals(List.of(1003L, 1002L), repo.page(ALICE, 3, 3).stream().map(SnapshotRepository.Snapshot::createdAt).toList());
        SnapshotRepository.Snapshot found = repo.find(first.get(0).id()).orElseThrow();
        assertEquals("inventory-payload", found.inventory());
        assertEquals(30, found.level());
        assertEquals(0.5f, found.progress());
        assertEquals(1, repo.purge(501), "the age limit removes Bob's old snapshot");
        assertEquals(0, repo.count(BOB));
    }

    @Test
    void pendingRestoresAreQueuedPerPlayerAndFinishedOnce() throws Exception {
        SnapshotRepository repo = repository();
        UUID id = UUID.randomUUID();
        repo.queue(new SnapshotRepository.Pending(id, BOB, snap(ALICE, 1000, "MANUAL"), true, false, true, false, "Staff", 2000));
        List<SnapshotRepository.Pending> pending = repo.pending(BOB);
        assertEquals(1, pending.size());
        assertEquals(BOB, pending.get(0).player(), "the restore targets Bob even though the snapshot was Alice's");
        assertTrue(pending.get(0).inventory());
        assertFalse(pending.get(0).armor());
        assertTrue(pending.get(0).experience());
        assertEquals("inventory-payload", pending.get(0).snapshot().inventory());
        assertTrue(repo.pending(ALICE).isEmpty());
        assertTrue(repo.finish(id));
        assertFalse(repo.finish(id), "finishing twice changes nothing");
        assertTrue(repo.pending(BOB).isEmpty());
    }
}
