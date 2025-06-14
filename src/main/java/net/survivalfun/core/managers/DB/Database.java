package net.survivalfun.core.managers.DB;

import net.survivalfun.core.PluginStart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class Database {
    private final PluginStart plugin;
    private Connection connection;
    private final String dbUrl;

    public Database (PluginStart plugin) {
        this.plugin = plugin;

        // Create database directory if it doesn't exist
        File dataFolder = new File(plugin.getDataFolder(), "database");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // H2 database URL - file mode
        this.dbUrl = "jdbc:h2:" + dataFolder.getAbsolutePath() + "/sfcore;MODE=MySQL";

        // Initialize the database
        initialize();
    }

    private void initialize() {
        try {
            // Load H2 JDBC driver
            Class.forName("org.h2.Driver");

            // Open connection
            openConnection();

            // Create tables if they don't exist and run migrations
            createTables();

        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "H2 JDBC driver not found!", e);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database!", e);
        }
    }

    private void openConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }

        connection = DriverManager.getConnection(dbUrl, "sa", "");
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Spawn location table
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

            // Player data table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_data (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "is_flying BOOLEAN DEFAULT FALSE, " +
                            "allow_flight BOOLEAN DEFAULT FALSE, " +
                            "last_seen_date TIMESTAMP, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
            
            // Run migrations to add any missing columns to existing tables
            runMigrations();

            // Create player inventory table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_inventories (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "survival_inventory CLOB, " +
                            "survival_armor CLOB, " +
                            "survival_offhand CLOB, " +
                            "creative_inventory CLOB, " +
                            "creative_armor CLOB, " +
                            "creative_offhand CLOB, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            // Create gamemode resets table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS gamemode_resets (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "reset_gamemode VARCHAR(20) NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
            // Create player effects table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_effects (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "has_slow_falling BOOLEAN DEFAULT FALSE, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
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
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_balances (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );



        }
    }

    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database connection", e);
        }
    }

    /**
     * Prepares a SQL statement with the given parameters
     *
     * @param sql    The SQL query with placeholders
     * @param params The parameters to substitute in the SQL query
     * @return The prepared statement
     * @throws SQLException If there's an error preparing the statement
     */
    private PreparedStatement prepareStatement(String sql, Object... params) throws SQLException {
        if (connection == null) {
            throw new SQLException("Database connection is null");
        }

        PreparedStatement stmt = connection.prepareStatement(sql);

        // Set parameters if any
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                stmt.setNull(i + 1, java.sql.Types.NULL);
            } else if (params[i] instanceof String) {
                stmt.setString(i + 1, (String) params[i]);
            } else if (params[i] instanceof Integer) {
                stmt.setInt(i + 1, (Integer) params[i]);
            } else if (params[i] instanceof Long) {
                stmt.setLong(i + 1, (Long) params[i]);
            } else if (params[i] instanceof Double) {
                stmt.setDouble(i + 1, (Double) params[i]);
            } else if (params[i] instanceof Float) {
                stmt.setFloat(i + 1, (Float) params[i]);
            } else if (params[i] instanceof Boolean) {
                stmt.setBoolean(i + 1, (Boolean) params[i]);
            } else if (params[i] instanceof byte[]) {
                stmt.setBytes(i + 1, (byte[]) params[i]);
            } else if (params[i] instanceof java.util.Date) {
                stmt.setTimestamp(i + 1, new java.sql.Timestamp(((java.util.Date) params[i]).getTime()));
            } else if (params[i] instanceof java.sql.Date) {
                stmt.setDate(i + 1, (java.sql.Date) params[i]);
            } else if (params[i] instanceof java.sql.Timestamp) {
                stmt.setTimestamp(i + 1, (java.sql.Timestamp) params[i]);
            } else {
                // For any other types, convert to string
                stmt.setString(i + 1, params[i].toString());
            }
        }

        return stmt;
    }

    /**
     * Interface to handle ResultSet processing
     */
    public interface ResultSetHandler {
        void handle(ResultSet resultSet) throws SQLException;
    }

    /**
     * Executes a query that returns a ResultSet (typically a SELECT statement)
     * and processes the results using the provided handler
     *
     * @param sql     The SQL query to execute
     * @param handler A callback that will process the ResultSet
     * @param params  Parameters to be inserted into the prepared statement
     */
    public void executeQuery(String sql, ResultSetHandler handler, Object... params) {
        if (connection == null) {
            plugin.getLogger().severe("Cannot execute query: Database connection is null");
            return;
        }

        PreparedStatement stmt = null;
        ResultSet resultSet = null;

        try {
            stmt = prepareStatement(sql, params);
            resultSet = stmt.executeQuery();
            handler.handle(resultSet);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error executing query: " + sql, e);
        } finally {
            // Clean up resources
            try {
                if (resultSet != null) resultSet.close();
                if (stmt != null) stmt.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing database resources", e);
            }
        }
    }


    /**
     * Executes an SQL query and returns the first row as a Map of column names to values
     *
     * @param sql The SQL query to execute
     * @param params The parameters to use in the query
     * @return A Map of column names to values, or null if no result
     */
    public Map<String, Object> queryRow(String sql, Object... params) {
        try {
            openConnection();

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                // Set parameters
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }

                // Execute the query
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        Map<String, Object> row = new HashMap<>();

                        // Get metadata to retrieve column names
                        java.sql.ResultSetMetaData metaData = resultSet.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        // Add each column to the map
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i).toLowerCase();
                            Object value = resultSet.getObject(i);
                            row.put(columnName, value);
                        }

                        return row;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to execute query: " + sql, e);
        }

        return null;
    }

    // Add these methods to save and retrieve slow falling status
    public void savePlayerSlowFallingStatus(UUID playerUUID, boolean hasSlowFalling) {
        try {
            openConnection();

            String sql = "MERGE INTO player_effects (uuid, has_slow_falling, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setBoolean(2, hasSlowFalling);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save slow falling status for player UUID: " + playerUUID, e);
        }
    }

    public boolean getPlayerSlowFallingStatus(UUID playerUUID) {
        try {
            openConnection();

            String sql = "SELECT has_slow_falling FROM player_effects WHERE uuid = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getBoolean("has_slow_falling");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get slow falling status for player UUID: " + playerUUID, e);
        }

        return false;
    }

    // Save player flight status
    public void savePlayerFlightStatus(Player player) {
        try {
            openConnection();

            String sql = "MERGE INTO player_data (uuid, name, is_flying, allow_flight, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player.getUniqueId().toString());
                statement.setString(2, player.getName());
                statement.setBoolean(3, player.isFlying());
                statement.setBoolean(4, player.getAllowFlight());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save flight status for player: " + player.getName(), e);
        }
    }

    // Get player flight status
    public PlayerFlightData getPlayerFlightStatus(UUID playerUUID) {
        try {
            openConnection();

            String sql = "SELECT is_flying, allow_flight FROM player_data WHERE uuid = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        boolean isFlying = resultSet.getBoolean("is_flying");
                        boolean allowFlight = resultSet.getBoolean("allow_flight");
                        return new PlayerFlightData(allowFlight, isFlying);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get flight status for player UUID: " + playerUUID, e);
        }

        return null;
    }

    /**
     * Executes an update query (INSERT, UPDATE, DELETE)
     *
     * @param sql    The SQL query to execute
     * @param params Parameters to be inserted into the prepared statement
     * @return The number of rows affected
     */
    public int executeUpdate(String sql, Object... params) {
        if (connection == null) {
            plugin.getLogger().severe("Cannot execute update: Database connection is null");
            return 0;
        }

        PreparedStatement stmt = null;
        int result = 0;

        try {
            stmt = prepareStatement(sql, params);
            result = stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error executing update: " + sql, e);
        } finally {
            // Clean up resources
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing statement", e);
            }
        }

        return result;
    }

    public BigDecimal getPlayerBalance(UUID playerUUID) {
        String sql = "SELECT balance FROM player_balances WHERE uuid = ?";
        BigDecimal[] balance = {BigDecimal.ZERO};

        executeQuery(sql, resultSet -> {
            if (resultSet.next()) {
                balance[0] = resultSet.getBigDecimal("balance");
            } else {
                // Player not found in database, insert with default balance
                balance[0] = createDefaultPlayerBalance(playerUUID);
            }
        }, playerUUID.toString());

        return balance[0];
    }

    // Create a default balance record for a new player
    private BigDecimal createDefaultPlayerBalance(UUID playerUUID) {
        BigDecimal defaultBalance = new BigDecimal("0.00");
        try {
            String sql = "INSERT INTO player_balances (uuid, balance) VALUES (?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setBigDecimal(2, defaultBalance);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create default balance for player UUID: " + playerUUID, e);
        }
        return defaultBalance;
    }

    // Set player balance
    public boolean setPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();

            String sql = "MERGE INTO player_balances (uuid, balance, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setBigDecimal(2, amount);
                int rowsAffected = statement.executeUpdate();
                return rowsAffected > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set balance for player UUID: " + playerUUID, e);
            return false;
        }
    }


    // Add to player balance (deposit)
    public boolean addToPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();

            // First, check if player exists in the database
            String checkSql = "SELECT balance FROM player_balances WHERE uuid = ?";
            try (PreparedStatement checkStatement = connection.prepareStatement(checkSql)) {
                checkStatement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = checkStatement.executeQuery()) {
                    if (resultSet.next()) {
                        // Player exists, update balance
                        BigDecimal currentBalance = resultSet.getBigDecimal("balance");
                        BigDecimal newBalance = currentBalance.add(amount);

                        String updateSql = "UPDATE player_balances SET balance = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
                        try (PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
                            updateStatement.setBigDecimal(1, newBalance);
                            updateStatement.setString(2, playerUUID.toString());
                            return updateStatement.executeUpdate() > 0;
                        }
                    } else {
                        // Player doesn't exist, create new record
                        String insertSql = "INSERT INTO player_balances (uuid, balance) VALUES (?, ?)";
                        try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                            insertStatement.setString(1, playerUUID.toString());
                            insertStatement.setBigDecimal(2, amount);
                            return insertStatement.executeUpdate() > 0;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to add to balance for player UUID: " + playerUUID, e);
        }
        return false;
    }

    // Subtract from player balance (withdraw)
    public boolean subtractFromPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();

            // First, check if player exists and has enough balance
            String checkSql = "SELECT balance FROM player_balances WHERE uuid = ?";
            try (PreparedStatement checkStatement = connection.prepareStatement(checkSql)) {
                checkStatement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = checkStatement.executeQuery()) {
                    if (resultSet.next()) {
                        BigDecimal currentBalance = resultSet.getBigDecimal("balance");

                        // Check if player has enough balance
                        if (currentBalance.compareTo(amount) < 0) {
                            return false; // Not enough funds
                        }

                        BigDecimal newBalance = currentBalance.subtract(amount);

                        String updateSql = "UPDATE player_balances SET balance = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
                        try (PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
                            updateStatement.setBigDecimal(1, newBalance);
                            updateStatement.setString(2, playerUUID.toString());
                            return updateStatement.executeUpdate() > 0;
                        }
                    } else {
                        return false; // Player doesn't exist
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to subtract from balance for player UUID: " + playerUUID, e);
        }
        return false;
    }

    // Check if player has enough balance
    public boolean hasEnoughBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();

            String sql = "SELECT balance FROM player_balances WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        BigDecimal balance = resultSet.getBigDecimal("balance");
                        return balance.compareTo(amount) >= 0;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check balance for player UUID: " + playerUUID, e);
        }
        return false;
    }



    /**
     * Executes an SQL query and returns the first column of the first row as a String
     *
     * @param sql The SQL query to execute
     * @param params The parameters to use in the query
     * @return The result as a String, or null if no result
     */
    public String queryString(String sql, Object... params) {
        try {
            openConnection();

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                // Set parameters
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }

                // Execute the query
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to execute query: " + sql, e);
        }

        return null;
    }

    // Data class to hold flight status
    public record PlayerFlightData(boolean allowFlight, boolean isFlying) {}
    
    // Data class to hold player last seen information
    public record PlayerLastSeenData(java.sql.Timestamp lastSeenDate) {}
    
    /**
     * Runs database migrations to add any missing columns to existing tables
     */
    private void runMigrations() {
        try {
            // Check if last_seen_date column exists in player_data table
            if (!columnExists("player_data", "last_seen_date")) {
                addLastSeenDateColumn();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to run database migrations", e);
        }
    }
    
    /**
     * Checks if a column exists in a table
     * @param tableName The name of the table
     * @param columnName The name of the column
     * @return true if the column exists, false otherwise
     */
    private boolean columnExists(String tableName, String columnName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return rs.next(); // If there's a result, the column exists
        }
    }
    
    /**
     * Adds the last_seen_date column to the player_data table
     */
    private void addLastSeenDateColumn() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            plugin.getLogger().info("Adding last_seen_date column to player_data table...");
            statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS last_seen_date TIMESTAMP");
            plugin.getLogger().info("Successfully added last_seen_date column to player_data table");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add last_seen_date column to player_data table", e);
            throw e;
        }
    }
    
    /**
     * Updates the last seen date for a player
     * @param playerUUID The UUID of the player
     * @param playerName The name of the player
     * @return true if successful, false otherwise
     */
    public boolean updatePlayerLastSeen(UUID playerUUID, String playerName) {
        try {
            openConnection();
            
            // First check if the last_seen_date column exists
            if (!columnExists("player_data", "last_seen_date")) {
                // Run migration to add the column
                addLastSeenDateColumn();
            }
            
            String sql = "MERGE INTO player_data (uuid, name, last_seen_date, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, playerName);
                int rowsAffected = statement.executeUpdate();
                return rowsAffected > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update last seen date for player: " + playerName, e);
            return false;
        }
    }
    
    /**
     * Gets the last seen date for a player
     * @param playerUUID The UUID of the player
     * @return PlayerLastSeenData containing the last seen date, or null if not found
     */
    public PlayerLastSeenData getPlayerLastSeen(UUID playerUUID) {
        try {
            openConnection();
            
            // First check if the last_seen_date column exists
            if (!columnExists("player_data", "last_seen_date")) {
                // Run migration to add the column
                addLastSeenDateColumn();
            }
            
            String sql = "SELECT last_seen_date FROM player_data WHERE uuid = ?";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        java.sql.Timestamp lastSeenDate = resultSet.getTimestamp("last_seen_date");
                        return new PlayerLastSeenData(lastSeenDate);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get last seen date for player UUID: " + playerUUID, e);
        }
        return null;
    }
    
    // The getPlayerFlightStatus method is already defined elsewhere in this class

    // Serialize ItemStack array to Base64 string
    private String itemStackArrayToBase64(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) { // Check for AIR
                    dos.writeBoolean(false); // Mark as null/AIR
                } else {
                    dos.writeBoolean(true); // Indicate valid item
                    byte[] nbtBytes = item.serializeAsBytes();
                    if (nbtBytes == null || nbtBytes.length == 0) {
                        plugin.getLogger().warning("serializeAsBytes returned null or empty for item: " + item.getType() + " in itemStackArrayToBase64. Marking as null/AIR.");
                        dos.writeBoolean(false); // Fallback: treat as if it were null/AIR
                        continue; // Skip this item effectively by re-writing the boolean marker
                    }

                    ByteArrayOutputStream itemBaosCompressed = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzos = new GZIPOutputStream(itemBaosCompressed)) {
                        gzos.write(nbtBytes);
                    }
                    byte[] compressedItemBytes = itemBaosCompressed.toByteArray();

                    dos.writeInt(compressedItemBytes.length);
                    dos.write(compressedItemBytes);
                }
            }
            dos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize item stack array to Base64 with DataOutputStream", e);
            return null;
        }
    }

    private ItemStack[] itemStackArrayFromBase64(String data) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[0]; // Return empty array for null or empty data
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             DataInputStream dis = new DataInputStream(bais)) {
            int size = dis.readInt();
            // Basic sanity check for size to prevent excessive memory allocation
            if (size < 0 || size > 10240) { // Limiting array size (e.g., 10240 for about 280 stacks)
                plugin.getLogger().warning("Invalid or excessively large array size during deserialization: " + size);
                return new ItemStack[size > 0 ? size : 0]; // Return array of nulls or empty
            }
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                try {
                    boolean hasItemData = dis.readBoolean(); // True if item is valid and has data, false if null/AIR
    
                    if (hasItemData) {
                        // This is a valid item, read its length and data
                        int length = dis.readInt();
                        if (length <= 0 || length > 1024 * 1024) { // Check for non-positive or excessively large length (e.g. 1MB)
                            plugin.getLogger().warning("Invalid or excessively large item byte array length during deserialization: " + length + " at index " + i);
                            items[i] = null;
                            if (length > 0) { // Attempt to skip if length was positive but too large
                               long skipped = dis.skip(length);
                               if (skipped != length) {
                                   plugin.getLogger().warning("Failed to skip full " + length + " bytes for corrupted item at index " + i + ", skipped " + skipped + ". Further items may be misread.");
                                   break; // Critical error, stop processing
                               }
                            }
                            continue;
                        }
    
                        byte[] compressedItemBytes = new byte[length];
                        dis.readFully(compressedItemBytes); // Read the item data
    
                        // Attempt to decompress and deserialize the item
                        try {
                            byte[] nbtBytes;
                            // Attempt GZIP decompression
                            try (ByteArrayInputStream itemBaisCompressed = new ByteArrayInputStream(compressedItemBytes);
                                 GZIPInputStream gzis = new GZIPInputStream(itemBaisCompressed);
                                 ByteArrayOutputStream itemBaosNbt = new ByteArrayOutputStream()) {
                                
                                byte[] buffer = new byte[1024];
                                int lenGzis;
                                while ((lenGzis = gzis.read(buffer)) > 0) {
                                    itemBaosNbt.write(buffer, 0, lenGzis);
                                }
                                nbtBytes = itemBaosNbt.toByteArray();
                            } catch (java.util.zip.ZipException ze) {
                                // If GZIP fails, assume it's old, uncompressed NBT data
                                plugin.getLogger().log(Level.FINER, "GZIP decompression failed for item at index " + i + ", attempting direct deserialization (likely old format).");
                                nbtBytes = compressedItemBytes; // Use the original bytes
                            }
    
                            if (nbtBytes == null || nbtBytes.length == 0) {
                                plugin.getLogger().log(Level.WARNING, "NBT data is null or empty after GZIP/direct attempt for item at index " + i + ", setting to null.");
                                items[i] = null;
                                continue;
                            }
                            items[i] = ItemStack.deserializeBytes(nbtBytes);
                        } catch (Exception e) { // Catch exceptions during decompression or deserialization of this specific item
                            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item at index " + i + " from byte array (after GZIP/direct attempt)", e);
                            items[i] = null; // Set to null if deserialization fails for this item
                        }
                    } else {
                        // This item was marked as null or AIR by the serializer
                        items[i] = null;
                    }
                } catch (EOFException eof) {
                    plugin.getLogger().warning("Unexpected end of stream while processing item at index " + i + ". Array may be truncated.");
                    items[i] = null; // Mark current item as null
                    break; // Stop processing further items as the stream is likely corrupted
                } catch (IOException ioe) {
                    plugin.getLogger().log(Level.WARNING, "IOException while processing item at index " + i, ioe);
                    items[i] = null;
                    break; // Stop processing further items
                }
            }
            return items;
        } catch (EOFException eof) {
            plugin.getLogger().log(Level.WARNING, "EOFException while reading initial array size or early in stream. Returning empty array.", eof);
            return new ItemStack[0]; // Data is too short or corrupt to even get size
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item stack array from Base64", e);
            return new ItemStack[0]; // Return empty array on general failure
        }
    }

    // Serialize a single ItemStack to Base64 string
    private String itemStackToBase64(ItemStack item) {
        if (item == null || item.getType().isAir()) { // Check for AIR
            return null;
        }
        try {
            byte[] nbtBytes = item.serializeAsBytes();
            if (nbtBytes == null || nbtBytes.length == 0) {
                plugin.getLogger().warning("serializeAsBytes returned null or empty for item: " + (item != null ? item.getType() : "null_item_passed_check") + " in itemStackToBase64. Returning null.");
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(nbtBytes);
            }
            byte[] compressedBytes = baos.toByteArray();
            return Base64.getEncoder().encodeToString(compressedBytes);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize item stack to Base64 for item type: " + (item != null ? item.getType() : "null_item_passed_check"), e);
            return null;
        }
    }

    // Deserialize Base64 string to a single ItemStack
    private ItemStack itemStackFromBase64(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            byte[] compressedBytes = Base64.getDecoder().decode(data);
            if (compressedBytes == null || compressedBytes.length == 0) {
                plugin.getLogger().warning("Base64 decoded data is null or empty in itemStackFromBase64. Returning null.");
                return null;
            }
            byte[] nbtBytes;
            try {
                // Attempt GZIP decompression first
                ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes);
                ByteArrayOutputStream baosNbt = new ByteArrayOutputStream();
                try (GZIPInputStream gzis = new GZIPInputStream(bais)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) {
                        baosNbt.write(buffer, 0, len);
                    }
                }
                nbtBytes = baosNbt.toByteArray();
                if (nbtBytes.length == 0) {
                    plugin.getLogger().log(Level.WARNING, "Decompressed NBT data (GZIP attempt) is empty in itemStackFromBase64. Input: " + data.substring(0, Math.min(data.length(), 50)) + "... Returning null.");
                    return null;
                }
            } catch (java.util.zip.ZipException ze) {
                // If GZIP fails, assume it's old, uncompressed NBT data
                plugin.getLogger().log(Level.FINER, "GZIP decompression failed in itemStackFromBase64, attempting direct deserialization (likely old format). Input: " + data.substring(0, Math.min(data.length(), 50)) + "...");
                nbtBytes = compressedBytes; // Use the original bytes
            }

            return ItemStack.deserializeBytes(nbtBytes);
        } catch (Exception e) {
            // Catch a broader exception range as deserializeBytes might throw various things
            // including IllegalArgumentException if data is corrupt or not an itemstack.
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item stack from Base64", e);
            return null;
        }
    }
    
    // Save player inventories (both survival and creative)
    public void savePlayerInventories(UUID playerUUID, String playerName,
                                      ItemStack[] survivalInventory, ItemStack[] survivalArmor, ItemStack survivalOffhand,
                                      ItemStack[] creativeInventory, ItemStack[] creativeArmor, ItemStack creativeOffhand) {
        try {
            openConnection();

            String sql = "MERGE INTO player_inventories (uuid, name, survival_inventory, survival_armor, survival_offhand, " +
                    "creative_inventory, creative_armor, creative_offhand, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, playerName);

                // Serialize and store survival inventory
                statement.setString(3, survivalInventory != null ? itemStackArrayToBase64(survivalInventory) : null);
                statement.setString(4, survivalArmor != null ? itemStackArrayToBase64(survivalArmor) : null);
                statement.setString(5, survivalOffhand != null ? itemStackToBase64(survivalOffhand) : null);

                // Serialize and store creative inventory
                statement.setString(6, creativeInventory != null ? itemStackArrayToBase64(creativeInventory) : null);
                statement.setString(7, creativeArmor != null ? itemStackArrayToBase64(creativeArmor) : null);
                statement.setString(8, creativeOffhand != null ? itemStackToBase64(creativeOffhand) : null);

                statement.executeUpdate();
                plugin.getLogger().fine("Saved inventories for player: " + playerName);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save inventories for player: " + playerName, e);
        }
    }

    // Get player inventories
    public PlayerInventories getPlayerInventories(UUID playerUUID) {
        try {
            openConnection();

            String sql = "SELECT survival_inventory, survival_armor, survival_offhand, " +
                    "creative_inventory, creative_armor, creative_offhand " +
                    "FROM player_inventories WHERE uuid = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        // Get survival inventory data
                        String survivalInventoryData = resultSet.getString("survival_inventory");
                        String survivalArmorData = resultSet.getString("survival_armor");
                        String survivalOffhandData = resultSet.getString("survival_offhand");

                        // Get creative inventory data
                        String creativeInventoryData = resultSet.getString("creative_inventory");
                        String creativeArmorData = resultSet.getString("creative_armor");
                        String creativeOffhandData = resultSet.getString("creative_offhand");

                        // Deserialize inventory data
                        ItemStack[] survivalInventory = survivalInventoryData != null ?
                                itemStackArrayFromBase64(survivalInventoryData) : null;
                        ItemStack[] survivalArmor = survivalArmorData != null ?
                                itemStackArrayFromBase64(survivalArmorData) : null;
                        ItemStack survivalOffhand = survivalOffhandData != null ?
                                itemStackFromBase64(survivalOffhandData) : null;

                        ItemStack[] creativeInventory = creativeInventoryData != null ?
                                itemStackArrayFromBase64(creativeInventoryData) : null;
                        ItemStack[] creativeArmor = creativeArmorData != null ?
                                itemStackArrayFromBase64(creativeArmorData) : null;
                        ItemStack creativeOffhand = creativeOffhandData != null ?
                                itemStackFromBase64(creativeOffhandData) : null;

                        return new PlayerInventories(
                                survivalInventory, survivalArmor, survivalOffhand,
                                creativeInventory, creativeArmor, creativeOffhand
                        );
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get inventories for player UUID: " + playerUUID, e);
            return null;
        }
    }

    /**
     * Saves a spawn location to the database
     * @param world The world name
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param yaw The yaw rotation
     * @param pitch The pitch rotation
     * @return true if successful, false otherwise
     */
    public boolean setSpawnLocation(String world, double x, double y, double z, float yaw, float pitch) {
        try {
            openConnection();
            
            // First, clear any existing spawn locations
            try (PreparedStatement clearStmt = connection.prepareStatement(
                    "DELETE FROM spawn_locations")) {
                clearStmt.executeUpdate();
            }
            
            // Insert the new spawn location
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO spawn_locations (world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, world);
                stmt.setDouble(2, x);
                stmt.setDouble(3, y);
                stmt.setDouble(4, z);
                stmt.setFloat(5, yaw);
                stmt.setFloat(6, pitch);
                
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to set spawn location", e);
            return false;
        }
    }
    
    /**
     * Gets the spawn location from the database
     * @return A Map containing the spawn location data, or null if not found
     */
    public Map<String, Object> getSpawnLocation() {
        return queryRow("SELECT * FROM spawn_locations ORDER BY id DESC LIMIT 1");
    }
}