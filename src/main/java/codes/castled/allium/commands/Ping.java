package codes.castled.allium.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.managers.lang.Lang;

public class Ping implements CommandExecutor {
    private final Lang lang;

    public Ping(PluginStart plugin) {
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ping")) {
            if (args.length == 0) {
                // Self ping
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "[player]"));
                    return true;
                }

                if (!player.hasPermission("allium.ping")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "ping");
                    return true;
                }

                int ping = player.getPing();
                String message = lang.get("ping.self")
                        .replace("{ping}", String.valueOf(ping));
                lang.sendMessage(player, message);
                return true;
            } else {
                // Ping another player
                if (!sender.hasPermission("allium.ping.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "ping others");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }

                int ping = target.getPing();
                String message = lang.get("ping.other")
                        .replace("{ping}", String.valueOf(ping))
                        .replace("{name}", target.getName());
                lang.sendMessage(sender, message);
                return true;
            }
        }
        return false;
    }
}
