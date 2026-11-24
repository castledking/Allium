package codes.castled.allium.spawn;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Durable per-player ledger for coordinated first-join onboarding (announcement, initial placement,
 * and starter kit delivery) so each step runs at most once across reconnects and partial outages.
 */
public final class OnboardingRepository {

    public static final String COMPONENT = "onboarding";
    public static final String TABLE = "allium_onboarding";

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "announced_at BIGINT NOT NULL DEFAULT 0, "
            + "placed_at BIGINT NOT NULL DEFAULT 0, "
            + "kit_granted_at BIGINT NOT NULL DEFAULT 0, "
            + "kit_name VARCHAR(64) NOT NULL DEFAULT '', "
            + "completed_at BIGINT NOT NULL DEFAULT 0)";

    public static final List<Migration> MIGRATIONS = List.of(Migration.of(
            1,
            "first-join onboarding ledger",
            List.of(CREATE_TABLE),
            List.of(CREATE_TABLE + " ENGINE=InnoDB")
    ));

    public record State(
            UUID playerId,
            long announcedAt,
            long placedAt,
            long kitGrantedAt,
            String kitName,
            long completedAt
    ) {
        public boolean announced() { return announcedAt > 0L; }
        public boolean placed() { return placedAt > 0L; }
        public boolean kitGranted() { return kitGrantedAt > 0L; }
        public boolean completed() { return completedAt > 0L; }
    }

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public OnboardingRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, MIGRATIONS);
    }

    public Optional<State> load(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT announced_at, placed_at, kit_granted_at, kit_name, completed_at FROM " + TABLE
                             + " WHERE player_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new State(
                        playerId,
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getLong(5)
                ));
            }
        }
    }

    /** Ensures an onboarding row exists for {@code playerId} and returns its current state. */
    public State begin(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        String insert = dialect == SqlDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + TABLE + " (player_uuid, announced_at, placed_at, kit_granted_at, kit_name, completed_at) VALUES (?, 0, 0, 0, '', 0)"
                : "INSERT IGNORE INTO " + TABLE + " (player_uuid, announced_at, placed_at, kit_granted_at, kit_name, completed_at) VALUES (?, 0, 0, 0, '', 0)";
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        }
        return load(playerId).orElseThrow(() -> new SQLException("onboarding row missing after insert for " + playerId));
    }

    /** Atomically claims the once-only first-join announcement slot. */
    public boolean claimAnnounce(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        long now = Math.max(1L, clock.getAsLong());
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + TABLE + " SET announced_at = ? WHERE player_uuid = ? AND announced_at = 0")) {
            ps.setLong(1, now);
            ps.setString(2, playerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Marks initial placement as complete (idempotent). */
    public boolean markPlaced(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        long now = Math.max(1L, clock.getAsLong());
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + TABLE + " SET placed_at = ? WHERE player_uuid = ? AND placed_at = 0")) {
            ps.setLong(1, now);
            ps.setString(2, playerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Atomically claims the once-only starter-kit grant slot. */
    public boolean claimKit(UUID playerId, String kitName) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        String clean = kitName == null ? "" : kitName.trim();
        if (clean.length() > 64) clean = clean.substring(0, 64);
        long now = Math.max(1L, clock.getAsLong());
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + TABLE + " SET kit_granted_at = ?, kit_name = ? WHERE player_uuid = ? AND kit_granted_at = 0")) {
            ps.setLong(1, now);
            ps.setString(2, clean);
            ps.setString(3, playerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Releases a previously claimed starter-kit slot when delivery was refused or failed before applying items. */
    public boolean releaseKit(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + TABLE + " SET kit_granted_at = 0, kit_name = '' WHERE player_uuid = ? AND completed_at = 0")) {
            ps.setString(1, playerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Marks onboarding fully completed (idempotent). */
    public boolean markCompleted(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        long now = Math.max(1L, clock.getAsLong());
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + TABLE + " SET completed_at = ? WHERE player_uuid = ? AND completed_at = 0")) {
            ps.setLong(1, now);
            ps.setString(2, playerId.toString());
            return ps.executeUpdate() == 1;
        }
    }
}
