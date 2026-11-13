package codes.castled.allium.teleport;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual SQLite storage, plus isolated MySQL tables when ALLIUM_TEST_MYSQL_URL is configured. */
class TeleportRepositoryTest {

    @TempDir
    Path directory;

    private static final UUID SERVER = new UUID(0, 0);
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final SavedLocation SPAWN = new SavedLocation(new UUID(1, 1), "world", 0.5, 80, -0.5, 180, 10);
    private static final SavedLocation ORIGIN = new SavedLocation(new UUID(1, 2), "world_nether", -1024.125, -32.5, 999.75, -90.5f, 15.25f);
    private static final SavedLocation OTHER = new SavedLocation(new UUID(1, 3), "world_the_end", 1.5, 100, -200.25, 360.5f, -30.25f);

    private static TeleportRepository repository(TeleportDatabases.Db db) throws SQLException {
        TeleportRepository repository = new TeleportRepository(db.source(), db.dialect(), () -> 123456L);
        assertEquals(1, repository.migrate(), db.name());
        assertEquals(1, repository.migrate(), db.name() + ": repeated startup is idempotent");
        return repository;
    }

    @Test
    void backSpawnAndReservedDeathRoundTripAndUpsertsPreserveOtherOwnersAndSlots() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                assertTrue(repo.load(SERVER, "spawn").isEmpty());
                assertTrue(repo.load(ALICE, "back").isEmpty());
                repo.save(SERVER, "spawn", SPAWN);
                repo.save(ALICE, "back", ORIGIN);
                repo.save(ALICE, "death", OTHER);
                repo.save(BOB, "back", SPAWN);
                repo.save(ALICE, "back", OTHER);
                repo.save(ALICE, "back", OTHER);
                assertEquals(SPAWN, repo.load(SERVER, "spawn").orElseThrow());
                assertEquals(OTHER, repo.load(ALICE, "back").orElseThrow());
                assertEquals(OTHER, repo.load(ALICE, "death").orElseThrow());
                assertEquals(SPAWN, repo.load(BOB, "back").orElseThrow());
                assertTrue(repo.load(BOB, "death").isEmpty());
                repo.save(SERVER, "spawn", ORIGIN);
                assertEquals(ORIGIN, repo.load(SERVER, "spawn").orElseThrow());
                assertEquals(4, count(db, TeleportRepository.LOCATIONS_TABLE), "replaying upserts never adds rows");
                TeleportRepository reopened = new TeleportRepository(db.source(), db.dialect(), () -> 654321L);
                assertEquals(ORIGIN, reopened.load(SERVER, "spawn").orElseThrow(), "state is durable across repository instances");
            }
        }
    }

    @Test
    void cooldownsRoundTripUpsertReplayAndZeroOverwriteOnlyTheSpecifiedCommandAndOwner() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                assertEquals(Map.of(), repo.loadCooldowns(ALICE));
                repo.saveCooldown(ALICE, "spawn", 1000);
                repo.saveCooldown(ALICE, "back", Long.MAX_VALUE);
                repo.saveCooldown(BOB, "spawn", 2222);
                repo.saveCooldown(ALICE, "spawn", 2000);
                repo.saveCooldown(ALICE, "spawn", 2000);
                assertEquals(Map.of("spawn", 2000L, "back", Long.MAX_VALUE), repo.loadCooldowns(ALICE));
                repo.saveCooldown(ALICE, "spawn", 0);
                assertEquals(Map.of("spawn", 0L, "back", Long.MAX_VALUE), repo.loadCooldowns(ALICE));
                assertEquals(Map.of("spawn", 2222L), repo.loadCooldowns(BOB));
                assertEquals(3, count(db, TeleportRepository.COOLDOWNS_TABLE));
                TeleportRepository reopened = new TeleportRepository(db.source(), db.dialect(), () -> 0);
                assertEquals(repo.loadCooldowns(ALICE), reopened.loadCooldowns(ALICE));
            }
        }
    }

    @Test
    void newerDeathHistoryRetainsAllEarlierCommandDeadlines() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                repo.saveSuccess(ALICE, ORIGIN, "spawn", 5000);
                repo.saveHistory(ALICE, OTHER, Map.of("spawn", 5000L, "back", 8000L));
                assertEquals(OTHER, repo.load(ALICE, "back").orElseThrow());
                assertEquals(Map.of("spawn", 5000L, "back", 8000L), repo.loadCooldowns(ALICE));
            }
        }
    }

    @Test
    void successStoresOriginAndCooldownTogetherAndReplaysWithoutDuplicatingRows() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                repo.save(SERVER, "spawn", SPAWN);
                repo.save(ALICE, "death", SPAWN);
                repo.saveCooldown(ALICE, "back", 9999);
                repo.saveSuccess(ALICE, ORIGIN, "spawn", 5000);
                assertEquals(ORIGIN, repo.load(ALICE, "back").orElseThrow());
                assertEquals(Map.of("back", 9999L, "spawn", 5000L), repo.loadCooldowns(ALICE));
                repo.saveSuccess(ALICE, ORIGIN, "spawn", 5000);
                repo.saveSuccess(ALICE, OTHER, "spawn", 0);
                assertEquals(OTHER, repo.load(ALICE, "back").orElseThrow());
                assertEquals(Map.of("back", 9999L, "spawn", 0L), repo.loadCooldowns(ALICE));
                assertEquals(SPAWN, repo.load(SERVER, "spawn").orElseThrow());
                assertEquals(SPAWN, repo.load(ALICE, "death").orElseThrow());
                assertTrue(repo.load(BOB, "back").isEmpty());
                assertEquals(Map.of(), repo.loadCooldowns(BOB));
                assertEquals(3, count(db, TeleportRepository.LOCATIONS_TABLE));
                assertEquals(2, count(db, TeleportRepository.COOLDOWNS_TABLE));
            }
        }
    }

    @Test
    void failureOnTheCooldownWriteRollsBackBothInsertedAndUpdatedBackLocations() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                repo.save(ALICE, "back", SPAWN);
                repo.saveCooldown(ALICE, "spawn", 1234);
                for (UUID owner : new UUID[]{ALICE, BOB}) {
                    AtomicBoolean attemptedCooldown = new AtomicBoolean();
                    AtomicInteger opened = new AtomicInteger();
                    ConnectionSource source = failWrite(db.source(), TeleportRepository.COOLDOWNS_TABLE, attemptedCooldown, opened);
                    TeleportRepository failing = new TeleportRepository(source, db.dialect(), () -> 0);
                    SQLException failure = assertThrows(SQLException.class, () -> failing.saveSuccess(owner, ORIGIN, "spawn", 5000));
                    assertEquals("injected write failure", failure.getMessage());
                    assertTrue(attemptedCooldown.get(), "the location write completed before the failing cooldown write");
                    assertEquals(1, opened.get(), "both writes use the same connection");
                }
                assertEquals(SPAWN, repo.load(ALICE, "back").orElseThrow(), "existing back was restored");
                assertEquals(Map.of("spawn", 1234L), repo.loadCooldowns(ALICE));
                assertTrue(repo.load(BOB, "back").isEmpty(), "new back insert was rolled back");
                assertEquals(Map.of(), repo.loadCooldowns(BOB));
            }
        }
    }

    @Test
    void failureOnTheFirstWriteDoesNotStoreACooldown() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                AtomicBoolean failed = new AtomicBoolean();
                ConnectionSource source = failWrite(db.source(), TeleportRepository.LOCATIONS_TABLE, failed, new AtomicInteger());
                TeleportRepository failing = new TeleportRepository(source, db.dialect(), () -> 0);
                assertThrows(SQLException.class, () -> failing.saveSuccess(ALICE, ORIGIN, "spawn", 5000));
                assertTrue(failed.get());
                assertTrue(repo.load(ALICE, "back").isEmpty());
                assertEquals(Map.of(), repo.loadCooldowns(ALICE));
            }
        }
    }

    @Test
    void migrationsRecoverAfterUnrecordedDdlAndPreserveTheirRowsAndOtherComponentMetadata() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                repo.save(SERVER, "spawn", SPAWN);
                repo.saveCooldown(ALICE, "spawn", 777);
                try (var connection = db.source().connection(); var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO allium_schema (component, version, applied_at) VALUES ('unrelated', 17, 42)");
                    statement.execute("DELETE FROM allium_schema WHERE component = 'teleports'");
                }
                assertEquals(1, repo.migrate(), "tables already exist after an interrupted migration");
                assertEquals(SPAWN, repo.load(SERVER, "spawn").orElseThrow());
                assertEquals(Map.of("spawn", 777L), repo.loadCooldowns(ALICE));
                SchemaMigrator migrator = new SchemaMigrator(db.source(), db.dialect(), () -> 0);
                assertEquals(1, migrator.version("teleports"));
                assertEquals(17, migrator.version("unrelated"));
                try (var connection = db.source().connection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT applied_at FROM allium_schema WHERE component = 'teleports'")) {
                    assertTrue(row.next());
                    assertEquals(123456L, row.getLong(1));
                }
            }
        }
    }

    @Test
    void migrationRefusesANewerComponentVersion() throws Exception {
        for (TeleportDatabases.Db database : TeleportDatabases.all(directory)) {
            try (var db = database) {
                TeleportRepository repo = repository(db);
                try (var connection = db.source().connection(); var statement = connection.createStatement()) {
                    statement.execute("UPDATE allium_schema SET version = 2 WHERE component = 'teleports'");
                }
                assertThrows(SQLException.class, repo::migrate);
            }
        }
    }

    @Test
    void invalidCommandsAndArgumentsAreRejectedBeforeOpeningAConnection() {
        ConnectionSource unopened = () -> { throw new AssertionError("invalid arguments must not reach JDBC"); };
        TeleportRepository repo = new TeleportRepository(unopened, codes.castled.allium.storage.SqlDialect.SQLITE, () -> 0);
        for (String command : new String[]{"", " ", "Spawn", "SPAWN", "spawn1", "tp here", "/spawn", "tpa-here", "spawn\n", "spáwn"}) {
            assertThrows(IllegalArgumentException.class, () -> repo.saveCooldown(ALICE, command, 100));
            assertThrows(IllegalArgumentException.class, () -> repo.saveSuccess(ALICE, ORIGIN, command, 100));
        }
        assertThrows(NullPointerException.class, () -> repo.saveCooldown(ALICE, null, 0));
        assertThrows(NullPointerException.class, () -> repo.saveSuccess(ALICE, ORIGIN, null, 0));
        assertThrows(NullPointerException.class, () -> repo.saveCooldown(null, "spawn", 0));
        assertThrows(NullPointerException.class, () -> repo.saveSuccess(ALICE, null, "spawn", 0));
        assertThrows(IllegalArgumentException.class, () -> repo.saveCooldown(ALICE, "spawn", -1));
        assertThrows(IllegalArgumentException.class, () -> repo.saveSuccess(ALICE, ORIGIN, "spawn", -1));
        assertThrows(NullPointerException.class, () -> repo.load(null, "back"));
        assertThrows(NullPointerException.class, () -> repo.load(ALICE, null));
        assertThrows(NullPointerException.class, () -> repo.loadCooldowns(null));
        assertThrows(NullPointerException.class, () -> repo.save(ALICE, "back", null));
        assertThrows(IllegalArgumentException.class, () -> repo.save(ALICE, "", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> repo.load(ALICE, " "));
    }

    private static int count(TeleportDatabases.Db db, String table) throws SQLException {
        try (var connection = db.source().connection(); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    /** Injects a JDBC execution failure while all earlier writes use the real database connection. */
    private static ConnectionSource failWrite(ConnectionSource source, String table, AtomicBoolean attempted, AtomicInteger opened) {
        return () -> {
            opened.incrementAndGet();
            Connection connection = source.connection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = TeleportDatabases.invoke(connection, method, args);
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO " + table)) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (p, m, a) -> {
                                        if (m.getName().equals("executeUpdate")) {
                                            attempted.set(true);
                                            throw new SQLException("injected write failure");
                                        }
                                        return TeleportDatabases.invoke(statement, m, a);
                                    });
                        }
                        return result;
                    });
        };
    }
}
