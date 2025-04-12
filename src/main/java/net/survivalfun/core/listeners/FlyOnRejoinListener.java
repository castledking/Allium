package net.survivalfun.core.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;

public class FlyOnRejoinListener implements Listener {

    private final JavaPlugin plugin;
    private final Map<String, Boolean> flyingCache = new HashMap<>();

    public FlyOnRejoinListener(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("core.fly") && player.isFlying()) {
            flyingCache.put(player.getName(), true);
        } else {
            flyingCache.remove(player.getName()); // Remove if not flying or no permission
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("core.fly")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> { //Delay to ensure player is fully loaded
                if (flyingCache.containsKey(player.getName()) && flyingCache.get(player.getName())) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                    flyingCache.remove(player.getName()); // Clean up after rejoin
                }
            }, 5L); // Delay of 5 ticks (adjust as needed)
        }
    }
}
