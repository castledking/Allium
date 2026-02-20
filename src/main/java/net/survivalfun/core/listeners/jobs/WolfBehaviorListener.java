package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class WolfBehaviorListener implements Listener {

    // List to track calmed wolves by their UUID
    private final Set<UUID> calmedWolves = new HashSet<>();

    @EventHandler
    public void onWolfInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Wolf wolf)) {
            Text.sendDebugLog(INFO, "Interacted entity is not a wolf");
            return;
        }

        Text.sendDebugLog(INFO, "Wolf interaction detected for wolf " + wolf.getUniqueId() + " owned by " + event.getPlayer().getName());
        
        if (!wolf.isTamed()) {
            Text.sendDebugLog(INFO, "Wolf is not tamed");
            return;
        }

        AnimalTamer owner = wolf.getOwner();
        Player player = event.getPlayer();

        if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) {
            Text.sendDebugLog(INFO, "Player is not the owner of the wolf");
            return;
        }

        LivingEntity currentTarget = wolf.getTarget();
        if (!(currentTarget instanceof Player targetPlayer)) {
            Text.sendDebugLog(INFO, "Wolf has no player target");
            return;
        }

        Text.sendDebugLog(INFO, "Wolf is targeting player " + targetPlayer.getName() + " and is angry: " + wolf.isAngry());
        
        // Store the target player for use in delayed task
        final Player finalTargetPlayer = targetPlayer;
        
        // Use Bukkit scheduler with longer delay for testing
        Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
            Text.sendDebugLog(INFO, "Delayed task running for wolf " + wolf.getUniqueId());
            
            if (!wolf.isValid()) {
                Text.sendDebugLog(INFO, "Wolf " + wolf.getUniqueId() + " is not valid");
                return;
            }

            Text.sendDebugLog(INFO, "Wolf is sitting: " + wolf.isSitting());
            Text.sendDebugLog(INFO, "Wolf is angry: " + wolf.isAngry());
            
            // Check if wolf is sitting (target may be cleared by sit command)
            if (!wolf.isSitting()) {
                Text.sendDebugLog(INFO, "Wolf " + wolf.getUniqueId() + " is not sitting");
                return;
            }

            // Since target may be cleared, use the stored target player
            if (finalTargetPlayer == null) {
                Text.sendDebugLog(INFO, "No stored target player");
                return;
            }

            // Add wolf to calmed list
            calmedWolves.add(wolf.getUniqueId());
            
            wolf.setTarget(null);
            if (wolf.isAngry()) {
                wolf.setAngry(false);
            }
            
            Text.sendDebugLog(INFO, "Wolf " + wolf.getUniqueId() + " calmed successfully");
            player.sendMessage(Text.colorize("&aYou have calmed the wolf, it will no longer damage &e" + finalTargetPlayer.getName() + "&a."));
        }, 5L);
    }

    @EventHandler
    public void onWolfDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Wolf wolf)) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Text.sendDebugLog(INFO, "Wolf damage event: wolf " + wolf.getUniqueId() + " attacking " + victim.getName());
        Text.sendDebugLog(INFO, "Wolf in calmed list: " + calmedWolves.contains(wolf.getUniqueId()));
        
        // Check if the wolf is in the calmed list
        if (!calmedWolves.contains(wolf.getUniqueId())) {
            return;
        }

        // Prevent damage from calmed wolves
        event.setCancelled(true);
        
        // Force the wolf to sit if not already sitting
        if (!wolf.isSitting()) {
            wolf.setSitting(true);
        }
        
        // Clear target and anger to ensure calmness
        wolf.setTarget(null);
        if (wolf.isAngry()) {
            wolf.setAngry(false);
        }
        
        Text.sendDebugLog(INFO, "Damage prevented for calmed wolf " + wolf.getUniqueId());
        
        // Notify the victim
        if (victim.hasPermission("allium.wolfcalm.notify")) {
            victim.sendMessage(Text.colorize("&aA calmed wolf tried to attack you but was stopped."));
        }
        
        // Notify the owner if online
        AnimalTamer owner = wolf.getOwner();
        if (owner instanceof Player ownerPlayer && ownerPlayer.isOnline()) {
            ownerPlayer.sendMessage(Text.colorize("&aYour calmed wolf tried to attack &e" + victim.getName() + "&a but was forced to sit."));
        }
    }
}