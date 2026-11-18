package codes.castled.allium.alts;

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
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * IP history and alt exemptions, on SQLite or MySQL (legacy Allium's {@code player_ip_history}
 * and alt exemptions). Blocking: callers use a bounded pool, never the server thread.
 *
 * <p>An account is an alt on an IP when another account was first seen on that IP before it. Two
 * accounts are linked when they share any IP in their history, so a shared IP keeps showing on
 * both after either one moves.
 */
public final class AltRepository {

    public static final String COMPONENT = "alts";

    public record Exemption(UUID uuid, String actor, String reason, long createdAt) {
    }

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public AltRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    static List<Migration> migrations() {
        List<String> v1 = List.of(
                "CREATE TABLE IF NOT EXISTS allium_ip_history (uuid CHAR(36) NOT NULL, ip VARCHAR(64) NOT NULL, "
                        + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip))",
                "CREATE INDEX allium_ip_history_by_ip ON allium_ip_history (ip, first_seen)",
                "CREATE TABLE IF NOT EXISTS allium_alt_exemptions (uuid CHAR(36) NOT NULL PRIMARY KEY, actor VARCHAR(64) NOT NULL, "
                        + "reason VARCHAR(255) NOT NULL, created_at BIGINT NOT NULL)");
        return List.of(Migration.of(1, "IP history and alt exemptions", v1, v1));
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, migrations());
    }

    /** Records that {@code uuid} joined from {@code ip} at {@code now}. */
    public void record(UUID uuid, String ip, long now) throws SQLException {
        try (Connection c = connections.connection()) {
            String insert = dialect == SqlDialect.MYSQL
                    ? "INSERT IGNORE INTO allium_ip_history (uuid, ip, first_seen, last_seen) VALUES (?, ?, ?, ?)"
                    : "INSERT OR IGNORE INTO allium_ip_history (uuid, ip, first_seen, last_seen) VALUES (?, ?, ?, ?)";
            try (PreparedStatement s = c.prepareStatement(insert)) {
                s.setString(1, uuid.toString());
                s.setString(2, ip);
                s.setLong(3, now);
                s.setLong(4, now);
                if (s.executeUpdate() == 1) {
                    return;
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE allium_ip_history SET last_seen = ? WHERE uuid = ? AND ip = ? AND last_seen < ?")) {
                s.setLong(1, now);
                s.setString(2, uuid.toString());
                s.setString(3, ip);
                s.setLong(4, now);
                s.executeUpdate();
            }
        }
    }

    /** True when another account was first seen on {@code ip} before {@code uuid} was. */
    public boolean isAlt(UUID uuid, String ip) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM allium_ip_history mine JOIN allium_ip_history other "
                     + "ON other.ip = mine.ip AND other.uuid <> mine.uuid AND other.first_seen < mine.first_seen "
                     + "WHERE mine.uuid = ? AND mine.ip = ? LIMIT 1")) {
            s.setString(1, uuid.toString());
            s.setString(2, ip);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    /** Accounts that share any IP with {@code uuid}, most recently seen first. */
    public List<UUID> linkedAccounts(UUID uuid, int limit) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT other.uuid, MAX(other.last_seen) AS seen FROM allium_ip_history mine "
                     + "JOIN allium_ip_history other ON other.ip = mine.ip AND other.uuid <> mine.uuid WHERE mine.uuid = ? "
                     + "GROUP BY other.uuid ORDER BY seen DESC LIMIT ?")) {
            s.setString(1, uuid.toString());
            s.setInt(2, Math.max(1, limit));
            List<UUID> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(UUID.fromString(r.getString(1)));
                }
            }
            return out;
        }
    }

    /** The IP {@code uuid} last joined from. */
    public Optional<String> lastIp(UUID uuid) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT ip FROM allium_ip_history WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    public Optional<Exemption> exemption(UUID uuid) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT actor, reason, created_at FROM allium_alt_exemptions WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new Exemption(uuid, r.getString(1), r.getString(2), r.getLong(3))) : Optional.empty();
            }
        }
    }

    /** Adds or replaces {@code uuid}'s exemption. */
    public void exempt(UUID uuid, String actor, String reason, long now) throws SQLException {
        try (Connection c = connections.connection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM allium_alt_exemptions WHERE uuid = ?")) {
                s.setString(1, uuid.toString());
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO allium_alt_exemptions (uuid, actor, reason, created_at) VALUES (?, ?, ?, ?)")) {
                s.setString(1, uuid.toString());
                s.setString(2, actor.length() > 64 ? actor.substring(0, 64) : actor);
                s.setString(3, reason.length() > 255 ? reason.substring(0, 255) : reason);
                s.setLong(4, now);
                s.executeUpdate();
            }
        }
    }

    /** False when {@code uuid} was not exempt. */
    public boolean unexempt(UUID uuid) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM allium_alt_exemptions WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            return s.executeUpdate() == 1;
        }
    }
}
