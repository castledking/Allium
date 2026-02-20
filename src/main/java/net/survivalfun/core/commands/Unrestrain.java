package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.security.HandcuffsListener;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Unrestrain implements CommandExecutor {

    private final PluginStart plugin;

    public Unrestrain(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check if sender has admin permissions
        if (!sender.hasPermission("allium.admin") && !sender.hasPermission("allium.*")) {
            sender.sendMessage(Text.colorize("&cYou don't have permission to use this command!"));
            return true;
        }

        // Check if a player name was provided
        if (args.length == 0) {
            sender.sendMessage(Text.colorize("&cUsage: /unrestrain <player>"));
            return true;
        }

        // Get the target player
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Text.colorize("&cPlayer '" + args[0] + "' not found or not online!"));
            return true;
        }

        // Get the HandcuffsListener instance
        HandcuffsListener handcuffsListener = plugin.getHandcuffsListener();

        if (handcuffsListener == null) {
            sender.sendMessage(Text.colorize("&cHandcuffs system is not initialized!"));
            return true;
        }

        // Check if player is currently restrained
        boolean wasRestrained = handcuffsListener.isPlayerRestrained(target);

        // Unmount the player from anyone they're riding (if mounted)
        target.eject();

        // Remove handcuff restrictions from the player (this restores their state)
        handcuffsListener.removeHandcuffRestrictionsExternal(target);

        // Send messages
        target.sendMessage(Text.colorize("&aYou have been released from any restraints!"));
        sender.sendMessage(Text.colorize("&aSuccessfully released " + target.getName() +
            (wasRestrained ? " from restraints." : " (they weren't restrained, but restrictions have been cleared).")));

        Text.sendDebugLog(Text.DebugSeverity.INFO, sender.getName() + " used /unrestrain on " + target.getName());
        return true;
    }
}
