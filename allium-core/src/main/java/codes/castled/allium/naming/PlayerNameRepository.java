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
 * {@code allium_player_names}: one row per UUID with its current name, written on join, plus
 * {@code allium_player_name_history} (schema v2) tracking every name seen for each UUID.
 * Shared by mail, {@code /whois}, and future payments. Blocking JDBC: callers use a bounded pool.
 */
public final class PlayerNameRepository {

    public static final String COMPONENT = "player-names";
    public static final String TABLE = "allium_player_names";
    public static final String HISTORY_TABLE = "allium_player_name_history";

    public record NameHistoryEntry(String name, long firstSeen, long lastSeen) {
    }

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL, last_seen BIGINT NOT NULL)";
    private static final String INDEX = "CREATE INDEX allium_player_names_by_name ON " + TABLE + " (name_lower, last_seen)";

    private static final String CREATE_HISTORY = "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
            + "uuid CHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL, "
            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, name_lower))";
    private static final String HISTORY_INDEX = "CREATE INDEX allium_player_name_history_by_uuid ON " + HISTORY_TABLE + " (uuid, last_seen)";
    private static final String BACKFILL_HISTORY_SQLITE = "INSERT OR IGNORE INTO " + HISTORY_TABLE
            + " (uuid, name, name_lower, first_seen, last_seen) "
            + "SELECT uuid, name, name_lower, last_seen, last_seen FROM " + TABLE;
    private static final String BACKFILL_HISTORY_MYSQL = "INSERT IGNORE INTO " + HISTORY_TABLE
            + " (uuid, name, name_lower, first_seen, last_seen) "
            + "SELECT uuid, name, name_lower, last_seen, last_seen FROM " + TABLE;

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
        List<String> v2Sqlite = List.of(CREATE_HISTORY, HISTORY_INDEX, BACKFILL_HISTORY_SQLITE);
        List<String> v2Mysql = List.of(CREATE_HISTORY, HISTORY_INDEX, BACKFILL_HISTORY_MYSQL);
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(1, "player name index", v1, v1),
                Migration.of(2, "player name history", v2Sqlite, v2Mysql)
        ));
    }

    public void record(UUID uuid, String name, long seen) throws SQLException {
        new NameCache.Entry(uuid, name, seen); // Validate before persisting an entry used in completion.
        String lower = name.toLowerCase(Locale.ROOT);
        String insert = "INSERT INTO " + TABLE + " (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?)";
        String upsert = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE name = IF(VALUES(last_seen) >= last_seen, VALUES(name), name), "
                  + "name_lower = IF(VALUES(last_seen) >= last_seen, VALUES(name_lower), name_lower), "
                  + "last_seen = GREATEST(last_seen, VALUES(last_seen))"
                : " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                  + "last_seen = excluded.last_seen WHERE excluded.last_seen >= " + TABLE + ".last_seen";

        String histInsert = "INSERT INTO " + HISTORY_TABLE
                + " (uuid, name, name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)";
        String histUpsert = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE name = IF(VALUES(last_seen) >= last_seen, VALUES(name), name), "
                  + "first_seen = LEAST(first_seen, VALUES(first_seen)), "
                  + "last_seen = GREATEST(last_seen, VALUES(last_seen))"
                : " ON CONFLICT(uuid, name_lower) DO UPDATE SET "
                  + "name = CASE WHEN excluded.last_seen >= " + HISTORY_TABLE + ".last_seen THEN excluded.name ELSE " + HISTORY_TABLE + ".name END, "
                  + "first_seen = MIN(" + HISTORY_TABLE + ".first_seen, excluded.first_seen), "
                  + "last_seen = MAX(" + HISTORY_TABLE + ".last_seen, excluded.last_seen)";

        try (Connection c = connections.connection()) {
            try (PreparedStatement s = c.prepareStatement(insert + upsert)) {
                s.setString(1, uuid.toString());
                s.setString(2, name);
                s.setString(3, lower);
                s.setLong(4, seen);
                s.executeUpdate();
            }
            try (PreparedStatement h = c.prepareStatement(histInsert + histUpsert)) {
                h.setString(1, uuid.toString());
                h.setString(2, name);
                h.setString(3, lower);
                h.setLong(4, seen);
                h.setLong(5, seen);
                h.executeUpdate();
            }
        }
    }

    /** The most recent holder of {@code name}, case-insensitive. */
    public Optional<NameCache.Entry> byName(String name) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM " + TABLE + " WHERE name_lower = ? "
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
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM " + TABLE + " WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new NameCache.Entry(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    /** Recorded names for {@code uuid}, ordered most-recently-seen first. */
    public List<NameHistoryEntry> nameHistory(UUID uuid) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT name, first_seen, last_seen FROM " + HISTORY_TABLE
                     + " WHERE uuid = ? ORDER BY last_seen DESC, name ASC")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                List<NameHistoryEntry> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new NameHistoryEntry(r.getString(1), r.getLong(2), r.getLong(3)));
                }
                return List.copyOf(out);
            }
        }
    }

    /** The {@code limit} most recently seen players, oldest first (to fill a cache in order). */
    public List<NameCache.Entry> recent(int limit) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT uuid, name, last_seen FROM " + TABLE + " ORDER BY last_seen DESC LIMIT ?")) {
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
