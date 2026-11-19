package codes.castled.allium.flight;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Each player's banked timed-flight seconds ({@code allium_flight_time}). Blocking; call from a
 * worker. Changes are relative ({@link #add}) wherever they can be, so time spent on one server
 * and time given on another (sharing MySQL) both count.
 */
public final class FlightTimeRepository {

    public static final String COMPONENT = "flight_time";
    /** Ten years: more than any server sells, small enough for every sum to stay exact. */
    public static final long MAX_SECONDS = 3650L * 86_400L;

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public FlightTimeRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    public int migrate() throws SQLException {
        String table = "CREATE TABLE IF NOT EXISTS allium_flight_time (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                + "seconds BIGINT NOT NULL, updated_at BIGINT NOT NULL)";
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT,
                List.of(Migration.of(1, "banked timed flight", List.of(table), List.of(table))));
    }

    /** The player's banked seconds, 0 when they have none. */
    public long seconds(UUID player) throws SQLException {
        try (Connection c = connections.connection()) {
            return read(c, player, false);
        }
    }

    /** Adds {@code delta} seconds (negative to take), never below 0 or above {@link #MAX_SECONDS}; returns the new total. */
    public long add(UUID player, long delta) throws SQLException {
        try (Connection c = connections.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // MySQL locks the row so two servers adding at once both count.
                long next = clamp(read(c, player, dialect == SqlDialect.MYSQL) + delta);
                write(c, player, next);
                c.commit();
                return next;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Sets the player's banked seconds; returns what was stored. */
    public long set(UUID player, long seconds) throws SQLException {
        long next = clamp(seconds);
        try (Connection c = connections.connection()) {
            write(c, player, next);
            return next;
        }
    }

    static long clamp(long seconds) {
        return Math.max(0, Math.min(MAX_SECONDS, seconds));
    }

    private static long read(Connection c, UUID player, boolean lock) throws SQLException {
        String sql = "SELECT seconds FROM allium_flight_time WHERE uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        }
    }

    private void write(Connection c, UUID player, long seconds) throws SQLException {
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO allium_flight_time (uuid, seconds, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE seconds = VALUES(seconds), updated_at = VALUES(updated_at)"
                : "INSERT INTO allium_flight_time (uuid, seconds, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET seconds = excluded.seconds, updated_at = excluded.updated_at";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, player.toString());
            s.setLong(2, seconds);
            s.setLong(3, clock.getAsLong());
            s.executeUpdate();
        }
    }
}
