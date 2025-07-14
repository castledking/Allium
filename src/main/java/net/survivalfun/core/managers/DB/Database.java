package net.survivalfun.core.managers.DB;

import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
// Removed unused imports
import net.survivalfun.core.managers.economy.BalanceEntry;

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

        // H2 database URL - file mode with empty credentials
        this.dbUrl = "jdbc:h2:" + dataFolder.getAbsolutePath() + "/sfcore;USER=sa;PASSWORD=;MODE=MySQL";

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
        
        plugin.getLogger().info("Attempting database connection to: " + dbUrl);
        connection = DriverManager.getConnection(dbUrl + ";USER=sa;PASSWORD=");
        plugin.getLogger().info("Successfully connected to database");
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Player permissions table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_permissions (" +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "permission VARCHAR(255) NOT NULL, " +
                            "PRIMARY KEY (player_uuid, permission)" +
                            ")"
            );
            
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
                            "gamemode VARCHAR(20), " +
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
                            "survival_inventory BLOB, " +
                            "survival_armor BLOB, " +
                            "survival_offhand BLOB, " +
                            "creative_inventory BLOB, " +
                            "creative_armor BLOB, " +
                            "creative_offhand BLOB, " +
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
                    "CREATE TABLE IF NOT EXISTS player_teleport_toggle (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "state BOOLEAN NOT NULL" +
                            ")"
            );
            
            // Create player locations table for persistent /back command and other location tracking
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_locations (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "location_type VARCHAR(50) NOT NULL, " +
                            "world VARCHAR(255) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT NOT NULL, " +
                            "pitch FLOAT NOT NULL, " +
                            "timestamp BIGINT NOT NULL, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "INDEX (player_uuid, location_type)" +
                            ")"
            );
            
            // Migrate data from old player_death_locations table if it exists
            try {
                if (tableExists("player_death_locations")) {
                    statement.executeUpdate(
                            "INSERT INTO player_locations (player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp) " +
                            "SELECT player_uuid, 'DEATH', world, x, y, z, yaw, pitch, death_time " +
                            "FROM player_death_locations"
                    );
                }
            } catch (SQLException e) {
                // Migration failed, but we can continue
                plugin.getLogger().log(Level.WARNING, "Failed to migrate death locations data", e);
            }
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_balances (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            // Create server data table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS server_data (\"key\" VARCHAR(255) PRIMARY KEY, \"value\" TEXT)"
            );
            
            // Create player gamemode table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_gamemodes (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "gamemode VARCHAR(20) NOT NULL, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
            
            // Create notes table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_notes (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "staff_uuid VARCHAR(36), " +
                            "note TEXT NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
        }
    }

    public void addPlayerPermission(UUID playerId, String permission) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO player_permissions (player_uuid, permission) VALUES (?, ?)")) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, permission);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add permission", e);
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
            plugin.getLogger().log(Level.SEVERE, "Failed to check permission", e);
            return false;
        }
    }


    /**
     * Create a default balance record for a new player
     * @param playerUUID The UUID of the player
     * @param defaultBalance The default balance to set
     * @return true if successful, false otherwise
     */
    public boolean createDefaultPlayerBalance(UUID playerUUID, BigDecimal defaultBalance) {
        try {
            openConnection();
            String sql = "INSERT INTO player_balances (uuid, balance) VALUES (?, ?)";
            return executeUpdate(sql, playerUUID.toString(), defaultBalance) > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create default player balance", e);
            return false;
        }
    }

    /**
     * Set player balance
     * @param playerUUID The UUID of the player
     * @param amount The new balance
     * @return true if successful, false otherwise
     */
    public boolean setPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
            // Check if player has a balance record
            if (getPlayerBalance(playerUUID) == null) {
                return createDefaultPlayerBalance(playerUUID, amount);
            }
            
            String sql = "UPDATE player_balances SET balance = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
            return executeUpdate(sql, amount, playerUUID.toString()) > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to set player balance", e);
            return false;
        }
    }

    /**
     * Add to player balance (deposit)
     * @param playerUUID The UUID of the player
     * @param amount The amount to add
     * @return true if successful, false otherwise
     */
    public boolean addToPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
            BigDecimal currentBalance = getPlayerBalance(playerUUID);
            
            // If player doesn't have a balance record, create one
            if (currentBalance == null) {
                return createDefaultPlayerBalance(playerUUID, amount);
            }
            
            BigDecimal newBalance = currentBalance.add(amount);
            return setPlayerBalance(playerUUID, newBalance);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add to player balance", e);
            return false;
        }
    }

    /**
     * Subtract from player balance (withdraw)
     * @param playerUUID The UUID of the player
     * @param amount The amount to subtract
     * @return true if successful, false otherwise
     */
    public boolean subtractFromPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
            BigDecimal currentBalance = getPlayerBalance(playerUUID);
            
            // If player doesn't have a balance record or not enough balance
            if (currentBalance == null || currentBalance.compareTo(amount) < 0) {
                return false;
            }
            
            BigDecimal newBalance = currentBalance.subtract(amount);
            return setPlayerBalance(playerUUID, newBalance);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to subtract from player balance", e);
            return false;
        }
    }

    /**
     * Check if player has enough balance
     * @param playerUUID The UUID of the player
     * @param amount The amount to check
     * @return true if player has enough balance, false otherwise
     */
    public boolean hasEnoughBalance(UUID playerUUID, BigDecimal amount) {
        BigDecimal currentBalance = getPlayerBalance(playerUUID);
        return currentBalance != null && currentBalance.compareTo(amount) >= 0;
    }
    
    /**
     * Get top player balances
     * @param limit Maximum number of entries to return
     * @return List of BalanceEntry objects
     */
    public List<BalanceEntry> getTopBalances(int limit) {
        List<BalanceEntry> topBalances = new ArrayList<>();
        try {
            openConnection();
            String sql = "SELECT b.uuid, p.name, b.balance FROM player_balances b "
                    + "LEFT JOIN player_data p ON b.uuid = p.uuid "
                    + "ORDER BY b.balance DESC LIMIT ?";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                        String name = resultSet.getString("name");
                        BigDecimal balance = resultSet.getBigDecimal("balance");
                        
                        // If name is null (player not in player_data table), use UUID as name
                        if (name == null) {
                            name = uuid.toString().substring(0, 8);
                        }
                        
                        topBalances.add(new BalanceEntry(uuid, name, balance));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get top balances", e);
        }
        return topBalances;
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
     * Location types for player location tracking
     */
    public enum LocationType {
        DEATH,      // Death location
        TELEPORT,   // Last location before teleporting
        COMMAND,    // Last location before executing a command
        LOGIN,      // Location at login
        LOGOUT      // Location at logout
    }
    
    /**
     * Saves a player's location with a specific type to the database
     * 
     * @param playerUUID The UUID of the player
     * @param locationType The type of location being saved
     * @param location The location to save
     * @param timestamp The timestamp when the location was recorded (milliseconds since epoch)
     */
    public void savePlayerLocation(UUID playerUUID, LocationType locationType, Location location, long timestamp) {
        try {
            openConnection();
            
            String sql = "INSERT INTO player_locations (player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp, last_updated) " +
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
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save " + locationType + " location for player UUID: " + playerUUID, e);
        }
    }
    
    /**
     * Gets a player's most recent location of a specific type from the database
     * 
     * @param playerUUID The UUID of the player
     * @param locationType The type of location to retrieve
     * @return The location, or null if not found
     */
    public Location getPlayerLocation(UUID playerUUID, LocationType locationType) {
        try {
            openConnection();
            
            String sql = "SELECT world, x, y, z, yaw, pitch FROM player_locations " +
                         "WHERE player_uuid = ? AND location_type = ? " +
                         "ORDER BY timestamp DESC LIMIT 1";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get " + locationType + " location for player UUID: " + playerUUID, e);
        }
        
        return null;
    }
    
    /**
     * Gets the timestamp of a player's most recent location of a specific type
     * 
     * @param playerUUID The UUID of the player
     * @param locationType The type of location
     * @return The timestamp in milliseconds since epoch, or 0 if not found
     */
    public long getPlayerLocationTimestamp(UUID playerUUID, LocationType locationType) {
        try {
            openConnection();
            
            String sql = "SELECT timestamp FROM player_locations " +
                         "WHERE player_uuid = ? AND location_type = ? " +
                         "ORDER BY timestamp DESC LIMIT 1";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, locationType.name());
                
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getLong("timestamp");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get " + locationType + " timestamp for player UUID: " + playerUUID, e);
        }
        
        return 0L;
    }
    
    /**
     * Gets the most recent player location of any type
     * 
     * @param playerUUID The UUID of the player
     * @return A Map containing location data and type, or null if not found
     */
    public Map<String, Object> getLastPlayerLocation(UUID playerUUID) {
        try {
            openConnection();
            
            String sql = "SELECT location_type, world, x, y, z, yaw, pitch, timestamp " +
                         "FROM player_locations WHERE player_uuid = ? " +
                         "ORDER BY timestamp DESC LIMIT 1";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            plugin.getLogger().log(Level.WARNING, "Failed to get last location for player UUID: " + playerUUID, e);
        }
        
        return null;
    }
    
    /**
     * Gets the most recent non-death location for a player
     * 
     * @param playerUUID The UUID of the player
     * @return The location, or null if not found
     */
    public Location getLastNonDeathLocation(UUID playerUUID) {
        try {
            openConnection();
            
            String sql = "SELECT world, x, y, z, yaw, pitch FROM player_locations " +
                         "WHERE player_uuid = ? AND location_type != 'DEATH' " +
                         "ORDER BY timestamp DESC LIMIT 1";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            plugin.getLogger().log(Level.WARNING, "Failed to get last non-death location for player UUID: " + playerUUID, e);
        }
        
        return null;
    }

    public void saveTeleportToggleState(UUID playerUuid, boolean state) {
        String sql = "REPLACE INTO player_teleport_toggle (player_uuid, state) VALUES (?, ?)";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setBoolean(2, state);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving teleport toggle state", e);
        }
    }
    
    public Boolean getTeleportToggleState(UUID playerUuid) {
        String sql = "SELECT state FROM player_teleport_toggle WHERE player_uuid = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return rs.getBoolean("state");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading teleport toggle state", e);
        }
        return null;
    }
    
    /**
     * For backward compatibility - saves a player's death location and time to the database
     * 
     * @param playerUUID The UUID of the player
     * @param location The location where the player died
     * @param deathTime The time when the player died (milliseconds since epoch)
     */
    public void savePlayerDeathLocation(UUID playerUUID, Location location, long deathTime) {
        savePlayerLocation(playerUUID, LocationType.DEATH, location, deathTime);
    }
    
    /**
     * For backward compatibility - gets a player's death location from the database
     * 
     * @param playerUUID The UUID of the player
     * @return The location where the player died, or null if not found
     */
    public Location getPlayerDeathLocation(UUID playerUUID) {
        return getPlayerLocation(playerUUID, LocationType.DEATH);
    }
    
    /**
     * For backward compatibility - gets the time when a player died from the database
     * 
     * @param playerUUID The UUID of the player
     * @return The time of death in milliseconds since epoch, or 0 if not found
     */
    public long getPlayerDeathTime(UUID playerUUID) {
        return getPlayerLocationTimestamp(playerUUID, LocationType.DEATH);
    }
    
    /**
     * For backward compatibility - removes a player's death location and time from the database
     * 
     * @param playerUUID The UUID of the player
     */
    public void clearPlayerDeathLocation(UUID playerUUID) {
        deletePlayerLocation(playerUUID, LocationType.DEATH);
    }
    
    /**
     * Deletes a player's location of a specific type from the database
     * 
     * @param playerUUID The UUID of the player
     * @param locationType The type of location to delete
     * @return true if successful, false otherwise
     */
    public boolean deletePlayerLocation(UUID playerUUID, LocationType locationType) {
        try {
            openConnection();
            
            String sql = "DELETE FROM player_locations WHERE player_uuid = ? AND location_type = ?";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, locationType.name());
                statement.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete " + locationType + " location for player UUID: " + playerUUID);
            return false;
        }
    }

    public void savePlayerFlightStatus(UUID uuid, boolean status) {
        try {
            openConnection();

            String sql = "MERGE INTO player_data (uuid, is_flying, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setBoolean(2, status);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save flight status for player UUID: " + uuid, e);
        }
    }

    /**
     * Saves a player's flight status to the database
     * @param player The player whose flight status to save
     */
    public void savePlayerFlightStatus(Player player) {
        try {
            openConnection();
            
            // First ensure the player exists in the database
            updatePlayerLastSeen(player.getUniqueId(), player.getName());
            
            String sql = "UPDATE player_data SET is_flying = ?, allow_flight = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, player.isFlying());
                statement.setBoolean(2, player.getAllowFlight());
                statement.setString(3, player.getUniqueId().toString());
                
                int rowsAffected = statement.executeUpdate();
                
                if (rowsAffected == 0) {
                    // Player doesn't exist in the database, insert a new record
                    String insertSql = "INSERT INTO player_data (uuid, name, is_flying, allow_flight, last_updated) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
                    try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                        insertStmt.setString(1, player.getUniqueId().toString());
                        insertStmt.setString(2, player.getName());
                        insertStmt.setBoolean(3, player.isFlying());
                        insertStmt.setBoolean(4, player.getAllowFlight());
                        insertStmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save flight status for player: " + player.getName(), e);
        }
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
            stmt = connection.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
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
        
        try {
            openConnection();
            
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getBigDecimal("balance");
                    } else {
                        // Player not found in database, insert with default balance
                        return createDefaultPlayerBalance(playerUUID);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get player balance", e);
            return BigDecimal.ZERO;
        }
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
     * Checks if a table exists in the database
     * @param tableName The name of the table
     * @return true if the table exists, false otherwise
     */
    private boolean tableExists(String tableName) {
        try {
            openConnection();
            
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), null)) {
                return tables.next(); // If there's at least one result, the table exists
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error checking if table exists: " + tableName, e);
            return false;
        }
    }
    
    /**
     * Checks if a column exists in a table
     * @param tableName The name of the table
     * @param columnName The name of the column
     * @return true if the column exists, false otherwise
     */
    private boolean columnExists(String tableName, String columnName) {
        try {
            openConnection();
            
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
                return columns.next(); // If there's at least one result, the column exists
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error checking if column exists: " + columnName, e);
            return false;
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
    
    /**
     * Gets a player's flight status from the database
     * @param playerUUID The UUID of the player
     * @return PlayerFlightData containing the flight status, or null if not found
     */
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

    public ItemStack[] deserializeItemStacks(byte[] data) throws SQLException {
        if (data == null) return null;
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {
            
            int length = dis.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                try {
                    boolean hasItemData = dis.readBoolean();
                    if (hasItemData) {
                        int itemDataLength = dis.readInt();
                        if (itemDataLength <= 0) {
                            // No valid data for this item
                            items[i] = null;
                            continue;
                        }
                        
                        if (itemDataLength > 1024 * 1024) { // 1MB limit as safety check
                            plugin.getLogger().warning("Invalid item length: " + itemDataLength);
                            items[i] = null;
                            dis.skip(itemDataLength); // Skip this oversized data
                            continue;
                        }
                        
                        byte[] itemBytes = new byte[itemDataLength];
                        dis.readFully(itemBytes);
                        
                        try {
                            items[i] = ItemStack.deserializeBytes(itemBytes);
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item at index " + i, e);
                            items[i] = null;
                        }
                    } else {
                        items[i] = null;
                    }
                } catch (EOFException eof) {
                    plugin.getLogger().warning("Unexpected EOF while reading item at index " + i);
                    items[i] = null;
                    break;
                } catch (IOException ioe) {
                    plugin.getLogger().log(Level.WARNING, "IOException while reading item at index " + i, ioe);
                    items[i] = null;
                    break;
                }
            }
            return items;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error deserializing item stacks", e);
            return new ItemStack[0];
        }
    }
    
    // Serialization/deserialization methods
    private byte[] serializeItemStacks(ItemStack[] items) throws SQLException, IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                byte[] itemData = serializeItemStack(item);
                dos.write(itemData); // Write the serialized item directly
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SQLException("ItemStack serialization failed", e);
        }
    }
    
    private ItemStack[] deserializeItemStacks(ResultSet resultSet, String columnName) throws SQLException {
        byte[] data = resultSet.getBytes(columnName);
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        try {
            return deserializeItemStacks(data);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item stacks from column " + columnName, e);
            return new ItemStack[0];
        }
    }
    
    // Methods for single ItemStack serialization/deserialization
    private byte[] serializeItemStack(ItemStack item) throws IOException {
        if (item == null || item.getType().isAir()) {
            // For null or air items, just return a single byte array with a false flag
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeBoolean(false);
            }
            return baos.toByteArray();
        }
        
        // For valid items
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // Write a boolean flag to indicate there's an item
            dos.writeBoolean(true);
            
            // Get the NBT bytes
            byte[] nbtBytes = item.serializeAsBytes();
            if (nbtBytes != null && nbtBytes.length > 0) {
                // Write the length of the item data
                dos.writeInt(nbtBytes.length);
                // Write the item data
                dos.write(nbtBytes);
            } else {
                // Write zero length if no valid NBT data
                dos.writeInt(0);
            }
        }
        return baos.toByteArray();
    }
    
    /**
     * Deserializes a single ItemStack from a byte array
     * @param data The serialized ItemStack data
     * @return The deserialized ItemStack, or null if the data is invalid
     */
    private ItemStack deserializeSingleItemStack(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {
            
            // Read the boolean flag that indicates if there's an item
            boolean hasItem = dis.readBoolean();
            if (!hasItem) {
                return null;
            }
            
            // Read the length of the item data
            int itemDataLength = dis.readInt();
            if (itemDataLength <= 0) {
                return null;
            }
            
            // Read the item data
            byte[] itemBytes = new byte[itemDataLength];
            dis.readFully(itemBytes);
            
            // Deserialize the item
            return ItemStack.deserializeBytes(itemBytes);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize single item stack", e);
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
        try {
            openConnection();
            
            // Log the query we're about to execute
            plugin.getLogger().info("Querying spawn location from database...");
            
            // Execute the query
            Map<String, Object> result = queryRow("SELECT * FROM spawn_locations ORDER BY id DESC LIMIT 1");
            
            if (result == null || result.isEmpty()) {
                plugin.getLogger().warning("No spawn location found in the database");
                return null;
            }
            
            // Log the raw result for debugging
            plugin.getLogger().info("Found spawn location data: " + result.toString());
            
            // Create a new case-insensitive map for the result
            Map<String, Object> caseInsensitiveResult = new HashMap<>();
            String worldValue = null;
            
            // Convert all keys to lowercase and find the world value
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                String key = entry.getKey().toLowerCase();
                Object value = entry.getValue();
                caseInsensitiveResult.put(key, value);
                
                if (key.equals("world") && value != null) {
                    worldValue = value.toString();
                }
                
                // Log field types for debugging
                plugin.getLogger().info(String.format("Spawn field '%s' (%s) = %s (type: %s)", 
                    entry.getKey(),
                    key,
                    value,
                    value != null ? value.getClass().getSimpleName() : "null"));
            }
            
            // Verify required fields exist (case-insensitive check)
            if (worldValue == null || worldValue.isEmpty()) {
                plugin.getLogger().warning("Spawn location data is missing required 'world' field");
                return null;
            }
            
            return caseInsensitiveResult;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error getting spawn location from database", e);
            return null;
        }
    }

    /**
     * Executes a query and returns the first row as a Map of column names to values
     * @param sql The SQL query to execute
     * @param params Parameters for the prepared statement
     * @return Map containing column names and values, or null if no results
     */
    public Map<String, Object> queryRow(String sql, Object... params) {
        try {
            openConnection();
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= metaData.getColumnCount(); i++) {
                            row.put(metaData.getColumnName(i), rs.getObject(i));
                        }
                        return row;
                    }
                    return null;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute query", e);
            return null;
        }
    }

    // Maintenance mode methods
    public void setMaintenance(boolean enabled, String reason) {
        try {
            openConnection();
            
            String setEnabledSql = "REPLACE INTO server_data (\"key\", \"value\") VALUES ('maintenance_enabled', ?)";
            executeUpdate(setEnabledSql, String.valueOf(enabled));
            
            String setReasonSql = "REPLACE INTO server_data (\"key\", \"value\") VALUES ('maintenance_reason', ?)";
            executeUpdate(setReasonSql, reason);
            
            // Log maintenance mode change
            plugin.getLogger().info("Maintenance mode set to: " + enabled + ", Reason: " + reason);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to set maintenance mode", e);
        }
    }

    public boolean isMaintenanceEnabled() {
        try {
            openConnection();
            
            String sql = "SELECT \"value\" FROM server_data WHERE \"key\" = 'maintenance_enabled'";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Boolean.parseBoolean(rs.getString("value"));
                } else {
                    // No row exists, maintenance is disabled by default
                    return false;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get maintenance mode status", e);
            return false;
        }
    }

    public String getMaintenanceReason() {
        try {
            openConnection();
            
            String sql = "SELECT \"value\" FROM server_data WHERE \"key\" = 'maintenance_reason'";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get maintenance reason", e);
        }
        return "Server Maintenance";
    }

    public boolean isPlayerOp(UUID playerId) {
        // TODO: Implement database query for op status
        Bukkit.getLogger().warning("isPlayerOp not implemented yet");
        return false;
    }

    public void savePlayerGamemode(UUID uuid, String gamemode) {
        try {
            openConnection();

            String sql = "MERGE INTO player_gamemodes (uuid, gamemode, last_updated) " +
                    "KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, gamemode);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save gamemode for player UUID: " + uuid, e);
        }
    }

    public String getPlayerGamemode(UUID uuid) {
        try {
            openConnection();

            String sql = "SELECT gamemode FROM player_gamemodes WHERE uuid = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("gamemode");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get gamemode for player UUID: " + uuid, e);
        }
        return null;
    }

    // Inventory persistence methods
    /**
     * Saves a player's inventory to the database
     * @param playerUUID The UUID of the player
     * @param inventories The player's inventories
     */
    public void savePlayerInventories(UUID playerUUID, PlayerInventories inventories) {
        try {
            openConnection();
            
            // Get player name from Bukkit API if available
            String playerName = "Unknown";
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null) {
                playerName = player.getName();
            } else {
                // Try to get from offline player
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                if (offlinePlayer.getName() != null) {
                    playerName = offlinePlayer.getName();
                }
            }
            
            // Serialize inventory data
            byte[] survivalInv = serializeItemStacks(inventories.getSurvivalInventory());
            byte[] survivalArmor = serializeItemStacks(inventories.getSurvivalArmor());
            byte[] survivalOffhand = serializeItemStack(inventories.getSurvivalOffhand());
            byte[] creativeInv = serializeItemStacks(inventories.getCreativeInventory());
            byte[] creativeArmor = serializeItemStacks(inventories.getCreativeArmor());
            byte[] creativeOffhand = serializeItemStack(inventories.getCreativeOffhand());
            
            String sql = "INSERT INTO player_inventories (uuid, name, survival_inv, survival_armor, survival_offhand, creative_inv, creative_armor, creative_offhand) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE " +
                         "name = VALUES(name), " +
                         "survival_inv = VALUES(survival_inv), " +
                         "survival_armor = VALUES(survival_armor), " +
                         "survival_offhand = VALUES(survival_offhand), " +
                         "creative_inv = VALUES(creative_inv), " +
                         "creative_armor = VALUES(creative_armor), " +
                         "creative_offhand = VALUES(creative_offhand)";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, playerName);
                stmt.setBytes(3, survivalInv);
                stmt.setBytes(4, survivalArmor);
                stmt.setBytes(5, survivalOffhand);
                stmt.setBytes(6, creativeInv);
                stmt.setBytes(7, creativeArmor);
                stmt.setBytes(8, creativeOffhand);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save inventory for " + playerUUID, e);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize inventory for " + playerUUID, e);
        }
    }
    
    /**
     * Loads a player's inventory from the database
     * @param playerUUID The UUID of the player
     * @return The player's inventories, or null if not found
     */
    public PlayerInventories loadPlayerInventories(UUID playerUUID) {
        try {
            openConnection();
            
            // First verify the table exists with all required columns
            verifyInventoryTable();
            
            String sql = "SELECT survival_inv, survival_armor, survival_offhand, creative_inv, creative_armor, creative_offhand " +
                         "FROM player_inventories WHERE uuid = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    // Handle potential null columns gracefully
                    ItemStack[] survivalInv = rs.getBytes("survival_inv") != null ? 
                        deserializeItemStacks(rs, "survival_inv") : new ItemStack[0];
                    ItemStack[] survivalArmor = rs.getBytes("survival_armor") != null ? 
                        deserializeItemStacks(rs, "survival_armor") : new ItemStack[0];
                    ItemStack survivalOffhand = null;
                    try {
                        byte[] offhandBytes = rs.getBytes("survival_offhand");
                        if (offhandBytes != null && offhandBytes.length > 0) {
                            survivalOffhand = deserializeSingleItemStack(offhandBytes);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to deserialize survival offhand item", e);
                    }
                    
                    ItemStack[] creativeInv = rs.getBytes("creative_inv") != null ? 
                        deserializeItemStacks(rs, "creative_inv") : new ItemStack[0];
                    ItemStack[] creativeArmor = rs.getBytes("creative_armor") != null ? 
                        deserializeItemStacks(rs, "creative_armor") : new ItemStack[0];
                    ItemStack creativeOffhand = null;
                    try {
                        byte[] offhandBytes = rs.getBytes("creative_offhand");
                        if (offhandBytes != null && offhandBytes.length > 0) {
                            creativeOffhand = deserializeSingleItemStack(offhandBytes);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to deserialize creative offhand item", e);
                    }
                    
                    return new PlayerInventories(
                        survivalInv,
                        survivalArmor,
                        survivalOffhand,
                        creativeInv,
                        creativeArmor,
                        creativeOffhand
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load inventory for " + playerUUID, e);
        }
        return null;
    }

    public void saveCreativeInventory(UUID playerUUID, ItemStack[] contents, ItemStack[] armor) {
        try {
            byte[] serializedContents = serializeItemStacks(contents);
            byte[] serializedArmor = serializeItemStacks(armor);
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO creative_inventories (player_uuid, contents, armor) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE contents = VALUES(contents), armor = VALUES(armor)")) {
                
                stmt.setString(1, playerUUID.toString());
                stmt.setBytes(2, serializedContents);
                stmt.setBytes(3, serializedArmor);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save creative inventory for " + playerUUID, e);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize item stacks for " + playerUUID, e);
        }
    }
    
    /**
     * Retrieves a player's creative inventory contents from the database
     */
    public ItemStack[] getCreativeInventoryContents(UUID playerUUID) {
        try {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                 "SELECT contents FROM creative_inventories WHERE player_uuid = ?")) {
                
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return deserializeItemStacks(rs, "contents");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load creative inventory contents for " + playerUUID, e);
        }
        return null;
    }
    
    /**
     * Retrieves a player's creative inventory armor from the database
     */
    public ItemStack[] getCreativeInventoryArmor(UUID playerUUID) {
        try {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                 "SELECT armor FROM creative_inventories WHERE player_uuid = ?")) {
                
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return deserializeItemStacks(rs, "armor");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load creative inventory armor for " + playerUUID, e);
        }
        return null;
    }

    public void debugPlayerInventory(UUID playerUUID) {
        try {
            openConnection();
            String sql = "SELECT survival_inv, survival_armor, survival_offhand FROM player_inventories WHERE uuid = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    byte[] survivalInv = rs.getBytes("survival_inv");
                    byte[] survivalArmor = rs.getBytes("survival_armor");
                    byte[] survivalOffhand = rs.getBytes("survival_offhand");
                    plugin.getLogger().info("[InventoryDebug] Survival inventory data for " + playerUUID + ":");
                    plugin.getLogger().info("[InventoryDebug] survival_inv: " + (survivalInv != null ? survivalInv.length + " bytes" : "null"));
                    plugin.getLogger().info("[InventoryDebug] survival_armor: " + (survivalArmor != null ? survivalArmor.length + " bytes" : "null"));
                    plugin.getLogger().info("[InventoryDebug] survival_offhand: " + (survivalOffhand != null ? survivalOffhand.length + " bytes" : "null"));
                    
                    // Attempt to deserialize and log item details
                    ItemStack[] inv = deserializeItemStacks(rs, "survival_inv");
                    ItemStack[] armor = deserializeItemStacks(rs, "survival_armor");
                    ItemStack offhand = deserializeSingleItemStack(rs.getBytes("survival_offhand"));
                    plugin.getLogger().info("[InventoryDebug] Survival inventory items: " + Arrays.toString(inv));
                    plugin.getLogger().info("[InventoryDebug] Survival armor items: " + Arrays.toString(armor));
                    plugin.getLogger().info("[InventoryDebug] Survival offhand item: " + (offhand != null ? offhand.toString() : "null"));
                } else {
                    plugin.getLogger().info("[InventoryDebug] No inventory data found for " + playerUUID);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to debug inventory for " + playerUUID, e);
        }
    }

    /**
     * Verifies the player_inventories table exists with all required columns
     */
    private void verifyInventoryTable() throws SQLException {
        // Check if table exists
        if (!tableExists("player_inventories")) {
            // Create the table if it doesn't exist
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS player_inventories (" +
                                "uuid VARCHAR(36) PRIMARY KEY," +
                                "name VARCHAR(16) NOT NULL," +
                                "survival_inv BLOB," +
                                "survival_armor BLOB," +
                                "survival_offhand BLOB," +
                                "creative_inv BLOB," +
                                "creative_armor BLOB," +
                                "creative_offhand BLOB," +
                                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")"
                );
            }
        } else {
            // Check for required columns
            String[] requiredColumns = {"survival_inv", "survival_armor", "survival_offhand", "creative_inv", "creative_armor", "creative_offhand"};
            for (String column : requiredColumns) {
                if (!columnExists("player_inventories", column)) {
                    // Add the missing column
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("ALTER TABLE player_inventories ADD COLUMN " + column + " BLOB");
                    }
                }
            }
        }
    }

    /**
     * Gets a database connection, opening a new one if needed
     * @return The database connection
     * @throws SQLException If there's an error getting the connection
     */
    private Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(dbUrl);
        }
        return connection;
    }

    public void addPlayerNote(UUID playerUuid, UUID staffUuid, String note) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO player_notes (player_uuid, staff_uuid, note) VALUES (?, ?, ?)")) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, staffUuid != null ? staffUuid.toString() : "CONSOLE");
            stmt.setString(3, note);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add player note", e);
        }
    }

    public List<NoteEntry> getPlayerNotes(UUID playerUuid, UUID staffUuid) {
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes WHERE player_uuid = ?" + 
                      (staffUuid != null ? " AND staff_uuid = ?" : "") + 
                      " ORDER BY created_at DESC";
                      
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get player notes", e);
        }
        return notes;
    }

    public List<NoteEntry> getAllNotes(UUID staffUuid) {
        List<NoteEntry> notes = new ArrayList<>();
        String query = staffUuid != null ? 
            "SELECT * FROM player_notes WHERE staff_uuid = ? ORDER BY created_at DESC" :
            "SELECT * FROM player_notes ORDER BY created_at DESC";
            
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get all notes", e);
        }
        return notes;
    }

    public boolean deleteNote(int noteId, UUID staffUuid, boolean isAdmin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM player_notes WHERE id = ? AND (staff_uuid = ? OR ? = true)")) {
            statement.setInt(1, noteId);
            statement.setString(2, staffUuid != null ? staffUuid.toString() : null);
            statement.setBoolean(3, isAdmin);
            return statement.executeUpdate() > 0;
        }
    }

    public List<NoteEntry> getNotesForPlayer(UUID playerUuid) throws SQLException {
        openConnection();
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes WHERE player_uuid = ? ORDER BY created_at DESC";
        
        try (PreparedStatement statement = connection.prepareStatement(query)) {
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
        openConnection();
        List<NoteEntry> notes = new ArrayList<>();
        String query = "SELECT * FROM player_notes ORDER BY created_at DESC";
        
        try (PreparedStatement statement = connection.prepareStatement(query)) {
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
        openConnection();
        String sql = "SELECT * FROM player_notes WHERE id = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
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

    public void savePlayerGameMode(UUID uuid, GameMode gameMode) throws SQLException {
        openConnection();
        String sql = "INSERT INTO player_data (uuid, gamemode, last_updated) " +
                     "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                     "ON DUPLICATE KEY UPDATE gamemode = VALUES(gamemode), last_updated = VALUES(last_updated)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, gameMode.name());
            stmt.executeUpdate();
        }
    }

    public GameMode getPlayerGameMode(UUID uuid) throws SQLException {
        openConnection();
        String sql = "SELECT gamemode FROM player_data WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return GameMode.valueOf(rs.getString("gamemode"));
            }
        }
        return null;
    }

    /**
     * Executes a query and processes the ResultSet using a handler function
     * 
     * @param <T> The return type of the handler function
     * @param sql The SQL query to execute
     * @param resultHandler Function to process the ResultSet
     * @param params Query parameters
     * @return The result from the handler function
     * @throws SQLException If there's a database error
     */
    public <T> T executeQuery(String sql, Function<ResultSet, T> resultHandler, Object... params) throws SQLException {
        openConnection();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return resultHandler.apply(rs);
            }
        }
    }

    /**
     * Saves the last message sender for a player
     * @param playerId The UUID of the player who received the message
     * @param senderId The UUID of the player who sent the message
     */
    public void saveLastMessageSender(UUID playerId, UUID senderId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO player_last_message_senders (player_uuid, sender_uuid) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE sender_uuid = ?")) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, senderId.toString());
            stmt.setString(3, senderId.toString());
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving last message sender", e);
        }
    }
}