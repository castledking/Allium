package codes.castled.allium.naming;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * {@code allium_player_names}: one row per UUID with its current name, written on join. Shared by
 * mail and, from M3, {@code /pay}. Blocking JDBC: callers use a bounded pool.
 */
public final class PlayerNameRepository {

    public static final String COMPONENT = "player-names";
    private static final String CREATE = "CREATE TABLE IF NOT EXISTS allium_player_names ("
            + "uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL, last_seen BIGINT NOT NULL)";
    private static final String INDEX = "CREATE INDEX allium_player_names_by_name ON allium_player_names (name_lower, last_seen)";

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public PlayerNameRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    public int migrate() throws SQLException {
        List<String> v1 = List.of(CREATE, INDEX);
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, List.of(Migration.of(1, "player name index", v1, v1)));
    }

    public void record(UUID uuid, String name, long seen) throws SQLException {
        new NameCache.Entry(uuid, name, seen); // Validate before persisting an entry used in completion.
        String insert = "INSERT INTO allium_player_names (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?)";
        String upsert = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE name = IF(VALUES(last_seen) >= last_seen, VALUES(name), name), "
                  + "name_lower = IF(VALUES(last_seen) >= last_seen, VALUES(name_lower), name_lower), "
                  + "last_seen = GREATEST(last_seen, VALUES(last_seen))"
                : " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                  + "last_seen = excluded.last_seen WHERE excluded.last_seen >= allium_player_names.last_seen";
        try (Connection c = connections.connection(); PreparedStatement s = c.prepareStatement(insert + upsert)) {
            s.setString(1, uuid.toString());
            s.setString(2, name);
            s.setString(3, name.toLowerCase(Locale.ROOT));
            s.setLong(4, seen);
            s.executeUpdate();
        }
    }

    /** The most recent holder of {@code name}, case-insensitive. */
    public Optional<NameCache.Entry> byName(String name) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM allium_player_names WHERE name_lower = ? "
                     + "ORDER BY last_seen DESC LIMIT 1")) {
            s.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new NameCache.Entry(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    public Optional<NameCache.Entry> byUuid(UUID uuid) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM allium_player_names WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new NameCache.Entry(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    /** The {@code limit} most recently seen players, oldest first (to fill a cache in order). */
    public List<NameCache.Entry> recent(int limit) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM allium_player_names ORDER BY last_seen DESC LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet r = s.executeQuery()) {
                List<NameCache.Entry> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new NameCache.Entry(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3)));
                }
                java.util.Collections.reverse(out);
                return out;
            }
        }
    }
}
