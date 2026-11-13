package codes.castled.allium.preference;

import codes.castled.allium.storage.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SocialSpyPreferenceTest {

    private static final PreferenceDefaults DEFAULTS = new PreferenceDefaults(true, true, true, 0.8D);

    @TempDir
    Path dir;

    private static PreferenceRepository repository(Databases.Db db) {
        return new PreferenceRepository(db.source(), db.dialect(), System::currentTimeMillis);
    }

    @Test
    void socialSpyIsOffByDefaultRegardlessOfOtherConfiguredDefaults() {
        StoredPreferences empty = StoredPreferences.empty(UUID.randomUUID());
        assertNull(empty.socialSpy(), "no override is stored for a new player");
        for (PreferenceDefaults defaults : List.of(DEFAULTS, new PreferenceDefaults(false, false, false, 0.0D))) {
            assertFalse(empty.effective(defaults).socialSpy(), "social spy requires an explicit opt-in");
        }
    }

    @Test
    void localSocialSpyChangesPreserveEveryOtherPreferenceAndUnsetTurnsItOff() {
        UUID uuid = UUID.randomUUID();
        StoredPreferences before = new StoredPreferences(uuid, false, true, false, 0.0D, 0.625D, null, 7L);

        StoredPreferences enabled = before.with(PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
        assertEquals(new StoredPreferences(uuid, false, true, false, 0.0D, 0.625D, true, 7L), enabled);
        assertTrue(enabled.effective(DEFAULTS).socialSpy());

        StoredPreferences disabled = enabled.with(PreferenceChange.set(PreferenceKey.SOCIAL_SPY, false));
        assertEquals(new StoredPreferences(uuid, false, true, false, 0.0D, 0.625D, false, 7L), disabled);
        assertFalse(disabled.effective(DEFAULTS).socialSpy());

        StoredPreferences reset = enabled.with(PreferenceChange.unset(PreferenceKey.SOCIAL_SPY));
        assertEquals(before, reset, "reset removes only the social spy override");
        assertFalse(reset.effective(DEFAULTS).socialSpy());
    }

    @Test
    void socialSpyRoundTripsThroughStorageAndOtherPreferenceWritesPreserveIt() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            repository.migrate();
            UUID uuid = UUID.randomUUID();
            repository.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, false)
                    .andSet(PreferenceKey.ACCEPT_PAYMENTS, true)
                    .andSet(PreferenceKey.ACCEPT_TELEPORT_REQUESTS, false)
                    .andSet(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.0D)
                    .andSet(PreferenceKey.MESSAGE_SOUND_RESTORE, 0.625D));

            StoredPreferences enabled = repository.apply(uuid, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
            assertEquals(new StoredPreferences(uuid, false, true, false, 0.0D, 0.625D, true, 2L), enabled, db.name());

            PreferenceRepository restarted = repository(db);
            assertEquals(Optional.of(enabled), restarted.load(uuid), db.name() + ": opt-in survives a restart");
            assertTrue(restarted.load(uuid).orElseThrow().effective(DEFAULTS).socialSpy());

            StoredPreferences otherChange = restarted.apply(uuid, PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, true));
            assertEquals(new StoredPreferences(uuid, true, true, false, 0.0D, 0.625D, true, 3L), otherChange,
                    db.name() + ": changing another preference must retain social spy");

            StoredPreferences disabled = restarted.apply(uuid, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, false));
            assertEquals(new StoredPreferences(uuid, true, true, false, 0.0D, 0.625D, false, 4L), disabled, db.name());
            assertEquals(Optional.of(disabled), repository(db).load(uuid), db.name() + ": explicit opt-out survives a restart");
            assertFalse(disabled.effective(DEFAULTS).socialSpy());

            restarted.apply(uuid, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
            StoredPreferences reset = restarted.apply(uuid, PreferenceChange.unset(PreferenceKey.SOCIAL_SPY));
            assertEquals(new StoredPreferences(uuid, true, true, false, 0.0D, 0.625D, null, 6L), reset, db.name());
            StoredPreferences reloaded = repository(db).load(uuid).orElseThrow();
            assertEquals(reset, reloaded, db.name() + ": reset survives a restart");
            assertFalse(reloaded.effective(DEFAULTS).socialSpy());
        }
    }

    @Test
    void socialSpyWritesAreIsolatedByUuidAndDoNotCreateRowsForOtherPlayers() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            PreferenceRepository repository = repository(db);
            repository.migrate();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID untouched = UUID.randomUUID();

            repository.apply(first, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
            assertEquals(Optional.empty(), repository.load(second), db.name() + ": another UUID has no row");
            StoredPreferences secondOff = repository.apply(second, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, false));

            PreferenceRepository restarted = repository(db);
            assertEquals(Optional.of(new StoredPreferences(first, null, null, null, null, null, true, 1L)),
                    restarted.load(first), db.name() + ": the second player's opt-out did not affect the first");
            assertEquals(Optional.of(new StoredPreferences(second, null, null, null, null, null, false, 1L)),
                    restarted.load(second), db.name());

            restarted.apply(first, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, false));
            assertEquals(Optional.of(secondOff), repository(db).load(second), db.name() + ": another UUID's write changed nothing");
            restarted.apply(second, PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
            assertEquals(Optional.of(new StoredPreferences(first, null, null, null, null, null, false, 2L)),
                    repository(db).load(first), db.name());
            assertEquals(Optional.of(new StoredPreferences(second, null, null, null, null, null, true, 2L)),
                    repository(db).load(second), db.name());
            assertEquals(Optional.empty(), repository(db).load(untouched), db.name() + ": defaults never create another row");
        }
    }

    @Test
    void migratingSeededVersionOneRowsPreservesAllPreferencesAndLeavesSocialSpyOff() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            List<StoredPreferences> legacy = List.of(
                    new StoredPreferences(UUID.randomUUID(), false, true, false, 0.0D, 0.625D, null, 12L),
                    new StoredPreferences(UUID.randomUUID(), true, false, true, 0.4D, null, null, 34L),
                    new StoredPreferences(UUID.randomUUID(), null, null, null, null, null, null, 56L));
            seedVersionOne(db, legacy);
            SchemaMigrator schema = new SchemaMigrator(db.source(), db.dialect(), System::currentTimeMillis);
            assertEquals(1, schema.version(PreferenceRepository.COMPONENT), db.name() + ": starts at v1");

            PreferenceRepository repository = repository(db);
            assertEquals(2, repository.migrate(), db.name());
            assertEquals(2, schema.version(PreferenceRepository.COMPONENT), db.name() + ": v2 is recorded");
            List<StoredPreferences> migrated = new ArrayList<>();
            for (StoredPreferences before : legacy) {
                StoredPreferences after = repository.load(before.uuid()).orElseThrow();
                assertAll(db.name() + ": migration preserves every override, NULL and row version",
                        () -> assertEquals(before.uuid(), after.uuid()),
                        () -> assertEquals(before.acceptMessages(), after.acceptMessages()),
                        () -> assertEquals(before.acceptPayments(), after.acceptPayments()),
                        () -> assertEquals(before.acceptTeleportRequests(), after.acceptTeleportRequests()),
                        () -> assertEquals(before.messageSoundVolume(), after.messageSoundVolume()),
                        () -> assertEquals(before.messageSoundRestore(), after.messageSoundRestore()),
                        () -> assertEquals(before.version(), after.version()),
                        () -> assertFalse(after.effective(DEFAULTS).socialSpy(), "existing players are not opted in"),
                        () -> assertEquals(before.effective(DEFAULTS), after.effective(DEFAULTS)));
                migrated.add(after);
            }

            StoredPreferences enabled = repository.apply(legacy.get(0).uuid(), PreferenceChange.set(PreferenceKey.SOCIAL_SPY, true));
            PreferenceRepository restarted = repository(db);
            assertEquals(2, restarted.migrate(), db.name() + ": migration is not reapplied on restart");
            assertEquals(Optional.of(enabled), restarted.load(enabled.uuid()), db.name() + ": a later opt-in survives restart");
            for (StoredPreferences untouched : migrated.subList(1, migrated.size())) {
                assertEquals(Optional.of(untouched), restarted.load(untouched.uuid()), db.name());
            }
        }
    }

    @Test
    void migrationRetriesWhenSocialSpyColumnExistsButVersionTwoWasNotRecorded() throws Exception {
        for (Databases.Db db : Databases.all(dir)) {
            StoredPreferences legacy = new StoredPreferences(UUID.randomUUID(), false, true, false,
                    0.0D, 0.625D, null, 12L);
            StoredPreferences optedIn = new StoredPreferences(UUID.randomUUID(), true, false, true,
                    0.4D, null, true, 34L);
            seedVersionOne(db, List.of(legacy, optedIn));
            // Simulate a stopped migration: the DDL succeeded, but allium_schema still records v1.
            try (Connection connection = db.source().connection(); Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + PreferenceRepository.TABLE + " ADD COLUMN social_spy BOOLEAN NULL");
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + PreferenceRepository.TABLE
                        + " SET social_spy = ? WHERE uuid = ?")) {
                    update.setBoolean(1, true);
                    update.setString(2, optedIn.uuid().toString());
                    assertEquals(1, update.executeUpdate(), db.name() + ": seed an existing opt-in");
                }
            }
            SchemaMigrator schema = new SchemaMigrator(db.source(), db.dialect(), System::currentTimeMillis);
            assertEquals(1, schema.version(PreferenceRepository.COMPONENT), db.name() + ": v2 has not been recorded");
            PreferenceRepository restarted = repository(db);
            assertEquals(Optional.of(legacy), restarted.load(legacy.uuid()), db.name() + ": v2 column already exists");
            assertEquals(Optional.of(optedIn), restarted.load(optedIn.uuid()), db.name() + ": opt-in exists before retry");

            int version = assertDoesNotThrow(restarted::migrate,
                    db.name() + ": retry must tolerate the already-applied column addition");
            assertEquals(2, version, db.name());
            assertEquals(2, schema.version(PreferenceRepository.COMPONENT), db.name() + ": retry records v2");
            assertEquals(Optional.of(legacy), restarted.load(legacy.uuid()),
                    db.name() + ": retry preserves every legacy override and row version");
            assertFalse(restarted.load(legacy.uuid()).orElseThrow().effective(DEFAULTS).socialSpy(),
                    db.name() + ": retry does not opt in existing players");
            assertEquals(Optional.of(optedIn), restarted.load(optedIn.uuid()),
                    db.name() + ": retry preserves the existing opt-in and every other override and row version");
            PreferenceRepository subsequentRestart = repository(db);
            assertEquals(2, subsequentRestart.migrate(), db.name() + ": subsequent restart succeeds");
            assertEquals(Optional.of(legacy), subsequentRestart.load(legacy.uuid()), db.name());
            assertEquals(Optional.of(optedIn), subsequentRestart.load(optedIn.uuid()),
                    db.name() + ": existing opt-in also survives subsequent restart");
        }
    }

    private static void seedVersionOne(Databases.Db db, List<StoredPreferences> rows) throws SQLException {
        // Freeze the pre-social-spy layout so this exercises an actual upgrade, not a fresh v2 database.
        try (Connection connection = db.source().connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + SchemaMigrator.TABLE + " (component VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "version INT NOT NULL, applied_at BIGINT NOT NULL)");
            statement.executeUpdate("INSERT INTO " + SchemaMigrator.TABLE
                    + " (component, version, applied_at) VALUES ('preferences', 1, 1000)");
            statement.execute("CREATE TABLE " + PreferenceRepository.TABLE + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "accept_messages BOOLEAN NULL, "
                    + "accept_payments BOOLEAN NULL, "
                    + "accept_teleport_requests BOOLEAN NULL, "
                    + "message_sound_volume DOUBLE NULL, "
                    + "message_sound_restore DOUBLE NULL, "
                    + "version BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + PreferenceRepository.TABLE
                    + " (uuid, accept_messages, accept_payments, accept_teleport_requests, message_sound_volume, "
                    + "message_sound_restore, version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (StoredPreferences row : rows) {
                    insert.setString(1, row.uuid().toString());
                    insert.setObject(2, row.acceptMessages(), Types.BOOLEAN);
                    insert.setObject(3, row.acceptPayments(), Types.BOOLEAN);
                    insert.setObject(4, row.acceptTeleportRequests(), Types.BOOLEAN);
                    insert.setObject(5, row.messageSoundVolume(), Types.DOUBLE);
                    insert.setObject(6, row.messageSoundRestore(), Types.DOUBLE);
                    insert.setLong(7, row.version());
                    insert.setLong(8, 1000L);
                    insert.executeUpdate();
                }
            }
        }
    }
}
