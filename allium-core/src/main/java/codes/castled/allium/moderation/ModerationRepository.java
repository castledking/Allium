package codes.castled.allium.moderation;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * SQL persistence for per-UUID vanish state, active mutes, and moderation audit entries.
 * Blocking JDBC; runtime callers invoke this exclusively from the bounded moderation storage pool.
 */
public final class ModerationRepository {

    public static final String COMPONENT = "moderation";
    public static final String VANISH_TABLE = "allium_vanish";
    public static final String MUTES_TABLE = "allium_mutes";
    public static final String AUDIT_TABLE = "allium_moderation_audit";

    private static final String CREATE_VANISH = "CREATE TABLE IF NOT EXISTS " + VANISH_TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "vanished INT NOT NULL, "
            + "updated_at BIGINT NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL"
            + ")";

    private static final String CREATE_MUTES = "CREATE TABLE IF NOT EXISTS " + MUTES_TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "player_name VARCHAR(36) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "reason VARCHAR(256) NOT NULL, "
            + "created_at BIGINT NOT NULL, "
            + "expires_at BIGINT NULL"
            + ")";

    private static final String CREATE_AUDIT_SQLITE = "CREATE TABLE IF NOT EXISTS " + AUDIT_TABLE + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "created_at BIGINT NOT NULL, "
            + "action VARCHAR(32) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "target_uuid CHAR(36) NULL, "
            + "target_name VARCHAR(36) NULL, "
            + "detail VARCHAR(512) NOT NULL"
            + ")";

    private static final String CREATE_AUDIT_MYSQL = "CREATE TABLE IF NOT EXISTS " + AUDIT_TABLE + " ("
            + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "created_at BIGINT NOT NULL, "
            + "action VARCHAR(32) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "target_uuid CHAR(36) NULL, "
            + "target_name VARCHAR(36) NULL, "
            + "detail VARCHAR(512) NOT NULL"
            + ")";

    private final ConnectionSource source;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public ModerationRepository(ConnectionSource source, SqlDialect dialect, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(source, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(
                        1,
                        "vanish state, mutes, and moderation audit log",
                        List.of(CREATE_VANISH, CREATE_MUTES, CREATE_AUDIT_SQLITE),
                        List.of(CREATE_VANISH, CREATE_MUTES, CREATE_AUDIT_MYSQL)
                )
        ));
    }

    public Set<UUID> loadVanishedPlayers() throws SQLException {
        Set<UUID> out = new LinkedHashSet<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid FROM " + VANISH_TABLE + " WHERE vanished = 1 ORDER BY player_uuid")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return out;
    }

    public boolean loadVanished(UUID playerUuid) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT vanished FROM " + VANISH_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    public void setVanished(UUID playerUuid, boolean vanished, String actorName) throws SQLException {
        long now = clock.getAsLong();
        String safeActor = (actorName == null || actorName.isBlank()) ? "Console" : actorName.trim();
        if (!vanished) {
            try (Connection c = source.connection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM " + VANISH_TABLE + " WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }
            return;
        }
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + VANISH_TABLE + " (player_uuid, vanished, updated_at, actor_name) VALUES (?, 1, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE vanished = 1, updated_at = VALUES(updated_at), actor_name = VALUES(actor_name)"
                : "INSERT INTO " + VANISH_TABLE + " (player_uuid, vanished, updated_at, actor_name) VALUES (?, 1, ?, ?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET vanished = 1, updated_at = excluded.updated_at, actor_name = excluded.actor_name";
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, now);
            ps.setString(3, safeActor);
            ps.executeUpdate();
        }
    }

    public List<MuteRecord> loadActiveMutes() throws SQLException {
        long now = clock.getAsLong();
        purgeExpiredMutes(now);
        List<MuteRecord> out = new ArrayList<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, player_name, actor_name, reason, created_at, expires_at FROM "
                             + MUTES_TABLE + " ORDER BY created_at ASC, player_uuid ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MuteRecord record = readMute(rs);
                    if (!record.isExpired(now)) {
                        out.add(record);
                    }
                }
            }
        }
        return out;
    }

    public Optional<MuteRecord> loadActiveMute(UUID playerUuid) throws SQLException {
        long now = clock.getAsLong();
        MuteRecord record;
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, player_name, actor_name, reason, created_at, expires_at FROM "
                             + MUTES_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                record = readMute(rs);
            }
        }
        if (record.isExpired(now)) {
            removeMute(playerUuid);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public void saveMute(MuteRecord record) throws SQLException {
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + MUTES_TABLE
                  + " (player_uuid, player_name, actor_name, reason, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), actor_name = VALUES(actor_name), "
                  + "reason = VALUES(reason), created_at = VALUES(created_at), expires_at = VALUES(expires_at)"
                : "INSERT INTO " + MUTES_TABLE
                  + " (player_uuid, player_name, actor_name, reason, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, actor_name = excluded.actor_name, "
                  + "reason = excluded.reason, created_at = excluded.created_at, expires_at = excluded.expires_at";
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, record.playerUuid().toString());
            ps.setString(2, record.playerName());
            ps.setString(3, record.actorName());
            ps.setString(4, record.reason());
            ps.setLong(5, record.createdAt());
            if (record.expiresAt() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, record.expiresAt());
            }
            ps.executeUpdate();
        }
    }

    public boolean removeMute(UUID playerUuid) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + MUTES_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public int purgeExpiredMutes(long nowMillis) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + MUTES_TABLE + " WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            ps.setLong(1, nowMillis);
            return ps.executeUpdate();
        }
    }

    public void recordAudit(
            String action,
            String actorName,
            UUID targetUuid,
            String targetName,
            String detail
    ) throws SQLException {
        String safeAction = Objects.requireNonNull(action, "action").trim();
        String safeActor = (actorName == null || actorName.isBlank()) ? "Console" : actorName.trim();
        String safeDetail = detail == null ? "" : (detail.length() <= 512 ? detail : detail.substring(0, 512));
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + AUDIT_TABLE
                             + " (created_at, action, actor_name, target_uuid, target_name, detail) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, clock.getAsLong());
            ps.setString(2, safeAction);
            ps.setString(3, safeActor);
            if (targetUuid == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, targetUuid.toString());
            }
            if (targetName == null || targetName.isBlank()) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, targetName.trim());
            }
            ps.setString(6, safeDetail);
            ps.executeUpdate();
        }
    }

    public List<ModerationAuditRecord> loadRecentAudit(int limit) throws SQLException {
        List<ModerationAuditRecord> out = new ArrayList<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, created_at, action, actor_name, target_uuid, target_name, detail FROM "
                             + AUDIT_TABLE + " ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawUuid = rs.getString(5);
                    out.add(new ModerationAuditRecord(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getString(3),
                            rs.getString(4),
                            rawUuid == null ? null : UUID.fromString(rawUuid),
                            rs.getString(6),
                            rs.getString(7)
                    ));
                }
            }
        }
        return out;
    }

    private static MuteRecord readMute(ResultSet rs) throws SQLException {
        UUID playerUuid = UUID.fromString(rs.getString(1));
        String playerName = rs.getString(2);
        String actorName = rs.getString(3);
        String reason = rs.getString(4);
        long createdAt = rs.getLong(5);
        long rawExpires = rs.getLong(6);
        Long expiresAt = rs.wasNull() ? null : rawExpires;
        return new MuteRecord(playerUuid, playerName, actorName, reason, createdAt, expiresAt);
    }
}
