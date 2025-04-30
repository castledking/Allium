package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SlimeCushionListener implements Listener {

    private final JavaPlugin plugin;
    private final double checkRadius;
    private final boolean showMessage;
    private final String cushionMessage;
    private final double damageReductionFactor;
    private final double bounceMultiplier;
    private final double maxBounceVelocity;
    private final boolean playSound;

    // Set to track players who have already received the cushion message
    private final Set<Player> messagedPlayers = new HashSet<>();

    /**
     * Creates a listener that reduces fall damage when landing near slimes.
     *
     * @param plugin The plugin instance
     * @param checkRadius The radius to check for slimes near the player
     * @param damageReductionFactor How much to reduce damage by (0.0 = no damage, 1.0 = full damage)
     * @param bounceMultiplier How much to multiply the damage for the bounce height
     * @param maxBounceVelocity Maximum upward velocity for the bounce
     * @param showMessage Whether to show a message when cushioning fall
     * @param cushionMessage The message to show (can be null if showMessage is false)
     * @param playSound Whether to play a bounce sound
     */
    public SlimeCushionListener(JavaPlugin plugin, double checkRadius, double damageReductionFactor,
                                double bounceMultiplier, double maxBounceVelocity,
                                boolean showMessage, String cushionMessage, boolean playSound) {
        this.plugin = plugin;
        this.checkRadius = checkRadius;
        this.damageReductionFactor = Math.max(0.0, Math.min(1.0, damageReductionFactor)); // Clamp between 0 and 1
        this.bounceMultiplier = bounceMultiplier;
        this.maxBounceVelocity = maxBounceVelocity;
        this.showMessage = showMessage;
        this.cushionMessage = cushionMessage;
        this.playSound = playSound;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("SlimeCushionListener registered with radius: " + checkRadius);
    }

    /**
     * Creates a listener with default settings.
     *
     * @param plugin The plugin instance
     */
    public SlimeCushionListener(JavaPlugin plugin) {
        this(plugin, 2.0, 0.5, 0.1, 1.5, true, "&aThe slime cushioned your fall!", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        // Check if it's fall damage to a player
        if (event.getEntityType() != EntityType.PLAYER || event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        Player player = (Player) event.getEntity();

        // Check if there's a slime within the specified radius
        if (isNearSlime(player)) {
            // Get the original damage before reduction
            double originalDamage = event.getDamage();
            double newDamage = originalDamage * damageReductionFactor;

            // Apply bounce effect based on original damage
            applyBounceEffect(player, originalDamage);

            // Reduce the damage
            event.setDamage(newDamage);

            // If damage is very small, just cancel the event
            if (newDamage < 0.5) {
                event.setCancelled(true);
            }

            // Show message if enabled and the player hasn't already received it
            if (showMessage && cushionMessage != null && !cushionMessage.isEmpty() && !messagedPlayers.contains(player)) {
                player.sendMessage(Text.parseColors(cushionMessage));
                messagedPlayers.add(player); // Add the player to the set
            }

            // Play sound if enabled
            if (playSound) {
                player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1.0f, 1.0f);
            }

            // Debug info
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Player " + player.getName() + " cushioned fall. Original damage: " +
                        originalDamage + ", New damage: " + newDamage);
            }
        }
    }

    /**
     * Applies a bounce effect to the player based on the fall damage
     *
     * @param player The player to bounce
     * @param fallDamage The original fall damage amount
     */
    private void applyBounceEffect(Player player, double fallDamage) {
        // Calculate bounce velocity based on fall damage
        // Higher damage = higher bounce
        double bounceVelocity = Math.min(fallDamage * bounceMultiplier, maxBounceVelocity);

        // Apply the upward velocity
        Vector currentVelocity = player.getVelocity();
        Vector newVelocity = new Vector(
                currentVelocity.getX() * 0.8, // Preserve some horizontal momentum
                bounceVelocity,               // Set upward velocity based on damage
                currentVelocity.getZ() * 0.8  // Preserve some horizontal momentum
        );

        // Apply the velocity in the next tick to avoid conflicts
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.setVelocity(newVelocity);

            // Play bounce sound
            if (playSound) {
                player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1.0f,
                        // Adjust pitch based on bounce height - higher bounce = lower pitch
                        (float)(1.0 - (bounceVelocity / maxBounceVelocity) * 0.3));
            }

            // Debug info
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Applied bounce to " + player.getName() +
                        " with velocity: " + bounceVelocity +
                        " (fall damage: " + fallDamage + ")");
            }
        });
    }

    /**
     * Checks if there is a slime within the specified radius of the player.
     *
     * @param player The player to check
     * @return true if a slime is within the radius
     */
    private boolean isNearSlime(Player player) {
        Location playerLocation = player.getLocation();

        // Get nearby entities within the check radius
        List<Entity> nearbyEntities = player.getNearbyEntities(checkRadius, checkRadius, checkRadius);

        // Check if any of the nearby entities are slimes
        for (Entity entity : nearbyEntities) {
            if (entity instanceof Slime) {
                Location slimeLocation = entity.getLocation();

                // Ensure the slime is below or near the player
                if (slimeLocation.getY() <= playerLocation.getY()) {
                    return true;
                }
            }
        }

        return false;
    }
}