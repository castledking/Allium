package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventoryManager;
import net.survivalfun.core.inventory.gui.RestoreGUI;
import net.survivalfun.core.managers.core.Text;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class Restore implements CommandExecutor {
    private final PluginStart plugin;
    private final InventoryManager inventoryManager;

    public Restore(PluginStart plugin, InventoryManager inventoryManager) {
        this.plugin = plugin;
        this.inventoryManager = inventoryManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", plugin.getLangManager());
            return true;
        }

        Player player = (Player) sender;
        
        if (!player.hasPermission("allium.restore")) {
            Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "{cmd}", label.toLowerCase());
            return true;
        }

        UUID targetId = player.getUniqueId();
        
        if (args.length > 0 && player.hasPermission("allium.restore.others")) {
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null) {
                targetId = target.getUniqueId();
            } else {
                // Try to get offline player
                targetId = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
            }
        }

        new RestoreGUI(player, targetId, inventoryManager, plugin).open();
        return true;
    }
}
