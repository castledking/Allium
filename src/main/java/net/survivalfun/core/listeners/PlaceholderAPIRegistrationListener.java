package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.placeholderapi.AlliumPlaceholder;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * Registers our PlaceholderAPI expansion when ServerLoadEvent fires.
 * PlaceholderAPI loads its expansion manager at this point, so registering here
 * ensures our expansion is recognized.
 */
public class PlaceholderAPIRegistrationListener implements Listener {

    private final PluginStart plugin;

    public PlaceholderAPIRegistrationListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        HandlerList.unregisterAll(this);
        tryRegister();
    }

    private void tryRegister() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        if (plugin.getPlaceholderExpansion() != null && plugin.getPlaceholderExpansion().isRegistered()) {
            return;
        }
        try {
            AlliumPlaceholder expansion = new AlliumPlaceholder(plugin);
            if (expansion.register()) {
                plugin.setPlaceholderExpansion(expansion);
                plugin.getLogger().info("PlaceholderAPI expansion 'allium' registered successfully.");
            } else {
                plugin.getLogger().warning("PlaceholderAPI expansion 'allium' registration returned false. Try /papi reload.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI expansion registration error: " + t.getMessage());
            if (plugin.isDebugMode()) {
                t.printStackTrace();
            }
        }
    }
}
