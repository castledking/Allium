package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Heal implements CommandExecutor {

    private final Lang lang;
    private final FileConfiguration config;
    private final PluginStart plugin;
    private final Selectors selectors;
    private final int healCooldownSeconds;
    private final Map<UUID, Long> lastHealTime = new HashMap<>();

    public Heal(Lang lang, FileConfiguration config, PluginStart plugin) {
        this.lang = lang;
        this.config = config;
        this.plugin = plugin;
        this.selectors = new Selectors(lang, plugin);
        this.healCooldownSeconds = config.getInt("cooldown", 60);
        if (!config.contains("cooldown")) {
            config.set("cooldown", 60);
        }
    }

    private boolean canHeal(Player player) {
        // Admins with permission bypass cooldown
        if (player.hasPermission("allium.heal.nocooldown")) {
            return true;
        }

        // If player hasn't used the command before, allow it
        if (!lastHealTime.containsKey(player.getUniqueId())) {
            return true;
        }

        // Check if cooldown has passed
        long lastUsed = lastHealTime.get(player.getUniqueId());
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = healCooldownSeconds * 1000L;

        if (currentTime - lastUsed < cooldownMillis) {
            // Cooldown not yet passed
            int remainingSeconds = (int) ((cooldownMillis - (currentTime - lastUsed)) / 1000);

            // Format the time in a more readable way
            String formattedTime = Text.formatTime(remainingSeconds);

            // Send message about remaining cooldown time
            String cooldownMessage = lang.get("cooldown")
                    .replace("{time}", formattedTime)
                    .replace("{cmd}", "heal");
            lang.sendMessage(player, cooldownMessage);

            return false;
        }

        return true;
    }



    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        // Handle console sender
        if (!(sender instanceof Player player)) {
            return handleConsoleCommand(sender, args);
        }
        
        // Check permission
        if (!player.hasPermission("allium.heal")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "heal");
            return true;
        }

        // Process command based on number of arguments
        switch (args.length) {
            case 0:
                // Heal self
                return handleHealSelf(player);
                
            case 1:
                // Check permission for healing others
                if (!player.hasPermission("allium.heal.others")) {
                    return handleHealSelf(player);
                }
                
                // Handle entity selector or player name
                return handleHealTarget(player, args[0]);
                
            default:
                // Show usage for invalid number of arguments
                String usage = lang.get("command-usage")
                        .replace("{cmd}", "/heal")
                        .replace("{args}", "[player|@selector]");
                lang.sendMessage(player, usage);
                return true;
        }
    }

    private boolean handleHealSelf(Player player) {
        if (!canHeal(player)) {
            return false;
        }
        
        if (healEntity(player)) {
            lang.sendMessage(player, "heal.self");
            lastHealTime.put(player.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        return false;
    }

    private boolean handleHealTarget(Player healer, String target) {
        // Check if target is a selector
        if (target.startsWith("@")) {
            if (!healer.hasPermission("allium.admin") || !healer.hasPermission("allium.selectors") &&
                    (target.startsWith("@e") || target.startsWith("@a"))) {
                Text.sendErrorMessage(healer, "no-permission", lang, "{cmd}", "use entity selectors");
                return false;
            }

            // Handle entity selector
            List<Entity> targets = selectors.parseSelector(target, healer);
            if (targets.isEmpty()) {
                Text.sendErrorMessage(healer, "no-entities-found", lang);
                return false;
            }

            // Heal all matching entities
            AtomicInteger healedCount = new AtomicInteger(0);
            targets.forEach(entity -> {
                if (healEntity(entity)) {
                    healedCount.incrementAndGet();

                    // Send message to the healed entity if it's a player
                    if (entity instanceof Player targetPlayer && !targetPlayer.equals(healer)) {
                        lang.sendMessage(targetPlayer, "heal.other",
                            "player", healer.getName()
                        );
                    }
                }
            });

            // Send success message to healer
            lang.sendMessage(healer, "heal.selector",
                "count", String.valueOf(healedCount.get()),
                "selector", target
            );

            return true;
        } else {
            // Handle player name
            return handlePlayerHealTarget(healer, target);
        }
    }

    private boolean handleConsoleCommand(CommandSender sender, String[] args) {
        if (args.length != 1) {
            String usage = lang.get("command-usage")
                    .replace("{cmd}", "heal")
                    .replace("{args}", "<player|@selector>");
            lang.sendMessage(sender, usage);
            return false;
        }

        // Try to find the target player(s)
        if (args[0].startsWith("@")) {
            // Handle selectors in console
            List<Entity> targets = selectors.parseSelector(args[0], null);
            if (targets.isEmpty()) {
                Text.sendErrorMessage(sender, "no-entities-found", lang);
                return false;
            }

            // Heal all matching entities
            int healedCount = 0;
            for (Entity entity : targets) {
                if (healEntity(entity)) {
                    healedCount++;
                }
            }

            // Send success message
            lang.sendMessage(sender, "heal.selector",
                "count", String.valueOf(healedCount),
                "selector", args[0]
            );

            return true;
        } else {
            // Handle single player name
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                    String targetName = Bukkit.getOfflinePlayer(args[0]).getName();
                    Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetName);
                    return true;
                } else {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }
            }

            // Heal target and notify
            if (healEntity(target)) {
                // Send message to command sender
                lang.sendMessage(sender, "heal.healed-other",
                    "name", target.getName()
                );

                // Send message to target
                lang.sendMessage(target, "heal.other",
                    "player", "Console"
                );
                return true;
            }
            return false;
        }
    }

    private boolean handlePlayerHealTarget(Player healer, String targetName) {
        // Check if player is trying to heal themselves by name
        if (targetName.equalsIgnoreCase(healer.getName())) {
            return handleHealSelf(healer);
        }

        // Try to find the target player
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            // Target is online - heal and notify
            if (healEntity(target)) {
                // Send message to command sender
                lang.sendMessage(healer, "heal.healed-other",
                    "name", target.getName()
                );

                // Send message to target with sound
                lang.sendMessage(target, "heal.other",
                    "player", healer.getName()
                );

                // Apply cooldown
                if (!healer.hasPermission("allium.heal.nocooldown")) {
                    lastHealTime.put(healer.getUniqueId(), System.currentTimeMillis());
                }
                return true;
            }
        } else {
            // Target is offline or doesn't exist
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
            if (offlineTarget.hasPlayedBefore()) {
                Text.sendErrorMessage(healer, "player-not-online", lang, "{name}", targetName);
            } else {
                Text.sendErrorMessage(healer, "player-not-found", lang, "{name}", targetName);
            }
        }
        return false;
    }

    private boolean healEntity(Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            try {
                // Heal the entity
                double maxHealth = 20.0; // Default max health
                
                // Get max health attribute if available
                try {
                    AttributeInstance maxHealthAttr = livingEntity.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealthAttr != null) {
                        maxHealth = maxHealthAttr.getValue();
                    }
                } catch (Exception e) {
                    // Fallback if attribute system is not available
                    maxHealth = 20.0;
                }
                
                // Heal to max health
                livingEntity.setHealth(Math.min(livingEntity.getHealth(), maxHealth));
                
                // Clear negative potion effects for living entities
                livingEntity.clearActivePotionEffects();

                // remove stuck arrows
                livingEntity.setArrowsInBody(0);
                
                // Extinguish fire and freeze
                livingEntity.setFireTicks(0);
                try {
                    livingEntity.setFreezeTicks(0);
                } catch (NoSuchMethodError e) {
                    // Method not available in this version
                }
                
                // Additional player-specific healing
                if (livingEntity instanceof Player player) {
                    player.setFoodLevel(20);
                    player.setSaturation(20);
                    
                    // Try to clear arrows (using reflection for cross-version compatibility)
                    try {
                        player.setArrowsInBody(0, true);
                        player.setArrowsInBody(0, false);
                    } catch (NoSuchMethodError e) {
                        // Method not available in this version
                    }
                }
                
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Error while healing entity: " + e.getMessage());
                return false;
            }
        } else if (entity instanceof Damageable damageable) {
            // For non-living damageable entities (if any)
            damageable.setHealth(20.0);
            return true;
        }
        return false;
    }

    /**
     * Clean up the cooldown map when plugin disables to prevent memory leaks
     */
    public void cleanup() {
        lastHealTime.clear();
    }

}
