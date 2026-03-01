package net.survivalfun.core.managers.DB;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import net.survivalfun.core.managers.economy.BalanceEntry;
import net.survivalfun.core.inventory.InventorySnapshot;
import net.survivalfun.core.managers.lang.Lang;

public class Database {
    private final PluginStart plugin;
    private final HikariDataSource dataSource;
    
    /**
     * Checks if the database connection is ready to be used
     * @return true if the database is ready, false otherwise
     */
    public boolean isReady() {
        return dataSource != null && !dataSource.isClosed();
    }
    
    /**
     * Closes the database connection pool and releases all resources.
     * This should be called when the plugin is disabled.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
                if (debugMode) {
                    plugin.getLogger().info("Database connection pool closed successfully");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error closing database connection pool: " + e.getMessage());
            }
        }
    }
    
    private final boolean debugMode;
    private final Lang lang;
    private final String jdbcUrl;
    private static final int MAX_POOL_SIZE = 30;  // Increased from 20
    private static final int MIN_IDLE = 5;
    private static final long CONNECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long MAX_LIFETIME_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long LEAK_DETECTION_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(60);

    public Database(PluginStart plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("debug-mode", false);
        this.lang = plugin.getLangManager();

        File dataFolder = new File(plugin.getDataFolder(), "database");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // Configure H2 logging
        System.setProperty("java.util.logging.config.file", 
            new File(plugin.getDataFolder().getParentFile().getParentFile(), 
                   "h2.log.properties").getAbsolutePath());
            
        String dbPath = new File(dataFolder, "sfcore").getAbsolutePath();
        this.jdbcUrl = "jdbc:h2:" + dbPath + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_SYSTEM_OUT=0";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");
        // Connection pool configuration
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(MIN_IDLE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setIdleTimeout(TimeUnit.MINUTES.toMillis(10));
        config.setMaxLifetime(MAX_LIFETIME_MS);
        config.setAutoCommit(true);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        config.setKeepaliveTime(TimeUnit.SECONDS.toMillis(30));
        
        // Connection test query
        config.setConnectionTestQuery("SELECT 1");
        
        // Optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        
        this.dataSource = new HikariDataSource(config);
        
        // Initialize database schema
        initialize();
    }
    

    private void initialize() {
        Text.sendDebugLog(INFO, "Initializing database connection pool...");
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            
            // Test the connection
            stmt.execute("SELECT 1");
            
            Text.sendDebugLog(INFO, "Database connection pool initialized successfully");
            
            // Create tables if they don't exist
            createTables(connection);
            
            // Run any pending migrations
            runMigrations(stmt);
            
            Text.sendDebugLog(INFO, "Database initialization completed successfully");
            
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to initialize database connection pool", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
    
    /**
     * Checks the database status and logs important information
     */
    private void checkDatabaseStatus() {
        try (Connection conn = getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Text.sendDebugLog(INFO, "Connected to database: " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());

            // Check if inventory_snapshots table exists and has data
            try (ResultSet tables = meta.getTables(null, null, "INVENTORY_SNAPSHOTS", null)) {
                if (tables.next()) {
                    // Table exists, check row count
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inventory_snapshots")) {
                        if (rs.next()) {
                            Text.sendDebugLog(INFO, "Found " + rs.getInt(1) + " inventory snapshots in the database");
                        }
                    }

                    // Check table structure
                    try (ResultSet columns = meta.getColumns(null, null, "INVENTORY_SNAPSHOTS", null)) {
                        Text.sendDebugLog(INFO, "Inventory snapshots table structure:");
                        while (columns.next()) {
                            String column = columns.getString("COLUMN_NAME") + " " +
                                         columns.getString("TYPE_NAME") +
                                         (columns.getInt("COLUMN_SIZE") > 0 ?
                                          "(" + columns.getInt("COLUMN_SIZE") + ")" : "");
                            Text.sendDebugLog(INFO, "- " + column);
                        }
                    }
                } else {
                    Text.sendDebugLog(WARN, "inventory_snapshots table does not exist!");
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error checking database status", e);
        }
    }

    /**
     * Serializes an array of ItemStacks to a byte array.
     * @param items The array of ItemStacks to serialize
     * @return Byte array containing the serialized ItemStacks
     * @throws IOException If an I/O error occurs during serialization
     */
    private byte[] serializeItemStacks(ItemStack[] items) throws IOException {
        if (items == null) {
            return new byte[0];
        }
        
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            // Write the size of the inventory
            dataOutput.writeInt(items.length);
            
            // Save every element in the list
            for (ItemStack item : items) {
                dataOutput.writeObject(item != null ? item.serialize() : null);
            }
            
            // Serialize that array
            dataOutput.flush();
            return outputStream.toByteArray();
        }
    }

    /**
     * Save a mail gift entry to the database.
     * Items are serialized to bytes, then Base64-encoded and stored in the 'items' column.
     */
    public int saveMailGift(UUID sender, String senderName, UUID recipient, ItemStack[] items) throws SQLException, IOException {
        try (Connection conn = getConnection()) {  // Use getConnection() instead of openConnection()
            byte[] serialized = serializeItemStacks(items);
            String base64 = Base64.getEncoder().encodeToString(serialized);
            String sql = "INSERT INTO mail_gifts (sender, sender_name, recipient, items) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, sender.toString());
                stmt.setString(2, senderName);
                stmt.setString(3, recipient.toString());
                stmt.setString(4, base64);
                stmt.executeUpdate();

                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        }
        return -1; // Return -1 if no generated key was returned
    }

    private static final int CURRENT_DB_VERSION = 4;

