package codes.castled.allium.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import codes.castled.allium.PluginStart;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseManager {

    private final PluginStart plugin;
    private HikariDataSource dataSource;
    private DatabaseType type;
    private EconomyTable economyTable;

    public DatabaseManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        String typeStr = plugin.getConfig().getString("database.type", "SQLITE");
        type = DatabaseType.valueOf(typeStr.toUpperCase());

        HikariConfig config = new HikariConfig();
        config.setPoolName("Allium-Pool");

        if (type == DatabaseType.MYSQL) {
            configureMysql(config);
        } else {
            configureSqlite(config);
        }

        dataSource = new HikariDataSource(config);
        plugin.logger().info("Database connected (" + type.name() + ").");
        initTables();
    }

    private void configureMysql(HikariConfig config) {
        ConfigurationSection mysql = plugin.getConfig().getConfigurationSection("database.mysql");
        if (mysql == null) throw new IllegalStateException("Missing database.mysql configuration.");

        String host = mysql.getString("host", "localhost");
        int port = mysql.getInt("port", 3306);
        String database = mysql.getString("database", "allium");
        String username = mysql.getString("username", "root");
        String password = mysql.getString("password", "");

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        ConfigurationSection pool = mysql.getConfigurationSection("pool");
        if (pool != null) {
            config.setMaximumPoolSize(pool.getInt("maximum-pool-size", 10));
            config.setMinimumIdle(pool.getInt("minimum-idle", 2));
            config.setConnectionTimeout(pool.getLong("connection-timeout", 30000));
            config.setIdleTimeout(pool.getLong("idle-timeout", 600000));
            config.setMaxLifetime(pool.getLong("max-lifetime", 1800000));
        }
    }

    private void configureSqlite(HikariConfig config) {
        File dbFile = new File(plugin.getDataFolder(), "allium.db");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionTestQuery("SELECT 1");
        config.setConnectionInitSql("SELECT 1");
    }

    private void initTables() {
        try (Connection conn = getConnection()) {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS allium_players ("
                    + "uuid VARCHAR(36) PRIMARY KEY,"
                    + "name VARCHAR(16) NOT NULL,"
                    + "last_login BIGINT NOT NULL DEFAULT 0,"
                    + "last_logout BIGINT NOT NULL DEFAULT 0"
                    + ")"
            );
        } catch (SQLException e) {
            plugin.logger().severe("Failed to initialize tables: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Data source is not available.");
        }
        return dataSource.getConnection();
    }

    public DatabaseType getType() {
        return type;
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.logger().info("Database connection pool closed.");
        }
    }

    public void setEconomyTable(EconomyTable table) {
        this.economyTable = table;
    }

    public EconomyTable getEconomyTable() {
        return economyTable;
    }
}
