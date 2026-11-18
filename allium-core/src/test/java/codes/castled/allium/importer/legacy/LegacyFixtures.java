package codes.castled.allium.importer.legacy;

import codes.castled.allium.storage.ConnectionSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Legacy Allium's own table definitions (managers/DB/Database.java, schema 9) on a real H2 2.1.214. */
final class LegacyFixtures {

    static final String OWNER_A = "11111111-1111-4111-8111-111111111111";
    static final String OWNER_B = "22222222-2222-4222-8222-222222222222";

    private LegacyFixtures() {
    }

    static Path create(Path dir) throws SQLException {
        Path base = dir.resolve("sfcore");
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + base.toAbsolutePath() + ";MODE=MySQL");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS database_version (version INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO database_version (version) VALUES (9)");
            s.execute("CREATE TABLE IF NOT EXISTS player_locations (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
                    + "location_type VARCHAR(50) NOT NULL, home_name VARCHAR(50), world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, "
                    + "y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, timestamp BIGINT NOT NULL, "
                    + "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_home (player_uuid, location_type, home_name))");
            s.execute("CREATE TABLE IF NOT EXISTS warps (name VARCHAR(50) PRIMARY KEY, world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, "
                    + "y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, creator_uuid VARCHAR(36) NOT NULL, "
                    + "creator_name VARCHAR(16) NOT NULL, is_public BOOLEAN DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS player_max_homes (uuid VARCHAR(36) PRIMARY KEY, max_homes INT NOT NULL DEFAULT 1)");
            // Legacy's sethome inserted a new row each time (no unique key): the newest wins.
            home(s, OWNER_A, "base", "world", 10, 64, 10, 1000);
            home(s, OWNER_A, "base", "world", 20, 65, 20, 2000);
            home(s, OWNER_A, "Farm", "world", 30, 70, -30, 1500);
            home(s, OWNER_A, "my home", "world", 1, 1, 1, 1500);
            home(s, OWNER_A, "nether", "world_nether", 5, 40, 5, 1500);
            home(s, OWNER_B, "Base", "world", 0, 64, 0, 1000);
            home(s, OWNER_B, "base", "world", 1, 64, 1, 1100);
            home(s, "not-a-uuid", "home", "world", 0, 64, 0, 1000);
            s.execute("INSERT INTO player_locations (player_uuid, location_type, home_name, world, x, y, z, yaw, pitch, timestamp) "
                    + "VALUES ('" + OWNER_B + "', 'HOME', NULL, 'world', 0, 64, 0, 0, 0, 900)");
            location(s, OWNER_A, "LOGOUT", "world", 100, 64, 100, 5000);
            location(s, OWNER_A, "LOGOUT", "world", 101, 64, 101, 6000);
            location(s, OWNER_B, "LOGOUT", "world", 200, 64, 200, 5000);
            location(s, OWNER_A, "DEATH", "world", 9, 9, 9, 7000);
            s.execute("INSERT INTO warps (name, world, x, y, z, yaw, pitch, creator_uuid, creator_name, is_public) VALUES "
                    + "('Spawn', 'world', 0.5, 64, 0.5, 90, 0, '" + OWNER_A + "', 'Alice', TRUE), "
                    + "('shop', 'world', 50, 64, 50, 0, 0, '" + OWNER_A + "', 'Alice', FALSE), "
                    + "('bad warp', 'world', 1, 1, 1, 0, 0, '" + OWNER_A + "', 'Alice', TRUE)");
            s.execute("INSERT INTO player_max_homes (uuid, max_homes) VALUES ('" + OWNER_A + "', 5), ('" + OWNER_B + "', -1)");
        }
        return dir.resolve("sfcore.mv.db");
    }

    private static void home(Statement s, String owner, String name, String world, double x, double y, double z, long at) throws SQLException {
        s.execute("INSERT INTO player_locations (player_uuid, location_type, home_name, world, x, y, z, yaw, pitch, timestamp) VALUES ('"
                + owner + "', 'HOME', '" + name + "', '" + world + "', " + x + ", " + y + ", " + z + ", 12.5, -3, " + at + ")");
    }

    private static void location(Statement s, String owner, String type, String world, double x, double y, double z, long at) throws SQLException {
        s.execute("INSERT INTO player_locations (player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp) VALUES ('"
                + owner + "', '" + type + "', '" + world + "', " + x + ", " + y + ", " + z + ", 0, 0, " + at + ")");
    }

    static Connection open(Path mvDb) throws SQLException {
        String file = mvDb.toAbsolutePath().toString();
        return DriverManager.getConnection("jdbc:h2:" + file.substring(0, file.length() - 6) + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=MySQL");
    }

    /** A file-backed SQLite destination. */
    static ConnectionSource sqlite(Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("allium.db").toAbsolutePath();
        return () -> {
            Connection c = DriverManager.getConnection(url);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 10000");
            }
            return c;
        };
    }
}
