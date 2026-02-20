package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.security.FlightRestoration;
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
                    String usage = lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<player>");
                    lang.sendMessage(sender, usage);
                    return true;
                }
                if (!player.hasPermission("allium.fly")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                toggleFly(player, sender);

            } else if (args.length == 1) {
                // /fly <player>
                if (!(sender instanceof Player) && !sender.hasPermission("allium.fly.others")) {
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
                String usage = lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "<player>");
                lang.sendMessage(sender, usage);
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
            player.setFlying(false);
            boolean midAir = !player.isOnGround() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;

            // Get the style for disabled state
            String disabledStyle = lang.get("styles.state.false");

            if (midAir) {
                FlightRestoration restoration = plugin.getFlyOnRejoinListener();
                if (restoration != null) {
                    restoration.applySlowFallingUntilLanded(player);
                } else {
                    // Fallback: give ample slow falling time to reach the ground
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60, 0, true, false));
                }
            }

            String message = flyToggleMessage
                    .replace("{state}", disabledStyle + "disabled" + firstColorOfFlyToggle)
                    .replace(" {name}", "");
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                // Send message to command sender
                String senderMessage = flyToggleMessage
                        .replace("{state}", disabledStyle + "disabled" + firstColorOfFlyToggle)
                        .replace("{name}", "for " + player.getName());
                lang.sendMessage(sender, senderMessage);
                
                // Send message to player with sound
                lang.sendMessage(player, message);
            } else {
                // Send message to player with sound
                lang.sendMessage(player, message);
            }

        } else {
            player.setAllowFlight(true);
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);

            // Get the style for enabled state
            String enabledStyle = lang.get("styles.state.true");

            // Check if the sender is the same as the player being toggled.
            String message = flyToggleMessage
                    .replace("{state}", enabledStyle + "enabled" + firstColorOfFlyToggle)
                    .replace(" {name}", ""); // Remove the space before {name} when it's empty
                    
            if (!(sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId()))) {
                // Send message to command sender
                String senderMessage = flyToggleMessage
                        .replace("{state}", enabledStyle + "enabled" + firstColorOfFlyToggle)
                        .replace("{name}", "for " + player.getName());
                lang.sendMessage(sender, senderMessage);
                
                // Send message to player with sound
                lang.sendMessage(player, message);
            } else {
                // Send message to player with sound
                lang.sendMessage(player, message);
            }
        }
    }
}