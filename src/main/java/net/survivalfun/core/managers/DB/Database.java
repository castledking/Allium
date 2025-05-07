package net.survivalfun.core.managers.DB;

import net.survivalfun.core.PluginStart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

            // Create tables if they don't exist
            createTables();

            plugin.getLogger().info("Database connection established successfully.");
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
            // Player data table
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_data (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "is_flying BOOLEAN DEFAULT FALSE, " +
                            "allow_flight BOOLEAN DEFAULT FALSE, " +
                            "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

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
                            "sent_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " + // Added comma here
                            "is_claimed BOOLEAN DEFAULT FALSE" +
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
        public record PlayerFlightData(boolean allowFlight, boolean isFlying) {
    }

    // Serialize ItemStack array to Base64 string
    private String itemStackArrayToBase64(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            // Write the size of the inventory
            dataOutput.writeInt(items.length);

            // Save every element in the list
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            // Serialize that array
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize item stack array", e);
            return null;
        }
    }

    // Deserialize Base64 string to ItemStack array
    private ItemStack[] itemStackArrayFromBase64(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];

            // Read the serialized inventory
            for (int i = 0; i < items.length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();
            return items;
        } catch (ClassNotFoundException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize item stack array", e);
            return null;
        }
    }

    // Serialize a single ItemStack to Base64 string
    private String itemStackToBase64(ItemStack item) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeObject(item);

            // Serialize that array
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize item stack", e);
            return null;
        }
    }

    // Deserialize Base64 string to a single ItemStack
    private ItemStack itemStackFromBase64(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            ItemStack item = (ItemStack) dataInput.readObject();

            dataInput.close();
            return item;
        } catch (ClassNotFoundException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize item stack", e);
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get inventories for player UUID: " + playerUUID, e);
        }

        return null;
    }

    // Data class to hold player inventories
    public static class PlayerInventories {
        private final ItemStack[] survivalInventory;
        private final ItemStack[] survivalArmor;
        private final ItemStack survivalOffhand;
        private final ItemStack[] creativeInventory;
        private final ItemStack[] creativeArmor;
        private final ItemStack creativeOffhand;

        public PlayerInventories(ItemStack[] survivalInventory, ItemStack[] survivalArmor, ItemStack survivalOffhand,
                                 ItemStack[] creativeInventory, ItemStack[] creativeArmor, ItemStack creativeOffhand) {
            this.survivalInventory = survivalInventory;
            this.survivalArmor = survivalArmor;
            this.survivalOffhand = survivalOffhand;
            this.creativeInventory = creativeInventory;
            this.creativeArmor = creativeArmor;
            this.creativeOffhand = creativeOffhand;
        }

        public ItemStack[] getSurvivalInventory() {
            return survivalInventory;
        }

        public ItemStack[] getSurvivalArmor() {
            return survivalArmor;
        }

        public ItemStack getSurvivalOffhand() {
            return survivalOffhand;
        }

        public ItemStack[] getCreativeInventory() {
            return creativeInventory;
        }

        public ItemStack[] getCreativeArmor() {
            return creativeArmor;
        }

        public ItemStack getCreativeOffhand() {
            return creativeOffhand;
        }
    }
}