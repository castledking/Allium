package net.survivalfun.core.managers.economy;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Economy manager for SFCore
 * Handles all economy-related operations
 */
public class Economy {
    private final Database database;
    private final PluginStart plugin;
    private BigDecimal defaultBalance;
    private String currencySymbol;
    private int decimalPlaces;
    private boolean symbolBeforeAmount;
    private boolean spaceBetween;

    public Economy(PluginStart plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        loadConfig();
    }
    
    /**
     * Load economy configuration values
     */
    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        
        // Load currency symbol from config, default to $ if not found
        currencySymbol = config.getString("economy.currency-symbol", "$");
        
        // Load default balance from config, default to 100.00 if not found
        double startingBalance = config.getDouble("economy.starting-balance", 100.00);
        defaultBalance = new BigDecimal(String.valueOf(startingBalance));
        
        // Load decimal places from config, default to 2 if not found
        decimalPlaces = config.getInt("economy.decimal-places", 2);
        
        // Load symbol position from config
        symbolBeforeAmount = config.getBoolean("economy.symbol-before-amount", true);
        
        // Load space between setting from config
        spaceBetween = config.getBoolean("economy.space-between", false);
    }

    /**
     * Get a player's balance
     * @param player The player
     * @return The player's balance
     */
    public BigDecimal getBalance(OfflinePlayer player) {
        return getBalance(player.getUniqueId());
    }

    /**
     * Get a player's balance
     * @param playerUUID The player's UUID
     * @return The player's balance
     */
    public BigDecimal getBalance(UUID playerUUID) {
        BigDecimal balance = database.getPlayerBalance(playerUUID);
        if (balance == null) {
            // Create default balance for new player
            database.createDefaultPlayerBalance(playerUUID, defaultBalance);
            return defaultBalance;
        }
        return balance;
    }

    /**
     * Set a player's balance
     * @param player The player
     * @param amount The new balance
     * @return true if successful, false otherwise
     */
    public boolean setBalance(OfflinePlayer player, BigDecimal amount) {
        return setBalance(player.getUniqueId(), amount);
    }

    /**
     * Set a player's balance
     * @param playerUUID The player's UUID
     * @param amount The new balance
     * @return true if successful, false otherwise
     */
    public boolean setBalance(UUID playerUUID, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return false; // Don't allow negative balances
        }
        return database.setPlayerBalance(playerUUID, amount);
    }

    /**
     * Add to a player's balance
     * @param player The player
     * @param amount The amount to add
     * @return true if successful, false otherwise
     */
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        return deposit(player.getUniqueId(), amount);
    }

    /**
     * Add to a player's balance
     * @param playerUUID The player's UUID
     * @param amount The amount to add
     * @return true if successful, false otherwise
     */
    public boolean deposit(UUID playerUUID, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false; // Don't allow negative or zero deposits
        }
        return database.addToPlayerBalance(playerUUID, amount);
    }

    /**
     * Subtract from a player's balance
     * @param player The player
     * @param amount The amount to subtract
     * @return true if successful, false otherwise
     */
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    /**
     * Subtract from a player's balance
     * @param playerUUID The player's UUID
     * @param amount The amount to subtract
     * @return true if successful, false otherwise
     */
    public boolean withdraw(UUID playerUUID, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false; // Don't allow negative or zero withdrawals
        }
        
        // Check if player has enough balance
        if (!hasEnough(playerUUID, amount)) {
            return false;
        }
        
        return database.subtractFromPlayerBalance(playerUUID, amount);
    }

    /**
     * Check if a player has enough balance
     * @param player The player
     * @param amount The amount to check
     * @return true if the player has enough balance, false otherwise
     */
    public boolean hasEnough(OfflinePlayer player, BigDecimal amount) {
        return hasEnough(player.getUniqueId(), amount);
    }

    /**
     * Check if a player has enough balance
     * @param playerUUID The player's UUID
     * @param amount The amount to check
     * @return true if the player has enough balance, false otherwise
     */
    public boolean hasEnough(UUID playerUUID, BigDecimal amount) {
        return database.hasEnoughBalance(playerUUID, amount);
    }

    /**
     * Transfer money from one player to another
     * @param from The player sending money
     * @param to The player receiving money
     * @param amount The amount to transfer
     * @return true if successful, false otherwise
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, BigDecimal amount) {
        return transfer(from.getUniqueId(), to.getUniqueId(), amount);
    }

    /**
     * Transfer money from one player to another
     * @param fromUUID The UUID of the player sending money
     * @param toUUID The UUID of the player receiving money
     * @param amount The amount to transfer
     * @return true if successful, false otherwise
     */
    public boolean transfer(UUID fromUUID, UUID toUUID, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false; // Don't allow negative or zero transfers
        }
        
        // Check if sender has enough balance
        if (!hasEnough(fromUUID, amount)) {
            return false;
        }
        
        // Withdraw from sender
        if (!withdraw(fromUUID, amount)) {
            return false;
        }
        
        // Deposit to receiver
        if (!deposit(toUUID, amount)) {
            // If deposit fails, refund the sender
            deposit(fromUUID, amount);
            return false;
        }
        
        return true;
    }

    /**
     * Get the top balances
     * @param limit The maximum number of entries to return
     * @return A list of top balances
     */
    public List<BalanceEntry> getTopBalances(int limit) {
        return database.getTopBalances(limit);
    }

    /**
     * Get the name of this economy system
     * @return The name
     */
    public String getName() {
        return "Allium";
    }

    /**
     * Format a balance as a string
     * @param balance The balance to format
     * @return The formatted balance
     */
    public String formatBalance(BigDecimal balance) {
        // Format the number with the configured decimal places
        String formattedAmount = String.format("%,." + decimalPlaces + "f", balance);
        
        // Construct the final string based on configuration
        if (symbolBeforeAmount) {
            return currencySymbol + formattedAmount;
        } else if (spaceBetween) {
            return formattedAmount + " " + currencySymbol;
        } else {
            return formattedAmount + currencySymbol;
        }
    }
}
