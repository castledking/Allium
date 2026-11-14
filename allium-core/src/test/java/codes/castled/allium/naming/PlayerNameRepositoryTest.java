package codes.castled.allium.naming;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerNameRepositoryTest {
    @TempDir
    Path dir;

    @Test
    void delayedWritesCannotRevertRenamesAndRecentLoadingIsBounded() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("names.db"));
        PlayerNameRepository repository = new PlayerNameRepository(source, SqlDialect.SQLITE, () -> 1L);
        assertEquals(2, repository.migrate());
        UUID renamed = UUID.randomUUID(), recent = UUID.randomUUID();
        repository.record(renamed, "OldName", 10);
        repository.record(renamed, "NewName", 30);
        repository.record(renamed, "OldName", 20);
        repository.record(recent, "Recent", 40);
        assertTrue(repository.byName("OldName").isEmpty());
        assertEquals(renamed, repository.byName("NEWNAME").orElseThrow().uuid());
        assertEquals("NewName", repository.byUuid(renamed).orElseThrow().name());
        assertEquals(List.of("Recent"), repository.recent(1).stream().map(NameCache.Entry::name).toList());
        assertEquals(List.of("NewName", "Recent"), repository.recent(2).stream().map(NameCache.Entry::name).toList());

        List<PlayerNameRepository.NameHistoryEntry> history = repository.nameHistory(renamed);
        assertEquals(List.of("NewName", "OldName"), history.stream().map(PlayerNameRepository.NameHistoryEntry::name).toList());
        assertEquals(10L, history.get(1).firstSeen());
        assertEquals(20L, history.get(1).lastSeen());
        assertEquals(30L, history.get(0).lastSeen());
    }

    @Test
    void interruptedMigrationAcceptsOnlyTheExactExistingIndex() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("interrupted.db"));
        PlayerNameRepository repository = new PlayerNameRepository(source, SqlDialect.SQLITE, () -> 1L);
        repository.migrate();
        try (var c = source.connection(); var s = c.createStatement()) {
            s.execute("DELETE FROM allium_schema WHERE component = 'player-names'");
        }
        assertEquals(2, repository.migrate(), "DDL succeeded before an interrupted version record");
        try (var c = source.connection(); var s = c.createStatement()) {
            s.execute("DELETE FROM allium_schema WHERE component = 'player-names'");
            s.execute("DROP INDEX allium_player_names_by_name");
            s.execute("CREATE INDEX allium_player_names_by_name ON allium_player_names (last_seen)");
        }
        assertThrows(SQLException.class, repository::migrate, "a wrong index cannot masquerade as applied DDL");
    }

    @Test
    void v2MigrationBackfillsNameHistoryFromExistingV1Rows() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("upgrade.db"));
        UUID legacyId = UUID.randomUUID();
        try (var c = source.connection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE allium_schema (component VARCHAR(64) NOT NULL PRIMARY KEY, version INT NOT NULL, applied_at BIGINT NOT NULL)");
            s.execute("INSERT INTO allium_schema VALUES ('player-names', 1, 100)");
            s.execute("CREATE TABLE allium_player_names (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL, last_seen BIGINT NOT NULL)");
            s.execute("CREATE INDEX allium_player_names_by_name ON allium_player_names (name_lower, last_seen)");
            s.execute("INSERT INTO allium_player_names VALUES ('" + legacyId + "', 'LegacyHero', 'legacyhero', 500)");
        }
        PlayerNameRepository repository = new PlayerNameRepository(source, SqlDialect.SQLITE, () -> 2L);
        assertEquals(2, repository.migrate());
        List<PlayerNameRepository.NameHistoryEntry> history = repository.nameHistory(legacyId);
        assertEquals(1, history.size());
        assertEquals("LegacyHero", history.get(0).name());
        assertEquals(500L, history.get(0).firstSeen());
        assertEquals(500L, history.get(0).lastSeen());
    }
}
