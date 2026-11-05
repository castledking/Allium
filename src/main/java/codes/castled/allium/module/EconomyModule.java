package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.database.EconomyTable;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.UUID;

public final class EconomyModule extends AbstractModule {

    private final EconomyTable table;
    private VaultEconomyProvider vaultProvider;

    public EconomyModule(PluginStart plugin) {
        super(plugin);
        this.table = new EconomyTable(plugin, plugin.getDatabaseManager());
    }

    @Override
    public String getName() {
        return "economy";
    }

    @Override
    public void onEnable() {
        table.init();
        plugin.getDatabaseManager().setEconomyTable(table);

        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vaultProvider = new VaultEconomyProvider();
            Bukkit.getServicesManager().register(Economy.class, vaultProvider, plugin, ServicePriority.Highest);
            plugin.logger().info("Hooked into Vault for economy.");
        }
    }

    @Override
    public void onDisable() {
        if (vaultProvider != null) {
            Bukkit.getServicesManager().unregister(Economy.class, vaultProvider);
            vaultProvider = null;
        }
    }

    public BigDecimal getBalance(UUID uuid) {
        BigDecimal bal = table.getBalance(uuid);
        if (bal == null) {
            table.createDefault(uuid, BigDecimal.valueOf(100));
            return BigDecimal.valueOf(100);
        }
        return bal;
    }

    public boolean setBalance(UUID uuid, BigDecimal amount) {
        return table.setBalance(uuid, amount);
    }

    public boolean deposit(UUID uuid, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return false;
        return table.addToBalance(uuid, amount);
    }

    public boolean withdraw(UUID uuid, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return false;
        if (!table.hasEnough(uuid, amount)) return false;
        return table.subtractFromBalance(uuid, amount);
    }

    public boolean hasAccount(UUID uuid) {
        return table.hasAccount(uuid);
    }

    public String format(BigDecimal amount) {
        return "$" + String.format("%,.2f", amount);
    }

    private final class VaultEconomyProvider implements Economy {

        @Override public boolean isEnabled() { return plugin.isEnabled(); }
        @Override public String getName() { return "Allium"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double amount) { return EconomyModule.this.format(BigDecimal.valueOf(amount)); }
        @Override public String currencyNamePlural() { return "dollars"; }
        @Override public String currencyNameSingular() { return "dollar"; }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return table.hasAccount(player.getUniqueId());
        }

        @Override
        public boolean hasAccount(String playerName) {
            return hasAccount(Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return hasAccount(player);
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return hasAccount(playerName);
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return EconomyModule.this.getBalance(player.getUniqueId()).doubleValue();
        }

        @Override
        public double getBalance(String playerName) {
            return getBalance(Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return getBalance(player);
        }

        @Override
        public double getBalance(String playerName, String world) {
            return getBalance(playerName);
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return getBalance(player) >= amount;
        }

        @Override
        public boolean has(String playerName, double amount) {
            return getBalance(playerName) >= amount;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return has(playerName, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (amount < 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amounts");
            }
            BigDecimal bd = BigDecimal.valueOf(amount);
            if (!table.hasEnough(player.getUniqueId(), bd)) {
                return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            }
            table.subtractFromBalance(player.getUniqueId(), bd);
            return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            return withdrawPlayer(playerName, amount);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            if (amount < 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amounts");
            }
            BigDecimal bd = BigDecimal.valueOf(amount);
            table.addToBalance(player.getUniqueId(), bd);
            return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            return depositPlayer(playerName, amount);
        }

        @Override public EconomyResponse createBank(String name, String player) { return notImplemented(); }
        @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notImplemented(); }
        @Override public EconomyResponse deleteBank(String name) { return notImplemented(); }
        @Override public EconomyResponse bankBalance(String name) { return notImplemented(); }
        @Override public EconomyResponse bankHas(String name, double amount) { return notImplemented(); }
        @Override public EconomyResponse bankWithdraw(String name, double amount) { return notImplemented(); }
        @Override public EconomyResponse bankDeposit(String name, double amount) { return notImplemented(); }
        @Override public EconomyResponse isBankOwner(String name, String playerName) { return notImplemented(); }
        @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notImplemented(); }
        @Override public EconomyResponse isBankMember(String name, String playerName) { return notImplemented(); }
        @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notImplemented(); }
        @Override public java.util.List<String> getBanks() { return java.util.Collections.emptyList(); }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            return table.createDefault(player.getUniqueId(), BigDecimal.valueOf(100));
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return createPlayerAccount(player);
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return createPlayerAccount(playerName);
        }

        private EconomyResponse notImplemented() {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank not supported");
        }
    }
}
