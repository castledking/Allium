package codes.castled.allium.preference;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Test databases: a fresh SQLite file, and MySQL/MariaDB when {@code ALLIUM_TEST_MYSQL_URL} is set. */
final class Databases {

    record Db(String name, SqlDialect dialect, ConnectionSource source) {
        @Override
        public String toString() {
            return name;
        }
    }

    private Databases() {
    }

    static Db sqlite(Path file) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        return new Db("sqlite", SqlDialect.SQLITE, () -> {
            Connection connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 10000");
            }
            return connection;
        });
    }

    /** A MySQL/MariaDB database with the preference tables dropped, or none. */
    static List<Db> mysql() throws SQLException {
        String url = System.getenv("ALLIUM_TEST_MYSQL_URL");
        List<Db> out = new ArrayList<>();
        if (url == null || url.isBlank()) {
            return out;
        }
        Db db = new Db("mysql", SqlDialect.MYSQL, () -> DriverManager.getConnection(url));
        try (Connection connection = db.source().connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PreferenceRepository.TABLE);
            statement.execute("DROP TABLE IF EXISTS allium_schema");
        }
        out.add(db);
        return out;
    }

    static List<Db> all(Path dir) throws SQLException {
        List<Db> out = new ArrayList<>();
        out.add(sqlite(dir.resolve("prefs-" + System.nanoTime() + ".db")));
        out.addAll(mysql());
        return out;
    }
}
