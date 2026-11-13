package codes.castled.allium.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Applies versioned migrations per component and records them in {@code allium_schema}.
 *
 * <p>Statements must be idempotent ({@code CREATE TABLE IF NOT EXISTS}, ...) because two servers
 * sharing a MySQL database may start together; the version row is advanced with a conditional
 * update, so concurrent starts converge. A database that is already at a newer version than this
 * build knows is refused rather than written to.
 */
public final class SchemaMigrator {

    public static final String TABLE = "allium_schema";

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public SchemaMigrator(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    /** @return the component's schema version after migrating */
    public int migrate(String component, List<Migration> migrations) throws SQLException {
        int latest = migrations.stream().mapToInt(Migration::version).max().orElse(0);
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i).version() != i + 1) {
                throw new IllegalArgumentException(component + " migrations must be numbered 1.." + migrations.size());
            }
        }
        try (Connection connection = connections.connection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (component VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "version INT NOT NULL, applied_at BIGINT NOT NULL)");
            }
            int current = version(connection, component);
            if (current > latest) {
                throw new SQLException("the " + component + " schema is at version " + current + ", newer than this Allium build ("
                        + latest + "); refusing to use it. Update Allium or restore a matching backup.");
            }
            for (Migration migration : migrations) {
                if (migration.version() <= current) {
                    continue;
                }
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.statements().get(dialect)) {
                        try {
                            statement.execute(sql);
                        } catch (SQLException error) {
                            // MySQL DDL commits before the version record, and SQLite can also
                            // restart between DDL and record(). Concurrent starters may reach the
                            // same ADD COLUMN. Only the dialect's duplicate-column error is safe
                            // to accept; all other migration failures still abort startup.
                            if (!duplicateAddedColumn(sql, error)) {
                                throw error;
                            }
                        }
                    }
                }
                record(connection, component, current, migration.version());
                current = migration.version();
            }
            return current;
        }
    }

    private boolean duplicateAddedColumn(String sql, SQLException error) {
        if (!sql.matches("(?is)^\\s*ALTER\\s+TABLE\\s+[A-Za-z0-9_]+\\s+ADD\\s+COLUMN\\s+.*")) {
            return false;
        }
        return switch (dialect) {
            case MYSQL -> error.getErrorCode() == 1060 && "42S21".equals(error.getSQLState());
            case SQLITE -> error.getErrorCode() == 1 && error.getMessage() != null
                    && error.getMessage().contains("duplicate column name:");
        };
    }

    public int version(String component) throws SQLException {
        try (Connection connection = connections.connection()) {
            return version(connection, component);
        }
    }

    private static int version(Connection connection, String component) throws SQLException {
        try (Statement check = connection.createStatement()) {
            check.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (component VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "version INT NOT NULL, applied_at BIGINT NOT NULL)");
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT version FROM " + TABLE + " WHERE component = ?")) {
            select.setString(1, component);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private void record(Connection connection, String component, int from, int to) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE + " SET version = ?, applied_at = ? WHERE component = ? AND version = ?")) {
            update.setInt(1, to);
            update.setLong(2, clock.getAsLong());
            update.setString(3, component);
            update.setInt(4, from);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (component, version, applied_at) VALUES (?, ?, ?)")) {
            insert.setString(1, component);
            insert.setInt(2, to);
            insert.setLong(3, clock.getAsLong());
            insert.executeUpdate();
        } catch (SQLException e) {
            // Another server recorded it first; it must now be at least this version.
            if (version(connection, component) < to) {
                throw e;
            }
        }
    }
}
