package codes.castled.allium.preference;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * JDBC storage for {@link StoredPreferences}, one row per UUID, on SQLite or MySQL.
 *
 * <p>A write is a field-level patch: it updates only the columns in the {@link PreferenceChange}
 * (inserting the row first if needed) and increments {@code version}, in one transaction. Two
 * sessions changing different fields of one player therefore never overwrite each other, and
 * nothing here ever writes a default: unset columns stay {@code NULL}.
 */
public final class PreferenceRepository {

    public static final String COMPONENT = "preferences";
    public static final String TABLE = "allium_preferences";

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "accept_messages BOOLEAN NULL, "
            + "accept_payments BOOLEAN NULL, "
            + "accept_teleport_requests BOOLEAN NULL, "
            + "message_sound_volume DOUBLE NULL, "
            + "message_sound_restore DOUBLE NULL, "
            + "version BIGINT NOT NULL, "
            + "updated_at BIGINT NOT NULL)";

    /** Schema history; append only. */
    public static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "create " + TABLE, List.of(CREATE), List.of(CREATE)));

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public PreferenceRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** @return the schema version after migrating */
    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, MIGRATIONS);
    }

    public Optional<StoredPreferences> load(UUID uuid) throws SQLException {
        try (Connection connection = connections.connection()) {
            return load(connection, uuid);
        }
    }

    private static Optional<StoredPreferences> load(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT accept_messages, accept_payments, "
                + "accept_teleport_requests, message_sound_volume, message_sound_restore, version FROM " + TABLE + " WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredPreferences(uuid, bool(row, 1), bool(row, 2), bool(row, 3),
                        dbl(row, 4), dbl(row, 5), row.getLong(6)));
            }
        }
    }

    /** Applies a patch and returns the row as stored afterwards. */
    public StoredPreferences apply(UUID uuid, PreferenceChange change) throws SQLException {
        if (change.isEmpty()) {
            return load(uuid).orElse(StoredPreferences.empty(uuid));
        }
        List<Map.Entry<PreferenceKey, Optional<Object>>> entries = new ArrayList<>(change.entries().entrySet());
        try (Connection connection = connections.connection()) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (update(connection, uuid, entries) == 0) {
                    try {
                        insert(connection, uuid, entries);
                    } catch (SQLException duplicate) {
                        // Another session inserted the row first: update it instead.
                        connection.rollback();
                        if (update(connection, uuid, entries) == 0) {
                            throw duplicate;
                        }
                    }
                }
                StoredPreferences stored = load(connection, uuid).orElseThrow(() -> new SQLException("row vanished after write"));
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
        }
    }

    private int update(Connection connection, UUID uuid, List<Map.Entry<PreferenceKey, Optional<Object>>> entries) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE " + TABLE + " SET ");
        for (Map.Entry<PreferenceKey, Optional<Object>> e : entries) {
            sql.append(e.getKey().column()).append(" = ?, ");
        }
        sql.append("version = version + 1, updated_at = ? WHERE uuid = ?");
        try (PreparedStatement update = connection.prepareStatement(sql.toString())) {
            int i = 1;
            for (Map.Entry<PreferenceKey, Optional<Object>> e : entries) {
                bind(update, i++, e.getKey(), e.getValue().orElse(null));
            }
            update.setLong(i++, clock.getAsLong());
            update.setString(i, uuid.toString());
            return update.executeUpdate();
        }
    }

    private void insert(Connection connection, UUID uuid, List<Map.Entry<PreferenceKey, Optional<Object>>> entries) throws SQLException {
        StringBuilder columns = new StringBuilder("uuid");
        StringBuilder values = new StringBuilder("?");
        for (Map.Entry<PreferenceKey, Optional<Object>> e : entries) {
            columns.append(", ").append(e.getKey().column());
            values.append(", ?");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE + " (" + columns
                + ", version, updated_at) VALUES (" + values + ", 1, ?)")) {
            int i = 1;
            insert.setString(i++, uuid.toString());
            for (Map.Entry<PreferenceKey, Optional<Object>> e : entries) {
                bind(insert, i++, e.getKey(), e.getValue().orElse(null));
            }
            insert.setLong(i, clock.getAsLong());
            insert.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, int index, PreferenceKey key, Object value) throws SQLException {
        if (key.kind() == PreferenceKey.Kind.BOOLEAN) {
            if (value == null) statement.setNull(index, Types.BOOLEAN);
            else statement.setBoolean(index, (Boolean) value);
        } else {
            if (value == null) statement.setNull(index, Types.DOUBLE);
            else statement.setDouble(index, (Double) value);
        }
    }

    private static Boolean bool(ResultSet row, int column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }

    private static Double dbl(ResultSet row, int column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }
}
