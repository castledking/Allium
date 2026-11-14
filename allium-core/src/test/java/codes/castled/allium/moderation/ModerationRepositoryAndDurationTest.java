package codes.castled.allium.moderation;

import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moderation persistence across SQLite and MariaDB/MySQL (when {@code ALLIUM_TEST_MYSQL_URL} is set) plus duration parsing. */
class ModerationRepositoryAndDurationTest {

    @Test
    void durationParserHandlesSimpleCompoundAndInvalidTokens() {
        assertEquals(OptionalLong.of(5_000L), DurationParser.parseMillis("5s"));
        assertEquals(OptionalLong.of(600_000L), DurationParser.parseMillis("10m"));
        assertEquals(OptionalLong.of(7_200_000L), DurationParser.parseMillis("2h"));
        assertEquals(OptionalLong.of(86_400_000L), DurationParser.parseMillis("1d"));
        assertEquals(OptionalLong.of(604_800_000L), DurationParser.parseMillis("1w"));
        assertEquals(OptionalLong.of(5_430_000L), DurationParser.parseMillis("1h30m30s"));

        assertTrue(DurationParser.parseMillis("").isEmpty());
        assertTrue(DurationParser.parseMillis(null).isEmpty());
        assertTrue(DurationParser.parseMillis("0s").isEmpty());
        assertTrue(DurationParser.parseMillis("-5m").isEmpty());
        assertTrue(DurationParser.parseMillis("spam").isEmpty());
        assertTrue(DurationParser.parseMillis("10x").isEmpty());
        assertTrue(DurationParser.parseMillis("10m extra").isEmpty());

        assertTrue(DurationParser.startsWithDigit("10x"));
        assertTrue(DurationParser.startsWithDigit("0s"));
        assertTrue(DurationParser.startsWithDigit(" 5foo"));
        assertTrue(DurationParser.startsWithDigit("123"));
        assertFalse(DurationParser.startsWithDigit("spam"));
        assertFalse(DurationParser.startsWithDigit("-5m"));
        assertFalse(DurationParser.startsWithDigit(""));
        assertFalse(DurationParser.startsWithDigit(null));

        assertEquals("1s", DurationParser.formatRemaining(100L));
        assertEquals("45s", DurationParser.formatRemaining(45_000L));
        assertEquals("4m 59s", DurationParser.formatRemaining(299_000L));
        assertEquals("2h 15m", DurationParser.formatRemaining(8_100_000L));
        assertEquals("3d 4h", DurationParser.formatRemaining(3L * 86_400_000L + 4L * 3_600_000L));
    }

    @Test
    void repositoryPersistsVanishMutesExpiryAndAuditRecords(@TempDir Path tempDir) throws Exception {
        for (ModerationDatabases.Db db : ModerationDatabases.all(tempDir)) {
            AtomicLong clock = new AtomicLong(1_000_000L);
            ModerationRepository repo = new ModerationRepository(db.source(), db.dialect(), clock::get);

            assertEquals(1, repo.migrate(), db.name());
            assertEquals(1, repo.migrate(), db.name());

            UUID staffA = UUID.randomUUID();
            UUID staffB = UUID.randomUUID();
            assertFalse(repo.loadVanished(staffA), db.name());

            repo.setVanished(staffA, true, "Console");
            repo.setVanished(staffB, true, "StaffA");
            assertTrue(repo.loadVanished(staffA), db.name());
            assertEquals(Set.of(staffA, staffB), repo.loadVanishedPlayers(), db.name());

            // Verify UPSERT on vanished player works cleanly on both SQLite and MariaDB/MySQL
            repo.setVanished(staffB, true, "Console");
            assertTrue(repo.loadVanished(staffB), db.name());

            repo.setVanished(staffA, false, "Console");
            assertFalse(repo.loadVanished(staffA), db.name());
            assertEquals(Set.of(staffB), repo.loadVanishedPlayers(), db.name());

            UUID mutedPerm = UUID.randomUUID();
            UUID mutedTemp = UUID.randomUUID();
            MuteRecord permRecord = new MuteRecord(mutedPerm, "PermBob", "Admin", "Spamming chat", clock.get(), null);
            MuteRecord tempRecord = new MuteRecord(mutedTemp, "TempAlice", "Admin", "Cooldown", clock.get(), clock.get() + 5_000L);

            repo.saveMute(permRecord);
            repo.saveMute(tempRecord);

            // Verify UPSERT on existing mute works cleanly on both SQLite and MariaDB/MySQL
            MuteRecord updatedPerm = new MuteRecord(mutedPerm, "PermBob", "Console", "Repeated spam", clock.get() + 100L, null);
            repo.saveMute(updatedPerm);

            assertEquals(2, repo.loadActiveMutes().size(), db.name());
            assertEquals(Optional.of(updatedPerm), repo.loadActiveMute(mutedPerm), db.name());
            assertEquals(Optional.of(tempRecord), repo.loadActiveMute(mutedTemp), db.name());

            // Advance clock past tempRecord expiration
            clock.addAndGet(6_000L);
            assertTrue(repo.loadActiveMute(mutedTemp).isEmpty(), db.name());
            List<MuteRecord> remaining = repo.loadActiveMutes();
            assertEquals(1, remaining.size(), db.name());
            assertEquals(mutedPerm, remaining.get(0).playerUuid(), db.name());

            assertTrue(repo.removeMute(mutedPerm), db.name());
            assertFalse(repo.removeMute(mutedPerm), db.name());
            assertTrue(repo.loadActiveMutes().isEmpty(), db.name());

            repo.recordAudit("VANISH_ON", "StaffA", staffA, "StaffA", "vanished=true");
            repo.recordAudit("MUTE", "Admin", mutedTemp, "TempAlice", "duration=5s reason=Cooldown");
            List<ModerationAuditRecord> audits = repo.loadRecentAudit(10);
            assertEquals(2, audits.size(), db.name());
            assertEquals("MUTE", audits.get(0).action(), db.name());
            assertEquals("VANISH_ON", audits.get(1).action(), db.name());
        }
    }

    @Test
    void moderationCommandSpecsEnforcePermissionsAndSuggestVisiblePlayers() {
        CommandCompletionContext staff = new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return Set.of("allium.vanish", "allium.vanish.others", "allium.vanish.see", "allium.mute", "allium.unmute").contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return List.of("Alice", "Bob");
            }
        };

        assertEquals(List.of("on", "off"), CoreCommandSpecs.VANISH.complete(staff, new String[]{""}));
        assertEquals(List.of("Alice", "Bob"), CoreCommandSpecs.VANISH.complete(staff, new String[]{"on", ""}));
        assertEquals(List.of("Alice"), CoreCommandSpecs.MUTE.complete(staff, new String[]{"a"}));
        assertEquals(List.of("5m", "15m", "1h", "1d", "7d"), CoreCommandSpecs.MUTE.complete(staff, new String[]{"Alice", ""}));
        assertEquals(List.of("Alice", "Bob"), CoreCommandSpecs.UNMUTE.complete(staff, new String[]{""}));
    }
}
