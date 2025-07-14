package net.survivalfun.core.managers.economy;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;

public class VaultEconomyProvider implements Economy {
    private final PluginStart plugin;
    private final net.survivalfun.core.managers.economy.Economy economyManager;

    public VaultEconomyProvider(PluginStart plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomy();
    }

    private BigDecimal getPlayerBalance(UUID uuid) {
        BigDecimal balance = economyManager.getBalance(Bukkit.getOfflinePlayer(uuid));
        plugin.getLogger().info("Retrieved balance for UUID " + uuid + ": " + balance);
        return balance;
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = plugin != null && plugin.isEnabled();
        plugin.getLogger().info("VaultEconomyProvider isEnabled: " + enabled);
        return enabled;
    }

    @Override
    @NotNull
    public String getName() {
        return "Allium";
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(@NotNull String pluginName) {
        return 2;
    }

    @Override
    @NotNull
    @Deprecated
    public String format(@NotNull BigDecimal amount) {
        return economyManager.formatBalance(amount);
    }

    @Override
    @NotNull
    public String format(@NotNull String pluginName, @NotNull BigDecimal amount) {
        return economyManager.formatBalance(amount);
    }

    @Override
    @NotNull
    @Deprecated
    public String format(@NotNull BigDecimal amount, @NotNull String currency) {
        return format(plugin.getDescription().getName(), amount, currency);
    }

    @Override
    @NotNull
    public String format(@NotNull String pluginName, @NotNull BigDecimal amount, @NotNull String currency) {
        return economyManager.formatBalance(amount);
    }

    @Override
    public boolean hasCurrency(@NotNull String currency) {
        return "Dollar".equalsIgnoreCase(currency) || "Dollars".equalsIgnoreCase(currency);
    }

    @Override
    @NotNull
    public String getDefaultCurrency(@NotNull String pluginName) {
        return "Dollar";
    }

    @Override
    @NotNull
    public String defaultCurrencyNamePlural(@NotNull String pluginName) {
        return "Dollars";
    }

    @Override
    @NotNull
    public String defaultCurrencyNameSingular(@NotNull String pluginName) {
        return "Dollar";
    }

    @Override
    @NotNull
    public Collection<String> currencies() {
        return Collections.singletonList("Dollar");
    }

    @Override
    @Deprecated
    public boolean createAccount(@NotNull UUID accountID, @NotNull String name) {
        plugin.getLogger().info("Creating account for UUID: " + accountID + ", Name: " + name);
        return createAccount(accountID, name, true);
    }

    @Override
    public boolean createAccount(@NotNull UUID accountID, @NotNull String name, boolean player) {
        plugin.getLogger().info("Creating account for UUID: " + accountID + ", Name: " + name + ", Player: " + player);
        return true; // Accounts are auto-created in getBalance
    }

    @Override
    @Deprecated
    public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName) {
        return createAccount(accountID, name, worldName, true);
    }

    @Override
    public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName, boolean player) {
        plugin.getLogger().info("Creating account for UUID: " + accountID + ", Name: " + name + ", World: " + worldName);
        return true; // Accounts are auto-created in getBalance
    }

    @Override
    @NotNull
    public Map<UUID, String> getUUIDNameMap() {
        return Collections.emptyMap();
    }

    @Override
    public Optional<String> getAccountName(@NotNull UUID accountID) {
        String name = Bukkit.getOfflinePlayer(accountID).getName();
        plugin.getLogger().info("Account name for UUID " + accountID + ": " + name);
        return Optional.ofNullable(name);
    }

    @Override
    public boolean hasAccount(@NotNull UUID accountID) {
        return true; // Accounts are auto-created
    }

    @Override
    public boolean hasAccount(@NotNull UUID accountID, @NotNull String worldName) {
        return hasAccount(accountID);
    }

    @Override
    public boolean renameAccount(@NotNull UUID accountID, @NotNull String name) {
        plugin.getLogger().info("Rename account not supported for UUID: " + accountID);
        return false;
    }

    @Override
    public boolean renameAccount(@NotNull String plugin, @NotNull UUID accountID, @NotNull String name) {
        return renameAccount(accountID, name);
    }

    @Override
    public boolean deleteAccount(@NotNull String plugin, @NotNull UUID accountID) {
        economyManager.setBalance(Bukkit.getOfflinePlayer(accountID), BigDecimal.ZERO);
        return true;
    }

    @Override
    public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency) {
        return hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency, @NotNull String world) {
        return hasCurrency(currency);
    }

    @Override
    @NotNull
    @Deprecated
    public BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID) {
        return getPlayerBalance(accountID);
    }

    @Override
    @NotNull
    @Deprecated
    public BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world) {
        return getPlayerBalance(accountID);
    }

    @Override
    @NotNull
    @Deprecated
    public BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull String currency) {
        return getPlayerBalance(accountID);
    }

    @Override
    public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        boolean hasEnough = economyManager.hasEnough(Bukkit.getOfflinePlayer(accountID), amount);
        plugin.getLogger().info("Checking if UUID " + accountID + " has " + amount + ": " + hasEnough);
        return hasEnough;
    }

    @Override
    public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    @NotNull
    public EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(accountID);
        plugin.getLogger().info("Withdrawing " + amount + " from UUID: " + accountID);
        if (amount.signum() < 0) {
            return new EconomyResponse(BigDecimal.ZERO, getPlayerBalance(accountID), ResponseType.FAILURE, "Cannot withdraw negative funds.");
        }
        if (economyManager.hasEnough(player, amount)) {
            economyManager.withdraw(player, amount);
            return new EconomyResponse(amount, getPlayerBalance(accountID), ResponseType.SUCCESS, "");
        }
        return new EconomyResponse(BigDecimal.ZERO, getPlayerBalance(accountID), ResponseType.FAILURE, "Insufficient funds.");
    }

    @Override
    @NotNull
    public EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    @NotNull
    public EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    @NotNull
    public EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(accountID);
        plugin.getLogger().info("Depositing " + amount + " to UUID: " + accountID);
        if (amount.signum() < 0) {
            return new EconomyResponse(BigDecimal.ZERO, getPlayerBalance(accountID), ResponseType.FAILURE, "Cannot deposit negative funds.");
        }
        economyManager.deposit(player, amount);
        return new EconomyResponse(amount, getPlayerBalance(accountID), ResponseType.SUCCESS, "");
    }

    @Override
    @NotNull
    public EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    @NotNull
    public EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public boolean createSharedAccount(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String name, @NotNull UUID owner) {
        plugin.getLogger().info("Create shared account not supported for UUID: " + accountID);
        return false;
    }

    @Override
    public boolean isAccountOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return accountID.equals(uuid);
    }

    @Override
    public boolean setOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        plugin.getLogger().info("Set owner not supported for UUID: " + accountID);
        return false;
    }

    @Override
    public boolean isAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return accountID.equals(uuid);
    }

    @Override
    public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        plugin.getLogger().info("Add account member not supported for UUID: " + accountID);
        return false;
    }

    @Override
    public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission... initialPermissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        plugin.getLogger().info("Remove account member not supported for UUID: " + accountID);
        return false;
    }

    @Override
    public boolean hasAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission) {
        return isAccountOwner(pluginName, accountID, uuid);
    }

    @Override
    public boolean updateAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission, boolean value) {
        plugin.getLogger().info("Update account permission not supported for UUID: " + accountID);
        return false;
    }
}