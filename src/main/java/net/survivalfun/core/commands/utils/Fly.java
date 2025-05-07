package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class Fly implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    public Fly(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("fly")) {
            if (args.length == 0) {
                // /fly (self)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("error-prefix")
                            + lang.get("not-a-player"));
                    return true;
                }
                if (!player.hasPermission("core.fly")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                toggleFly(player, sender);

            } else if (args.length == 1) {
                // /fly <player>
                if (!sender.hasPermission("core.fly.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others.");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }

                // Check if the target player is in spectator mode
                if (target.getGameMode() == GameMode.SPECTATOR) {
                    // Send special error message for spectator mode players
                    Text.sendErrorMessage(sender, lang.get("player-not-found"), lang, "{name}", target.getName());
                    return true;
                }

                toggleFly(target, sender);
            } else {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[player]"));
                return true;
            }
            return true;
        }
        return false;
    }

    private void toggleFly(Player player, CommandSender sender) {
        // Prevent flight disabling for spectator mode players
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // If attempt to disable flight for a spectator, silently return
            if (player.getAllowFlight()) {
                return;
            }
        }

        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
            player.setFlying(false); //Important: disable flight if they are currently flying

            // Check if the sender is the same as the player being toggled.
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                // Console message
                sender.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§c§ndisabled§r")
                        .replace("{name}", "for " + player.getName()));
                player.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§c§ndisabled§r")
                        .replace("{name}", ""));
            } else {
                player.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§c§ndisabled§r")
                        .replace("{name}", ""));
                if (!player.isOnGround()) {
                    // Apply slow falling effect
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0, true, false));

                    // Schedule a task to remove the effect when the player touches the ground
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (player.isOnGround()) {
                                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                                cancel(); // Stop the task
                            }
                        }
                    }.runTaskTimer(plugin, 0L, 1L); // Run every tick (0 delay, 1 tick period)
                }
            }

        } else {
            player.setAllowFlight(true);

            // Check if the sender is the same as the player being toggled.
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                sender.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§a§nenabled§r")
                        .replace("{name}", "for " + player.getName()));
                player.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§a§nenabled§r")
                        .replace("{name}", ""));
            } else {
                player.sendMessage(lang.get("fly.toggle")
                        .replace("{state}", "§a§nenabled§r")
                        .replace("{name}", ""));
            }
        }
    }
}