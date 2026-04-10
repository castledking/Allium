package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class AltExempt implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public AltExempt(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("allium.altexempt")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "use /{cmd}", "manage alt exemptions.", true);
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Text.colorize("&cUsage: /" + label + " <add|remove|check> <player> [reason]"));
            return true;
        }

        String action = args[0].toLowerCase();
        String targetName = args[1];

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore()) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetName);
            return true;
        }

        UUID targetUUID = target.getUniqueId();
        UUID senderUUID = sender instanceof Player ? ((Player) sender).getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");

        switch (action) {
            case "add":
                String reason = args.length > 2 ? String.join(" ", args).substring(targetName.length() + 5) : "No reason provided";
                boolean added = plugin.getDatabase().addAltExemption(targetUUID, senderUUID, reason);
                if (added) {
                    sender.sendMessage(Text.colorize("&aSuccessfully added alt exemption for &e" + targetName));
                    sender.sendMessage(Text.colorize("&7Note: The exemption will prevent them from being added to the alt group on future joins."));
                    sender.sendMessage(Text.colorize("&7If they are currently in the alt group, remove it manually with your permissions plugin."));
                } else {
                    sender.sendMessage(Text.colorize("&cFailed to add alt exemption for &e" + targetName));
                }
                break;

            case "remove":
                boolean removed = plugin.getDatabase().removeAltExemption(targetUUID);
                if (removed) {
                    sender.sendMessage(Text.colorize("&aSuccessfully removed alt exemption for &e" + targetName));
                } else {
                    sender.sendMessage(Text.colorize("&c" + targetName + " is not exempted from alt detection."));
                }
                break;

            case "check":
                boolean isExempt = plugin.getDatabase().isPlayerAltExempt(targetUUID);
                if (isExempt) {
                    sender.sendMessage(Text.colorize("&e" + targetName + " &ais exempted from alt detection."));
                } else {
                    sender.sendMessage(Text.colorize("&e" + targetName + " &cis not exempted from alt detection."));
                }
                break;

            default:
                sender.sendMessage(Text.colorize("&cUsage: /" + label + " <add|remove|check> <player> [reason]"));
                break;
        }

        return true;
    }
}
