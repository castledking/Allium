package codes.castled.allium.moderation;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.teleport.SavedLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite plus optional MySQL; every table is isolated by a random prefix. */
class ModerationS19RepositoryTest {
    @TempDir Path dir;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final SavedLocation ORIGIN = new SavedLocation(UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "world", -123.25, 67.5, 902.75, 90.5F, -30.25F);
    private static final SavedLocation CELL = new SavedLocation(UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "world_nether", 4.25, 80.5, -21.75, 180, 45);

    private static ModerationRepository repository(ModerationDatabases.Db db, AtomicLong clock) throws SQLException {
        ModerationRepository repo = reopen(db, clock);
        assertEquals(3, repo.migrate(), db.name());
        assertEquals(3, repo.migrate(), db.name());
        return repo;
    }

    private static ModerationRepository reopen(ModerationDatabases.Db db, AtomicLong clock) {
        return new ModerationRepository(db.source(), db.dialect(), clock::get);
    }

    @Test
    void migrationUpgradesV1AndReplaysInterruptedDdlWithoutLosingState() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                repo.setVanished(PLAYER, true, "Staff");
                MuteRecord mute = new MuteRecord(OTHER, "Other", "Staff", "mute", 900, null);
                repo.saveMute(mute);
                repo.recordAudit("MUTE", "Staff", OTHER, "Other", "existing audit");
                // Recreate the actual v1 layout/version, then upgrade it.
                try (Connection c = db.source().connection(); var s = c.createStatement()) {
                    for (String table : ModerationDatabases.S19_TABLES) s.execute("DROP TABLE " + table);
                    s.executeUpdate("UPDATE allium_schema SET version = 1 WHERE component = 'moderation'");
                }
                assertEquals(3, repo.migrate());
                BanRecord ban = new BanRecord(PLAYER, "Player", "Staff", "ban", 1000, null, "192.0.2.10");
                repo.saveBan(ban);
                // Simulate all v2+v3 DDL having committed before the schema version was recorded.
                try (Connection c = db.source().connection(); var s = c.createStatement()) {
                    s.executeUpdate("UPDATE allium_schema SET version = 1 WHERE component = 'moderation'");
                }
                assertEquals(3, repo.migrate(), "existing index and column definitions are safely recognized");
                assertEquals(3, repo.migrate());
                assertTrue(repo.loadVanished(PLAYER));
                assertEquals(Optional.of(mute), repo.loadActiveMute(OTHER));
                assertEquals(Optional.of(ban), repo.loadActiveBan(PLAYER));
                assertEquals(List.of("BAN", "MUTE"), repo.loadRecentAudit(10).stream().map(ModerationAuditRecord::action).toList());
            }
        }
    }

    @Test
    void bansPersistReplaceAndExpireInclusivelyWithoutDeletingNewerOrSharedIpBans() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                BanRecord permanent = new BanRecord(PLAYER, "Player", "Staff", "permanent", 900, null, "192.0.2.10");
                BanRecord timed = new BanRecord(OTHER, "Other", "Staff", "timed", 1000, 2000L, "192.0.2.10");
                UUID uuidOnly = UUID.randomUUID();
                BanRecord noIp = new BanRecord(uuidOnly, "OnlyUuid", "Staff", null, 1100, null, null);
                repo.saveBan(permanent);
                repo.saveBan(timed);
                repo.saveBan(noIp);
                ModerationRepository restarted = reopen(db, clock);
                assertEquals(List.of(permanent, timed, noIp), restarted.loadActiveBans());
                assertEquals(Optional.of(timed), restarted.loadBanByIp("192.0.2.10"));
                assertTrue(restarted.loadBanByIp(null).isEmpty());
                assertTrue(restarted.loadBanByIp(" ").isEmpty());
                assertTrue(restarted.loadBanByIp("192.0.2.20").isEmpty());
                clock.set(1999);
                assertEquals(Optional.of(timed), restarted.loadActiveBan(OTHER));
                clock.set(2000);
                assertTrue(restarted.loadActiveBan(OTHER).isEmpty());
                assertEquals(Optional.of(permanent), restarted.loadBanByIp("192.0.2.10"));
                assertEquals(List.of(permanent, noIp), restarted.loadActiveBans());
                assertEquals(3, count(db, ModerationRepository.BANS_TABLE), "expiry reads never issue a stale UUID delete");
                BanRecord renewed = new BanRecord(OTHER, "OtherNewName", "Console", "renewed", 2000, null, null);
                repo.saveBan(renewed);
                assertEquals(Optional.of(renewed), restarted.loadActiveBan(OTHER));
                assertTrue(repo.removeBan(PLAYER, "Releaser"));
                assertFalse(repo.removeBan(PLAYER, "Releaser"));
                assertTrue(restarted.loadBanByIp("192.0.2.10").isEmpty());
                assertEquals(Optional.of(renewed), restarted.loadActiveBan(OTHER));
                ModerationAuditRecord audit = repo.loadRecentAudit(1).get(0);
                assertEquals("UNBAN", audit.action());
                assertEquals("Releaser", audit.actorName());
                assertEquals("Player", audit.targetName());
                assertEquals(5, repo.loadRecentAudit(20).size(), "no audit for a missing removal or expiry read");
            }
        }
    }

    @Test
    void freezesSurviveRestartAndAuditUpdatesAndReleaseExactlyOnce() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                FreezeRecord initial = new FreezeRecord(PLAYER, "Player", "Staff", "investigating", 900);
                FreezeRecord updated = new FreezeRecord(PLAYER, "Renamed", "OtherStaff", "still investigating", 1000);
                repo.setFrozen(initial);
                repo.setFrozen(updated);
                ModerationRepository restarted = reopen(db, clock);
                assertEquals(Optional.of(updated), restarted.loadFrozen(PLAYER));
                assertEquals(List.of(updated), restarted.loadFrozenPlayers());
                assertTrue(restarted.loadFrozen(OTHER).isEmpty());
                assertTrue(repo.removeFrozen(PLAYER, "Releaser"));
                assertFalse(repo.removeFrozen(PLAYER, "Releaser"));
                assertTrue(restarted.loadFrozenPlayers().isEmpty());
                assertEquals(List.of("UNFREEZE", "FREEZE", "FREEZE"), repo.loadRecentAudit(10).stream()
                        .map(ModerationAuditRecord::action).toList());
                assertEquals("Renamed", repo.loadRecentAudit(1).get(0).targetName());
            }
        }
    }

    @Test
    void jailReassignmentKeepsOriginalOriginAndDeletionRefusesPersistedOccupants() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                assertFalse(repo.setJailed(new JailAssignment(PLAYER, "Player", "Staff", "reason", 1000, "missing", ORIGIN)));
                assertFalse(repo.deleteJail("missing", "Staff"));
                repo.saveJail(new JailRecord("Cell", CELL), "Staff");
                repo.saveJail(new JailRecord("SECOND", ORIGIN), "Staff");
                JailAssignment first = new JailAssignment(PLAYER, "Player", "Staff", "first", 1000, "CELL", ORIGIN);
                assertTrue(repo.setJailed(first));
                ModerationRepository restarted = reopen(db, clock);
                assertEquals(Optional.of(first), restarted.loadJailed(PLAYER));
                assertEquals(Optional.of(new JailRecord("cell", CELL)), restarted.loadJail("CeLl"));
                assertFalse(repo.deleteJail("CELL", "Staff"));
                assertTrue(repo.setJailed(new JailAssignment(PLAYER, "Renamed", "OtherStaff", "second", 1100, "SECOND", CELL)));
                JailAssignment expected = new JailAssignment(PLAYER, "Renamed", "OtherStaff", "second", 1100, "second", ORIGIN);
                assertEquals(Optional.of(expected), restarted.loadJailed(PLAYER));
                assertEquals(List.of(expected), restarted.loadJailedPlayers());
                assertEquals(List.of("cell", "second"), restarted.loadJails().stream().map(JailRecord::name).toList());
                repo.saveJail(new JailRecord("second", CELL), "Staff");
                assertEquals(Optional.of(new JailRecord("second", CELL)), restarted.loadJail("SECOND"));
                assertEquals(ORIGIN, restarted.loadJailed(PLAYER).orElseThrow().returnLocation());
                assertTrue(repo.deleteJail("cell", "Staff"));
                assertFalse(repo.deleteJail("second", "Staff"));
                assertTrue(repo.removeJailed(PLAYER, "Releaser"));
                assertFalse(repo.removeJailed(PLAYER, "Releaser"));
                assertTrue(repo.deleteJail("second", "Staff"));
                assertTrue(restarted.loadJailedPlayers().isEmpty());
                assertTrue(restarted.loadJails().isEmpty());
                assertEquals(8, repo.loadRecentAudit(20).size());
            }
        }
    }

    @Test
    void warningAndNoteHistoriesAreIndependentDurableNewestFirstAndBounded() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                HistoryEntry first = repo.addWarning(PLAYER, "Player", "Staff", "first warning");
                HistoryEntry second = repo.addWarning(PLAYER, "Renamed", "OtherStaff", "second warning");
                HistoryEntry note = repo.addNote(PLAYER, "Renamed", "Staff", "private note");
                repo.addWarning(OTHER, "Other", "Staff", "unrelated warning");
                repo.addNote(OTHER, "Other", "Staff", "unrelated note");
                assertTrue(second.id() > first.id());
                ModerationRepository restarted = reopen(db, clock);
                assertEquals(List.of(second, first), restarted.listWarnings(PLAYER, 10));
                assertEquals(List.of(note), restarted.listNotes(PLAYER, 10));
                assertEquals(List.of(second), restarted.listWarnings(PLAYER, 0));
                assertEquals(List.of(note), restarted.listNotes(PLAYER, -100));
                // Seed history in one transaction to exercise the persisted hard cap efficiently.
                seedHistory(db, ModerationRepository.WARNINGS_TABLE, 105);
                seedHistory(db, ModerationRepository.NOTES_TABLE, 105);
                var warnings = restarted.listWarnings(PLAYER, Integer.MAX_VALUE);
                var notes = restarted.listNotes(PLAYER, Integer.MAX_VALUE);
                assertEquals(100, warnings.size());
                assertEquals(100, notes.size());
                assertEquals("entry104", warnings.get(0).reason());
                assertEquals("entry5", warnings.get(warnings.size() - 1).reason());
                assertEquals("entry104", notes.get(0).reason());
                assertThrows(UnsupportedOperationException.class, warnings::clear);
                assertEquals(List.of("STAFF_NOTE", "WARN", "STAFF_NOTE", "WARN", "WARN"),
                        repo.loadRecentAudit(20).stream().map(ModerationAuditRecord::action).toList());
            }
        }
    }

    @Test
    void unnoteRemovesActiveNoteEnforcesOwnershipAndRetainsAuditHistory() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(2000);
                ModerationRepository repo = repository(db, clock);
                HistoryEntry first = repo.addNote(PLAYER, "Target", "ModA", "suspicious trade");
                HistoryEntry second = repo.addNote(PLAYER, "Target", "ModB", "verified clean");
                assertEquals(Optional.of(first), repo.findNoteById(first.id()));

                // Wrong target filter returns NOT_FOUND without touching the note
                var wrongTarget = repo.removeNote(first.id(), "ModA", OTHER, true);
                assertEquals(ModerationRepository.NoteRemovalStatus.NOT_FOUND, wrongTarget.status());
                assertEquals(2, repo.listNotes(PLAYER, 10).size());

                // Another staff member without canRemoveOthers is rejected with NOT_OWNER
                var denied = repo.removeNote(first.id(), "ModB", PLAYER, false);
                assertEquals(ModerationRepository.NoteRemovalStatus.NOT_OWNER, denied.status());
                assertEquals(first, denied.removed());
                assertEquals(2, repo.listNotes(PLAYER, 10).size());

                // Author removing their own note succeeds and records retained STAFF_NOTE_REMOVE audit
                clock.set(3000);
                var ownRemoved = repo.removeNote(first.id(), "ModA", PLAYER, false);
                assertEquals(ModerationRepository.NoteRemovalStatus.REMOVED, ownRemoved.status());
                assertEquals(first, ownRemoved.removed());
                assertEquals(List.of(second), repo.listNotes(PLAYER, 10));

                // Privileged staff member removing another staff member's note succeeds
                clock.set(4000);
                var adminRemoved = repo.removeNote(second.id(), "Admin", null, true);
                assertEquals(ModerationRepository.NoteRemovalStatus.REMOVED, adminRemoved.status());
                assertTrue(repo.listNotes(PLAYER, 10).isEmpty());

                // Both removals are permanently retained in allium_moderation_audit with original author and note text
                List<ModerationAuditRecord> audit = repo.loadRecentAudit(10);
                ModerationAuditRecord latestRemove = audit.get(0);
                ModerationAuditRecord earlierRemove = audit.get(1);
                assertEquals("STAFF_NOTE_REMOVE", latestRemove.action());
                assertEquals("Admin", latestRemove.actorName());
                assertTrue(latestRemove.detail().contains("note_id=" + second.id()));
                assertTrue(latestRemove.detail().contains("author=ModB"));
                assertTrue(latestRemove.detail().contains("verified clean"));
                assertEquals("STAFF_NOTE_REMOVE", earlierRemove.action());
                assertEquals("ModA", earlierRemove.actorName());
                assertTrue(earlierRemove.detail().contains("note_id=" + first.id()));
                assertTrue(earlierRemove.detail().contains("suspicious trade"));
            }
        }
    }

    @Test
    void allMutationsRollbackBothStateAndAuditOnSqlAndRuntimeFailures() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                repo.saveBan(new BanRecord(PLAYER, "Player", "Staff", "original", 900, null, "192.0.2.1"));
                repo.setFrozen(new FreezeRecord(PLAYER, "Player", "Staff", "original", 900));
                repo.saveJail(new JailRecord("cell", CELL), "Staff");
                repo.saveJail(new JailRecord("second", ORIGIN), "Staff");
                assertTrue(repo.setJailed(new JailAssignment(PLAYER, "Player", "Staff", "original", 900, "cell", ORIGIN)));
                repo.addWarning(PLAYER, "Player", "Staff", "original");
                repo.addNote(PLAYER, "Player", "Staff", "original");
                List<Object> baseline = snapshot(repo);
                List<Mutation> mutations = List.of(
                        r -> r.saveBan(new BanRecord(PLAYER, "Changed", "Other", "changed", 1000, 2000L, null)),
                        r -> r.removeBan(PLAYER, "Other"),
                        r -> r.setFrozen(new FreezeRecord(PLAYER, "Changed", "Other", "changed", 1000)),
                        r -> r.removeFrozen(PLAYER, "Other"),
                        r -> r.saveJail(new JailRecord("cell", ORIGIN), "Other"),
                        r -> r.deleteJail("second", "Other"),
                        r -> r.setJailed(new JailAssignment(PLAYER, "Changed", "Other", "changed", 1000, "second", CELL)),
                        r -> r.removeJailed(PLAYER, "Other"),
                        r -> r.addWarning(PLAYER, "Changed", "Other", "changed"),
                        r -> r.addNote(PLAYER, "Changed", "Other", "changed")
                );
                for (Mutation mutation : mutations) {
                    for (Throwable failure : List.of(new SQLException("injected audit failure"), new IllegalStateException("injected audit failure"))) {
                        FaultSource faults = new FaultSource(db.source(), failure, false);
                        ModerationRepository failing = new ModerationRepository(faults, db.dialect(), clock::get);
                        assertSame(failure, assertThrows(failure.getClass(), () -> mutation.apply(failing)));
                        assertEquals(baseline, snapshot(repo), "real mutation and audit INSERT must both roll back");
                        assertTrue(faults.events.contains("auto:true"));
                    }
                }
            }
        }
    }

    @Test
    void failedRollbackNeverEnablesAutoCommitAndKeepsOriginalFailure() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                SQLException failure = new SQLException("original failure");
                FaultSource faults = new FaultSource(db.source(), failure, true);
                ModerationRepository failing = new ModerationRepository(faults, db.dialect(), clock::get);
                assertSame(failure, assertThrows(SQLException.class, () -> failing.saveBan(
                        new BanRecord(PLAYER, "Player", "Staff", "reason", 1000, null, null))));
                assertEquals(1, failure.getSuppressed().length);
                assertEquals("rollback failed", failure.getSuppressed()[0].getMessage());
                assertEquals(List.of("auto:false", "rollback", "close"), faults.events);
                assertTrue(repo.loadActiveBans().isEmpty());
                assertTrue(repo.loadRecentAudit(100).isEmpty());
            }
        }
    }

    @Test
    void concurrentAssignmentAndDeletionCannotStrandAnOccupant() throws Exception {
        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                for (int round = 0; round < 4; round++) {
                    String jail = "cell" + round;
                    UUID player = UUID.randomUUID();
                    repo.saveJail(new JailRecord(jail, CELL), "Staff");
                    CountDownLatch start = new CountDownLatch(1);
                    var executor = Executors.newFixedThreadPool(2);
                    try {
                        var assigned = executor.submit(() -> {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            return reopen(db, clock).setJailed(new JailAssignment(player, "Player", "Staff", "reason", 1000, jail, ORIGIN));
                        });
                        var deleted = executor.submit(() -> {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            return reopen(db, clock).deleteJail(jail, "Staff");
                        });
                        start.countDown();
                        boolean assignmentWon = assigned.get(20, TimeUnit.SECONDS);
                        boolean deletionWon = deleted.get(20, TimeUnit.SECONDS);
                        assertNotEquals(assignmentWon, deletionWon, "exactly one operation may succeed");
                        assertEquals(assignmentWon, repo.loadJailed(player).isPresent());
                        assertEquals(assignmentWon, repo.loadJail(jail).isPresent());
                    } finally {
                        start.countDown();
                        executor.shutdownNow();
                        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                    }
                }
            }
        }
    }

    @Test
    void recordsBoundTextNormalizeNamesAndRejectInvalidLocationsBeforePersistence() throws Exception {
        UUID uuid = UUID.randomUUID();
        BanRecord defaults = new BanRecord(uuid, null, null, null, 0, null, " 192.0.2.1 ");
        assertEquals(uuid.toString(), defaults.playerName());
        assertEquals("Console", defaults.actorName());
        assertEquals(MuteRecord.DEFAULT_REASON, defaults.reason());
        assertEquals("192.0.2.1", defaults.ipAddress());
        assertTrue(defaults.isPermanent());
        assertFalse(defaults.isExpired(Long.MAX_VALUE));
        assertTrue(new BanRecord(uuid, "Player", "Staff", "reason", 0, 0L, null).isExpired(0));
        BanRecord bounded = new BanRecord(uuid, "x".repeat(100), "y".repeat(100), "z".repeat(1000), 0, null, null);
        assertEquals(36, bounded.playerName().length());
        assertEquals(64, bounded.actorName().length());
        assertEquals(256, bounded.reason().length());
        assertEquals("cell_one-2", new JailRecord("CELL_one-2", CELL).name());
        assertThrows(IllegalArgumentException.class, () -> new BanRecord(uuid, "Player", "Staff", "reason", 0, null, "x".repeat(46)));
        for (String name : List.of("", "bad name", "a/b", "a.b", "\u212a", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> new JailRecord(name, CELL));
        }
        assertThrows(IllegalArgumentException.class, () -> new SavedLocation(uuid, "world", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SavedLocation(uuid, "world", 0, 0, 0, Float.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new JailRecord("cell", new SavedLocation(uuid, " ", 0, 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new JailAssignment(uuid, "Player", "Staff", "reason", 0, "cell",
                new SavedLocation(uuid, "x".repeat(256), 0, 0, 0, 0, 0)));
        assertThrows(NullPointerException.class, () -> new FreezeRecord(null, "Player", "Staff", "reason", 0));
        // Invalid query names must also be rejected without touching JDBC.
        ModerationRepository closed = new ModerationRepository(() -> { fail("unexpected connection"); return null; },
                codes.castled.allium.storage.SqlDialect.SQLITE, () -> 0);
        assertThrows(IllegalArgumentException.class, () -> closed.loadJail("bad name"));
        assertThrows(IllegalArgumentException.class, () -> closed.deleteJail("bad name", "Staff"));
    }

    @Test
    void timedJailExpiryAndRemoveBansByIpAndLiteralIpValidationWork() throws Exception {
        assertTrue(ModerationRepository.isLiteralIp("192.0.2.10"));
        assertTrue(ModerationRepository.isLiteralIp("2001:db8::1"));
        assertTrue(ModerationRepository.isLiteralIp("::1"));
        assertFalse(ModerationRepository.isLiteralIp("999.0.0.1"));
        assertFalse(ModerationRepository.isLiteralIp("Steve"));
        assertFalse(ModerationRepository.isLiteralIp("example.com"));

        for (var database : ModerationDatabases.all(dir)) {
            try (var db = database) {
                AtomicLong clock = new AtomicLong(1000);
                ModerationRepository repo = repository(db, clock);
                repo.saveJail(new JailRecord("cell", CELL), "Staff");

                JailAssignment timed = new JailAssignment(PLAYER, "Player", "Staff", "timeout", 1000, "cell", ORIGIN, 61_000L);
                assertTrue(repo.setJailed(timed));
                ModerationRepository restarted = reopen(db, clock);
                JailAssignment loaded = restarted.loadJailed(PLAYER).orElseThrow();
                assertEquals(61_000L, loaded.expiresAt());
                assertFalse(loaded.isPermanent());
                assertFalse(loaded.isExpired(60_999L));
                assertTrue(loaded.isExpired(61_000L));

                UUID ipKey = ModerationRepository.ipBanUuid("192.0.2.99");
                repo.saveBan(new BanRecord(PLAYER, "Player", "Staff", "a", 1000, 50_000L, "192.0.2.99"));
                repo.saveBan(new BanRecord(ipKey, "192.0.2.99", "Staff", "b", 1100, 60_000L, "192.0.2.99"));
                repo.saveBan(new BanRecord(OTHER, "Other", "Staff", "c", 1200, null, "192.0.2.100"));

                assertEquals(2, repo.removeBansByIp("192.0.2.99", "Admin"));
                assertEquals(0, repo.removeBansByIp("192.0.2.99", "Admin"));
                assertTrue(restarted.loadBanByIp("192.0.2.99").isEmpty());
                assertTrue(restarted.loadBanByIp("192.0.2.100").isPresent());
                assertEquals("UNBANIP", repo.loadRecentAudit(1).get(0).action());
            }
        }
    }

    @FunctionalInterface
    private interface Mutation { void apply(ModerationRepository repo) throws SQLException; }

    private static List<Object> snapshot(ModerationRepository repo) throws SQLException {
        return List.of(repo.loadActiveBans(), repo.loadFrozenPlayers(), repo.loadJails(), repo.loadJailedPlayers(),
                repo.listWarnings(PLAYER, 100), repo.listNotes(PLAYER, 100), repo.loadRecentAudit(100));
    }

    private static int count(ModerationDatabases.Db db, String table) throws SQLException {
        try (Connection c = db.source().connection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void seedHistory(ModerationDatabases.Db db, String table, int count) throws SQLException {
        try (Connection c = db.source().connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                    + " (player_uuid, player_name, actor_name, reason, created_at) VALUES (?, 'Player', 'Staff', ?, 1000)")) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, PLAYER.toString());
                    ps.setString(2, "entry" + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    /** Fails after a real audit INSERT, so the database contains both writes until rollback. */
    private static final class FaultSource implements ConnectionSource {
        private final ConnectionSource source;
        private final Throwable failure;
        private final boolean rollbackFails;
        private final AtomicBoolean failNext = new AtomicBoolean(true);
        private final List<String> events = new ArrayList<>();

        private FaultSource(ConnectionSource source, Throwable failure, boolean rollbackFails) {
            this.source = source;
            this.failure = failure;
            this.rollbackFails = rollbackFails;
        }

        @Override
        public Connection connection() throws SQLException {
            Connection actual = source.connection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setAutoCommit" -> events.add("auto:" + args[0]);
                            case "close" -> events.add("close");
                            case "rollback" -> {
                                events.add("rollback");
                                if (rollbackFails) throw new SQLException("rollback failed");
                            }
                        }
                        Object value = ModerationDatabases.invoke(method, actual, args);
                        if (!method.getName().equals("prepareStatement") || args == null
                                || !((String) args[0]).startsWith("INSERT INTO " + ModerationRepository.AUDIT_TABLE + " ")) return value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, m, a) -> {
                                    Object result = ModerationDatabases.invoke(m, value, a);
                                    if (m.getName().equals("executeUpdate") && failNext.compareAndSet(true, false)) throw failure;
                                    return result;
                                });
                    });
        }
    }
}
