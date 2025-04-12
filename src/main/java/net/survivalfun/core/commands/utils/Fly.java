package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
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
                    player.sendMessage(lang.get("error-prefix")
                            + lang.get("no-permission"));
                    return true;
                }
                toggleFly(player, sender);

            } else if (args.length == 1) {
                // /fly <player>
                if (!sender.hasPermission("core.fly.others")) {
                    sender.sendMessage(lang.get("error-prefix")
                            + lang.get("no-permission"));
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(lang.get("error-prefix")
                            + lang.get("player-not-found")
                            .replace("{name}", args[0]));
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
        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
            player.setFlying(false); //Important: disable flight if they are currently flying

            // Check if the sender is the same as the player being toggled.
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                if (sender instanceof Player) {
                    sender.sendMessage(lang.get("fly.disable-other")
                            .replace("{name}", player.getName()));
                } else {
                    sender.sendMessage(lang.get("fly.disable-other")
                            .replace("{name}", player.getName())); // Console message
                }
                player.sendMessage(lang.get("fly.disable"));
            } else {
                player.sendMessage(lang.get("fly.disable"));
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
                if (sender instanceof Player) {
                    sender.sendMessage(lang.get("fly.enable-other")
                            .replace("{name}", player.getName()));
                } else {
                    sender.sendMessage(lang.get("fly.enable-other")
                            .replace("{name}", player.getName()));
                }
                player.sendMessage(lang.get("fly.enable"));
            } else {
                player.sendMessage(lang.get("fly.enable"));
            }
        }
    }
}