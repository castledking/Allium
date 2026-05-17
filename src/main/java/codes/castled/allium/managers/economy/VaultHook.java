package codes.castled.allium.managers.economy;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.logging.Logger;

/**
 * Handles integration with Vault
 */
public class VaultHook {
    private final PluginStart plugin;
    private final Logger logger;
    private Economy provider;

    public VaultHook(PluginStart plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Attempts to hook into Vault
     * @return true if successful, false otherwise
     */
    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            logger.info("Vault not found, economy hook not enabled");
            return false;
        }

        ServicesManager servicesManager = Bukkit.getServicesManager();
        provider = new VaultEconomyProvider(plugin, plugin.getEconomyManager());
        
        servicesManager.register(Economy.class, provider, plugin, ServicePriority.Highest);
        logger.info("Successfully registered economy with Vault");
        return true;
    }

    /**
     * Unhooks from Vault
     */
    public void unhook() {
        if (provider != null) {
            Bukkit.getServicesManager().unregister(Economy.class, provider);
            logger.info("Unregistered economy from Vault");
        }
    }
}
