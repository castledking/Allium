package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.Freeze;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

public class FreezeListener implements Listener {

    private final PluginStart plugin;
    private final Freeze freezeCommand;

    public FreezeListener(PluginStart plugin, Freeze freezeCommand) {
        this.plugin = plugin;
        this.freezeCommand = freezeCommand;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // If freeze all is active and player doesn't have resist permission, freeze them
        if (freezeCommand.isFreezeAllActive() && !player.hasPermission("allium.freeze.resist")) {
            // Use console as the freezer to avoid permission issues
            freezeCommand.freezePlayer(plugin.getServer().getConsoleSender(), player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // If player quits while frozen, unfreeze them
        if (freezeCommand.isFrozen(player)) {
            freezeCommand.handlePlayerQuit(player);
        }
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        
        // Check if the player is frozen
        if (freezeCommand.isFrozen(player)) {
            // Get the from and to locations
            Location from = event.getFrom();
            Location to = event.getTo();
            
            // If player is trying to move up (jumping)
            if (to.getY() > from.getY()) {
                // Cancel vertical movement but allow looking around
                to.setY(from.getY());
                
                // If the player is on the ground, prevent any vertical movement
                if (player.isOnGround()) {
                    // Teleport them back to their current position to prevent any movement
                    player.teleport(new Location(
                        from.getWorld(),
                        from.getX(),
                        Math.floor(from.getY()) + 0.1,  // Slightly above the block to prevent falling through
                        from.getZ(),
                        to.getYaw(),
                        to.getPitch()
                    ));
                } else {
                    // If in the air, just prevent upward movement
                    event.setTo(to);
                }
                
                // Also set velocity to zero to prevent any remaining momentum
                player.setVelocity(new Vector(0, 0, 0));
            }
        }
    }
}
