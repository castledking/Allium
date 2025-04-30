package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Heal implements CommandExecutor {

    private final Lang lang;
    private final FileConfiguration config;
    private final PluginStart plugin;
    private final int healCooldownSeconds;
    private final Map<UUID, Long> lastHealTime = new HashMap<>();

    public Heal(Lang lang, FileConfiguration config, PluginStart plugin) {
        this.lang = lang;
        this.config = config;
        this.plugin = plugin;
        this.healCooldownSeconds = config.getInt("heal.cooldown", 60);
        if(!config.contains("heal.cooldown")){
            config.set("heal.cooldown", 60);
        }
    }

    private boolean canHeal(Player player) {
        // Admins with permission bypass cooldown
        if (player.hasPermission("core.heal.nocooldown")) {
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
            String cooldownMessage = lang.get("heal.cooldown")
                    .replace("{time}", formattedTime)
                    .replace("{cmd}", "/heal");
            player.sendMessage(cooldownMessage);

            return false;
        }

        return true;
    }



    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        // Handle console sender
        if (!(sender instanceof Player player)) {
            return handleConsoleCommand(sender, args);
        }
        // Check permission
        if (!player.hasPermission("core.heal")) {
            Text.sendErrorMessage(player, "no-permission", lang);
            return true;
        }

        // Process command based on number of arguments
        switch (args.length) {
            case 0:
                // Heal self
                if(!canHeal(player)){
                    return true;
                }
                healPlayer(player);
                player.sendMessage(Text.parseColors(lang.get("heal.self")));
                if(!player.hasPermission("core.heal.nocooldown")){
                    lastHealTime.put(player.getUniqueId(), System.currentTimeMillis());
                }
                return true;
            case 1:
                return handlePlayerHealTarget(player, args[0]);
            default:
                player.sendMessage("§cUsage: /heal [player]");
                return true;
        }
    }

    private boolean handleConsoleCommand(CommandSender sender, String[] args) {
        switch (args.length) {
            case 0:
                sender.sendMessage("§cUsage: /heal <player>");
                return true;
            case 1:
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    // Check if player exists but is offline
                    if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                        Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
                    } else {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", args[0]);
                    }
                    return true;
                }

                // Heal target and notify
                healPlayer(target);
                sender.sendMessage(Text.parseColors(lang.get("heal.other")
                        .replace("{name}", target.getName())));
                target.sendMessage(Text.parseColors(lang.get("heal.self")));
                return true;
            default:
                sender.sendMessage("§cUsage: /heal <player>");
                return false;
        }
    }

    private boolean handlePlayerHealTarget(Player player, String targetName) {
        // Check if player is trying to heal themselves by name
        if (targetName.equalsIgnoreCase(player.getName())) {
            healPlayer(player);
            player.sendMessage(Text.parseColors(lang.get("heal.self")));
            return true;
        }

        // Check permission for healing others
        if (!player.hasPermission("core.heal.others")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "/heal on others");
            return true;
        }

        // Try to find the target player
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            // Target is online - heal and notify
            healPlayer(target);
            player.sendMessage(Text.parseColors(lang.get("heal.other")
                    .replace("{name}", target.getName())));
            target.sendMessage(Text.parseColors(lang.get("heal.self")));
        } else {
            // Target is offline or doesn't exist
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
            if (offlineTarget.hasPlayedBefore()) {
                Text.sendErrorMessage(player, "player-not-online", lang, "{name}", targetName);
            } else {
                Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
            }
        }
        return true;
    }

    private void healPlayer(Player player) {
        // Null-safe max health setting
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            player.setHealth(maxHealthAttr.getValue());
        } else {
            player.setHealth(20.0); // Fallback (should never happen for real players)
        }

        // Rest of the healing logic (food, effects, etc.)
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFireTicks(0);
    }
    /**
     * Clean up the cooldown map when plugin disables to prevent memory leaks
     */
    public void cleanup() {
        lastHealTime.clear();
    }

}
