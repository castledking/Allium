package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

public class Fly implements CommandExecutor {
    private final PluginStart plugin;
    public Fly(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        LangManager lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (command.getName().equalsIgnoreCase("fly")) {
            if (args.length == 0) {
                // /fly (self)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cConsole cannot use this command without specifying a player.");
                    return true;
                }
                if (!player.hasPermission("core.fly")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                toggleFly(player, sender);

            } else if (args.length == 1) {
                // /fly <player>
                if (!sender.hasPermission("core.fly.others")) {
                    sender.sendMessage("§cYou do not have permission to set fly for others.");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                toggleFly(target, sender);
            } else {
                sender.sendMessage("§cUsage: /fly [player]");
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
                    sender.sendMessage("§cFly mode disabled for " + player.getName() + ".");
                } else {
                    sender.sendMessage("Fly mode disabled for " + player.getName() + "."); // Console message
                }
                player.sendMessage("§cFly mode disabled.");
            } else {
                player.sendMessage("§cFly mode disabled.");
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
                    sender.sendMessage("§aFly mode enabled for " + player.getName() + ".");
                } else {
                    sender.sendMessage("Fly mode enabled for " + player.getName() + "."); // Console message
                }
                player.sendMessage("§aFly mode enabled.");
            } else {
                player.sendMessage("§aFly mode enabled.");
            }
        }
    }
}