    private void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Create database version table if it doesn't exist
            Text.sendDebugLog(INFO, "Creating database_version table...");
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS database_version (" +
                "version INT PRIMARY KEY," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Get current version
            int currentVersion = 0;
            Text.sendDebugLog(INFO, "Checking database version...");
            try (ResultSet rs = statement.executeQuery("SELECT version FROM database_version")) {
                if (rs.next()) {
                    currentVersion = rs.getInt("version");
                    Text.sendDebugLog(INFO, "Found existing database version: " + currentVersion);
                } else {
                    // Initialize version if table is empty
                    Text.sendDebugLog(INFO, "Initializing database version to 0...");
                    statement.executeUpdate("INSERT INTO database_version (version) VALUES (0)");
                }
            }

            Text.sendDebugLog(INFO, "Creating player_permissions table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_permissions (" +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "permission VARCHAR(255) NOT NULL, " +
                            "PRIMARY KEY (player_uuid, permission)" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating spawn_locations table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS spawn_locations (" +
                            "id INT PRIMARY KEY AUTO_INCREMENT, " +
                            "world VARCHAR(255) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT, " +
                            "pitch FLOAT, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating player_data table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_data (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "is_flying BOOLEAN DEFAULT FALSE, " +
                            "allow_flight BOOLEAN DEFAULT FALSE, " +
                            "gamemode VARCHAR(20), " +
                            "walk_speed FLOAT DEFAULT 0.2, " +
                            "fly_speed FLOAT DEFAULT 0.1, " +
                            "vanish_level INT DEFAULT 0, " +
                            "last_seen_date TIMESTAMP, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating player_inventories table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_inventories (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "survival_inventory BLOB, " +
                            "survival_armor BLOB, " +
                            "survival_offhand BLOB, " +
                            "creative_inventory BLOB, " +
                            "creative_armor BLOB, " +
                            "creative_offhand BLOB, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating gamemode_resets table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS gamemode_resets (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "reset_gamemode VARCHAR(20) NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating player_effects table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_effects (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "has_slow_falling BOOLEAN DEFAULT FALSE, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating mail_messages table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS mail_messages (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "recipient VARCHAR(36) NOT NULL, " +
                            "sender VARCHAR(36) NOT NULL, " +
                            "sender_name VARCHAR(36) NOT NULL, " +
                            "message TEXT NOT NULL, " +
                            "sent_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "is_read BOOLEAN DEFAULT FALSE" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating mail_gifts table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS mail_gifts (" +
                            "id INT PRIMARY KEY AUTO_INCREMENT, " +
                            "sender VARCHAR(36) NOT NULL, " +
                            "sender_name VARCHAR(36) NOT NULL, " +
                            "recipient VARCHAR(36) NOT NULL, " +
                            "items LONGTEXT NOT NULL, " +
                            "sent_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "is_claimed BOOLEAN DEFAULT FALSE" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating player_teleport_toggle table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_teleport_toggle (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "state BOOLEAN NOT NULL" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating inventory_snapshots table...");
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS inventory_snapshots (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "timestamp BIGINT NOT NULL, " +
                "reason VARCHAR(32) NOT NULL, " +
                "inventory_data LONGTEXT NOT NULL, " +
                "armor_data LONGTEXT NOT NULL, " +
                "offhand_data LONGTEXT NOT NULL, " +
                "enderchest_data LONGTEXT NOT NULL, " +
                "experience INT NOT NULL, " +
                "world VARCHAR(64) NOT NULL, " +
                "x DOUBLE NOT NULL, " +
                "y DOUBLE NOT NULL, " +
                "z DOUBLE NOT NULL, " +
                "yaw FLOAT NOT NULL, " +
                "pitch FLOAT NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "INDEX idx_player (player_uuid), " +
                "INDEX idx_timestamp (timestamp)" +
                ")"
            );

            Text.sendDebugLog(INFO, "Creating player_locations table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_locations (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "location_type VARCHAR(50) NOT NULL, " +
                            "home_name VARCHAR(50), " +
                            "world VARCHAR(255) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT NOT NULL, " +
                            "pitch FLOAT NOT NULL, " +
                            "timestamp BIGINT NOT NULL, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "INDEX idx_home (player_uuid, location_type, home_name)" +
                            ")"
            );

            try {
                if (tableExists(null, "player_death_locations")) {
                    Text.sendDebugLog(INFO, "Migrating death locations data...");
                    statement.executeUpdate(
                            "INSERT INTO player_locations (player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp) " +
                            "SELECT player_uuid, 'DEATH', world, x, y, z, yaw, pitch, death_time " +
                            "FROM player_death_locations"
                    );
                }
            } catch (SQLException e) {
                Text.sendDebugLog(WARN, "Failed to migrate death locations data", e);
            }

            Text.sendDebugLog(INFO, "Creating player_balances table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_balances (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating server_data table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS server_data (\"key\" VARCHAR(255) PRIMARY KEY, \"value\" TEXT)"
            );

            Text.sendDebugLog(INFO, "Creating player_notes table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_notes (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "staff_uuid VARCHAR(36), " +
                            "note TEXT NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating warps table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS warps (" +
                            "name VARCHAR(50) PRIMARY KEY, " +
                            "world VARCHAR(255) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT NOT NULL, " +
                            "pitch FLOAT NOT NULL, " +
                            "creator_uuid VARCHAR(36) NOT NULL, " +
                            "creator_name VARCHAR(16) NOT NULL, " +
                            "is_public BOOLEAN DEFAULT TRUE, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            Text.sendDebugLog(INFO, "Creating restrained_players table...");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS restrained_players (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "restrained_uuid VARCHAR(36) NOT NULL, " +
                            "handcuffer_uuid VARCHAR(36) NOT NULL, " +
                            "handcuffer_location LONGTEXT NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "INDEX idx_restrained_uuid (restrained_uuid), " +
                            "INDEX idx_handcuffer_uuid (handcuffer_uuid)" +
                            ")"
            );

            // Run migrations if needed (now that tables exist)
            if (currentVersion < CURRENT_DB_VERSION) {
                Text.sendDebugLog(INFO, "Running migrations for version " + currentVersion + " to " + CURRENT_DB_VERSION);
                runMigrations(currentVersion, statement);
            }

            // Update database version if needed
            if (currentVersion < CURRENT_DB_VERSION) {
                Text.sendDebugLog(INFO, "Updating database version to " + CURRENT_DB_VERSION);
                statement.executeUpdate("UPDATE database_version SET version = " + CURRENT_DB_VERSION);
                Text.sendDebugLog(INFO, "Database schema updated to version " + CURRENT_DB_VERSION);
            }

            Text.sendDebugLog(INFO, "Calling final migrations...");
            runMigrations(currentVersion, statement);
            Text.sendDebugLog(INFO, "Database table creation completed successfully");
        }
    }

    /**
     * Runs database migrations from the current version to the latest version
     * @param currentVersion The current database version
     */
    private void runMigrations(int currentVersion, Statement statement) throws SQLException {
        Text.sendDebugLog(INFO, "Running database migrations from version " + currentVersion + " to " + CURRENT_DB_VERSION);

        if (currentVersion < 1) {
            // Migration to version 1 - Initial schema setup
            Text.sendDebugLog(INFO, "Applied migration to version 1");
        }

        if (currentVersion < 2) {
            // Migration to version 2 - Add offhand_data column to inventory_snapshots
            try (Connection connection = getConnection()) {
                if (tableExists(connection, "inventory_snapshots") && !columnExists(connection, "inventory_snapshots", "offhand_data")) {
                    Text.sendDebugLog(INFO, "Adding offhand_data column to inventory_snapshots table...");
                    statement.executeUpdate("ALTER TABLE inventory_snapshots ADD COLUMN IF NOT EXISTS offhand_data LONGTEXT");
                    Text.sendDebugLog(INFO, "Successfully added offhand_data column to inventory_snapshots table");
                }
            }

            Text.sendDebugLog(INFO, "Applied migration to version 2");
        }

        if (currentVersion < 3) {
            try (Connection connection = getConnection()) {
                if (tableExists(connection, "inventory_snapshots") && !columnExists(connection, "inventory_snapshots", "enderchest_data")) {
                    Text.sendDebugLog(INFO, "Adding enderchest_data column to inventory_snapshots table...");
                    statement.executeUpdate("ALTER TABLE inventory_snapshots ADD COLUMN IF NOT EXISTS enderchest_data LONGTEXT DEFAULT ''");
                    Text.sendDebugLog(INFO, "Successfully added enderchest_data column to inventory_snapshots table");
                }
            }

            Text.sendDebugLog(INFO, "Applied migration to version 3");
        }

        if (currentVersion < 4) {
            try (Connection connection = getConnection()) {
                if (tableExists(connection, "player_data") && !columnExists(connection, "player_data", "vanish_level")) {
                    Text.sendDebugLog(INFO, "Adding vanish_level column to player_data table...");
                    statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS vanish_level INT DEFAULT 0");
                    Text.sendDebugLog(INFO, "Successfully added vanish_level column to player_data table");
                }
            }

            Text.sendDebugLog(INFO, "Applied migration to version 4");
        }
    }

    /**
     * Runs database migrations, including adding migrated_perms column to player_data.
     */
    private void runMigrations(Statement statement) throws SQLException {
        // Use a single connection for all operations to avoid leaks
        try (Connection connection = getConnection()) {
            // Check and add home_name column (unchanged)
            if (!columnExists(connection, "player_locations", "home_name")) {
                Text.sendDebugLog(INFO, "Adding home_name column to player_locations table...");
                statement.executeUpdate("ALTER TABLE player_locations ADD COLUMN IF NOT EXISTS home_name VARCHAR(50)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_home ON player_locations (player_uuid, location_type, home_name)");
                Text.sendDebugLog(INFO, "Successfully added home_name column and index to player_locations table");
            }

            // Check and add migrated_perms column to player_data
            if (!columnExists(connection, "player_data", "migrated_perms")) {
                Text.sendDebugLog(INFO, "Adding migrated_perms column to player_data table...");
                statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS migrated_perms BOOLEAN DEFAULT FALSE");
                Text.sendDebugLog(INFO, "Successfully added migrated_perms column to player_data table");
            }

            // Check and add walk_speed and fly_speed columns to player_data
            if (!columnExists(connection, "player_data", "walk_speed")) {
                Text.sendDebugLog(INFO, "Adding walk_speed column to player_data table...");
                statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS walk_speed FLOAT DEFAULT 0.2");
                Text.sendDebugLog(INFO, "Successfully added walk_speed column to player_data table");
            }

            if (!columnExists(connection, "player_data", "fly_speed")) {
                Text.sendDebugLog(INFO, "Adding fly_speed column to player_data table...");
                statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS fly_speed FLOAT DEFAULT 0.1");
                Text.sendDebugLog(INFO, "Successfully added fly_speed column to player_data table");
            }

            if (!columnExists(connection, "inventory_snapshots", "enderchest_data")) {
                Text.sendDebugLog(INFO, "Adding enderchest_data column to inventory_snapshots table (final pass)...");
                statement.executeUpdate("ALTER TABLE inventory_snapshots ADD COLUMN IF NOT EXISTS enderchest_data LONGTEXT DEFAULT ''");
                Text.sendDebugLog(INFO, "Successfully added enderchest_data column to inventory_snapshots table (final pass)");
            }

            // Add player_displayname column to player_data if it doesn't exist
            if (!columnExists(connection, "player_data", "player_displayname")) {
                Text.sendDebugLog(INFO, "Adding player_displayname column to player_data table...");
                statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS player_displayname VARCHAR(100)");
                Text.sendDebugLog(INFO, "Successfully added player_displayname column to player_data table");
            }
        }
    }

    public void addPlayerPermission(UUID playerId, String permission) {
        try {
            try (PreparedStatement stmt = getConnection().prepareStatement(
                    "INSERT INTO player_permissions (player_uuid, permission) VALUES (?, ?)") ) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, permission);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to add permission", e);
        }
    }

