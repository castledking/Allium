package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.inventory.gui.NicknameGUI;
import net.survivalfun.core.managers.core.Dialog;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class EditNick implements CommandExecutor {
    private final PluginStart plugin;

    public EditNick(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "player-only-command", plugin.getLangManager(), "use /{cmd}", "execute this command.", true);
            return true;
        }

        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("allium.editnick")) {
            Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "use /{cmd}", "edit your nickname via GUI.", true);
            return true;
        }

        String targetName = player.getName();
        
        // If args provided, check if editing another player
        if (args.length >= 1) {
            if (!player.hasPermission("allium.nick.other")) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "edit /{cmd}", "other players' nicknames.", true);
                return true;
            }
            
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                Text.sendErrorMessage(player, "player-not-found", plugin.getLangManager(), "{name}", args[0]);
                return true;
            }
            targetName = target.getName();
        }

        // Open the nickname editor GUI
        try {
            NicknameGUI gui = new NicknameGUI(player, plugin, targetName);
            gui.open();
        } catch (Exception e) {
            Text.sendErrorMessage(player, "command-error", plugin.getLangManager(), "{cmd}", "/" + label, true);
            plugin.getLogger().warning("Error opening nickname editor: " + e.getMessage());
        }
        
        return true;
    }
}
