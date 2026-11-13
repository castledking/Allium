package codes.castled.allium.preference;

import codes.castled.allium.storage.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PreferenceStorageTest {

    private static final PreferenceDefaults DEFAULTS = new PreferenceDefaults(true, true, true, 0.8D);

    @TempDir
    Path dir;

    private static PreferenceRepository repository(Databases.Db db) {
        return new PreferenceRepository(db.source(), db.dialect(), System::currentTimeMillis);
    }

    @Test
    void migrationsAreIdempotentAndANewerSchemaIsRefused() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            assertEquals(2, repository.migrate(), db.name());
            assertEquals(2, repository.migrate(), db.name() + " second start");
            try (Connection c = db.source().connection(); Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE " + SchemaMigrator.TABLE + " SET version = 7 WHERE component = 'preferences'");
            }
            SQLException newer = assertThrows(SQLException.class, repository::migrate, db.name());
            assertTrue(newer.getMessage().contains("newer than this Allium build"), newer.getMessage());
        }
    }

    @Test
    void roundTripPatchesOnlyTheGivenFieldsAndSurvivesARestart() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            repository.migrate();
            UUID uuid = UUID.randomUUID();
            assertEquals(Optional.empty(), repository.load(uuid), "a new player has no row; defaults are never stored");

            StoredPreferences one = repository.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false));
            assertEquals(new StoredPreferences(uuid, false, null, null, null, null, 1L), one, db.name());
            StoredPreferences two = repository.apply(uuid, PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.4D)
                    .andSet(PreferenceKey.ACCEPT_TELEPORT_REQUESTS, false));
            assertEquals(new StoredPreferences(uuid, false, null, false, 0.4D, null, 2L), two, db.name());
            StoredPreferences three = repository.apply(uuid, PreferenceChange.unset(PreferenceKey.ACCEPT_MESSAGES));
            assertNull(three.acceptMessages(), "unset returns the field to the configured default");
            assertEquals(3L, three.version());
            assertTrue(three.effective(DEFAULTS).acceptMessages());
            assertFalse(three.effective(DEFAULTS).acceptTeleportRequests());

            PreferenceRepository restarted = repository(db);
            assertEquals(2, restarted.migrate());
            assertEquals(Optional.of(three), restarted.load(uuid), db.name() + " after a restart");
        }
    }

    @Test
    void concurrentWritesFromTwoSessionsNeverClobberEachOther() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            repository(db).migrate();
            UUID uuid = UUID.randomUUID();
            // Two independent stores (two servers or sessions) over the same database.
            PreferenceStore a = new PreferenceStore(repository(db), 1, 1024, u -> { });
            PreferenceStore b = new PreferenceStore(repository(db), 1, 1024, u -> { });
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<StoredPreferences>> all = new ArrayList<>();
            Thread ta = new Thread(() -> {
                await(go);
                for (int i = 0; i < 40; i++) {
                    all.add(a.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, i % 2 == 0)
                            .andSet(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.3D)));
                }
            });
            List<CompletableFuture<StoredPreferences>> allB = new ArrayList<>();
            Thread tb = new Thread(() -> {
                await(go);
                for (int i = 0; i < 40; i++) {
                    allB.add(b.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS, i % 2 == 1)
                            .andSet(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.7D)));
                }
            });
            ta.start();
            tb.start();
            go.countDown();
            ta.join();
            tb.join();
            all.addAll(allB);
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            assertTrue(a.close(10_000L).completed() && b.close(10_000L).completed());
            StoredPreferences row = repository(db).load(uuid).orElseThrow();
            assertEquals(Boolean.FALSE, row.acceptMessages(), db.name() + ": session A's last write (i=39) kept");
            assertEquals(Boolean.TRUE, row.acceptPayments(), db.name() + ": session B's last write (i=39) kept");
            assertTrue(Set.of(0.3D, 0.7D).contains(row.messageSoundVolume()), "the contested field holds one session's value");
            assertEquals(80L, row.version(), db.name() + ": every write applied exactly once");
            assertNull(row.acceptTeleportRequests(), "untouched fields stay unset");
        }
    }

    @Test
    void sessionsDiscardAStaleSlowLoadAndNeverStoreDefaults() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            repository.migrate();
            UUID uuid = UUID.randomUUID();
            repository.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false).andSet(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.3D));
            StoredPreferences before = repository.load(uuid).orElseThrow();

            CountDownLatch slow = new CountDownLatch(1);
            AtomicReference<Boolean> first = new AtomicReference<>(true);
            PreferenceStore store = new PreferenceStore(repository, 1, 64, u -> {
                if (first.getAndSet(false)) await(slow);
            });
            List<String> diagnostics = new ArrayList<>();
            PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, diagnostics::add);

            long g1 = sessions.begin(uuid);
            PreferenceSessions.View loading = sessions.view(uuid).orElseThrow();
            assertEquals(PreferenceSessions.State.LOADING, loading.state());
            assertNull(loading.effective(), "nothing to enforce from until the load completes");
            assertThrows(Exception.class, () -> sessions.toggleSound(uuid).get(5, TimeUnit.SECONDS), "toggles need the loaded value");

            // A patch made while loading is persisted and laid over the load, never replaced by it.
            CompletableFuture<StoredPreferences> patched = sessions.change(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS, false));
            // Reconnect before the slow load finishes.
            sessions.end(uuid, g1);
            long g2 = sessions.begin(uuid);
            assertNotEquals(g1, g2);
            slow.countDown();
            PreferenceSessions.View ready = sessions.whenReady(uuid).get(30, TimeUnit.SECONDS);
            patched.get(30, TimeUnit.SECONDS);
            assertEquals(g2, ready.generation());
            assertEquals(1L, sessions.stats().staleLoadsDiscarded(), "the first connection's load was discarded");
            assertFalse(ready.effective().acceptMessages(), db.name() + ": stored data, not the default (true)");
            assertFalse(ready.effective().acceptPayments(), "the patch made while loading survived the reconnect");
            assertEquals(0.3D, ready.effective().messageSoundVolume());

            StoredPreferences after = repository.load(uuid).orElseThrow();
            assertEquals(new StoredPreferences(uuid, false, false, null, 0.3D, null, before.version() + 1), after,
                    db.name() + ": loads wrote nothing; only the one patch changed the row");
            assertTrue(store.close(10_000L).completed());
            assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        }
    }

    @Test
    void soundRestorationStateRoundTripsThroughStorage() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            repository.migrate();
            UUID uuid = UUID.randomUUID();
            StoredPreferences s = repository.apply(uuid, MessageSoundRules.setVolume(StoredPreferences.empty(uuid), 0.45D));
            MessageSoundRules.Toggle mute = MessageSoundRules.toggle(s, DEFAULTS);
            s = repository.apply(uuid, mute.change());
            assertEquals(0.0D, s.messageSoundVolume());
            assertEquals(0.45D, s.messageSoundRestore(), db.name() + ": the restoration state is stored");
            s = repository(db).load(uuid).orElseThrow();
            MessageSoundRules.Toggle unmute = MessageSoundRules.toggle(s, DEFAULTS);
            assertEquals(0.45D, unmute.volume(), "after a restart, toggle restores the last positive override");
            assertEquals(MessageSoundRules.Outcome.RESTORED, unmute.outcome());
            s = repository.apply(uuid, unmute.change());
            assertEquals(0.45D, s.messageSoundVolume());
            assertNull(s.messageSoundRestore());
            s = repository.apply(uuid, MessageSoundRules.reset());
            assertNull(s.messageSoundVolume());
            assertNull(s.messageSoundRestore());
        }
    }

    @Test
    void soundRulesFollowTheContract() {
        UUID uuid = UUID.randomUUID();
        StoredPreferences none = StoredPreferences.empty(uuid);
        // No override: toggling mutes; toggling again follows the fallback (no restoration stored).
        MessageSoundRules.Toggle mute = MessageSoundRules.toggle(none, DEFAULTS);
        assertEquals(MessageSoundRules.Outcome.MUTED, mute.outcome());
        assertFalse(mute.stillMuted(), "muting is not 'still muted'");
        StoredPreferences muted = none.with(mute.change());
        assertEquals(0.0D, muted.messageSoundVolume());
        assertNull(muted.messageSoundRestore());
        MessageSoundRules.Toggle back = MessageSoundRules.toggle(muted, DEFAULTS);
        assertNull(muted.with(back.change()).messageSoundVolume(), "restores the configured fallback");
        assertEquals(0.8D, back.volume());
        assertEquals(MessageSoundRules.Outcome.FALLBACK, back.outcome());
        assertFalse(back.stillMuted());
        // A zero fallback stays muted and says so.
        PreferenceDefaults silent = new PreferenceDefaults(true, true, true, 0.0D);
        MessageSoundRules.Toggle silentBack = MessageSoundRules.toggle(muted, silent);
        assertEquals(MessageSoundRules.Outcome.FALLBACK_SILENT, silentBack.outcome());
        assertTrue(silentBack.stillMuted());
        // volume 0 remembers the previous positive override.
        StoredPreferences quiet = none.with(MessageSoundRules.setVolume(none, 0.6D));
        StoredPreferences zero = quiet.with(MessageSoundRules.setVolume(quiet, 0.0D));
        assertEquals(0.6D, zero.messageSoundRestore());
        assertThrows(IllegalArgumentException.class, () -> MessageSoundRules.setVolume(none, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MessageSoundRules.setVolume(none, 1.5D));
        assertThrows(IllegalArgumentException.class, () -> PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, "yes"));
    }

    @Test
    void aFailedLoadIsReportedAndNeverReplacedByDefaults() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("broken.db"));
        // No migration: the table does not exist, so the load fails.
        PreferenceStore store = new PreferenceStore(repository(db), 1, 8, u -> { });
        List<String> diagnostics = new ArrayList<>();
        PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, diagnostics::add);
        UUID uuid = UUID.randomUUID();
        sessions.begin(uuid);
        assertThrows(Exception.class, () -> sessions.whenReady(uuid).get(10, TimeUnit.SECONDS));
        PreferenceSessions.View view = sessions.view(uuid).orElseThrow();
        assertEquals(PreferenceSessions.State.FAILED, view.state());
        assertNull(view.effective());
        assertEquals(1, diagnostics.size());
        assertTrue(store.close(5_000L).completed());
    }

    @Test
    void anEarlierWriteResultNeverHidesALaterPendingPatch() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            repository(db).migrate();
            PreferenceStore store = new PreferenceStore(repository(db), 1, 64, u -> { });
            PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, m -> { });
            UUID uuid = UUID.randomUUID();
            sessions.begin(uuid);
            sessions.whenReady(uuid).get(10, TimeUnit.SECONDS);
            AtomicReference<PreferenceSessions.View> between = new AtomicReference<>();
            // The first write's completion runs on the storage lane before the second write starts.
            CompletableFuture<?> first = sessions.change(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false))
                    .thenRun(() -> between.set(sessions.view(uuid).orElseThrow()));
            CompletableFuture<StoredPreferences> second = sessions.change(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS, false));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(1L, between.get().stored().version(), db.name() + ": the first write's row is confirmed");
            assertEquals(Boolean.FALSE, between.get().stored().acceptPayments(), db.name() + ": the pending second patch stays visible");
            StoredPreferences done = sessions.view(uuid).orElseThrow().stored();
            assertEquals(new StoredPreferences(uuid, false, false, null, null, null, 2L), done, db.name());
            assertTrue(store.close(5_000L).completed());
        }
    }

    @Test
    void aFailedWriteDisappearsFromTheLocalView() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("flaky.db"));
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
        codes.castled.allium.storage.ConnectionSource flaky = () -> {
            if (broken.get()) {
                throw new SQLException("database unavailable (test)");
            }
            return db.source().connection();
        };
        PreferenceRepository repository = new PreferenceRepository(flaky, db.dialect(), System::currentTimeMillis);
        repository.migrate();
        UUID uuid = UUID.randomUUID();
        repository.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false));
        PreferenceStore store = new PreferenceStore(repository, 1, 8, u -> { });
        List<String> diagnostics = new ArrayList<>();
        PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, diagnostics::add);
        sessions.begin(uuid);
        sessions.whenReady(uuid).get(10, TimeUnit.SECONDS);
        broken.set(true);
        CompletableFuture<StoredPreferences> write = sessions.change(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, true));
        assertThrows(Exception.class, () -> write.get(10, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, sessions.view(uuid).orElseThrow().stored().acceptMessages(), "the unsaved value is not shown");
        assertEquals(1L, sessions.stats().writeFailures());
        assertEquals(1, diagnostics.size());
        broken.set(false);
        assertEquals(Boolean.FALSE, repository.load(uuid).orElseThrow().acceptMessages());
        assertTrue(store.close(5_000L).completed());
    }

    /** Runs retries at once on a fresh thread and records the requested delays. */
    private static PreferenceSessions.Delayer immediate(List<Long> delays) {
        return (task, delay) -> {
            synchronized (delays) {
                delays.add(delay);
            }
            new Thread(task, "test-retry").start();
        };
    }

    private static codes.castled.allium.storage.ConnectionSource failingFirst(Databases.Db db, java.util.concurrent.atomic.AtomicInteger failures) {
        return () -> {
            if (failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new SQLException("database unavailable (test)");
            }
            return db.source().connection();
        };
    }

    @Test
    void retryDelaysDoubleUpToTheCap() {
        PreferenceSessions.RetryPolicy policy = new PreferenceSessions.RetryPolicy(5, 1_000L, 15_000L);
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L, 15_000L),
                List.of(policy.delayMillis(1), policy.delayMillis(2), policy.delayMillis(3), policy.delayMillis(4), policy.delayMillis(5)));
        assertThrows(IllegalArgumentException.class, () -> new PreferenceSessions.RetryPolicy(-1, 0L, 0L));
    }

    @Test
    void aFailedLoadIsRetriedWithBackoffAndRecovers() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("blip.db"));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        PreferenceRepository repository = new PreferenceRepository(failingFirst(db, failures), db.dialect(), System::currentTimeMillis);
        repository.migrate();
        UUID uuid = UUID.randomUUID();
        repository.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS, false));
        PreferenceStore store = new PreferenceStore(repository, 1, 8, u -> { });
        List<Long> delays = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, diagnostics::add,
                new PreferenceSessions.RetryPolicy(4, 100L, 1_000L), immediate(delays));
        failures.set(2);
        sessions.begin(uuid);
        PreferenceSessions.View view = sessions.whenReady(uuid).get(10, TimeUnit.SECONDS);
        assertEquals(PreferenceSessions.State.READY, view.state());
        assertEquals(3, view.loadAttempts());
        assertEquals(Boolean.FALSE, view.stored().acceptPayments(), "the real row, not defaults");
        assertEquals(List.of(100L, 200L), delays);
        assertEquals(2L, sessions.stats().loadRetries());
        assertEquals(1L, sessions.stats().loadsRecovered());
        assertEquals(2, diagnostics.size(), "one line for the first failure, one for the recovery: " + diagnostics);
        assertEquals(PreferenceSessions.Acceptance.REFUSES, sessions.acceptance(uuid, PreferenceKey.ACCEPT_PAYMENTS));
        assertTrue(store.close(5_000L).completed());
    }

    @Test
    void retriesAreCappedAndTheSessionStaysUnavailable() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("down.db"));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        PreferenceStore store = new PreferenceStore(new PreferenceRepository(failingFirst(db, failures), db.dialect(), System::currentTimeMillis), 1, 8, u -> { });
        List<Long> delays = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        PreferenceSessions sessions = new PreferenceSessions(store, () -> DEFAULTS, diagnostics::add,
                new PreferenceSessions.RetryPolicy(3, 100L, 250L), immediate(delays));
        UUID uuid = UUID.randomUUID();
        sessions.begin(uuid);
        assertThrows(Exception.class, () -> sessions.whenReady(uuid).get(10, TimeUnit.SECONDS));
        PreferenceSessions.View view = sessions.view(uuid).orElseThrow();
        assertEquals(PreferenceSessions.State.FAILED, view.state());
        assertEquals(4, view.loadAttempts(), "the first load plus three retries, then no more");
        assertEquals(List.of(100L, 200L, 250L), delays);
        assertNull(view.effective());
        assertEquals(PreferenceSessions.Acceptance.UNAVAILABLE, sessions.acceptance(uuid, PreferenceKey.ACCEPT_MESSAGES));
        assertEquals(2, diagnostics.size(), "the first failure and the give-up: " + diagnostics);
        assertTrue(diagnostics.get(1).contains("Gave up"), diagnostics.get(1));
        assertTrue(store.close(5_000L).completed());
    }

    @Test
    void acceptanceFailsClosedUntilTheSessionIsReady() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("accept.db"));
        repository(db).migrate();
        CountDownLatch release = new CountDownLatch(1);
        PreferenceStore store = new PreferenceStore(repository(db), 1, 8, u -> await(release));
        PreferenceSessions sessions = new PreferenceSessions(store, () -> new PreferenceDefaults(true, false, true, 1.0D), m -> { });
        UUID uuid = UUID.randomUUID();
        assertEquals(PreferenceSessions.Acceptance.UNAVAILABLE, sessions.acceptance(uuid, PreferenceKey.ACCEPT_MESSAGES), "no session");
        sessions.begin(uuid);
        for (PreferenceKey flag : List.of(PreferenceKey.ACCEPT_MESSAGES, PreferenceKey.ACCEPT_PAYMENTS, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)) {
            assertEquals(PreferenceSessions.Acceptance.UNAVAILABLE, sessions.acceptance(uuid, flag), "LOADING: " + flag);
        }
        release.countDown();
        sessions.whenReady(uuid).get(10, TimeUnit.SECONDS);
        assertEquals(PreferenceSessions.Acceptance.ACCEPTS, sessions.acceptance(uuid, PreferenceKey.ACCEPT_MESSAGES));
        assertEquals(PreferenceSessions.Acceptance.REFUSES, sessions.acceptance(uuid, PreferenceKey.ACCEPT_PAYMENTS), "the configured default");
        assertThrows(IllegalArgumentException.class, () -> sessions.acceptance(uuid, PreferenceKey.MESSAGE_SOUND_VOLUME));
        assertTrue(store.close(5_000L).completed());
    }

    @Test
    void aShutdownTimeoutNamesEveryWriteItCouldNotFinish() throws Exception {
        Databases.Db db = Databases.sqlite(dir.resolve("stuck.db"));
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stuck = new java.util.concurrent.atomic.AtomicBoolean();
        codes.castled.allium.storage.ConnectionSource source = () -> {
            if (stuck.get()) {
                await(release);
            }
            return db.source().connection();
        };
        PreferenceRepository repository = new PreferenceRepository(source, db.dialect(), System::currentTimeMillis);
        repository.migrate();
        PreferenceStore store = new PreferenceStore(repository, 1, 16, u -> { });
        UUID uuid = UUID.randomUUID();
        stuck.set(true);
        CompletableFuture<StoredPreferences> running = store.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false));
        CompletableFuture<StoredPreferences> queued1 = store.apply(uuid, PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.25D));
        CompletableFuture<StoredPreferences> queued2 = store.apply(uuid, PreferenceChange.unset(PreferenceKey.ACCEPT_PAYMENTS));
        store.load(uuid);
        Thread.sleep(100L);
        long started = System.nanoTime();
        PreferenceStore.CloseReport report = store.close(300L);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "close returns after its timeout");
        assertFalse(report.completed());
        assertEquals(List.of(uuid + " {message-sound-volume=0.25}", uuid + " {accept-payments=unset}"), report.lostWrites());
        assertEquals(List.of(uuid + " {accept-messages=false}"), report.inProgressWrites());
        assertEquals(1, report.droppedLoads());
        ExecutionException dropped = assertThrows(ExecutionException.class, () -> queued1.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PreferenceStore.DroppedAtShutdownException.class, dropped.getCause());
        assertTrue(queued2.isCompletedExceptionally());
        assertInstanceOf(PreferenceStore.StoreBusyException.class,
                assertThrows(ExecutionException.class, () -> store.apply(uuid, PreferenceChange.unset(PreferenceKey.ACCEPT_MESSAGES)).get()).getCause(),
                "work after close is refused");
        release.countDown();
        running.handle((r, e) -> null).get(10, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
