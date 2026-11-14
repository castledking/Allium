package codes.castled.allium.info;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * {@code allium_player_info}: persists {@code first_join}, {@code last_join}, and {@code last_seen}
 * per UUID across restarts for {@code /seen} and {@code /whois}. Blocking JDBC: callers run via a
 * bounded storage executor.
 */
public final class PlayerInfoRepository {

    public static final String COMPONENT = "player-info";
    public static final String TABLE = "allium_player_info";

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "last_name VARCHAR(16) NOT NULL, "
            + "name_lower VARCHAR(16) NOT NULL, "
            + "first_join BIGINT NOT NULL, "
            + "last_join BIGINT NOT NULL, "
            + "last_seen BIGINT NOT NULL)";
    private static final String CREATE_INDEX = "CREATE INDEX allium_player_info_by_name ON " + TABLE + " (name_lower, last_seen)";

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public PlayerInfoRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        List<String> statements = List.of(CREATE_TABLE, CREATE_INDEX);
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(1, "player first-join and last-seen info", statements, statements)
        ));
    }

    /**
     * Records a player join. When {@code vanished} is {@code true}, existing {@code last_seen} is
     * intentionally preserved so vanished players continue to look offline to unprivileged viewers;
     * on first-ever insert, {@code last_seen} initializes to {@code joinTime}.
     */
    public void recordJoin(UUID uuid, String name, long joinTime, boolean vanished) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        String trimmed = validateName(name);
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String insert = "INSERT INTO " + TABLE + " (uuid, last_name, name_lower, first_join, last_join, last_seen) VALUES (?, ?, ?, ?, ?, ?)";
        String upsert;
        if (dialect == SqlDialect.MYSQL) {
            upsert = " ON DUPLICATE KEY UPDATE "
                    + "last_name = VALUES(last_name), "
                    + "name_lower = VALUES(name_lower), "
                    + "first_join = LEAST(first_join, VALUES(first_join)), "
                    + "last_join = GREATEST(last_join, VALUES(last_join))"
                    + (vanished ? "" : ", last_seen = GREATEST(last_seen, VALUES(last_seen))");
        } else {
            upsert = " ON CONFLICT(uuid) DO UPDATE SET "
                    + "last_name = excluded.last_name, "
                    + "name_lower = excluded.name_lower, "
                    + "first_join = MIN(" + TABLE + ".first_join, excluded.first_join), "
                    + "last_join = MAX(" + TABLE + ".last_join, excluded.last_join)"
                    + (vanished ? "" : ", last_seen = MAX(" + TABLE + ".last_seen, excluded.last_seen)");
        }

        try (Connection c = connections.connection(); PreparedStatement s = c.prepareStatement(insert + upsert)) {
            s.setString(1, uuid.toString());
            s.setString(2, trimmed);
            s.setString(3, lower);
            s.setLong(4, joinTime);
            s.setLong(5, joinTime);
            s.setLong(6, joinTime);
            s.executeUpdate();
        }
    }

    /**
     * Records an explicit {@code last_seen} point (non-vanished quit/shutdown, or entering/leaving
     * vanish so vanished players "logged off" at that instant).
     */
    public void recordLastSeen(UUID uuid, String name, long seenTime) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        String trimmed = validateName(name);
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String insert = "INSERT INTO " + TABLE + " (uuid, last_name, name_lower, first_join, last_join, last_seen) VALUES (?, ?, ?, ?, ?, ?)";
        String upsert = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE "
                  + "last_name = VALUES(last_name), "
                  + "name_lower = VALUES(name_lower), "
                  + "last_seen = GREATEST(last_seen, VALUES(last_seen))"
                : " ON CONFLICT(uuid) DO UPDATE SET "
                  + "last_name = excluded.last_name, "
                  + "name_lower = excluded.name_lower, "
                  + "last_seen = MAX(" + TABLE + ".last_seen, excluded.last_seen)";

        try (Connection c = connections.connection(); PreparedStatement s = c.prepareStatement(insert + upsert)) {
            s.setString(1, uuid.toString());
            s.setString(2, trimmed);
            s.setString(3, lower);
            s.setLong(4, seenTime);
            s.setLong(5, seenTime);
            s.setLong(6, seenTime);
            s.executeUpdate();
        }
    }

    public Optional<PlayerInfoRecord> findByUuid(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT uuid, last_name, first_join, last_join, last_seen FROM " + TABLE + " WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(mapRow(r)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerInfoRecord> findByName(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT uuid, last_name, first_join, last_join, last_seen FROM " + TABLE
                             + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            s.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(mapRow(r)) : Optional.empty();
            }
        }
    }

    private static PlayerInfoRecord mapRow(ResultSet r) throws SQLException {
        return new PlayerInfoRecord(
                UUID.fromString(r.getString(1)),
                r.getString(2),
                r.getLong(3),
                r.getLong(4),
                r.getLong(5)
        );
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }
}
