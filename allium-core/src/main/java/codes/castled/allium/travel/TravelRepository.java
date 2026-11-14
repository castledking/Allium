package codes.castled.allium.travel;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.SavedLocation;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Blocking, platform-neutral home and warp storage. Call from a bounded worker pool.
 * Every home mutation first writes/locks its owner's durable row; the persisted count and
 * insertion therefore share one transaction on both SQLite and MySQL. Warps lock their name.
 * Lock rows also allocate revisions and are never deleted, preventing deletion/recreation from
 * making an old confirmation valid again. No permission or economy policy is applied here.
 */
public final class TravelRepository {
    public static final String COMPONENT = "travel";

    public enum MutationResult { CREATED, EXISTS, LIMIT, UPDATED, DELETED, STALE, MISSING }

    private static final String HOMES = "allium_travel_homes";
    private static final String OWNERS = "allium_travel_home_owners";
    private static final String WARPS = "allium_travel_warps";
    private static final String WARP_NAMES = "allium_travel_warp_names";
    private static final String LOCATION_COLUMNS = "world_id, world_name, x, y, z, yaw, pitch";

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public TravelRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One authoritative snapshot includes revisions, so refreshes preserve unchanged warmups. */
    public Map<String, StoredDestination> warpEntries() throws SQLException {
        return plain(c -> {
            Map<String, StoredDestination> entries = new LinkedHashMap<>();
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM " + WARPS + " ORDER BY name"); ResultSet r = s.executeQuery()) {
                while (r.next()) {var row = new StoredDestination(r.getString("name"), location(r), r.getLong("revision"));entries.put(row.name(), row);}
            }
            return Map.copyOf(entries);
        });
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT,
                List.of(Migration.of(1, "homes, warps and durable revision locks", schema(SqlDialect.SQLITE), schema(SqlDialect.MYSQL))));
    }

    private static List<String> schema(SqlDialect dialect) {
        String engine = dialect == SqlDialect.MYSQL ? " ENGINE=InnoDB" : "";
        String location = "world_id CHAR(36) NOT NULL, world_name VARCHAR(255) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                + "revision BIGINT NOT NULL";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + OWNERS + " (owner CHAR(36) NOT NULL PRIMARY KEY, revision BIGINT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS " + HOMES + " (owner CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                        + location + ", PRIMARY KEY (owner, name))" + engine,
                "CREATE TABLE IF NOT EXISTS " + WARP_NAMES + " (name VARCHAR(32) NOT NULL PRIMARY KEY, revision BIGINT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS " + WARPS + " (name VARCHAR(32) NOT NULL PRIMARY KEY, " + location + ")" + engine);
    }

    /** Immutable view. Call home() again before a mutation rather than using a cached location. */
    public Map<String, SavedLocation> homes(UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        return plain(c -> locations(c, "SELECT * FROM " + HOMES + " WHERE owner = ? ORDER BY name", owner.toString()));
    }

    public Map<String, SavedLocation> warps() throws SQLException {
        return plain(c -> locations(c, "SELECT * FROM " + WARPS + " ORDER BY name"));
    }

    public Optional<StoredDestination> home(UUID owner, String name) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        String key = TravelNames.normalize(name);
        return plain(c -> find(c, HOMES, owner, key));
    }

    public Optional<StoredDestination> warp(String name) throws SQLException {
        String key = TravelNames.normalize(name);
        return plain(c -> find(c, WARPS, null, key));
    }

    /** Creates only when absent. A pre-existing name takes precedence over the count limit. */
    public MutationResult createHome(UUID owner, String name, SavedLocation location, int limit) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(location, "location");
        String key = TravelNames.normalize(name);
        int maximum = HomePolicy.bounded(limit);
        return transaction(c -> {
            long revision = lock(c, OWNERS, "owner", owner.toString());
            if (find(c, HOMES, owner, key).isPresent()) return MutationResult.EXISTS;
            if (countHomes(c, owner) >= maximum) return MutationResult.LIMIT;
            long next = nextRevision(c, OWNERS, "owner", owner.toString(), revision);
            insert(c, HOMES, owner, key, location, next);
            return MutationResult.CREATED;
        });
    }

    /** An overwrite does not consume a new slot, even if permissions have since reduced the limit. */
    public MutationResult overwriteHome(UUID owner, String name, long expectedRevision, SavedLocation location) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        return mutate(HOMES, OWNERS, "owner", owner.toString(), owner, name, expectedRevision,
                Objects.requireNonNull(location, "location"));
    }

    public MutationResult deleteHome(UUID owner, String name, long expectedRevision) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        return mutate(HOMES, OWNERS, "owner", owner.toString(), owner, name, expectedRevision, null);
    }

    public MutationResult createWarp(String name, SavedLocation location) throws SQLException {
        Objects.requireNonNull(location, "location");
        String key = TravelNames.normalize(name);
        return transaction(c -> {
            long revision = lock(c, WARP_NAMES, "name", key);
            if (find(c, WARPS, null, key).isPresent()) return MutationResult.EXISTS;
            long next = nextRevision(c, WARP_NAMES, "name", key, revision);
            insert(c, WARPS, null, key, location, next);
            return MutationResult.CREATED;
        });
    }

    public MutationResult overwriteWarp(String name, long expectedRevision, SavedLocation location) throws SQLException {
        String key = TravelNames.normalize(name);
        return mutate(WARPS, WARP_NAMES, "name", key, null, key, expectedRevision,
                Objects.requireNonNull(location, "location"));
    }

    public MutationResult deleteWarp(String name, long expectedRevision) throws SQLException {
        String key = TravelNames.normalize(name);
        return mutate(WARPS, WARP_NAMES, "name", key, null, key, expectedRevision, null);
    }

    private MutationResult mutate(String table, String locks, String lockColumn, String lockKey, UUID owner,
                                  String name, long expectedRevision, SavedLocation location) throws SQLException {
        String key = TravelNames.normalize(name);
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be positive");
        return transaction(c -> {
            long revision = lock(c, locks, lockColumn, lockKey);
            Optional<StoredDestination> current = find(c, table, owner, key);
            if (current.isEmpty()) return MutationResult.MISSING;
            if (current.get().revision() != expectedRevision) return MutationResult.STALE;
            String where = (owner == null ? "" : "owner = ? AND ") + "name = ? AND revision = ?";
            if (location == null) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE " + where)) {
                    int index = bindKey(s, 1, owner, key);
                    s.setLong(index, expectedRevision);
                    return s.executeUpdate() == 1 ? MutationResult.DELETED : MutationResult.STALE;
                }
            }
            long next = nextRevision(c, locks, lockColumn, lockKey, revision);
            try (PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET world_id = ?, world_name = ?, "
                    + "x = ?, y = ?, z = ?, yaw = ?, pitch = ?, revision = ? WHERE " + where)) {
                int index = bindLocation(s, 1, location);
                s.setLong(index++, next);
                index = bindKey(s, index, owner, key);
                s.setLong(index, expectedRevision);
                return s.executeUpdate() == 1 ? MutationResult.UPDATED : MutationResult.STALE;
            }
        });
    }

    /** The INSERT is the first statement: SQLite takes its writer lock before any read snapshot. */
    private long lock(Connection c, String table, String column, String key) throws SQLException {
        String insert = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + table + " (" + column + ", revision) VALUES (?, 0) ON DUPLICATE KEY UPDATE " + column + " = " + column
                : "INSERT OR IGNORE INTO " + table + " (" + column + ", revision) VALUES (?, 0)";
        try (PreparedStatement s = c.prepareStatement(insert)) {
            s.setString(1, key);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("SELECT revision FROM " + table + " WHERE " + column + " = ?"
                + (dialect == SqlDialect.MYSQL ? " FOR UPDATE" : ""))) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("missing travel lock row");
                return r.getLong(1);
            }
        }
    }

    private static long nextRevision(Connection c, String table, String column, String key, long previous) throws SQLException {
        if (previous == Long.MAX_VALUE) throw new SQLException("travel revision exhausted");
        try (PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET revision = ? WHERE " + column + " = ?")) {
            s.setLong(1, previous + 1);
            s.setString(2, key);
            if (s.executeUpdate() != 1) throw new SQLException("missing travel lock row");
        }
        return previous + 1;
    }

    private static long countHomes(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + HOMES + " WHERE owner = ?")) {
            s.setString(1, owner.toString());
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static void insert(Connection c, String table, UUID owner, String name, SavedLocation location, long revision) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (" + (owner == null ? "" : "owner, ")
                + "name, " + LOCATION_COLUMNS + ", revision) VALUES (" + (owner == null ? "" : "?, ")
                + "?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int index = bindKey(s, 1, owner, name);
            index = bindLocation(s, index, location);
            s.setLong(index, revision);
            s.executeUpdate();
        }
    }

    private static Optional<StoredDestination> find(Connection c, String table, UUID owner, String name) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM " + table + " WHERE "
                + (owner == null ? "" : "owner = ? AND ") + "name = ?")) {
            bindKey(s, 1, owner, name);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new StoredDestination(r.getString("name"), location(r), r.getLong("revision")))
                        : Optional.empty();
            }
        }
    }

    private static Map<String, SavedLocation> locations(Connection c, String sql, String... parameters) throws SQLException {
        Map<String, SavedLocation> result = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) s.setString(i + 1, parameters[i]);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.put(r.getString("name"), location(r));
            }
        }
        return Map.copyOf(result);
    }

    private static SavedLocation location(ResultSet r) throws SQLException {
        return new SavedLocation(UUID.fromString(r.getString("world_id")), r.getString("world_name"),
                r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"));
    }

    private static int bindKey(PreparedStatement s, int index, UUID owner, String name) throws SQLException {
        if (owner != null) s.setString(index++, owner.toString());
        s.setString(index++, name);
        return index;
    }

    private static int bindLocation(PreparedStatement s, int index, SavedLocation location) throws SQLException {
        s.setString(index++, location.worldId().toString());
        s.setString(index++, location.worldName());
        s.setDouble(index++, location.x());
        s.setDouble(index++, location.y());
        s.setDouble(index++, location.z());
        s.setFloat(index++, location.yaw());
        s.setFloat(index++, location.pitch());
        return index;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T plain(Work<T> work) throws SQLException {
        try (Connection c = connections.connection()) {
            return work.run(c);
        }
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try (Connection c = connections.connection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                boolean transactionEnded = false;
                try {
                    T result = work.run(c);
                    c.commit();
                    transactionEnded = true;
                    return result;
                } catch (SQLException | RuntimeException error) {
                    try {
                        c.rollback();
                        transactionEnded = true;
                    } catch (SQLException rollback) {
                        error.addSuppressed(rollback);
                        throw error;
                    }
                    if (!(error instanceof SQLException sql) || !retryable(sql)) throw error;
                    last = sql;
                } finally {
                    // Enabling auto-commit commits an open transaction. After rollback fails,
                    // close/discard the connection without risking a commit of partial writes.
                    if (transactionEnded) c.setAutoCommit(auto);
                }
            }
            if (attempt == 5) break;
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * (attempt + 1)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted waiting for travel database lock", interrupted);
            }
        }
        throw Objects.requireNonNull(last);
    }

    private boolean retryable(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                boolean retry = switch (dialect) {
                    case MYSQL -> "40001".equals(sql.getSQLState())
                            || sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205;
                    case SQLITE -> (sql.getErrorCode() & 255) == 5 || (sql.getErrorCode() & 255) == 6;
                };
                if (retry) return true;
            }
        }
        return false;
    }
}
