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
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<player>"));
                    return true;
                }
                if (!player.hasPermission("core.fly")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                toggleFly(player, sender);

            } else if (args.length == 1) {
                // /fly <player>
                if (!(sender instanceof Player) && !sender.hasPermission("core.fly.others")) {
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
                        .replace("{args}", "<player>"));
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

        String flyToggleMessage = lang.get("fly.toggle");
        String firstColorOfFlyToggle = lang.getFirstColorCode("fly.toggle");

        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);

            // Get the style for disabled state
            String disabledStyle = lang.get("styles.state.false");

            // Check if the sender is the same as the player being toggled.
            // Add ChatColor.RESET (which is §r) before firstColorOfFlyToggle to stop previous formatting like underline
            String message = flyToggleMessage
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfFlyToggle)
                    .replace(" {name}", ""); // Remove the space before {name} when it's empty
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                // Console message
                sender.sendMessage(flyToggleMessage
                        .replace("{state}", disabledStyle + "disabled" + firstColorOfFlyToggle)
                        .replace("{name}", "for " + player.getName()));
                player.sendMessage(message);
            } else {
                player.sendMessage(message);
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

            // Get the style for enabled state
            String enabledStyle = lang.get("styles.state.true");

            // Check if the sender is the same as the player being toggled.
            // Add ChatColor.RESET (which is §r) before firstColorOfFlyToggle to stop previous formatting like underline
            String message = flyToggleMessage
                    .replace("{state}", enabledStyle + "enabled" + firstColorOfFlyToggle)
                    .replace(" {name}", ""); // Remove the space before {name} when it's empty
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                sender.sendMessage(flyToggleMessage
                        .replace("{state}", enabledStyle + "enabled" + firstColorOfFlyToggle)
                        .replace("{name}", "for " + player.getName()));
                player.sendMessage(message);
            } else {
                player.sendMessage(message);
            }
        }
    }
}