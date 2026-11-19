package codes.castled.allium.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaMigratorTest {

    @TempDir
    Path dir;

    @Test
    void aDropIndexThatAlreadyRanIsAcceptedButOtherFailuresStillAbort() throws Exception {
        String file = dir.resolve("schema.db").toAbsolutePath().toString();
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
        List<Migration> migrations = List.of(
                Migration.of(1, "table and index", List.of("CREATE TABLE IF NOT EXISTS t (a INT, b INT)", "CREATE INDEX t_a ON t (a)"),
                        List.of("CREATE TABLE IF NOT EXISTS t (a INT, b INT)", "CREATE INDEX t_a ON t (a)")),
                // MySQL has no DROP INDEX IF EXISTS, so the plain form must survive a rerun.
                Migration.of(2, "move the index", List.of("DROP INDEX t_a", "CREATE INDEX t_b ON t (b)"),
                        List.of("DROP INDEX t_a ON t", "CREATE INDEX t_b ON t (b)")));
        SchemaMigrator migrator = new SchemaMigrator(source, SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(2, migrator.migrate("demo", migrations));

        // The DDL of version 2 completed but its version record did not.
        try (var c = source.connection(); var statement = c.createStatement()) {
            statement.execute("UPDATE " + SchemaMigrator.TABLE + " SET version = 1 WHERE component = 'demo'");
        }
        assertEquals(2, migrator.migrate("demo", migrations), "the already-dropped index is not an error");

        List<Migration> broken = List.of(migrations.get(0), migrations.get(1),
                Migration.of(3, "bad", List.of("ALTER TABLE missing_table ADD COLUMN c INT"), List.of("ALTER TABLE missing_table ADD COLUMN c INT")));
        assertThrows(SQLException.class, () -> migrator.migrate("demo", broken));
    }
}
