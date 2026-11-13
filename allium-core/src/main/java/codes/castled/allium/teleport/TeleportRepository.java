package codes.castled.allium.teleport;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Blocking JDBC storage for teleport locations and cooldowns; callers run it off the server thread.
 * Server-wide spawn uses owner {@code new UUID(0, 0)}, slot {@code spawn}; player return locations
 * use slot {@code back}. Slot {@code death} is reserved for an optional death-location adapter.
 */
public final class TeleportRepository {

    public static final String COMPONENT = "teleports";
    public static final String LOCATIONS_TABLE = "allium_teleport_locations";
    public static final String COOLDOWNS_TABLE = "allium_teleport_cooldowns";

    private static final Pattern COMMAND = Pattern.compile("[a-z]+");
    private static final String CREATE_LOCATIONS = "CREATE TABLE IF NOT EXISTS " + LOCATIONS_TABLE + " ("
            + "owner CHAR(36) NOT NULL, slot VARCHAR(64) NOT NULL, "
            + "world_id CHAR(36) NOT NULL, world_name VARCHAR(255) NOT NULL, "
            + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
            + "yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, PRIMARY KEY (owner, slot))";
    private static final String CREATE_COOLDOWNS = "CREATE TABLE IF NOT EXISTS " + COOLDOWNS_TABLE + " ("
            + "owner CHAR(36) NOT NULL, command VARCHAR(64) NOT NULL, until_at BIGINT NOT NULL, "
            + "PRIMARY KEY (owner, command))";
    // Both lookups are covered by the owner-leading primary keys; no redundant secondary indexes.
    public static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "teleport locations and cooldowns",
            List.of(CREATE_LOCATIONS, CREATE_COOLDOWNS),
            List.of(CREATE_LOCATIONS + " ENGINE=InnoDB", CREATE_COOLDOWNS + " ENGINE=InnoDB")));

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public TeleportRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, MIGRATIONS);
    }

    public Optional<SavedLocation> load(UUID owner, String slot) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        validateSlot(slot);
        try (Connection connection = connections.connection();
             PreparedStatement select = connection.prepareStatement("SELECT world_id, world_name, x, y, z, yaw, pitch FROM "
                     + LOCATIONS_TABLE + " WHERE owner = ? AND slot = ?")) {
            select.setString(1, owner.toString());
            select.setString(2, slot);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new SavedLocation(UUID.fromString(row.getString(1)), row.getString(2),
                        row.getDouble(3), row.getDouble(4), row.getDouble(5), row.getFloat(6), row.getFloat(7)))
                        : Optional.empty();
            }
        }
    }

    public void save(UUID owner, String slot, SavedLocation location) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        validateSlot(slot);
        Objects.requireNonNull(location, "location");
        try (Connection connection = connections.connection()) {
            save(connection, owner, slot, location);
        }
    }

    public Map<String, Long> loadCooldowns(UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        Map<String, Long> cooldowns = new LinkedHashMap<>();
        try (Connection connection = connections.connection();
             PreparedStatement select = connection.prepareStatement("SELECT command, until_at FROM "
                     + COOLDOWNS_TABLE + " WHERE owner = ? ORDER BY command")) {
            select.setString(1, owner.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    cooldowns.put(rows.getString(1), rows.getLong(2));
                }
            }
        }
        return Map.copyOf(cooldowns);
    }

    /** A zero deadline is stored as zero, so a disabled cooldown overwrites any previous deadline. */
    public void saveCooldown(UUID owner, String command, long until) throws SQLException {
        validateCooldown(owner, command, until);
        try (Connection connection = connections.connection()) {
            saveCooldown(connection, owner, command, until);
        }
    }

    /**
     * Saves the origin as {@code back} and this command's cooldown in one transaction.
     * Call only after an actual successful teleport; failed/cancelled teleports must not call this.
     */
    public void saveSuccess(UUID owner, SavedLocation origin, String command, long until) throws SQLException {
        validateCooldown(owner, command, until);
        saveHistory(owner, origin, Map.of(command, until));
    }

    /** Also retains earlier command deadlines when a newer death location supersedes dirty history. */
    public void saveHistory(UUID owner, SavedLocation origin, Map<String, Long> cooldowns) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(origin, "origin");
        Map<String,Long> snapshot = Map.copyOf(cooldowns);
        snapshot.forEach((command, until) -> validateCooldown(owner, command, until));
        try (Connection connection = connections.connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            boolean transactionFinished = false;
            Throwable pendingFailure = null;
            try {
                save(connection, owner, "back", origin);
                for (var cooldown : snapshot.entrySet()) {
                    saveCooldown(connection, owner, cooldown.getKey(), cooldown.getValue());
                }
                connection.commit();
                transactionFinished = true;
            } catch (SQLException | RuntimeException | Error failure) {
                pendingFailure = failure;
                try {
                    connection.rollback();
                    transactionFinished = true;
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                // Enabling auto-commit after a failed rollback could commit a partial write.
                if (transactionFinished) {
                    try {
                        connection.setAutoCommit(autoCommit);
                    } catch (SQLException restoreFailure) {
                        if (pendingFailure == null) throw restoreFailure;
                        pendingFailure.addSuppressed(restoreFailure);
                    }
                }
            }
        }
    }

    private void save(Connection connection, UUID owner, String slot, SavedLocation location) throws SQLException {
        String update = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE world_id = VALUES(world_id), world_name = VALUES(world_name), "
                  + "x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch)"
                : " ON CONFLICT(owner, slot) DO UPDATE SET world_id = excluded.world_id, world_name = excluded.world_name, "
                  + "x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch";
        try (PreparedStatement write = connection.prepareStatement("INSERT INTO " + LOCATIONS_TABLE
                + " (owner, slot, world_id, world_name, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" + update)) {
            write.setString(1, owner.toString());
            write.setString(2, slot);
            write.setString(3, location.worldId().toString());
            write.setString(4, location.worldName());
            write.setDouble(5, location.x());
            write.setDouble(6, location.y());
            write.setDouble(7, location.z());
            write.setFloat(8, location.yaw());
            write.setFloat(9, location.pitch());
            write.executeUpdate();
        }
    }

    private void saveCooldown(Connection connection, UUID owner, String command, long until) throws SQLException {
        String update = dialect == SqlDialect.MYSQL
                ? " ON DUPLICATE KEY UPDATE until_at = VALUES(until_at)"
                : " ON CONFLICT(owner, command) DO UPDATE SET until_at = excluded.until_at";
        try (PreparedStatement write = connection.prepareStatement("INSERT INTO " + COOLDOWNS_TABLE
                + " (owner, command, until_at) VALUES (?, ?, ?)" + update)) {
            write.setString(1, owner.toString());
            write.setString(2, command);
            write.setLong(3, until);
            write.executeUpdate();
        }
    }

    private static void validateSlot(String slot) {
        Objects.requireNonNull(slot, "slot");
        if (slot.isBlank() || slot.length() > 64) {
            throw new IllegalArgumentException("slot must contain 1 to 64 characters");
        }
    }

    private static void validateCooldown(UUID owner, String command, long until) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(command, "command");
        if (!COMMAND.matcher(command).matches()) {
            throw new IllegalArgumentException("command must match [a-z]+");
        }
        if (until < 0) {
            throw new IllegalArgumentException("cooldown deadline must be nonnegative");
        }
    }
}