    public boolean playerHasPermission(UUID playerId, String permission) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                    "SELECT 1 FROM player_permissions WHERE player_uuid = ? AND permission = ?")) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, permission);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to check permission", e);
            return false;
        }
    }

    public boolean createDefaultPlayerBalance(UUID playerUUID, BigDecimal defaultBalance) {
        try {
            String sql = "INSERT INTO player_balances (uuid, balance) VALUES (?, ?)";
            try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                stmt.setBigDecimal(2, defaultBalance);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to create default player balance", e);
            return false;
        }
    }

    public boolean setPlayerBalance(UUID playerUUID, BigDecimal amount) {
        if (getPlayerBalance(playerUUID) == null) {
            return createDefaultPlayerBalance(playerUUID, amount);
        }
        String sql = "UPDATE player_balances SET balance = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
        return executeUpdate(sql, amount, playerUUID.toString()) > 0;
    }

    public boolean addToPlayerBalance(UUID playerUUID, BigDecimal amount) {
        BigDecimal currentBalance = getPlayerBalance(playerUUID);
        if (currentBalance == null) {
            return createDefaultPlayerBalance(playerUUID, amount);
        }
        BigDecimal newBalance = currentBalance.add(amount);
        return setPlayerBalance(playerUUID, newBalance);
    }

    public boolean subtractFromPlayerBalance(UUID playerUUID, BigDecimal amount) {
        BigDecimal currentBalance = getPlayerBalance(playerUUID);
        if (currentBalance == null) {
            return false;
        }
        BigDecimal newBalance = currentBalance.subtract(amount);
        return setPlayerBalance(playerUUID, newBalance);
    }

    public boolean hasEnoughBalance(UUID playerUUID, BigDecimal amount) {
        BigDecimal currentBalance = getPlayerBalance(playerUUID);
        if (currentBalance == null) {
            return false;
        }
        return currentBalance.compareTo(amount) >= 0;
    }

    public List<BalanceEntry> getTopBalances(int limit) {
        List<BalanceEntry> topBalances = new ArrayList<>();
        try {
            String sql = "SELECT b.uuid, p.name, b.balance FROM player_balances b " +
                    "LEFT JOIN player_data p ON b.uuid = p.uuid " +
                    "ORDER BY b.balance DESC LIMIT ?";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                        String name = resultSet.getString("name");
                        BigDecimal balance = resultSet.getBigDecimal("balance");
                        if (name == null) {
                            name = uuid.toString().substring(0, 8);
                        }
                        topBalances.add(new BalanceEntry(uuid, name, balance));
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get top balances", e);
        }
        return topBalances;
    }

    /**
     * Saves the player's current state for handcuffs system
     */
    public void savePlayerState(UUID playerUUID, GameMode gameMode, boolean allowFlight, boolean isFlying, float walkSpeed, float flySpeed) throws SQLException {
        String sql = "INSERT INTO player_data (uuid, name, gamemode, allow_flight, is_flying, walk_speed, fly_speed, last_updated) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE " +
                "name = VALUES(name), " +
                "gamemode = VALUES(gamemode), " +
                "allow_flight = VALUES(allow_flight), " +
                "is_flying = VALUES(is_flying), " +
                "walk_speed = VALUES(walk_speed), " +
                "fly_speed = VALUES(fly_speed), " +
                "last_updated = CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, ""); // Placeholder name - we don't have it in this context
            stmt.setString(3, gameMode != null ? gameMode.name() : null);
            stmt.setBoolean(4, allowFlight);
            stmt.setBoolean(5, isFlying);
            stmt.setFloat(6, walkSpeed);
            stmt.setFloat(7, flySpeed);
            stmt.executeUpdate();
        }
    }

    /**
     * Saves the player's current state including vanish level for vanish system
     */
    public void savePlayerStateWithVanish(UUID playerUUID, GameMode gameMode, boolean allowFlight, boolean isFlying, float walkSpeed, float flySpeed, int vanishLevel) throws SQLException {
        String sql = "INSERT INTO player_data (uuid, name, gamemode, allow_flight, is_flying, walk_speed, fly_speed, vanish_level, last_updated) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE " +
                "name = VALUES(name), " +
                "gamemode = VALUES(gamemode), " +
                "allow_flight = VALUES(allow_flight), " +
                "is_flying = VALUES(is_flying), " +
                "walk_speed = VALUES(walk_speed), " +
                "fly_speed = VALUES(fly_speed), " +
                "vanish_level = VALUES(vanish_level), " +
                "last_updated = CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, ""); // Placeholder name - we don't have it in this context
            stmt.setString(3, gameMode != null ? gameMode.name() : null);
            stmt.setBoolean(4, allowFlight);
            stmt.setBoolean(5, isFlying);
            stmt.setFloat(6, walkSpeed);
            stmt.setFloat(7, flySpeed);
            stmt.setInt(8, vanishLevel);
            stmt.executeUpdate();
        }
    }

    /**
     * Loads the player's saved state for handcuffs system
     */
    public Map<String, Object> loadPlayerState(UUID playerUUID) throws SQLException {
        String sql = "SELECT gamemode, allow_flight, is_flying, walk_speed, fly_speed FROM player_data WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> state = new HashMap<>();
                    String gameModeStr = rs.getString("gamemode");
                    state.put("gamemode", gameModeStr != null ? GameMode.valueOf(gameModeStr.toUpperCase()) : null);
                    state.put("allow_flight", rs.getBoolean("allow_flight"));
                    state.put("is_flying", rs.getBoolean("is_flying"));
                    state.put("walk_speed", rs.getFloat("walk_speed"));
                    state.put("fly_speed", rs.getFloat("fly_speed"));
                    return state;
                }
            }
        }
        return null;
    }

    /**
     * Loads the player's vanish state from database
     */
    public VanishState loadVanishState(UUID playerUUID) throws SQLException {
        String sql = "SELECT vanish_level, gamemode FROM player_data WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int vanishLevel = rs.getInt("vanish_level");
                    String gameModeStr = rs.getString("gamemode");
                    GameMode gameMode = null;
                    if (gameModeStr != null) {
                        try {
                            gameMode = GameMode.valueOf(gameModeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            Text.sendDebugLog(WARN, "Invalid gamemode in database for player " + playerUUID + ": " + gameModeStr);
                        }
                    }
                    return new VanishState(vanishLevel, gameMode);
                }
            }
        }
        return new VanishState(0, null); // Default state: not vanished
    }

    /**
     * Saves vanish state to database
     */
    public void saveVanishState(UUID playerUUID, int vanishLevel, GameMode gameMode) throws SQLException {
        String sql = "UPDATE player_data SET vanish_level = ?, gamemode = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, vanishLevel);
            stmt.setString(2, gameMode != null ? gameMode.name() : null);
            stmt.setString(3, playerUUID.toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Removes vanish state from database (sets vanish_level to 0)
     */
    public void removeVanishState(UUID playerUUID) throws SQLException {
        String sql = "UPDATE player_data SET vanish_level = 0, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Saves player movement speeds for handcuffs system
     */
    public void savePlayerSpeeds(UUID playerUUID, float walkSpeed, float flySpeed) throws SQLException {
        String sql = "UPDATE player_data SET walk_speed = ?, fly_speed = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setFloat(1, walkSpeed);
            stmt.setFloat(2, flySpeed);
            stmt.setString(3, playerUUID.toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Loads player movement speeds for handcuffs system
     */
    public Map<String, Float> loadPlayerSpeeds(UUID playerUUID) throws SQLException {
        String sql = "SELECT walk_speed, fly_speed FROM player_data WHERE uuid = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Float> speeds = new HashMap<>();
                    speeds.put("walk_speed", rs.getFloat("walk_speed"));
                    speeds.put("fly_speed", rs.getFloat("fly_speed"));
                    return speeds;
                }
            }
        }
        return null;
    }

    /**
     * Gets the stored display name (nickname) for a player from the database.
     * @param playerUUID The player's UUID
     * @return The stored nickname with color codes, or null if none/not found
     */
    public String getStoredPlayerDisplayname(UUID playerUUID) {
        if (!columnExists("player_data", "player_displayname")) {
            return null;
        }
        return queryString("SELECT player_displayname FROM player_data WHERE uuid = ?", playerUUID.toString());
    }

    public String getServerData(String key) {
        return queryString("SELECT \"value\" FROM server_data WHERE \"key\" = ?", key);
    }

    public void setServerData(String key, String value) {
        try {
            String sql = "INSERT INTO server_data (\"key\", \"value\") VALUES (?, ?) " +
                         "ON DUPLICATE KEY UPDATE \"value\" = VALUES(\"value\")";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to set server data for key: " + key, e);
        }
    }

    public void setWitherSpawnDisabled(boolean disabled) {
        setServerData("wither_spawn_disabled", String.valueOf(disabled));
    }

    public boolean isWitherSpawnDisabled() {
        String value = getServerData("wither_spawn_disabled");
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    public enum LocationType {
        DEATH, TELEPORT, COMMAND, LOGIN, LOGOUT, HOME
    }

    public void savePlayerLocation(UUID playerUUID, LocationType locationType, Location location, long timestamp) {
        try (Connection connection = getConnection()) {
            // Use REPLACE to ensure only one entry per player/location type
            String sql = "REPLACE INTO player_locations " +
                    "(player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp, last_updated) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, locationType.name());
                statement.setString(3, location.getWorld().getName());
                statement.setDouble(4, location.getX());
                statement.setDouble(5, location.getY());
                statement.setDouble(6, location.getZ());
                statement.setFloat(7, location.getYaw());
                statement.setFloat(8, location.getPitch());
                statement.setLong(9, timestamp);
                statement.executeUpdate();
                
                if (debugMode) {
                    Text.sendDebugLog(INFO, String.format(
                        "[Database] Saved %s location for %s at %s (%.1f, %.1f, %.1f)",
                        locationType, playerUUID, location.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ()
                    ));
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to save " + locationType + " location for player UUID: " + playerUUID, e);
        }
    }

    public void saveAllPlayerLocations() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayerLocation(player.getUniqueId(), LocationType.LOGOUT, player.getLocation(), System.currentTimeMillis());
        }
    }

    public void savePlayerHome(UUID playerUUID, String homeName, Location location, long timestamp) {
        try {
            String sql = "INSERT INTO player_locations (player_uuid, location_type, home_name, world, x, y, z, yaw, pitch, timestamp, last_updated) " +
                    "VALUES (?, 'HOME', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON DUPLICATE KEY UPDATE world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, timestamp = ?, last_updated = CURRENT_TIMESTAMP";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, homeName);
                statement.setString(3, location.getWorld().getName());
                statement.setDouble(4, location.getX());
                statement.setDouble(5, location.getY());
                statement.setDouble(6, location.getZ());
                statement.setFloat(7, location.getYaw());
                statement.setFloat(8, location.getPitch());
                statement.setLong(9, timestamp);
                statement.setString(10, location.getWorld().getName());
                statement.setDouble(11, location.getX());
                statement.setDouble(12, location.getY());
                statement.setDouble(13, location.getZ());
                statement.setFloat(14, location.getYaw());
                statement.setFloat(15, location.getPitch());
                statement.setLong(16, timestamp);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to save home '" + homeName + "' for player UUID: " + playerUUID, e);
        }
    }

    public Location getPlayerHome(UUID playerUUID, String homeName) {
        try (Connection connection = getConnection()) {
            String sql = "SELECT world, x, y, z, yaw, pitch FROM player_locations " +
                    "WHERE player_uuid = ? AND location_type = 'HOME' AND home_name = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, homeName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String worldName = resultSet.getString("world");
                        double x = resultSet.getDouble("x");
                        double y = resultSet.getDouble("y");
                        double z = resultSet.getDouble("z");
                        float yaw = resultSet.getFloat("yaw");
                        float pitch = resultSet.getFloat("pitch");
                        World world = plugin.getServer().getWorld(worldName);
                        if (world != null) {
                            return new Location(world, x, y, z, yaw, pitch);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get home '" + homeName + "' for player UUID: " + playerUUID, e);
        }
        return null;
    }

    public List<String> getPlayerHomes(UUID playerUUID) {
        List<String> homes = new ArrayList<>();
        String sql = "SELECT home_name FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME'";
        
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String homeName = resultSet.getString("home_name");
                    if (homeName != null) {
                        homes.add(homeName);
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get homes for player UUID: " + playerUUID, e);
        }
        return homes;
    }

    public int getPlayerHomeCount(UUID playerUUID) {
        String sql = "SELECT COUNT(*) FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME'";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to count homes for player UUID: " + playerUUID, e);
        }
        return 0;
    }

    public boolean deletePlayerHome(UUID playerUUID, String homeName) {
        String sql = "DELETE FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME' AND home_name = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, playerUUID.toString());
            statement.setString(2, homeName);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to delete home '" + homeName + "' for player UUID: " + playerUUID, e);
            return false;
        }
    }

    public Location getPlayerLocation(UUID playerUUID, LocationType locationType) {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM player_locations " +
                "WHERE player_uuid = ? AND location_type = ? " +
                "ORDER BY timestamp DESC, last_updated DESC LIMIT 1";
                
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, playerUUID.toString());
            statement.setString(2, locationType.name());
            
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String worldName = resultSet.getString("world");
                    double x = resultSet.getDouble("x");
                    double y = resultSet.getDouble("y");
                    double z = resultSet.getDouble("z");
                    float yaw = resultSet.getFloat("yaw");
                    float pitch = resultSet.getFloat("pitch");
                    World world = plugin.getServer().getWorld(worldName);
                    if (world != null) {
                        return new Location(world, x, y, z, yaw, pitch);
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get " + locationType + 
                " location for player UUID: " + playerUUID, e);
        }
        return null;
    }

    public long getPlayerLocationTimestamp(UUID playerUUID, LocationType locationType) {
        try {
            String sql = "SELECT timestamp FROM player_locations " +
                    "WHERE player_uuid = ? AND location_type = ? " +
                    "ORDER BY timestamp DESC, last_updated DESC LIMIT 1";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, locationType.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getLong("timestamp");
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get " + locationType + " timestamp for player UUID: " + playerUUID, e);
        }
        return 0L;
    }

    public Map<String, Object> getLastPlayerLocation(UUID playerUUID) {
        try {
            String sql = "SELECT location_type, world, x, y, z, yaw, pitch, timestamp " +
                    "FROM player_locations WHERE player_uuid = ? " +
                    "ORDER BY timestamp DESC, last_updated DESC LIMIT 1";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        Map<String, Object> locationData = new HashMap<>();
                        locationData.put("location_type", resultSet.getString("location_type"));
                        locationData.put("world", resultSet.getString("world"));
                        locationData.put("x", resultSet.getDouble("x"));
                        locationData.put("y", resultSet.getDouble("y"));
                        locationData.put("z", resultSet.getDouble("z"));
                        locationData.put("yaw", resultSet.getFloat("yaw"));
                        locationData.put("pitch", resultSet.getFloat("pitch"));
                        locationData.put("timestamp", resultSet.getLong("timestamp"));
                        return locationData;
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get last location for player UUID: " + playerUUID, e);
        }
        return null;
    }

    public Location getLastNonDeathLocation(UUID playerUUID) {
        try {
            String sql = "SELECT world, x, y, z, yaw, pitch FROM player_locations " +
                    "WHERE player_uuid = ? AND location_type != 'DEATH' " +
                    "ORDER BY timestamp DESC, last_updated DESC LIMIT 1";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String worldName = resultSet.getString("world");
                        double x = resultSet.getDouble("x");
                        double y = resultSet.getDouble("y");
                        double z = resultSet.getDouble("z");
                        float yaw = resultSet.getFloat("yaw");
                        float pitch = resultSet.getFloat("pitch");
                        World world = plugin.getServer().getWorld(worldName);
                        if (world != null) {
                            return new Location(world, x, y, z, yaw, pitch);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get last non-death location for player UUID: " + playerUUID, e);
        }
        return null;
    }

    public void saveTeleportToggleState(UUID playerUuid, boolean state) {
        String sql = "REPLACE INTO player_teleport_toggle (player_uuid, state) VALUES (?, ?)";
        try {
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.setBoolean(2, state);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error saving teleport toggle state", e);
        }
    }

    public Boolean getTeleportToggleState(UUID playerUuid) {
        String sql = "SELECT state FROM player_teleport_toggle WHERE player_uuid = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getBoolean("state");
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error loading teleport toggle state", e);
        }
        return null;
    }

    public void savePlayerDeathLocation(UUID playerUUID, Location location, long deathTime) {
        savePlayerLocation(playerUUID, LocationType.DEATH, location, deathTime);
    }

    public Location getPlayerDeathLocation(UUID playerUUID) {
        return getPlayerLocation(playerUUID, LocationType.DEATH);
    }

    public long getPlayerDeathTime(UUID playerUUID) {
        return getPlayerLocationTimestamp(playerUUID, LocationType.DEATH);
    }

    public void clearPlayerDeathLocation(UUID playerUUID) {
        deletePlayerLocation(playerUUID, LocationType.DEATH);
    }

    public boolean deletePlayerLocation(UUID playerUUID, LocationType locationType) {
        try {
            String sql = "DELETE FROM player_locations WHERE player_uuid = ? AND location_type = ?";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, locationType.name());
                statement.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to delete " + locationType + " location for player UUID: " + playerUUID);
            return false;
        }
    }

    public void savePlayerFlightStatus(Player player) {
        String updateSql = "UPDATE player_data SET is_flying = ?, allow_flight = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
        String insertSql = "INSERT INTO player_data (uuid, name, is_flying, allow_flight, last_updated) " +
                        "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
        
        try (Connection connection = getConnection();
            PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
            
            // Set parameters for UPDATE
            updateStmt.setBoolean(1, player.isFlying());
            updateStmt.setBoolean(2, player.getAllowFlight());
            updateStmt.setString(3, player.getUniqueId().toString());
            
            int rowsAffected = updateStmt.executeUpdate();
            
            // If no rows were updated, try to insert
            if (rowsAffected == 0) {
                try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                    insertStmt.setString(1, player.getUniqueId().toString());
                    insertStmt.setString(2, player.getName());
                    insertStmt.setBoolean(3, player.isFlying());
                    insertStmt.setBoolean(4, player.getAllowFlight());
                    insertStmt.executeUpdate();
                }
            }
            
            // Update last seen
            updatePlayerLastSeen(player.getUniqueId(), player.getName());
            
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to save flight status for player: " + player.getName(), e);
        }
    }

    /**
     * Executes an SQL update query with proper resource management and error handling
     * @param sql The SQL query to execute
     * @param params The parameters to set in the prepared statement
     * @return The number of rows affected, or -1 if an error occurred
     */
    public int executeUpdate(String sql, Object... params) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be null or empty");
        }

        try (Connection connection = validateConnection()) {
            if (connection == null) {
                Text.sendDebugLog(ERROR, "Failed to get valid database connection for query: " + sql);
                return -1;
            }

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                // Set parameters
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }

                // Execute update
                return stmt.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to execute update query: " + sql, e);
            return -1;
        }
    }

    /**
     * Executes a batch update within a transaction
     * @param sql The SQL query to execute in batch
     * @param batchParams List of parameter arrays for each batch item
     * @return Array of update counts, or null if an error occurred
     */
    public int[] executeBatchUpdate(String sql, List<Object[]> batchParams) {
        if (sql == null || sql.trim().isEmpty() || batchParams == null || batchParams.isEmpty()) {
            throw new IllegalArgumentException("SQL query and batch parameters cannot be null or empty");
        }

        try (Connection connection = validateConnection()) {
            if (connection == null) {
                Text.sendDebugLog(ERROR, "Failed to get valid database connection for batch update: " + sql);
                return null;
            }

            // Set auto-commit to false for transaction
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                // Add batch parameters
                for (Object[] params : batchParams) {
                    for (int i = 0; i < params.length; i++) {
                        stmt.setObject(i + 1, params[i]);
                    }
                    stmt.addBatch();
                }

                // Execute batch
                int[] result = stmt.executeBatch();
                connection.commit();
                return result;
            } catch (SQLException e) {
                // Rollback on error
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    Text.sendDebugLog(ERROR, "Failed to rollback transaction", ex);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Failed to reset auto-commit", e);
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to execute batch update: " + sql, e);
            return null;
        }
    }

    /**
     * Executes a database operation within a transaction
     * @param operation The database operation to execute
     * @param isolationLevel The transaction isolation level (use -1 for default)
     * @param <T> The return type of the operation
     * @return The result of the operation, or null if an error occurred
     */
    public <T> T executeInTransaction(SQLOperation<T> operation, int isolationLevel) {
        try (Connection connection = validateConnection()) {
            if (connection == null) {
                Text.sendDebugLog(ERROR, "Failed to get valid database connection for transaction");
                return null;
            }

            // Save current auto-commit state and set to false
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            // Set transaction isolation level if specified
            if (isolationLevel >= 0) {
                int oldIsolation = connection.getTransactionIsolation();
                connection.setTransactionIsolation(isolationLevel);
            }

            try {
                // Execute the operation
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                // Rollback on error
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    Text.sendDebugLog(ERROR, "Failed to rollback transaction", ex);
                }
                throw e;
            } finally {
                try {
                    // Restore auto-commit state
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Failed to restore auto-commit state", e);
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Transaction failed", e);
            return null;
        }
    }

    /**
     * Validates and returns a database connection
     * @return A valid database connection, or null if an error occurred
     */
    private Connection validateConnection() {
        try {
            if (dataSource == null || dataSource.isClosed()) {
                Text.sendDebugLog(ERROR, "Data source is not initialized or has been closed");
                return null;
            }

            Connection connection = dataSource.getConnection();
            if (connection == null || connection.isClosed() || !connection.isValid(5)) {
                closeResources(connection);
                Text.sendDebugLog(ERROR, "Database connection is not valid");
                return null;
            }
            return connection;
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to validate database connection", e);
            return null;
        }
    }

    /**
     * Safely closes database resources
     * @param resources The resources to close (can be AutoCloseable or array of AutoCloseable)
     */
    public static void closeResources(AutoCloseable... resources) {
        if (resources != null) {
            for (AutoCloseable resource : resources) {
                if (resource != null) {
                    try {
                        resource.close();
                    } catch (Exception e) {
                        Text.sendDebugLog(WARN, "Error closing resource: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Functional interface for database operations
     * @param <T> The return type of the operation
     */
    @FunctionalInterface
    public interface SQLOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    public BigDecimal getPlayerBalance(UUID playerUUID) {
        String sql = "SELECT balance FROM player_balances WHERE uuid = ?";
        try {
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getBigDecimal("balance");
                    } else {
                        return createDefaultPlayerBalance(playerUUID);
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get player balance", e);
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal createDefaultPlayerBalance(UUID playerUUID) {
        BigDecimal defaultBalance = new BigDecimal("0.00");
        try {
            String sql = "INSERT INTO player_balances (uuid, balance) VALUES (?, ?)";
            try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setBigDecimal(2, defaultBalance);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to create default balance for player UUID: " + playerUUID, e);
        }
        return defaultBalance;
    }

    /**
     * Executes a query that returns a single row as a Map of column names to values
     * @param sql The SQL query to execute
     * @param params The parameters to set in the prepared statement
     * @return A Map containing the column names and values of the first row, or null if no rows were returned
     */
    public Map<String, Object> queryRow(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            // Set parameters
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    Map<String, Object> row = new HashMap<>();
                    
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        Object value = resultSet.getObject(i);
                        row.put(columnName, value);
                    }
                    return row;
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to execute query: " + sql, e);
        }
        return null;
    }
    
    public String queryString(String sql, Object... params) {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to execute query: " + sql, e);
        }
        return null;
    }

    public record PlayerFlightData(boolean allowFlight, boolean isFlying) {}
    
    /**
     * Retrieves a player's flight status from the database
     * @param playerUUID The UUID of the player
     * @return PlayerFlightData containing flight status, or null if not found
     */
    public PlayerFlightData getPlayerFlightStatus(UUID playerUUID) {
        String sql = "SELECT allow_flight, is_flying FROM player_data WHERE uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    boolean allowFlight = rs.getBoolean("allow_flight");
                    boolean isFlying = rs.getBoolean("is_flying");
                    return new PlayerFlightData(allowFlight, isFlying);
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get flight status for player: " + playerUUID, e);
        }
        return null;
    }

    public record PlayerLastSeenData(java.sql.Timestamp lastSeenDate) {}


    public PlayerLastSeenData getPlayerLastSeen(UUID playerUUID) {
        try (Connection connection = getConnection()) {
            // Check and add column if needed in a separate connection
            if (!columnExists(connection, "player_data", "last_seen_date")) {
                addLastSeenDateColumn(connection);
            }
            
            // Use a new prepared statement for the query
            String sql = "SELECT last_seen_date FROM player_data WHERE uuid = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet resultSet = stmt.executeQuery()) {
                    if (resultSet.next()) {
                        java.sql.Timestamp lastSeenDate = resultSet.getTimestamp("last_seen_date");
                        return new PlayerLastSeenData(lastSeenDate);
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(WARN, "Failed to get last seen date for player UUID: " + playerUUID, e);
        }
        return null;
    }

    /**
     * Adds the last_seen_date column to the player_data table if it doesn't exist
     * @param connection The database connection to use
     * @throws SQLException If a database error occurs
     */
    private void addLastSeenDateColumn(Connection connection) throws SQLException {
        if (!columnExists(connection, "player_data", "last_seen_date")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("ALTER TABLE player_data ADD COLUMN last_seen_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                Text.sendDebugLog(INFO, "Added last_seen_date column to player_data table");
            }
        }
    }

    public boolean updatePlayerLastSeen(UUID playerUUID, String playerName) {
        final int MAX_RETRIES = 2;
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try (Connection connection = dataSource.getConnection()) {
                attempt++;
                Text.sendDebugLog(INFO, String.format("Updating last seen for player: %s (attempt %d/%d)", 
                    playerName, attempt, MAX_RETRIES));
                
                // Check if the last_seen_date column exists
                if (!columnExists(connection, "player_data", "last_seen_date")) {
                    addLastSeenDateColumn(connection);
                }
                
                String sql = "MERGE INTO player_data (uuid, name, last_seen_date, last_updated) " +
                        "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
                                
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerUUID.toString());
                    statement.setString(2, playerName);
                    int rowsAffected = statement.executeUpdate();
                    boolean success = rowsAffected > 0;
                    
                    if (success) {
                        Text.sendDebugLog(INFO, "Successfully updated last seen for player: " + playerName);
                        return true;
                    }
                }
            } catch (SQLException e) {
                Text.sendDebugLog(WARN, String.format("Attempt %d/%d - Failed to update last seen for player: %s", 
                    attempt, MAX_RETRIES, playerName), e);
                
                // If we've exhausted all retries, log the final failure
                if (attempt >= MAX_RETRIES) {
                    Text.sendDebugLog(ERROR, "All retry attempts failed for player: " + playerName, e);
                    return false;
                }
                
                // Small delay before retry
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Unexpected error updating last seen for player: " + playerName, e);
                return false;
            }
        }
        
        // Check if we need to add inventory columns
        String[] requiredColumns = {"survival_inventory", "survival_armor", "survival_offhand", 
                                "creative_inventory", "creative_armor", "creative_offhand"};
        for (String column : requiredColumns) {
            if (!columnExists("player_inventories", column)) {
                try (Statement statement = getConnection().createStatement()) {
                    statement.executeUpdate("ALTER TABLE player_inventories ADD COLUMN " + column + " BLOB");
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Error adding column " + column + " to player_inventories table", e);
                    // Continue with the next column even if one fails
                }
            }
        }
        
        return false;
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database connection pool is not available");
        }
        
        SQLException lastException = null;
        int maxRetries = 3;
        int retryDelayMs = 100; // Start with 100ms delay
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return dataSource.getConnection();
            } catch (SQLTransientConnectionException e) {
                lastException = e;
                // Log the connection acquisition failure
                plugin.getLogger().warning(String.format("[Attempt %d/%d] Failed to acquire database connection: %s", 
                    attempt + 1, maxRetries, e.getMessage()));
                
                if (attempt < maxRetries - 1) {
                    try {
                        // Exponential backoff: 100ms, 200ms, 400ms
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2; // Double the delay for next attempt
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Connection retry interrupted", ie);
                    }
                }
            } catch (SQLException e) {
                // Log other SQL exceptions
                plugin.getLogger().severe("Database error: " + e.getMessage());
                throw e;
            }
        }
        
        // If we've exhausted all retries, throw the last exception
        throw new SQLException("Unable to acquire database connection after " + maxRetries + " attempts - server may be under heavy load", lastException);
    }

    public void addPlayerNote(UUID playerUuid, UUID staffUuid, String note) {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(
                     "INSERT INTO player_notes (player_uuid, staff_uuid, note) VALUES (?, ?, ?)")) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, staffUuid != null ? staffUuid.toString() : "CONSOLE");
                stmt.setString(3, note);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to add player note", e);
        }
    }

    public List<NoteEntry> getPlayerNotes(UUID playerUuid, UUID staffUuid) {
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes WHERE player_uuid = ?" +
                (staffUuid != null ? " AND staff_uuid = ?" : "") +
                " ORDER BY created_at DESC";
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, playerUuid.toString());
                if (staffUuid != null) {
                    stmt.setString(2, staffUuid.toString());
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        notes.add(new NoteEntry(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                UUID.fromString(rs.getString("staff_uuid")),
                                rs.getString("note"),
                                rs.getTimestamp("created_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get player notes", e);
        }
        return notes;
    }

    public List<NoteEntry> getAllNotes(UUID staffUuid) {
        List<NoteEntry> notes = new ArrayList<>();
        String query = staffUuid != null ?
                "SELECT * FROM player_notes WHERE staff_uuid = ? ORDER BY created_at DESC" :
                "SELECT * FROM player_notes ORDER BY created_at DESC";
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                if (staffUuid != null) {
                    stmt.setString(1, staffUuid.toString());
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        notes.add(new NoteEntry(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                UUID.fromString(rs.getString("staff_uuid")),
                                rs.getString("note"),
                                rs.getTimestamp("created_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get all notes", e);
        }
        return notes;
    }

    public boolean deleteNote(int noteId, UUID staffUuid, boolean isAdmin) throws SQLException {
        try (PreparedStatement statement = getConnection().prepareStatement(
                "DELETE FROM player_notes WHERE id = ? AND (staff_uuid = ? OR ? = true)")) {
            statement.setInt(1, noteId);
            statement.setString(2, staffUuid != null ? staffUuid.toString() : null);
            statement.setBoolean(3, isAdmin);
            return statement.executeUpdate() > 0;
        }
    }

    public List<NoteEntry> getNotesForPlayer(UUID playerUuid) throws SQLException {
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes WHERE player_uuid = ? ORDER BY created_at DESC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, playerUuid.toString());
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                notes.add(new NoteEntry(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("player_uuid")),
                        UUID.fromString(rs.getString("staff_uuid")),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ));
            }
        }
        return notes;
    }

    public List<NoteEntry> getAllNotes() throws SQLException {
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes ORDER BY created_at DESC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                notes.add(new NoteEntry(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("player_uuid")),
                        UUID.fromString(rs.getString("staff_uuid")),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ));
            }
        }
        return notes;
    }

    public NoteEntry getNoteById(int noteId) throws SQLException {
        String sql = "SELECT * FROM player_notes WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, noteId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new NoteEntry(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("player_uuid")),
                        UUID.fromString(rs.getString("staff_uuid")),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                );
            }
        }
        return null;
    }

    public void savePlayerGameMode(UUID uuid, String playerName, GameMode gameMode) throws SQLException {
        String sql = "MERGE INTO player_data (uuid, name, gamemode, last_updated) " +
                "KEY (uuid) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, playerName);
            stmt.setString(3, gameMode != null ? gameMode.name() : null);
            stmt.executeUpdate();
        }
    }

    public GameMode getPlayerGameMode(UUID uuid) throws SQLException {
        String sql = "SELECT gamemode FROM player_data WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String gameModeString = rs.getString("gamemode");
                    if (gameModeString != null && !gameModeString.isEmpty()) {
                        try {
                            return GameMode.valueOf(gameModeString.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            Text.sendDebugLog(WARN, "Invalid gamemode in player_data for UUID " + uuid + ": " + gameModeString, e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to execute query: " + sql, e);
            return null;
        }
        return null;
    }

    public void saveLastMessageSender(UUID playerId, UUID senderId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO player_last_message_senders (player_uuid, sender_uuid) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE sender_uuid = ?")) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, senderId.toString());
            stmt.setString(3, senderId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error saving last message sender", e);
        }
    }

    public boolean playerAccountExists(UUID playerUUID) {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM player_balances WHERE uuid = ?")) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to check player account existence", e);
            return false;
        }
    }
    
    /**
     * Executes a query and processes the ResultSet with the provided handler
     * @param sql The SQL query to execute
     * @param handler The handler to process the ResultSet
     * @param params The parameters for the prepared statement
     * @return true if the query was executed successfully, false otherwise
     */
    public boolean executeQuery(String sql, ThrowingConsumer<ResultSet> handler, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            
            // Set parameters
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                handler.accept(rs);
                return true;
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to execute query: " + sql, e);
            return false;
        }
    }
    
    /**
     * Functional interface for handling ResultSet with checked exceptions
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws SQLException;
    }

    // ==================== WARP METHODS ====================

    /**
     * Creates or updates a warp location
     */
    public boolean setWarp(String warpName, Location location, UUID creatorUUID, String creatorName) {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(
                     "INSERT INTO warps (name, world, x, y, z, yaw, pitch, creator_uuid, creator_name, last_updated) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "world = VALUES(world), x = VALUES(x), y = VALUES(y), z = VALUES(z), " +
                     "yaw = VALUES(yaw), pitch = VALUES(pitch), last_updated = CURRENT_TIMESTAMP")) {
            
            stmt.setString(1, warpName.toLowerCase());
            stmt.setString(2, location.getWorld().getName());
            stmt.setDouble(3, location.getX());
            stmt.setDouble(4, location.getY());
            stmt.setDouble(5, location.getZ());
            stmt.setFloat(6, location.getYaw());
            stmt.setFloat(7, location.getPitch());
            stmt.setString(8, creatorUUID.toString());
            stmt.setString(9, creatorName);
            
            return stmt.executeUpdate() > 0;
        }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to set warp: " + warpName, e);
            return false;
        }
    }

    /**
     * Gets a warp location by name
     */
    public Location getWarp(String warpName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM warps WHERE name = ?")) {
            
            stmt.setString(1, warpName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String worldName = rs.getString("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        Text.sendDebugLog(WARN, "World '" + worldName + "' for warp '" + warpName + "' does not exist!");
                        return null;
                    }
                    
                    return new Location(
                            world,
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch")
                    );
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get warp: " + warpName, e);
        }
        return null;
    }

    /**
     * Gets warp information including creator and creation date
     */
    public Map<String, Object> getWarpInfo(String warpName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT * FROM warps WHERE name = ?")) {
            
            stmt.setString(1, warpName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", rs.getString("name"));
                    info.put("world", rs.getString("world"));
                    info.put("x", rs.getDouble("x"));
                    info.put("y", rs.getDouble("y"));
                    info.put("z", rs.getDouble("z"));
                    info.put("yaw", rs.getFloat("yaw"));
                    info.put("pitch", rs.getFloat("pitch"));
                    info.put("creator_uuid", rs.getString("creator_uuid"));
                    info.put("creator_name", rs.getString("creator_name"));
                    info.put("is_public", rs.getBoolean("is_public"));
                    info.put("created_at", rs.getTimestamp("created_at"));
                    info.put("last_updated", rs.getTimestamp("last_updated"));
                    return info;
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get warp info: " + warpName, e);
        }
        return null;
    }

    /**
     * Gets all warp names
     */
    public List<String> getAllWarpNames() {
        List<String> warps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT name FROM warps ORDER BY name")) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    warps.add(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get all warp names", e);
        }
        return warps;
    }

    /**
     * Gets all warp information
     */
    public List<Map<String, Object>> getAllWarps() {
        List<Map<String, Object>> warps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT * FROM warps ORDER BY name")) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", rs.getString("name"));
                    info.put("world", rs.getString("world"));
                    info.put("x", rs.getDouble("x"));
                    info.put("y", rs.getDouble("y"));
                    info.put("z", rs.getDouble("z"));
                    info.put("yaw", rs.getFloat("yaw"));
                    info.put("pitch", rs.getFloat("pitch"));
                    info.put("creator_uuid", rs.getString("creator_uuid"));
                    info.put("creator_name", rs.getString("creator_name"));
                    info.put("is_public", rs.getBoolean("is_public"));
                    info.put("created_at", rs.getTimestamp("created_at"));
                    info.put("last_updated", rs.getTimestamp("last_updated"));
                    warps.add(info);
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to get all warps", e);
        }
        return warps;
    }

    /**
     * Checks if a warp exists
     */
    public boolean warpExists(String warpName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM warps WHERE name = ?")) {
            
            stmt.setString(1, warpName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to check warp existence: " + warpName, e);
            return false;
        }
    }

    /**
     * Deletes a warp
     */
    public boolean deleteWarp(String warpName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement("DELETE FROM warps WHERE name = ?")) {
            
            stmt.setString(1, warpName.toLowerCase());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to delete warp: " + warpName, e);
            return false;
        }
    }

    /**
     * Checks if a table exists in the database
     * @param connection The database connection to use (can be null to create a new one)
     * @param tableName The name of the table to check
     * @return true if the table exists, false otherwise
     */
    private boolean tableExists(Connection connection, String tableName) {
        boolean shouldClose = false;
        try {
            if (connection == null || connection.isClosed()) {
                connection = dataSource.getConnection();
                shouldClose = true;
            }
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), null)) {
                return tables.next();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error checking if table exists: " + tableName, e);
            return false;
        } finally {
            if (shouldClose && connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Error closing connection", e);
                }
            }
        }
    }
    
    /**
     * Checks if a column exists in a table
     * @param connection The database connection to use (can be null to create a new one)
     * @param tableName The name of the table to check
     * @param columnName The name of the column to check
     * @return true if the column exists, false otherwise
     */
    private boolean columnExists(Connection connection, String tableName, String columnName) {
        boolean shouldClose = false;
        try {
            if (connection == null || connection.isClosed()) {
                connection = dataSource.getConnection();
                shouldClose = true;
            }
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
                return columns.next();
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, String.format("Error checking if column %s exists in table %s", columnName, tableName), e);
            return false;
        } finally {
            if (shouldClose && connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    Text.sendDebugLog(ERROR, "Error closing connection", e);
                }
            }
        }
    }
    
    /**
     * Checks if a column exists in a table (creates a new connection)
     * @param tableName The name of the table to check
     * @param columnName The name of the column to check
     * @return true if the column exists, false otherwise
     */
    private boolean columnExists(String tableName, String columnName) {
        return columnExists(null, tableName, columnName);
    }
    
    /**
     * Saves an inventory snapshot to the database with proper connection handling and retry logic
     * @param snapshot The inventory snapshot to save
     * @return true if the save was successful, false otherwise
     */
    public boolean saveInventorySnapshot(InventorySnapshot snapshot) {
        if (snapshot == null) {
            Text.sendDebugLog(WARN, "Attempted to save null inventory snapshot");
            return false;
        }

        final int MAX_RETRIES = 3;
        final long RETRY_DELAY_MS = 1000; // 1 second delay between retries
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (Connection conn = getConnection()) {
                // Set a reasonable timeout for this operation
                conn.setNetworkTimeout(null, (int) TimeUnit.SECONDS.toMillis(10));
                
                Text.sendDebugLog(INFO, String.format("Saving inventory snapshot for player: %s, reason: %s (attempt %d/%d)",
                    snapshot.getPlayerId(), snapshot.getReason(), attempt, MAX_RETRIES));

                // First, verify the table exists
                if (!tableExists(conn, "inventory_snapshots")) {
                    Text.sendDebugLog(WARN, "inventory_snapshots table does not exist! Creating it now...");
                    createTables(conn);
                }

                String sql = "INSERT INTO inventory_snapshots (player_uuid, timestamp, reason, inventory_data, " +
                            "armor_data, offhand_data, enderchest_data, experience, world, x, y, z, yaw, pitch) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    Location loc = snapshot.getLocation();
                    stmt.setString(1, snapshot.getPlayerId().toString());
                    stmt.setLong(2, snapshot.getTimestamp());
                    stmt.setString(3, snapshot.getReason());
                    stmt.setString(4, snapshot.getInventoryData());
                    stmt.setString(5, snapshot.getArmorData());
                    stmt.setString(6, snapshot.getOffhandData() != null ? snapshot.getOffhandData() : "");
                    stmt.setString(7, snapshot.getEnderChestData() != null ? snapshot.getEnderChestData() : "");
                    stmt.setInt(8, snapshot.getExperience());
                    stmt.setString(9, snapshot.getWorldName());
                    stmt.setDouble(10, loc.getX());
                    stmt.setDouble(11, loc.getY());
                    stmt.setDouble(12, loc.getZ());
                    stmt.setFloat(13, loc.getYaw());
                    stmt.setFloat(14, loc.getPitch());

                    int affectedRows = stmt.executeUpdate();
                    if (affectedRows > 0) {
                        Text.sendDebugLog(INFO, "Successfully saved inventory snapshot for player: " + snapshot.getPlayerId());
                        return true;
                    } else {
                        Text.sendDebugLog(WARN, "No rows affected when saving inventory snapshot for " + snapshot.getPlayerId());
                    }
                }
            } catch (SQLException e) {
                Text.sendDebugLog(ERROR, String.format("Attempt %d/%d - Database error saving inventory snapshot for player: %s - %s", 
                    attempt, MAX_RETRIES, snapshot.getPlayerId(), e.getMessage()));
                
                // If we've exhausted all retries, log the final failure
                if (attempt >= MAX_RETRIES) {
                    Text.sendDebugLog(ERROR, "All retry attempts failed for player: " + 
                        snapshot.getPlayerId() + 
                        ". Last error: " + e.getMessage());
                    return false;
                }
                
                // Small delay before retry with exponential backoff
                try {
                    long delay = RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1);
                    Thread.sleep(Math.min(delay, 5000)); // Cap at 5 seconds
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Text.sendDebugLog(WARN, "Thread interrupted while waiting to retry inventory save for " + 
                        snapshot.getPlayerId());
                    return false;
                }
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Unexpected error saving inventory snapshot for player: " + 
                    snapshot.getPlayerId() + 
                    ". Error: " + e.getMessage());
                return false;
            }
        }
        
        Text.sendDebugLog(ERROR, "Failed to save inventory snapshot for " + 
            snapshot.getPlayerId() + " after " + MAX_RETRIES + " attempts");
        return false;
    }

    /**
     * Loads all inventory snapshots for a player, ordered by timestamp (newest first)
     * @param playerId The UUID of the player
     * @return List of InventorySnapshot objects, or empty list if none found
     */
    public List<InventorySnapshot> getPlayerInventorySnapshots(UUID playerId) {
        List<InventorySnapshot> snapshots = new ArrayList<>();
        String sql = "SELECT * FROM inventory_snapshots WHERE player_uuid = ? ORDER BY timestamp DESC";
        
        Text.sendDebugLog(INFO, "=== Starting to fetch inventory snapshots for player: " + playerId + " ===");
        
        try (Connection conn = getConnection()) {
            // First, verify the table exists
            if (!tableExists(conn, "inventory_snapshots")) {
                Text.sendDebugLog(WARN, "inventory_snapshots table does not exist! Creating it now...");
                createTables(conn);
                return snapshots; // Return empty list as we just created the table
            }

            Text.sendDebugLog(INFO, "Executing SQL: " + sql + " with playerId: " + playerId);
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    int rowCount = 0;
                    
                    while (rs.next()) {
                        rowCount++;
                        try {
                            // Log all column names for debugging
                            ResultSetMetaData meta = rs.getMetaData();
                            int columnCount = meta.getColumnCount();
                            Text.sendDebugLog(INFO, "Available columns in result set:");
                            for (int i = 1; i <= columnCount; i++) {
                                Text.sendDebugLog(INFO, "  " + i + ". " + meta.getColumnName(i) + 
                                    " (" + meta.getColumnTypeName(i) + "): " + rs.getObject(i));
                            }
                            
                            String worldNameFromDb = rs.getString("world");
                            World world = Bukkit.getWorld(worldNameFromDb);
                            if (world == null) {
                                Text.sendDebugLog(WARN, "World not found for snapshot: " + worldNameFromDb + ". Available worlds: " + 
                                    String.join(", ", Bukkit.getWorlds().stream().map(org.bukkit.World::getName).collect(java.util.stream.Collectors.toList())));
                                // Continue with default world if specified world not found
                                world = Bukkit.getWorlds().get(0);
                            }
                            
                            Location location = new Location(
                                world,
                                rs.getDouble("x"),
                                rs.getDouble("y"),
                                rs.getDouble("z"),
                                rs.getFloat("yaw"),
                                rs.getFloat("pitch")
                            );
                            
                            // Get world name from the location or use the one from the database
                            String worldName = (location.getWorld() != null) ? 
                                             location.getWorld().getName() : 
                                             rs.getString("world");
                            
                            // Get required fields with null checks
                            String inventoryData = rs.getString("inventory_data");
                            String armorData = rs.getString("armor_data");
                            String offhandData = null;
                            String enderChestData = null;
                            
                            // Check if offhand_data column exists before accessing it
                            try {
                                offhandData = rs.getString("offhand_data");
                            } catch (SQLException e) {
                                // Column doesn't exist, use empty string for backward compatibility
                                offhandData = "";
                                Text.sendDebugLog(WARN, "offhand_data column not found, using empty string for backward compatibility");
                            }

                            try {
                                enderChestData = rs.getString("enderchest_data");
                            } catch (SQLException e) {
                                enderChestData = "";
                                Text.sendDebugLog(WARN, "enderchest_data column not found, using empty string for backward compatibility");
                            }
                            
                            String reason = rs.getString("reason");
                            long timestamp = rs.getLong("timestamp");
                            int experience = rs.getInt("experience");
                            
                            Text.sendDebugLog(INFO, "Creating snapshot with data - " +
                                "reason: " + reason + ", " +
                                "timestamp: " + timestamp + ", " +
                                "world: " + worldName + ", " +
                                "exp: " + experience + ", " +
                                "inventoryData length: " + (inventoryData != null ? inventoryData.length() : "null") + ", " +
                                "armorData length: " + (armorData != null ? armorData.length() : "null") + ", " +
                                "offhandData length: " + (offhandData != null ? offhandData.length() : "null"));
                            
                            InventorySnapshot snapshot = new InventorySnapshot(
                                playerId,
                                timestamp,
                                reason,
                                inventoryData,
                                armorData,
                                offhandData != null ? offhandData : "", // Use empty string for backward compatibility
                                enderChestData != null ? enderChestData : "",
                                experience,
                                location,
                                worldName
                            );
                            
                            snapshots.add(snapshot);
                            Text.sendDebugLog(INFO, "Successfully loaded snapshot: " + 
                                              "id=" + rs.getLong("id") + 
                                              ", reason=" + reason + 
                                              ", timestamp=" + timestamp);
                                              
                            // Verify the snapshot data is valid
                            try {
                                ItemStack[] invContents = snapshot.getInventoryContents();
                                ItemStack[] armorContents = snapshot.getArmorContents();
                                Text.sendDebugLog(INFO, "Snapshot validation - " +
                                    "Inventory items: " + (invContents != null ? invContents.length : 0) + ", " +
                                    "Armor items: " + (armorContents != null ? armorContents.length : 0));
                            } catch (Exception e) {
                                Text.sendDebugLog(ERROR, "Error validating snapshot data", e);
                            }
                            
                        } catch (Exception e) {
                            Text.sendDebugLog(ERROR, "Error processing inventory snapshot row " + rowCount, e);
                        }
                    }
                    
                    Text.sendDebugLog(INFO, "Found " + rowCount + " snapshots for player " + playerId);
                    
                    if (rowCount == 0) {
                        // Check if the player exists in the database
                        try (PreparedStatement checkPlayer = dataSource.getConnection().prepareStatement(
                                "SELECT name FROM player_data WHERE uuid = ?")) {
                            checkPlayer.setString(1, playerId.toString());
                            try (ResultSet playerRs = checkPlayer.executeQuery()) {
                                if (playerRs.next()) {
                                    Text.sendDebugLog(INFO, "Player " + playerRs.getString("name") + " exists but has no snapshots");
                                } else {
                                    Text.sendDebugLog(WARN, "Player " + playerId + " not found in player_data table");
                                }
                            }
                        } catch (SQLException e) {
                            Text.sendDebugLog(WARN, "Error checking player existence", e);
                        }
                        
                        // Check if there are any snapshots in the database at all
                        try (Statement checkSnapshots = conn.createStatement();
                             ResultSet snapshotRs = checkSnapshots.executeQuery("SELECT COUNT(*) as total FROM inventory_snapshots")) {
                            if (snapshotRs.next()) {
                                int totalSnapshots = snapshotRs.getInt("total");
                                Text.sendDebugLog(INFO, "Total snapshots in database: " + totalSnapshots);
                            }
                        } catch (SQLException e) {
                            Text.sendDebugLog(WARN, "Error checking total snapshots", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Error loading inventory snapshots for player: " + playerId, e);
        }
        
        return snapshots;
    }
    
    /**
     * Deletes an inventory snapshot by its ID
     * @param snapshotId The ID of the snapshot to delete
     * @return true if the snapshot was deleted, false otherwise
     */
    public boolean deleteInventorySnapshot(long snapshotId) {
        String sql = "DELETE FROM inventory_snapshots WHERE id = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, snapshotId);
            return stmt.executeUpdate() > 0;
            
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to delete inventory snapshot: " + snapshotId, e);
            return false;
        }
    }
    
    /**
     * Deletes all inventory snapshots older than the specified timestamp
     * @param olderThan The timestamp in milliseconds
     * @return Number of snapshots deleted
     */
    public int cleanupOldSnapshots(long olderThan) {
        String sql = "DELETE FROM inventory_snapshots WHERE timestamp < ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, olderThan);
            return stmt.executeUpdate();
            
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to clean up old inventory snapshots", e);
            return 0;
        }
    }

    /**
     * Saves a restrained player to the database for restoration after restart
     */
    public void saveRestrainedPlayer(UUID restrainedUUID, UUID handcufferUUID, Location handcufferLocation) throws SQLException, IOException {
        String serializedLocation = serializeLocation(handcufferLocation);
        String sql = "INSERT INTO restrained_players (restrained_uuid, handcuffer_uuid, handcuffer_location, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, restrainedUUID.toString());
            stmt.setString(2, handcufferUUID.toString());
            stmt.setString(3, serializedLocation);
            stmt.executeUpdate();
        }
        Text.sendDebugLog(INFO, "Saved restrained player " + restrainedUUID + " with handcuffer " + handcufferUUID + " to database");
    }

    public Map<String, Object> getSpawnLocation() {
        Map<String, Object> locationData = new HashMap<>();
        String query = "SELECT world, x, y, z, yaw, pitch FROM spawn_locations LIMIT 1";
        
        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            ResultSet rs = stmt.executeQuery()) {
            
            if (rs.next()) {
                locationData.put("world", rs.getString("world"));
                locationData.put("x", rs.getDouble("x"));
                locationData.put("y", rs.getDouble("y"));
                locationData.put("z", rs.getDouble("z"));
                locationData.put("yaw", rs.getFloat("yaw"));
                locationData.put("pitch", rs.getFloat("pitch"));
                return locationData;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get spawn location from database", e);
        }
        
        return locationData; // Returns empty map if no spawn location is set or an error occurs
    }

    /**
     * Loads all restrained players from the database
     */
    public List<RestrainedPlayerData> loadRestrainedPlayers() throws SQLException, IOException, ClassNotFoundException {
        List<RestrainedPlayerData> restrainedPlayers = new ArrayList<>();
        String sql = "SELECT restrained_uuid, handcuffer_uuid, handcuffer_location FROM restrained_players";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID restrainedUUID = UUID.fromString(rs.getString("restrained_uuid"));
                UUID handcufferUUID = UUID.fromString(rs.getString("handcuffer_uuid"));
                String serializedLocation = rs.getString("handcuffer_location");
                Location handcufferLocation = deserializeLocation(serializedLocation);

                restrainedPlayers.add(new RestrainedPlayerData(restrainedUUID, handcufferUUID, handcufferLocation));
            }
        }
        Text.sendDebugLog(INFO, "Loaded " + restrainedPlayers.size() + " restrained players from database");
        return restrainedPlayers;
    }

    /**
     * Removes a restrained player from the database
     */
    public void removeRestrainedPlayer(UUID restrainedUUID) throws SQLException {
        String sql = "DELETE FROM restrained_players WHERE restrained_uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, restrainedUUID.toString());
            stmt.executeUpdate();
        }
        Text.sendDebugLog(INFO, "Removed restrained player " + restrainedUUID + " from database");
    }

    /**
     * Clears all restrained players from the database (for cleanup)
     */
    public void clearAllRestrainedPlayers() throws SQLException {
        String sql = "DELETE FROM restrained_players";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }
    /**
     * Cleans up restrained player records that are older than 1 day (24 hours)
     * and executes commands-on-quit for each old record
     * @return The number of old records that were cleaned up
     */
    public int cleanupOldRestrainedPlayers() throws SQLException, IOException, ClassNotFoundException {
        List<RestrainedPlayerData> oldRecords = new ArrayList<>();

        // Get records older than 1 day (24 hours)
        String sql = "SELECT restrained_uuid, handcuffer_uuid, handcuffer_location, created_at FROM restrained_players " +
                    "WHERE created_at < DATEADD('DAY', -1, CURRENT_TIMESTAMP)";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID restrainedUUID = UUID.fromString(rs.getString("restrained_uuid"));
                UUID handcufferUUID = UUID.fromString(rs.getString("handcuffer_uuid"));
                String serializedLocation = rs.getString("handcuffer_location");
                Location handcufferLocation = deserializeLocation(serializedLocation);

                oldRecords.add(new RestrainedPlayerData(restrainedUUID, handcufferUUID, handcufferLocation));
            }
        }

        Text.sendDebugLog(INFO, "Found " + oldRecords.size() + " old restrained player records to cleanup");

        // Process each old record
        for (RestrainedPlayerData oldRecord : oldRecords) {
            // Execute commands-on-quit for this restrained player
            executeCommandsOnQuitForOfflinePlayer(oldRecord.getRestrainedUUID(), oldRecord.getHandcufferUUID());

            // Remove the record from database
            removeRestrainedPlayer(oldRecord.getRestrainedUUID());
        }

        return oldRecords.size();
    }

    /**
     * Executes commands-on-quit for an offline restrained player
     * This simulates the executeCommandsOnQuit method but for offline players
     */
    public void executeCommandsOnQuitForOfflinePlayer(UUID restrainedUUID, UUID handcufferUUID) {
        // This is a simplified version since we don't have the full player context
        // We just log that the commands would be executed for cleanup purposes
        Text.sendDebugLog(INFO, "Would execute commands-on-quit for offline restrained player " + restrainedUUID +
                               " (handcuffed by " + handcufferUUID + ") - record cleanup");
    }

    /**
     * Serializes a Location to a string for database storage
     */
    private String serializeLocation(Location location) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeUTF(location.getWorld().getName());
        dos.writeDouble(location.getX());
        dos.writeDouble(location.getY());
        dos.writeDouble(location.getZ());
        dos.writeFloat(location.getYaw());
        dos.writeFloat(location.getPitch());

        dos.close();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Deserializes a Location from a string stored in database
     */
    private Location deserializeLocation(String serializedLocation) throws IOException, ClassNotFoundException {
        byte[] data = Base64.getDecoder().decode(serializedLocation);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        String worldName = dis.readUTF();
        double x = dis.readDouble();
        double y = dis.readDouble();
        double z = dis.readDouble();
        float yaw = dis.readFloat();
        float pitch = dis.readFloat();

        dis.close();

        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            return new Location(world, x, y, z, yaw, pitch);
        }
        return null;
    }

    /**
     * Data class for restrained player information
     */
    public static class RestrainedPlayerData {
        private final UUID restrainedUUID;
        private final UUID handcufferUUID;
        private final Location handcufferLocation;

        public RestrainedPlayerData(UUID restrainedUUID, UUID handcufferUUID, Location handcufferLocation) {
            this.restrainedUUID = restrainedUUID;
            this.handcufferUUID = handcufferUUID;
            this.handcufferLocation = handcufferLocation;
        }

        public UUID getRestrainedUUID() {
            return restrainedUUID;
        }

        public UUID getHandcufferUUID() {
            return handcufferUUID;
        }

        public Location getHandcufferLocation() {
            return handcufferLocation;
        }
    }

    /**
     * Sets the spawn location in the database.
     * @param world The world name
     * @param x The x-coordinate
     * @param y The y-coordinate
     * @param z The z-coordinate
     * @param yaw The yaw rotation
     * @param pitch The pitch rotation
     * @return true if the operation was successful, false otherwise
     */
    public boolean setSpawnLocation(String world, double x, double y, double z, float yaw, float pitch) {
        String query = "INSERT INTO spawn_locations (world, x, y, z, yaw, pitch) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "world = VALUES(world), " +
                    "x = VALUES(x), " +
                    "y = VALUES(y), " +
                    "z = VALUES(z), " +
                    "yaw = VALUES(yaw), " +
                    "pitch = VALUES(pitch)";
        
        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(query)) {
            
            stmt.setString(1, world);
            stmt.setDouble(2, x);
            stmt.setDouble(3, y);
            stmt.setDouble(4, z);
            stmt.setFloat(5, yaw);
            stmt.setFloat(6, pitch);
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
            
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to set spawn location", e);
            return false;
        }
    }
    
    /**
     * Loads player inventories from the database
     * @param playerUUID The UUID of the player
     * @return PlayerInventories object containing all inventory data, or null if not found
     */
    public PlayerInventories loadPlayerInventories(UUID playerUUID) {
        String sql = "SELECT survival_inventory, survival_armor, survival_offhand, " +
                   "creative_inventory, creative_armor, creative_offhand " +
                   "FROM player_inventories WHERE uuid = ?";
                   
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUUID.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Instead of creating an empty object and using setters, create it with all fields
                    return new PlayerInventories(
                        deserializeItemStacks(rs.getBytes("survival_inventory")),
                        deserializeItemStacks(rs.getBytes("survival_armor")),
                        deserializeItemStack(rs.getBytes("survival_offhand")),
                        deserializeItemStacks(rs.getBytes("creative_inventory")),
                        deserializeItemStacks(rs.getBytes("creative_armor")),
                        deserializeItemStack(rs.getBytes("creative_offhand"))
                    );
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to load player inventories for " + playerUUID, e);
        }
        
        return null;
    }
    
    /**
     * Deserializes a single ItemStack from a byte array
     * @param data The serialized ItemStack data
     * @return The deserialized ItemStack, or null if data is null or empty
     */
    private ItemStack deserializeItemStack(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
             DataInputStream dataInput = new DataInputStream(inputStream)) {
            
            // Read the serialized data and convert back to ItemStack
            // This is a placeholder - you'll need to implement the actual deserialization
            // based on how your serialization works
            return ItemStack.deserializeBytes(data);
            
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to deserialize ItemStack", e);
            return null;
        }
    }
    
    /**
     * Deserializes gift items from Base64 encoded data
     * @param base64Data The Base64 encoded item data
     * @return Array of deserialized ItemStacks, or empty array if data is invalid
     */
    public ItemStack[] deserializeGiftItems(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return new ItemStack[0];
        }
        try {
            byte[] data = Base64.getDecoder().decode(base64Data);
            return deserializeItemStacks(data);
        } catch (IllegalArgumentException e) {
            return new ItemStack[0];
        }
    }
    
    private ItemStack[] deserializeItemStacks(byte[] data) {
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
             DataInputStream dataInput = new DataInputStream(inputStream)) {
            
            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];
            
            for (int i = 0; i < size; i++) {
                try {
                    items[i] = ItemStack.deserializeBytes(dataInput.readAllBytes());
                } catch (Exception e) {
                    items[i] = null;
                }
            }
            
            return items;
            
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to deserialize ItemStack array", e);
            return new ItemStack[0];
        }
    }
}
