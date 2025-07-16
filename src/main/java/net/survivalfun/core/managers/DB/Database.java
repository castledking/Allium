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
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import net.survivalfun.core.managers.economy.BalanceEntry;

public class Database {
    private final PluginStart plugin;
    private Connection connection;
    private final String dbUrl;

    public Database(PluginStart plugin) {
        this.plugin = plugin;

        File dataFolder = new File(plugin.getDataFolder(), "database");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        this.dbUrl = "jdbc:h2:" + dataFolder.getAbsolutePath() + "/sfcore;USER=sa;PASSWORD=;MODE=MySQL";
        initialize();
    }

    private void initialize() {
        try {
            Class.forName("org.h2.Driver");
            openConnection();
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
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_permissions (" +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "permission VARCHAR(255) NOT NULL, " +
                            "PRIMARY KEY (player_uuid, permission)" +
                            ")"
            );

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

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS gamemode_resets (" +
                            "player_uuid VARCHAR(36) PRIMARY KEY, " +
                            "reset_gamemode VARCHAR(20) NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

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
                if (tableExists("player_death_locations")) {
                    statement.executeUpdate(
                            "INSERT INTO player_locations (player_uuid, location_type, world, x, y, z, yaw, pitch, timestamp) " +
                            "SELECT player_uuid, 'DEATH', world, x, y, z, yaw, pitch, death_time " +
                            "FROM player_death_locations"
                    );
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to migrate death locations data", e);
            }

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_balances (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS server_data (\"key\" VARCHAR(255) PRIMARY KEY, \"value\" TEXT)"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_gamemodes (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "gamemode VARCHAR(20) NOT NULL, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_notes (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "staff_uuid VARCHAR(36), " +
                            "note TEXT NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            runMigrations();
        }
    }

    /**
     * Runs database migrations, including adding migrated_perms column to player_data.
     */
    private void runMigrations() throws SQLException {
        // Check and add home_name column (unchanged)
        if (!columnExists("player_locations", "home_name")) {
            try (Statement statement = connection.createStatement()) {
                plugin.getLogger().info("Adding home_name column to player_locations table...");
                statement.executeUpdate("ALTER TABLE player_locations ADD COLUMN IF NOT EXISTS home_name VARCHAR(50)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_home ON player_locations (player_uuid, location_type, home_name)");
                plugin.getLogger().info("Successfully added home_name column and index to player_locations table");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add home_name column to player_locations table", e);
                throw e;
            }
        }

        // Check and add migrated_perms column to player_data
        if (!columnExists("player_data", "migrated_perms")) {
            try (Statement statement = connection.createStatement()) {
                plugin.getLogger().info("Adding migrated_perms column to player_data table...");
                statement.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS migrated_perms BOOLEAN DEFAULT FALSE");
                plugin.getLogger().info("Successfully added migrated_perms column to player_data table");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add migrated_perms column to player_data table", e);
                throw e;
            }
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

    public boolean setPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
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

    public boolean addToPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
            BigDecimal currentBalance = getPlayerBalance(playerUUID);
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

    public boolean subtractFromPlayerBalance(UUID playerUUID, BigDecimal amount) {
        try {
            openConnection();
            BigDecimal currentBalance = getPlayerBalance(playerUUID);
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

    public boolean hasEnoughBalance(UUID playerUUID, BigDecimal amount) {
        BigDecimal currentBalance = getPlayerBalance(playerUUID);
        return currentBalance != null && currentBalance.compareTo(amount) >= 0;
    }

    public List<BalanceEntry> getTopBalances(int limit) {
        List<BalanceEntry> topBalances = new ArrayList<>();
        try {
            openConnection();
            String sql = "SELECT b.uuid, p.name, b.balance FROM player_balances b " +
                    "LEFT JOIN player_data p ON b.uuid = p.uuid " +
                    "ORDER BY b.balance DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    public enum LocationType {
        DEATH, TELEPORT, COMMAND, LOGIN, LOGOUT, HOME
    }

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

    public void savePlayerHome(UUID playerUUID, String homeName, Location location, long timestamp) {
        try {
            openConnection();
            String sql = "INSERT INTO player_locations (player_uuid, location_type, home_name, world, x, y, z, yaw, pitch, timestamp, last_updated) " +
                    "VALUES (?, 'HOME', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON DUPLICATE KEY UPDATE world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, timestamp = ?, last_updated = CURRENT_TIMESTAMP";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            plugin.getLogger().log(Level.WARNING, "Failed to save home '" + homeName + "' for player UUID: " + playerUUID, e);
        }
    }

    public Location getPlayerHome(UUID playerUUID, String homeName) {
        try {
            openConnection();
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
            plugin.getLogger().log(Level.WARNING, "Failed to get home '" + homeName + "' for player UUID: " + playerUUID, e);
        }
        return null;
    }

    public List<String> getPlayerHomes(UUID playerUUID) {
        List<String> homes = new ArrayList<>();
        try {
            openConnection();
            String sql = "SELECT home_name FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME'";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String homeName = resultSet.getString("home_name");
                        if (homeName != null) {
                            homes.add(homeName);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get homes for player UUID: " + playerUUID, e);
        }
        return homes;
    }

    public int getPlayerHomeCount(UUID playerUUID) {
        try {
            openConnection();
            String sql = "SELECT COUNT(*) FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME'";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to count homes for player UUID: " + playerUUID, e);
        }
        return 0;
    }

    public boolean deletePlayerHome(UUID playerUUID, String homeName) {
        try {
            openConnection();
            String sql = "DELETE FROM player_locations WHERE player_uuid = ? AND location_type = 'HOME' AND home_name = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUUID.toString());
                statement.setString(2, homeName);
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete home '" + homeName + "' for player UUID: " + playerUUID, e);
            return false;
        }
    }

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

    public void savePlayerFlightStatus(Player player) {
        try {
            openConnection();
            updatePlayerLastSeen(player.getUniqueId(), player.getName());
            String sql = "UPDATE player_data SET is_flying = ?, allow_flight = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, player.isFlying());
                statement.setBoolean(2, player.getAllowFlight());
                statement.setString(3, player.getUniqueId().toString());
                int rowsAffected = statement.executeUpdate();
                if (rowsAffected == 0) {
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

    public int executeUpdate(String sql, Object... params) {
        if (connection == null) {
            plugin.getLogger().severe("Cannot execute update: Database connection is null");
            return 0;
        }
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error executing update: " + sql, e);
            return 0;
        }
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
                        return createDefaultPlayerBalance(playerUUID);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get player balance", e);
            return BigDecimal.ZERO;
        }
    }

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

    public String queryString(String sql, Object... params) {
        try {
            openConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            plugin.getLogger().log(Level.WARNING, "Failed to execute query: " + sql, e);
        }
        return null;
    }

    public record PlayerFlightData(boolean allowFlight, boolean isFlying) {}

    public record PlayerLastSeenData(java.sql.Timestamp lastSeenDate) {}

    private boolean tableExists(String tableName) {
        try {
            openConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), null)) {
                return tables.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error checking if table exists: " + tableName, e);
            return false;
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            openConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
                return columns.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error checking if column exists: " + columnName, e);
            return false;
        }
    }

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

    public boolean updatePlayerLastSeen(UUID playerUUID, String playerName) {
        try {
            openConnection();
            if (!columnExists("player_data", "last_seen_date")) {
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

    public PlayerLastSeenData getPlayerLastSeen(UUID playerUUID) {
        try {
            openConnection();
            if (!columnExists("player_data", "last_seen_date")) {
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
                            items[i] = null;
                            continue;
                        }
                        if (itemDataLength > 1024 * 1024) {
                            plugin.getLogger().warning("Invalid item length: " + itemDataLength);
                            items[i] = null;
                            dis.skip(itemDataLength);
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

    private byte[] serializeItemStacks(ItemStack[] items) throws SQLException, IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                byte[] itemData = serializeItemStack(item);
                dos.write(itemData);
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

    private byte[] serializeItemStack(ItemStack item) throws IOException {
        if (item == null || item.getType().isAir()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeBoolean(false);
            }
            return baos.toByteArray();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeBoolean(true);
            byte[] nbtBytes = item.serializeAsBytes();
            if (nbtBytes != null && nbtBytes.length > 0) {
                dos.writeInt(nbtBytes.length);
                dos.write(nbtBytes);
            } else {
                dos.writeInt(0);
            }
        }
        return baos.toByteArray();
    }

    private ItemStack deserializeSingleItemStack(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {
            boolean hasItem = dis.readBoolean();
            if (!hasItem) {
                return null;
            }
            int itemDataLength = dis.readInt();
            if (itemDataLength <= 0) {
                return null;
            }
            byte[] itemBytes = new byte[itemDataLength];
            dis.readFully(itemBytes);
            return ItemStack.deserializeBytes(itemBytes);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize single item stack", e);
            return null;
        }
    }

    public boolean setSpawnLocation(String world, double x, double y, double z, float yaw, float pitch) {
        try {
            openConnection();
            try (PreparedStatement clearStmt = connection.prepareStatement(
                    "DELETE FROM spawn_locations")) {
                clearStmt.executeUpdate();
            }
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

    public Map<String, Object> getSpawnLocation() {
        try {
            openConnection();
            plugin.getLogger().info("Querying spawn location from database...");
            Map<String, Object> result = queryRow("SELECT * FROM spawn_locations ORDER BY id DESC LIMIT 1");
            if (result == null || result.isEmpty()) {
                plugin.getLogger().warning("No spawn location found in the database");
                return null;
            }
            plugin.getLogger().info("Found spawn location data: " + result.toString());
            Map<String, Object> caseInsensitiveResult = new HashMap<>();
            String worldValue = null;
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                String key = entry.getKey().toLowerCase();
                Object value = entry.getValue();
                caseInsensitiveResult.put(key, value);
                if (key.equals("world") && value != null) {
                    worldValue = value.toString();
                }
                plugin.getLogger().info(String.format("Spawn field '%s' (%s) = %s (type: %s)",
                        entry.getKey(),
                        key,
                        value,
                        value != null ? value.getClass().getSimpleName() : "null"));
            }
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

    public void setMaintenance(boolean enabled, String reason) {
        try {
            openConnection();
            String setEnabledSql = "REPLACE INTO server_data (\"key\", \"value\") VALUES ('maintenance_enabled', ?)";
            executeUpdate(setEnabledSql, String.valueOf(enabled));
            String setReasonSql = "REPLACE INTO server_data (\"key\", \"value\") VALUES ('maintenance_reason', ?)";
            executeUpdate(setReasonSql, reason);
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

    public void savePlayerInventories(UUID playerUUID, PlayerInventories inventories) {
        try {
            openConnection();
            String playerName = "Unknown";
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null) {
                playerName = player.getName();
            } else {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                if (offlinePlayer.getName() != null) {
                    playerName = offlinePlayer.getName();
                }
            }
            byte[] survivalInv = serializeItemStacks(inventories.getSurvivalInventory());
            byte[] survivalArmor = serializeItemStacks(inventories.getSurvivalArmor());
            byte[] survivalOffhand = serializeItemStack(inventories.getSurvivalOffhand());
            byte[] creativeInv = serializeItemStacks(inventories.getCreativeInventory());
            byte[] creativeArmor = serializeItemStacks(inventories.getCreativeArmor());
            byte[] creativeOffhand = serializeItemStack(inventories.getCreativeOffhand());
            String sql = "INSERT INTO player_inventories (uuid, name, survival_inventory, survival_armor, survival_offhand, creative_inventory, creative_armor, creative_offhand) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "name = VALUES(name), " +
                    "survival_inventory = VALUES(survival_inventory), " +
                    "survival_armor = VALUES(survival_armor), " +
                    "survival_offhand = VALUES(survival_offhand), " +
                    "creative_inventory = VALUES(creative_inventory), " +
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

    public PlayerInventories loadPlayerInventories(UUID playerUUID) {
        try {
            openConnection();
            verifyInventoryTable();
            String sql = "SELECT survival_inventory, survival_armor, survival_offhand, creative_inventory, creative_armor, creative_offhand " +
                    "FROM player_inventories WHERE uuid = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    ItemStack[] survivalInv = rs.getBytes("survival_inventory") != null ?
                            deserializeItemStacks(rs, "survival_inventory") : new ItemStack[0];
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
                    ItemStack[] creativeInv = rs.getBytes("creative_inventory") != null ?
                            deserializeItemStacks(rs, "creative_inventory") : new ItemStack[0];
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
            String sql = "SELECT survival_inventory, survival_armor, survival_offhand FROM player_inventories WHERE uuid = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    byte[] survivalInv = rs.getBytes("survival_inventory");
                    byte[] survivalArmor = rs.getBytes("survival_armor");
                    byte[] survivalOffhand = rs.getBytes("survival_offhand");
                    plugin.getLogger().info("[InventoryDebug] Survival inventory data for " + playerUUID + ":");
                    plugin.getLogger().info("[InventoryDebug] survival_inventory: " + (survivalInv != null ? survivalInv.length + " bytes" : "null"));
                    plugin.getLogger().info("[InventoryDebug] survival_armor: " + (survivalArmor != null ? survivalArmor.length + " bytes" : "null"));
                    plugin.getLogger().info("[InventoryDebug] survival_offhand: " + (survivalOffhand != null ? survivalOffhand.length + " bytes" : "null"));
                    ItemStack[] inv = deserializeItemStacks(rs, "survival_inventory");
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

    private void verifyInventoryTable() throws SQLException {
        if (!tableExists("player_inventories")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS player_inventories (" +
                                "uuid VARCHAR(36) PRIMARY KEY," +
                                "name VARCHAR(16) NOT NULL," +
                                "survival_inventory BLOB," +
                                "survival_armor BLOB," +
                                "survival_offhand BLOB," +
                                "creative_inventory BLOB," +
                                "creative_armor BLOB," +
                                "creative_offhand BLOB," +
                                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")"
                );
            }
        } else {
            String[] requiredColumns = {"survival_inventory", "survival_armor", "survival_offhand", "creative_inventory", "creative_armor", "creative_offhand"};
            for (String column : requiredColumns) {
                if (!columnExists("player_inventories", column)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("ALTER TABLE player_inventories ADD COLUMN " + column + " BLOB");
                    }
                }
            }
        }
    }

    public Connection getConnection() throws SQLException {
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

    public boolean playerAccountExists(UUID playerUUID) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM player_balances WHERE uuid = ?")) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check player account existence", e);
            return false;
        }
    }
}