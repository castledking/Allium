package codes.castled.allium.harvest.storage;

import codes.castled.allium.harvest.HarvestBranding;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dedicated connection pool + schema migrations for the harvest subsystem.
 * All JDBC work runs on a single dedicated executor thread — no SQL ever
 * touches the main/region threads. Local storage defaults to H2 (matching the
 * rest of Allium); SQLite and MySQL/MariaDB are selectable in
 * {@code harvest/config.yml}.
 */
public final class HarvestDatabase implements AutoCloseable {

    public enum Dialect { H2, SQLITE, MYSQL }

    /** Connection settings parsed from harvest/config.yml. */
    public record Settings(
        Dialect dialect,
        String host,
        int port,
        String database,
        String username,
        String password,
        String tablePrefix
    ) {}

    private static final int SCHEMA_VERSION = 2;

    private final Logger logger;
    private final Settings settings;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public HarvestDatabase(Logger logger, File dataFolder, Settings settings) {
        this.logger = logger;
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Allium-Harvest-DB");
            t.setDaemon(true);
            return t;
        });

        HikariConfig config = new HikariConfig();
        config.setPoolName("Allium-Harvest");
        config.setMaximumPoolSize(settings.dialect() == Dialect.SQLITE ? 1 : 4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        switch (settings.dialect()) {
            case H2 -> {
                File dbFile = new File(dataFolder, HarvestBranding.DB_FILE_BASE);
                config.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath()
                    + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_SYSTEM_OUT=0");
                config.setDriverClassName(driverClass("org.h2.Driver"));
            }
            case SQLITE -> {
                File dbFile = new File(dataFolder, HarvestBranding.DB_FILE_BASE + ".db");
                config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                config.setDriverClassName(driverClass("org.sqlite.JDBC"));
            }
            case MYSQL -> {
                config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port()
                    + "/" + settings.database()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
                config.setUsername(settings.username());
                config.setPassword(settings.password());
                config.setDriverClassName(driverClass("com.mysql.cj.jdbc.Driver"));
            }
        }
        this.dataSource = new HikariDataSource(config);
        migrate();
    }

    /**
     * Resolves a JDBC driver class name, or explains precisely what is missing.
     *
     * <p>Only H2 is bundled, shaded into this jar under a relocated package
     * name; it is tried first so the default backend always works offline with
     * no setup. SQLite and MySQL are not bundled — including them cost around
     * 14 MB, most of it sqlite native binaries for architectures a given
     * server will never run — so those backends require the admin to supply
     * the driver. Failing here with instructions beats letting Hikari surface
     * it as an opaque connection-pool error.
     */
    private static String driverClass(String original) {
        String relocated = switch (original) {
            case "org.h2.Driver" -> "codes.castled.allium.libs.h2.Driver";
            default -> original;
        };
        try {
            Class.forName(relocated);
            return relocated;
        } catch (ClassNotFoundException ignored) {
            // Fall through: dev/test classpaths run unshaded.
        }
        try {
            Class.forName(original);
            return original;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "JDBC driver " + original + " is not on the classpath. Allium bundles only H2;"
                    + " to use this backend, drop the driver jar into the server's libraries/"
                    + " folder (or add it via another plugin), then restart. Alternatively set"
                    + " storage.type to H2 in harvest/config.yml.", e);
        }
    }

    public Dialect dialect() {
        return settings.dialect();
    }

    public String table(String name) {
        return settings.tablePrefix() + name;
    }

    /** Runs work on the database thread. Never call from a region thread and join. */
    public <T> CompletableFuture<T> supply(SqlSupplier<T> work) {
        if (executor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Harvest database is closed"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try (Connection connection = dataSource.getConnection()) {
                future.complete(work.get(connection));
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "[" + HarvestBranding.DISPLAY_NAME + "] Database operation failed", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public CompletableFuture<Void> run(SqlConsumer work) {
        return supply(connection -> {
            work.accept(connection);
            return null;
        });
    }

    /** Runs work synchronously on the DB thread and waits — shutdown only. */
    public void flushAndWait(Supplier<CompletableFuture<?>> flush) {
        try {
            flush.get().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Timed out flushing pending writes: " + e);
        }
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    // ==================== migrations ====================

    private void migrate() {
        try {
            supply(this::migrateNow).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Harvest database migration failed", e);
        }
    }

    private Void migrateNow(Connection connection) throws SQLException {
        String versionTable = table("harvest_schema_version");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + versionTable
                + " (version INT NOT NULL)");
        }
        int current = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM " + versionTable)) {
            if (rs.next()) {
                current = rs.getInt(1);
            }
        }
        if (current < 1) {
            applyV1(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + versionTable + " (version) VALUES (?)")) {
                ps.setInt(1, 1);
                ps.executeUpdate();
            }
        }
        if (current < 2) {
            applyV2(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + versionTable + " (version) VALUES (?)")) {
                ps.setInt(1, 2);
                ps.executeUpdate();
            }
        }
        if (current > SCHEMA_VERSION) {
            logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Database schema version " + current
                + " is newer than this build supports (" + SCHEMA_VERSION + ")");
        }
        return null;
    }

    private void applyV1(Connection connection) throws SQLException {
        String crops = table("harvest_crops");
        String cells = table("harvest_crop_cells");
        String spawners = table("harvest_tracked_spawners");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + crops + " ("
                + "instance_uuid VARCHAR(36) PRIMARY KEY,"
                + "crop_id VARCHAR(128) NOT NULL,"
                + "path_id VARCHAR(128) NOT NULL,"
                + "world_uuid VARCHAR(36) NOT NULL,"
                + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + "stage INT NOT NULL,"
                + "state VARCHAR(32) NOT NULL,"
                + "planted_at BIGINT NOT NULL,"
                + "stage_started_at BIGINT NOT NULL,"
                + "next_growth_at BIGINT,"
                + "remaining_ms BIGINT,"
                + "planter_uuid VARCHAR(36),"
                + "visual_uuid VARCHAR(36),"
                + "fertilizer_id VARCHAR(128),"
                + "data_json TEXT,"
                + "CONSTRAINT uq_" + crops + "_pos UNIQUE (world_uuid, x, y, z))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + crops + "_chunk ON "
                + crops + " (world_uuid, x, z)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + crops + "_due ON "
                + crops + " (next_growth_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + cells + " ("
                + "instance_uuid VARCHAR(36) NOT NULL,"
                + "world_uuid VARCHAR(36) NOT NULL,"
                + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + "PRIMARY KEY (world_uuid, x, y, z))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + cells + "_instance ON "
                + cells + " (instance_uuid)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + spawners + " ("
                + "world_uuid VARCHAR(36) NOT NULL,"
                + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + "entity_type VARCHAR(128) NOT NULL,"
                + "visual_uuid VARCHAR(36),"
                + "updated_at BIGINT NOT NULL,"
                + "PRIMARY KEY (world_uuid, x, y, z))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + spawners + "_chunk ON "
                + spawners + " (world_uuid, x, z)");
        }
    }

    /** Adds soil lifecycle tracking, sprinklers, and the crop speed multiplier. */
    private void applyV2(Connection connection) throws SQLException {
        String soil = table("harvest_soil");
        String sprinklers = table("harvest_sprinklers");
        String crops = table("harvest_crops");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + soil + " ("
                + "world_uuid VARCHAR(36) NOT NULL,"
                + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + "established_at BIGINT NOT NULL,"
                + "exhausted_at BIGINT NOT NULL,"
                + "fertilizer_id VARCHAR(128),"
                + "PRIMARY KEY (world_uuid, x, y, z))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + soil + "_chunk ON "
                + soil + " (world_uuid, x, z)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + soil + "_exhausted ON "
                + soil + " (exhausted_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + sprinklers + " ("
                + "world_uuid VARCHAR(36) NOT NULL,"
                + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + "sprinkler_id VARCHAR(128) NOT NULL,"
                + "visual_uuid VARCHAR(36),"
                + "placed_at BIGINT NOT NULL,"
                + "owner_uuid VARCHAR(36),"
                + "PRIMARY KEY (world_uuid, x, y, z))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + sprinklers + "_chunk ON "
                + sprinklers + " (world_uuid, x, z)");
        }
        addColumnIfMissing(connection, crops, "speed_multiplier", "DOUBLE");
    }

    /**
     * Adds a column when it is not already present.
     *
     * <p>{@code ADD COLUMN IF NOT EXISTS} is not portable across H2, SQLite and
     * MySQL, so the column list is inspected through JDBC metadata instead.
     */
    private void addColumnIfMissing(Connection connection, String table, String column, String type)
        throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        // Some drivers report unquoted identifiers upper-cased; retry the
        // metadata lookup case-insensitively before altering the table.
        try (ResultSet rs = connection.getMetaData().getColumns(
            null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Database executor did not drain in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
