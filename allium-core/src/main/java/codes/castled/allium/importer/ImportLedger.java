package codes.castled.allium.importer;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * The durable record of what every import wrote. One row per {@code (source, domain, key)} with the
 * payload's hash, written in the same transaction as the destination row, so a replay of the same
 * payload is a no-op and a changed payload under the same key is a conflict. Runs are logged with
 * the source file's and the reviewed plan's hashes.
 */
public final class ImportLedger {

    public static final String COMPONENT = "import";
    public static final String RUNS = "allium_import_runs";
    public static final String RECORDS = "allium_import_records";

    private static final String CREATE_RUNS = "CREATE TABLE IF NOT EXISTS " + RUNS + " (run_id VARCHAR(64) NOT NULL PRIMARY KEY, "
            + "source VARCHAR(64) NOT NULL, source_sha256 CHAR(64) NOT NULL, plan_sha256 CHAR(64) NOT NULL, "
            + "started_at BIGINT NOT NULL, finished_at BIGINT NULL, result VARCHAR(255) NULL)";
    private static final String CREATE_RECORDS = "CREATE TABLE IF NOT EXISTS " + RECORDS + " (source VARCHAR(64) NOT NULL, "
            + "domain VARCHAR(32) NOT NULL, record_key VARCHAR(191) NOT NULL, payload_sha256 CHAR(64) NOT NULL, "
            + "status VARCHAR(16) NOT NULL, run_id VARCHAR(64) NOT NULL, applied_at BIGINT NOT NULL, "
            + "PRIMARY KEY (source, domain, record_key))";
    public static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "import runs and records",
            List.of(CREATE_RUNS, CREATE_RECORDS),
            List.of(CREATE_RUNS + " ENGINE=InnoDB", CREATE_RECORDS + " ENGINE=InnoDB")));

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public ImportLedger(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, MIGRATIONS);
    }

    /** {@code "<domain>:<key>" -> payload hash} for every record of {@code source}. */
    public Map<String, String> hashes(String source) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT domain, record_key, payload_sha256 FROM " + RECORDS + " WHERE source = ?")) {
            s.setString(1, source);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.put(r.getString(1) + ":" + r.getString(2), r.getString(3));
            }
        }
        return out;
    }

    public Optional<String> hash(Connection c, String source, String domain, String key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT payload_sha256 FROM " + RECORDS
                + " WHERE source = ? AND domain = ? AND record_key = ?")) {
            s.setString(1, source);
            s.setString(2, domain);
            s.setString(3, key);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    public void record(Connection c, String source, String domain, String key, String payload, String status, String runId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + RECORDS
                + " (source, domain, record_key, payload_sha256, status, run_id, applied_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, source);
            s.setString(2, domain);
            s.setString(3, key);
            s.setString(4, payload);
            s.setString(5, status);
            s.setString(6, runId);
            s.setLong(7, clock.getAsLong());
            s.executeUpdate();
        }
    }

    public void startRun(String runId, String source, String sourceSha256, String planSha256) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("INSERT INTO " + RUNS
                     + " (run_id, source, source_sha256, plan_sha256, started_at) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, runId);
            s.setString(2, source);
            s.setString(3, sourceSha256);
            s.setString(4, planSha256);
            s.setLong(5, clock.getAsLong());
            s.executeUpdate();
        }
    }

    public void finishRun(String runId, String result) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("UPDATE " + RUNS + " SET finished_at = ?, result = ? WHERE run_id = ?")) {
            s.setLong(1, clock.getAsLong());
            s.setString(2, result.length() > 255 ? result.substring(0, 255) : result);
            s.setString(3, runId);
            s.executeUpdate();
        }
    }

    /** Whether {@code runId} was applied (started) before. */
    public boolean ran(String runId) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM " + RUNS + " WHERE run_id = ?")) {
            s.setString(1, runId);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One transaction, retried on lock contention like the travel and economy repositories. */
    public <T> T transaction(Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try (Connection c = connections.connection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                boolean ended = false;
                try {
                    T result = work.run(c);
                    c.commit();
                    ended = true;
                    return result;
                } catch (SQLException | RuntimeException error) {
                    try {
                        c.rollback();
                        ended = true;
                    } catch (SQLException rollback) {
                        error.addSuppressed(rollback);
                        throw error;
                    }
                    if (!(error instanceof SQLException sql) || !retryable(sql)) throw error;
                    last = sql;
                } finally {
                    if (ended) c.setAutoCommit(auto);
                }
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * (attempt + 1)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted waiting for the database", interrupted);
            }
        }
        throw Objects.requireNonNull(last);
    }

    private boolean retryable(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                boolean retry = switch (dialect) {
                    case MYSQL -> "40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205;
                    case SQLITE -> (sql.getErrorCode() & 255) == 5 || (sql.getErrorCode() & 255) == 6;
                };
                if (retry) return true;
            }
        }
        return false;
    }
}
