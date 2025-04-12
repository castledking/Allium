package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;

public class More implements CommandExecutor {
    private final PluginStart plugin;

    public More(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("core.more")) {
            player.sendMessage(lang.get("no-permission"));
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand.getType().isAir()) {
            player.sendMessage("§cYou must be holding an item to use this command.");
            return true;
        }

        int maxStackSize = itemInHand.getMaxStackSize();

        if (maxStackSize <= 0) {
            player.sendMessage("§cThis item cannot be stacked.");
            return true;
        }
        itemInHand.setAmount(maxStackSize);
        player.getInventory().setItemInMainHand(itemInHand);
        player.sendMessage("§aGiving you " + maxStackSize + " of the item.");

        return true;
    }
}