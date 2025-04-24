package net.survivalfun.core.commands.utils;

import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.utils.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class Heal implements CommandExecutor {

    private final Lang lang;

    public Heal(Lang lang) {
        this.lang = lang;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        // If sender is console and no arguments provided
        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                sender.sendMessage("§cUsage: /heal <player>");
                return true;
            }

            if (args.length == 1) {
                Player target = Bukkit.getPlayer(args[0]);

                if (target == null) {
                    // Check if the player has ever joined (offline) or doesn't exist
                    if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                        sender.sendMessage("§cPlayer §e" + args[0] + " §cis currently offline.");
                    } else {
                        sender.sendMessage("§cPlayer §e" + args[0] + " §chas never joined this server.");
                    }
                    return true;
                }

                healPlayer(target);
                sender.sendMessage("§aHealed §e" + target.getName());
                target.sendMessage("§aYou have been healed by console.");
                return true;
            }

            sender.sendMessage("§cUsage: /heal <player>");
            return false;
        }

        // Check if player has permission to use heal command
        if (!player.hasPermission("core.heal")) {
            // Add this debug line
            player.sendMessage("§cDebug: Attempting to send no-permission message");

            // Check if lang is null
            if (lang == null) {
                player.sendMessage("§cError: Lang manager is null");
                return true;
            }

            // Try to get the message directly
            String message = lang.get("no-permission");
            player.sendMessage("§cDebug: Raw message from lang: " + message);

            // Then try the normal way
            Text.sendErrorMessage(player, "no-permission", lang);
            return true;
        }

        // If sender is a player
        if (args.length == 0) {
            // Heal self
            healPlayer(player);
            player.sendMessage(lang.get("heal.self"));
            return true;
        }

        // Heal another player or self with name
        if (args.length == 1) {
            if (args[0].equalsIgnoreCase(player.getName())) {
                // Player is healing themselves with their own name
                healPlayer(player);
                player.sendMessage("§aYou have been healed!");
                return true;
            }

            if (!player.hasPermission("core.heal.others")) {
                Text.sendErrorMessage(player, "no-permission", lang);
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);

            if (target != null) {
                // Player is online
                healPlayer(target);
                player.sendMessage("§aHealed §e" + target.getName());
                target.sendMessage("§aYou have been healed by §e" + player.getName());
            } else {
                // Player is offline or doesn't exist
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(args[0]);

                if (offlineTarget.hasPlayedBefore()) {
                    Text.sendErrorMessage(player, "player-not-online", lang, "{name}", args[0]);
                } else {
                    Text.sendErrorMessage(player, "player-not-found", lang, "{player}", args[0]);
                }
            }
            return true;
        }

        player.sendMessage("§cUsage: /heal [player]");
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
}
