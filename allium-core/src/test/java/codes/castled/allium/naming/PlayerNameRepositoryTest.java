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
        assertEquals(1, repository.migrate());
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
    }

    @Test
    void interruptedMigrationAcceptsOnlyTheExactExistingIndex() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("interrupted.db"));
        PlayerNameRepository repository = new PlayerNameRepository(source, SqlDialect.SQLITE, () -> 1L);
        repository.migrate();
        try (var c = source.connection(); var s = c.createStatement()) {
            s.execute("DELETE FROM allium_schema WHERE component = 'player-names'");
        }
        assertEquals(1, repository.migrate(), "DDL succeeded before an interrupted version record");
        try (var c = source.connection(); var s = c.createStatement()) {
            s.execute("DELETE FROM allium_schema WHERE component = 'player-names'");
            s.execute("DROP INDEX allium_player_names_by_name");
            s.execute("CREATE INDEX allium_player_names_by_name ON allium_player_names (last_seen)");
        }
        assertThrows(SQLException.class, repository::migrate, "a wrong index cannot masquerade as applied DDL");
    }
}
