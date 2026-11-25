package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Persistent SQLite/MySQL cooldown storage for outcomes-aware convenience commands.
 * Blocking JDBC; call exclusively from a bounded storage worker.
 */
public final class CommandCooldownRepository {

    public static final String COMPONENT = "command_cooldowns";
    public static final String TABLE = "allium_command_cooldowns";

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL, "
            + "action VARCHAR(32) NOT NULL, "
            + "expires_at BIGINT NOT NULL, "
            + "PRIMARY KEY (player_uuid, action)"
            + ")";

    private final ConnectionSource source;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public CommandCooldownRepository(ConnectionSource source, SqlDialect dialect, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(source, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(
                        1,
                        "persistent convenience command cooldowns",
                        List.of(CREATE_TABLE),
                        List.of(CREATE_TABLE)
                )
        ));
    }

    /** Loads all unexpired cooldown deadlines for {@code playerUuid}. */
    public Map<String, Long> loadActive(UUID playerUuid, long nowMillis) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Map<String, Long> out = new HashMap<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT action, expires_at FROM " + TABLE + " WHERE player_uuid = ? AND expires_at > ?")) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, nowMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return Map.copyOf(out);
    }

    /** Upserts a player's cooldown expiry for {@code action}. */
    public void save(UUID playerUuid, String action, long expiresAt) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String key = CommandActionPolicy.normalizeKey(action);
        if (key.isEmpty() || key.length() > 32) {
            throw new IllegalArgumentException("invalid cooldown action: " + action);
        }
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + TABLE + " (player_uuid, action, expires_at) VALUES (?, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)"
                : "INSERT INTO " + TABLE + " (player_uuid, action, expires_at) VALUES (?, ?, ?) "
                  + "ON CONFLICT(player_uuid, action) DO UPDATE SET expires_at = excluded.expires_at";
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, key);
            ps.setLong(3, expiresAt);
            ps.executeUpdate();
        }
    }

    /** Removes expired cooldown rows older than or equal to {@code nowMillis}. */
    public int purgeExpired(long nowMillis) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE expires_at <= ?")) {
            ps.setLong(1, nowMillis);
            return ps.executeUpdate();
        }
    }
}
