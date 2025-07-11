package net.survivalfun.core.listeners.jobs;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.teleportation.TP;
import net.survivalfun.core.PluginStart;

/**
 * Inner class to handle player quit events for pet teleport cleanup
 */
/**
 * Listener class to handle teleport cancellation due to movement or damage
 */
public class TeleportCancelListener implements Listener {
    private final PluginStart plugin;
    private final Player player;
    private final Location originalLocation;
    private final double leniency;
    private boolean cancelled = false;
    
    public TeleportCancelListener(PluginStart plugin, Player player, Location originalLocation, double leniency) {
        this.plugin = plugin;
        this.player = player;
        this.originalLocation = originalLocation;
        this.leniency = leniency;
    }
    
    public TeleportCancelListener(TP tp, Player player, Location originalLocation, double allowedDistance) {
        this.plugin = tp.getPlugin();
        this.player = player;
        this.originalLocation = originalLocation;
        this.leniency = allowedDistance;
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.getPlayer().equals(player)) return;
        
        // Check if player moved more than the leniency allows
        Location from = originalLocation;
        Location to = event.getTo();
        
        if (from.getWorld() != to.getWorld() ||
            Math.abs(from.getX() - to.getX()) > leniency ||
            Math.abs(from.getY() - to.getY()) > leniency ||
            Math.abs(from.getZ() - to.getZ()) > leniency) {
            
            // Cancel teleportation
            cancelled = true;
            HandlerList.unregisterAll(this);
            player.sendMessage(plugin.getLangManager().get("tp.moved"));
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player damaged = (Player) event.getEntity();
        
        if (damaged.equals(player)) {
            // Cancel teleportation
            cancelled = true;
            HandlerList.unregisterAll(this);
            player.sendMessage(plugin.getLangManager().get("tp.moved")); // Using the same message for simplicity
        }
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
}