package codes.castled.allium.database;

import codes.castled.allium.PluginStart;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class EconomyTable {

    private final PluginStart plugin;
    private final DatabaseManager db;

    public EconomyTable(PluginStart plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        try (Connection conn = db.getConnection()) {
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS allium_balances ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "balance DECIMAL(40,2) NOT NULL DEFAULT 100.00,"
                + "FOREIGN KEY (uuid) REFERENCES allium_players(uuid) ON DELETE CASCADE"
                + ")"
            );
        } catch (SQLException e) {
            plugin.logger().severe("Failed to create balances table: " + e.getMessage());
        }
    }

    public BigDecimal getBalance(UUID uuid) {
        String sql = "SELECT balance FROM allium_balances WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBigDecimal("balance");
            }
        } catch (SQLException e) {
            plugin.logger().severe("Failed to get balance: " + e.getMessage());
        }
        return null;
    }

    public boolean setBalance(UUID uuid, BigDecimal amount) {
        String sql = "INSERT INTO allium_balances (uuid, balance) VALUES (?, ?) "
                   + "ON DUPLICATE KEY UPDATE balance = ?";
        String sqlite = "INSERT OR REPLACE INTO allium_balances (uuid, balance) VALUES (?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 db.getType() == DatabaseType.SQLITE ? sqlite : sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setBigDecimal(2, amount);
            if (db.getType() != DatabaseType.SQLITE) {
                stmt.setBigDecimal(3, amount);
            }
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.logger().severe("Failed to set balance: " + e.getMessage());
            return false;
        }
    }

    public boolean addToBalance(UUID uuid, BigDecimal amount) {
        BigDecimal current = getBalance(uuid);
        if (current == null) {
            return setBalance(uuid, amount);
        }
        return setBalance(uuid, current.add(amount));
    }

    public boolean subtractFromBalance(UUID uuid, BigDecimal amount) {
        BigDecimal current = getBalance(uuid);
        if (current == null) return false;
        BigDecimal result = current.subtract(amount);
        if (result.compareTo(BigDecimal.ZERO) < 0) return false;
        return setBalance(uuid, result);
    }

    public boolean hasEnough(UUID uuid, BigDecimal amount) {
        BigDecimal balance = getBalance(uuid);
        return balance != null && balance.compareTo(amount) >= 0;
    }

    public boolean hasAccount(UUID uuid) {
        return getBalance(uuid) != null;
    }

    public boolean createDefault(UUID uuid, BigDecimal defaultBalance) {
        if (hasAccount(uuid)) return false;
        return setBalance(uuid, defaultBalance);
    }
}
