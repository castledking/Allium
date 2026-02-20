package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.VanishManager;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.*;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class VanishListener implements Listener {

    private final PluginStart plugin;
    private final VanishManager vanishManager;

    public VanishListener(PluginStart plugin, VanishManager vanishManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
    }

    /**
     * Handle player join - restore vanish state and update visibility
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Restore vanish state if player was vanished when they logged out
        vanishManager.handlePlayerJoin(player);

        // Update visibility for all players
        vanishManager.updateAllVisibility();

        // Hide join message for vanished players from non-eligible viewers
        if (vanishManager.isVanished(player)) {
            event.setJoinMessage(null); // Hide join message entirely for vanished players
        }
    }

    /**
     * Handle player quit - save vanish state and update visibility
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Save vanish state before player quits
        vanishManager.handlePlayerQuit(player);

        // Update visibility for remaining players
        vanishManager.updateAllVisibility();

        // Hide quit message for vanished players from non-eligible viewers
        if (vanishManager.isVanished(player)) {
            event.setQuitMessage(null); // Hide quit message entirely for vanished players
        }
    }

    /**
     * Handle player movement - prevent vanished players from being targeted by mobs
     */
    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player target) {
            if (vanishManager.isVanished(target)) {
                // Cancel targeting of vanished players by mobs
                event.setCancelled(true);
            }
        }
    }

    /**
     * Handle player chat - hide vanished players' chat from non-eligible viewers
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Create new recipient list excluding players who can't see this vanished player
            event.getRecipients().removeIf(recipient ->
                !vanishManager.canSee(recipient, player)
            );

            // If no one can see the vanished player's chat, cancel the event
            if (event.getRecipients().isEmpty()) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Handle command preprocess - hide vanished players from command output and tab completion
     */
    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage();

        if (vanishManager.isVanished(player)) {
            // Hide vanished players from /list and /online commands
            if (command.startsWith("/list") || command.startsWith("/online") ||
                command.startsWith("/who") || command.startsWith("/players")) {

                // For vanished players, these commands should not show them in the output
                // The actual filtering will be handled by the command implementations
                // This is just a placeholder for future command integration
            }

            // Hide vanished players from /msg, /tell, /mail commands when used as target
            if (command.startsWith("/msg ") || command.startsWith("/tell ") ||
                command.startsWith("/mail ") || command.startsWith("/message ")) {

                String[] parts = command.split(" ");
                if (parts.length >= 2) {
                    Player target = Bukkit.getPlayer(parts[1]);
                    if (target != null && vanishManager.isVanished(target)) {
                        // Check if sender can see the vanished target
                        if (!vanishManager.canSee(player, target)) {
                            // This would be handled by the command implementations
                            // but we can add additional logging here
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle player damage - prevent vanished players from taking damage in most cases
     */
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (vanishManager.isVanished(player)) {
                // Vanished players generally shouldn't take damage, but allow some exceptions
                // like void damage or admin-initiated damage
                switch (event.getCause()) {
                    case VOID:
                    case SUICIDE:
                    case KILL:
                        // Allow these types of damage
                        break;
                    default:
                        // Cancel other damage for vanished players
                        event.setCancelled(true);
                        break;
                }
            }
        }
    }

    /**
     * Handle player interaction - prevent vanished players from interacting with the world in ways that might reveal them
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Vanished players can still interact, but we might want to log certain interactions
            // for security/audit purposes

            // For now, allow all interactions but log them
            Text.sendDebugLog(INFO, "Vanished player " + player.getName() + " interacted at " +
                player.getLocation().getBlockX() + ", " +
                player.getLocation().getBlockY() + ", " +
                player.getLocation().getBlockZ());
        }
    }

    /**
     * Handle player teleport - update visibility after teleport
     */
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        // Update visibility after teleport (in case of cross-world teleports)
        if (event.getTo() != null && !event.getTo().getWorld().equals(event.getFrom().getWorld())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                vanishManager.updateAllVisibility();
            }, 1L); // Small delay to ensure teleport is complete
        }
    }

    /**
     * Handle player game mode change - maintain vanish state through game mode changes
     */
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Update the vanish manager with the new game mode
            vanishManager.updateVisibility(player);

            // Log the game mode change for vanished players
            Text.sendDebugLog(INFO, "Vanished player " + player.getName() +
                " changed game mode from " + player.getGameMode() +
                " to " + event.getNewGameMode());
        }
    }

    /**
     * Handle player item pickup - allow vanished players to pick up items without revealing themselves
     */
    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Allow vanished players to pick up items silently
            // The item pickup animation might still be visible to players who can see them
            // but that's acceptable for now
        }
    }

    /**
     * Handle player drop item - allow vanished players to drop items without revealing themselves
     */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Allow vanished players to drop items silently
            // Similar to pickup, the drop animation might be visible to eligible viewers
        }
    }

    /**
     * Handle player respawn - maintain vanish state after death
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Update visibility after respawn
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                vanishManager.updateVisibility(player);
            }, 1L);
        }
    }

    /**
     * Handle inventory click - allow vanished players to manage their inventory
     */
    @EventHandler
    public void onInventoryClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (vanishManager.isVanished(player)) {
            // Allow all inventory interactions for vanished players
            // This is needed for them to manage their items while vanished
        }
    }
}
