package codes.castled.allium.travel;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static codes.castled.allium.travel.TravelRepository.MutationResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against real SQLite, plus isolated random MySQL tables when ALLIUM_TEST_MYSQL_URL is set. */
class TravelRepositoryTest {
    @TempDir
    Path dir;

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final SavedLocation FIRST = new SavedLocation(UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "world", -12.5, 64.25, 1234.5, 90.5F, -30.25F);
    private static final SavedLocation SECOND = new SavedLocation(UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "world_nether", 45.25, 100, -800, 180, 45);

    private static TravelRepository repository(TravelDatabases.Db db) throws SQLException {
        TravelRepository repository = reopen(db);
        assertEquals(1, repository.migrate(), db.name());
        assertEquals(1, repository.migrate(), db.name() + ": migration is idempotent");
        return repository;
    }

    private static TravelRepository reopen(TravelDatabases.Db db) {
        return new TravelRepository(db.source(), db.dialect(), () -> 1234L);
    }

    @Test
    void locationsPersistAcrossRepositoryInstancesAndMapsAreImmutableAndOwnerScoped() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                assertTrue(repo.homes(OWNER).isEmpty());
                assertTrue(repo.warps().isEmpty());
                assertEquals(CREATED, repo.createHome(OWNER, "My_HOME", FIRST, 2));
                assertEquals(CREATED, repo.createHome(OTHER, "my_home", SECOND, 1));
                assertEquals(CREATED, repo.createWarp("Spawn", SECOND));
                TravelRepository restarted = reopen(db);
                assertEquals(Map.of("my_home", FIRST), restarted.homes(OWNER));
                assertEquals(Map.of("my_home", SECOND), restarted.homes(OTHER));
                assertEquals(Map.of("spawn", SECOND), restarted.warps());
                assertEquals(FIRST, restarted.home(OWNER, "MY_HOME").orElseThrow().location());
                assertEquals(SECOND, restarted.warp("SPAWN").orElseThrow().location());
                assertThrows(UnsupportedOperationException.class, () -> restarted.homes(OWNER).put("oops", FIRST));
                assertThrows(UnsupportedOperationException.class, () -> restarted.warps().clear());
            }
        }
    }

    @Test
    void homeOverwriteAndDeleteRequireTheExactRevisionIncludingAfterDeletionAndRecreation() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                assertEquals(LIMIT, repo.createHome(OWNER, "base", FIRST, 0));
                assertEquals(LIMIT, repo.createHome(OWNER, "base", FIRST, -10));
                assertEquals(CREATED, repo.createHome(OWNER, "Base", FIRST, 1));
                StoredDestination original = repo.home(OWNER, "base").orElseThrow();
                assertEquals(EXISTS, repo.createHome(OWNER, "BASE", SECOND, 0), "exists takes precedence over limit");
                assertEquals(LIMIT, repo.createHome(OWNER, "second", SECOND, 1));
                assertEquals(FIRST, repo.home(OWNER, "base").orElseThrow().location());
                assertEquals(UPDATED, repo.overwriteHome(OWNER, "base", original.revision(), SECOND));
                StoredDestination updated = repo.home(OWNER, "base").orElseThrow();
                assertTrue(updated.revision() > original.revision());
                assertEquals(STALE, repo.overwriteHome(OWNER, "base", original.revision(), FIRST));
                assertEquals(STALE, repo.deleteHome(OWNER, "base", original.revision()));
                assertEquals(SECOND, repo.home(OWNER, "base").orElseThrow().location());
                assertEquals(MISSING, repo.deleteHome(OTHER, "base", updated.revision()));
                assertEquals(DELETED, repo.deleteHome(OWNER, "base", updated.revision()));
                assertEquals(MISSING, repo.deleteHome(OWNER, "base", updated.revision()));
                assertEquals(MISSING, repo.overwriteHome(OWNER, "base", updated.revision(), FIRST));

                TravelRepository restarted = reopen(db);
                assertEquals(CREATED, restarted.createHome(OWNER, "base", FIRST, 1));
                StoredDestination recreated = restarted.home(OWNER, "base").orElseThrow();
                assertTrue(recreated.revision() > updated.revision(), "the durable owner counter survives deletion and restart");
                assertEquals(STALE, restarted.overwriteHome(OWNER, "base", original.revision(), SECOND));
                assertEquals(STALE, restarted.deleteHome(OWNER, "base", updated.revision()));
                assertEquals(FIRST, restarted.home(OWNER, "base").orElseThrow().location());
            }
        }
    }

    @Test
    void warpsHaveConditionalCreationOverwriteAndDeletionAndNeverReuseARevision() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                assertEquals(CREATED, repo.createWarp("Spawn", FIRST));
                StoredDestination original = repo.warp("spawn").orElseThrow();
                assertEquals(EXISTS, repo.createWarp("SPAWN", SECOND));
                assertEquals(UPDATED, repo.overwriteWarp("spawn", original.revision(), SECOND));
                StoredDestination changed = repo.warp("spawn").orElseThrow();
                assertTrue(changed.revision() > original.revision());
                assertEquals(STALE, repo.overwriteWarp("spawn", original.revision(), FIRST));
                assertEquals(STALE, repo.deleteWarp("spawn", original.revision()));
                assertEquals(DELETED, repo.deleteWarp("spawn", changed.revision()));
                assertEquals(MISSING, repo.overwriteWarp("spawn", changed.revision(), FIRST));
                assertEquals(MISSING, repo.deleteWarp("spawn", changed.revision()));
                TravelRepository restarted = reopen(db);
                assertEquals(CREATED, restarted.createWarp("spawn", FIRST));
                assertTrue(restarted.warp("spawn").orElseThrow().revision() > changed.revision());
                assertEquals(STALE, restarted.deleteWarp("spawn", changed.revision()));
                assertEquals(STALE, restarted.overwriteWarp("spawn", original.revision(), SECOND));
                assertEquals(FIRST, restarted.warp("spawn").orElseThrow().location());
            }
        }
    }

    @Test
    void competingRepositoriesEnforceOneOwnersPersistedLimitAndIndependentOwnersKeepTheirOwnSlots() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository first = repository(db);
                TravelRepository second = reopen(db);
                var results = race(12, i -> () -> (i % 2 == 0 ? first : second).createHome(OWNER, "home" + i, FIRST, 3));
                assertEquals(3, results.stream().filter(r -> r == CREATED).count(), db.name());
                assertEquals(9, results.stream().filter(r -> r == LIMIT).count(), db.name());
                assertEquals(3, first.homes(OWNER).size());
                assertEquals(CREATED, second.createHome(OTHER, "home0", FIRST, 1), "another owner has an independent limit");
                StoredDestination home = first.home(OWNER, first.homes(OWNER).keySet().iterator().next()).orElseThrow();
                assertEquals(UPDATED, second.overwriteHome(OWNER, home.name(), home.revision(), SECOND), "overwriting needs no new slot");
                assertEquals(3, first.homes(OWNER).size());
            }
        }
    }

    @Test
    void simultaneousConditionalCreatesAndConfirmationsHaveExactlyOneWinner() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository first = repository(db);
                TravelRepository second = reopen(db);
                var homes = race(8, i -> () -> (i % 2 == 0 ? first : second).createHome(OWNER, i % 2 == 0 ? "Base" : "BASE", FIRST, 20));
                assertEquals(1, homes.stream().filter(r -> r == CREATED).count(), db.name());
                assertEquals(7, homes.stream().filter(r -> r == EXISTS).count(), db.name());
                long homeRevision = first.home(OWNER, "base").orElseThrow().revision();
                var homeUpdates = race(8, i -> () -> (i % 2 == 0 ? first : second).overwriteHome(OWNER, "base", homeRevision, SECOND));
                assertEquals(1, homeUpdates.stream().filter(r -> r == UPDATED).count(), db.name());
                assertEquals(7, homeUpdates.stream().filter(r -> r == STALE).count(), db.name());

                var warps = race(8, i -> () -> (i % 2 == 0 ? first : second).createWarp(i % 2 == 0 ? "Spawn" : "SPAWN", FIRST));
                assertEquals(1, warps.stream().filter(r -> r == CREATED).count(), db.name());
                assertEquals(7, warps.stream().filter(r -> r == EXISTS).count(), db.name());
                long warpRevision = first.warp("spawn").orElseThrow().revision();
                var warpUpdates = race(8, i -> () -> (i % 2 == 0 ? first : second).overwriteWarp("spawn", warpRevision, SECOND));
                assertEquals(1, warpUpdates.stream().filter(r -> r == UPDATED).count(), db.name());
                assertEquals(7, warpUpdates.stream().filter(r -> r == STALE).count(), db.name());
            }
        }
    }

    @Test
    void theThousandHomeHardCapUsesThePersistedCountEvenForAnUnlimitedCaller() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                seedThousandHomes(db);
                assertEquals(LIMIT, repo.createHome(OWNER, "overflow", FIRST, Integer.MAX_VALUE));
                StoredDestination existing = repo.home(OWNER, "home0").orElseThrow();
                assertEquals(UPDATED, repo.overwriteHome(OWNER, "home0", existing.revision(), SECOND));
                long current = repo.home(OWNER, "home0").orElseThrow().revision();
                assertEquals(DELETED, repo.deleteHome(OWNER, "home0", current));
                assertEquals(CREATED, repo.createHome(OWNER, "replacement", FIRST, Integer.MAX_VALUE));
                assertEquals(1000, repo.homes(OWNER).size());
                assertEquals(LIMIT, repo.createHome(OWNER, "still_overflow", FIRST, Integer.MAX_VALUE));
            }
        }
    }

    @Test
    void repositoryRejectsInvalidNamesAndNonpositiveExpectedRevisionsBeforeOpeningConnections() {
        AtomicInteger opened = new AtomicInteger();
        TravelRepository repo = new TravelRepository(() -> {
            opened.incrementAndGet();
            throw new SQLException("must not open");
        }, SqlDialect.SQLITE, () -> 0L);
        assertThrows(IllegalArgumentException.class, () -> repo.createHome(OWNER, "bad name", FIRST, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.home(OWNER, "\u212a"));
        assertThrows(IllegalArgumentException.class, () -> repo.overwriteHome(OWNER, "bad.name", 1, FIRST));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteHome(OWNER, "a/b", 1));
        assertThrows(IllegalArgumentException.class, () -> repo.createWarp("", FIRST));
        assertThrows(IllegalArgumentException.class, () -> repo.warp("a".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> repo.overwriteWarp("a b", 1, FIRST));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteWarp("a:b", 1));
        assertThrows(IllegalArgumentException.class, () -> repo.overwriteHome(OWNER, "base", 0, FIRST));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteHome(OWNER, "base", -1));
        assertThrows(IllegalArgumentException.class, () -> repo.overwriteWarp("spawn", 0, FIRST));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteWarp("spawn", -1));
        assertEquals(0, opened.get());
    }

    @Test
    void failedSqlAndRuntimeWritesRollBackTheDestinationAndItsRevisionCounter() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                for (Throwable failure : List.of(new SQLException("injected write failure", "42000", 9999),
                        new IllegalStateException("injected runtime failure"))) {
                    FaultSource faults = new FaultSource(db.source(), failure, false);
                    TravelRepository failing = new TravelRepository(faults, db.dialect(), () -> 0L);
                    Throwable error = assertThrows(failure.getClass(), () -> failing.createHome(OWNER, "base", FIRST, 1));
                    assertSame(failure, error);
                    assertEquals(1, faults.opened.get(), "permanent failures are not retried");
                    assertTrue(repo.homes(OWNER).isEmpty(), "even the INSERT completed before the injected failure");
                    assertEquals(0, ownerCount(db), "owner lock and revision changes roll back too");
                    assertTrue(faults.events.contains("auto:true"), "a successful rollback may restore auto-commit");
                }
                assertEquals(CREATED, repo.createHome(OWNER, "base", FIRST, 1));
                assertEquals(1, repo.home(OWNER, "base").orElseThrow().revision());
            }
        }
    }

    @Test
    void onlyTheActualDialectsLockErrorsAreRetriedAndEachRetryIsAWholeTransaction() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                // MySQL 1029 happens to have low byte 5, but is not SQLITE_BUSY.
                int wrongDialectCode = db.dialect() == SqlDialect.SQLITE ? 1213 : 1029;
                SQLException permanent = new SQLException("other dialect's code", "HY000", wrongDialectCode);
                FaultSource noRetry = new FaultSource(db.source(), permanent, false);
                assertSame(permanent, assertThrows(SQLException.class, () -> new TravelRepository(noRetry, db.dialect(), () -> 0L)
                        .createHome(OWNER, "base", FIRST, 1)));
                assertEquals(1, noRetry.opened.get());
                assertEquals(0, ownerCount(db));

                int lockCode = db.dialect() == SqlDialect.SQLITE ? 5 : 1213;
                FaultSource retry = new FaultSource(db.source(), new SQLException("transient lock", "HY000", lockCode), false);
                assertEquals(CREATED, new TravelRepository(retry, db.dialect(), () -> 0L).createHome(OWNER, "base", FIRST, 1));
                assertEquals(2, retry.opened.get());
                assertEquals(Map.of("base", FIRST), repo.homes(OWNER));
                assertEquals(1, repo.home(OWNER, "base").orElseThrow().revision(), "the failed attempt consumed no revision");
            }
        }
    }

    @Test
    void aFailedRollbackNeverEnablesAutoCommitOrRetriesThePartiallyWrittenTransaction() throws Exception {
        for (TravelDatabases.Db database : TravelDatabases.all(dir)) {
            try (var db = database) {
                TravelRepository repo = repository(db);
                SQLException failure = new SQLException("injected transient write failure", "40001",
                        db.dialect() == SqlDialect.SQLITE ? 5 : 1213);
                FaultSource faults = new FaultSource(db.source(), failure, true);
                SQLException error = assertThrows(SQLException.class, () -> new TravelRepository(faults, db.dialect(), () -> 0L)
                        .createHome(OWNER, "base", FIRST, 1));
                assertSame(failure, error);
                assertEquals(1, error.getSuppressed().length);
                assertEquals("injected rollback failure", error.getSuppressed()[0].getMessage());
                assertEquals(1, faults.opened.get(), "an uncertain transaction is never retried");
                assertFalse(faults.events.contains("auto:true"), "setAutoCommit(true) would commit the partial INSERT");
                assertEquals(List.of("auto:false", "rollback", "close"), faults.events);
                assertTrue(repo.homes(OWNER).isEmpty(), "closing the real JDBC connection discards the open transaction");
            }
        }
    }

    private static <T> List<T> race(int count, IntFunction<Callable<T>> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Callable<T> operation = task.apply(i);
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
                    return operation.call();
                }));
            }
            start.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "JDBC workers terminated");
        }
    }

    private static int ownerCount(TravelDatabases.Db db) throws SQLException {
        try (Connection c = db.source().connection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT COUNT(*) FROM allium_travel_home_owners")) {
            r.next();
            return r.getInt(1);
        }
    }

    /** Seed directly to exercise a persisted count without a thousand separate test transactions. */
    private static void seedThousandHomes(TravelDatabases.Db db) throws SQLException {
        try (Connection c = db.source().connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO allium_travel_home_owners (owner, revision) VALUES (?, 1000)")) {
                s.setString(1, OWNER.toString());
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO allium_travel_homes "
                    + "(owner, name, world_id, world_name, x, y, z, yaw, pitch, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < 1000; i++) {
                    s.setString(1, OWNER.toString());
                    s.setString(2, "home" + i);
                    s.setString(3, FIRST.worldId().toString());
                    s.setString(4, FIRST.worldName());
                    s.setDouble(5, FIRST.x());
                    s.setDouble(6, FIRST.y());
                    s.setDouble(7, FIRST.z());
                    s.setFloat(8, FIRST.yaw());
                    s.setFloat(9, FIRST.pitch());
                    s.setLong(10, i + 1);
                    s.addBatch();
                }
                s.executeBatch();
            }
            c.commit();
        }
    }

    /** Injects failure after a real INSERT, leaving actual partial writes for rollback to undo. */
    private static final class FaultSource implements ConnectionSource {
        private final ConnectionSource source;
        private final Throwable failure;
        private final boolean rollbackFails;
        private final AtomicBoolean failNextInsert = new AtomicBoolean(true);
        private final AtomicInteger opened = new AtomicInteger();
        private final List<String> events = new ArrayList<>();

        private FaultSource(ConnectionSource source, Throwable failure, boolean rollbackFails) {
            this.source = source;
            this.failure = failure;
            this.rollbackFails = rollbackFails;
        }

        @Override
        public Connection connection() throws SQLException {
            opened.incrementAndGet();
            Connection actual = source.connection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "setAutoCommit" -> events.add("auto:" + arguments[0]);
                            case "close" -> events.add("close");
                            case "rollback" -> {
                                events.add("rollback");
                                if (rollbackFails) throw new SQLException("injected rollback failure", "08006");
                            }
                        }
                        Object value = TravelDatabases.invoke(method, actual, arguments);
                        if (!method.getName().equals("prepareStatement") || arguments == null
                                || !((String) arguments[0]).startsWith("INSERT INTO allium_travel_homes ")) return value;
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (statementProxy, statementMethod, statementArguments) -> {
                                    Object result = TravelDatabases.invoke(statementMethod, statement, statementArguments);
                                    if (statementMethod.getName().equals("executeUpdate") && failNextInsert.compareAndSet(true, false)) {
                                        throw failure;
                                    }
                                    return result;
                                });
                    });
        }
    }
}
