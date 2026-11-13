package codes.castled.allium.mail;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Test databases for mail: a fresh SQLite file, and MySQL when {@code ALLIUM_TEST_MYSQL_URL} is set. */
final class MailDatabases {

    record Db(String name, SqlDialect dialect, ConnectionSource source) {
        @Override
        public String toString() {
            return name;
        }
    }

    private MailDatabases() {
    }

    static List<Db> all(Path dir) throws SQLException {
        List<Db> out = new ArrayList<>();
        String file = dir.resolve("mail-" + System.nanoTime() + ".db").toAbsolutePath().toString();
        out.add(new Db("sqlite", SqlDialect.SQLITE, () -> {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 10000");
            }
            return connection;
        }));
        String url = System.getenv("ALLIUM_TEST_MYSQL_URL");
        if (url != null && !url.isBlank()) {
            Db db = new Db("mysql", SqlDialect.MYSQL, () -> DriverManager.getConnection(url));
            try (Connection connection = db.source().connection(); Statement statement = connection.createStatement()) {
                for (String table : List.of("allium_mail", "allium_mail_sends", "allium_mail_boxes", "allium_mail_activations", "allium_mail_claim_receipts")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
                statement.execute("DELETE FROM allium_schema WHERE component = 'mail'");
            } catch (SQLException ignored) {
                // allium_schema may not exist yet
            }
            out.add(db);
        }
        return out;
    }
}
