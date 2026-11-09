package codes.castled.allium.messaging;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Per-UUID ignore lists in their own table, {@code allium_ignores (owner, ignored)}. Blocking JDBC; callers keep it off the server thread. */
public final class IgnoreRepository {

    public static final String COMPONENT = "ignores";
    public static final String TABLE = "allium_ignores";

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " (owner CHAR(36) NOT NULL, ignored CHAR(36) NOT NULL, "
            + "created_at BIGINT NOT NULL, PRIMARY KEY (owner, ignored))";

    private final ConnectionSource source;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public IgnoreRepository(ConnectionSource source, SqlDialect dialect, LongSupplier clock) {
        this.source = source;
        this.dialect = dialect;
        this.clock = clock;
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(source, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(1, "ignore lists", List.of(CREATE), List.of(CREATE))));
    }

    public Set<UUID> load(UUID owner) throws SQLException {
        Set<UUID> out = new LinkedHashSet<>();
        try (Connection c = source.connection();
             PreparedStatement s = c.prepareStatement("SELECT ignored FROM " + TABLE + " WHERE owner = ? ORDER BY created_at, ignored")) {
            s.setString(1, owner.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(UUID.fromString(r.getString(1)));
                }
            }
        }
        return out;
    }

    /** @return whether a new entry was added */
    public boolean add(UUID owner, UUID ignored) throws SQLException {
        String sql = (dialect == SqlDialect.MYSQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ") + TABLE
                + " (owner, ignored, created_at) VALUES (?, ?, ?)";
        try (Connection c = source.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, owner.toString());
            s.setString(2, ignored.toString());
            s.setLong(3, clock.getAsLong());
            return s.executeUpdate() > 0;
        }
    }

    /** @return whether an entry was removed */
    public boolean remove(UUID owner, UUID ignored) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM " + TABLE + " WHERE owner = ? AND ignored = ?")) {
            s.setString(1, owner.toString());
            s.setString(2, ignored.toString());
            return s.executeUpdate() > 0;
        }
    }
}
