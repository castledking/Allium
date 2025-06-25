package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
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
        Player target;
        if (sender instanceof Player player) {
            if (!player.hasPermission("core.more")) {
                label = "§cmore";
                player.sendMessage(lang.get("no-permission").replace("{cmd}", label));
                return true;
            }
            target = player;
        } else {
            // Console or other sender: require player name as first argument
            if (args.length == 0) {
                sender.sendMessage("Usage: /more <player>");
                return true;
            }
            target = org.bukkit.Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
                return true;
            }
        }

        ItemStack itemInHand = target.getInventory().getItemInMainHand();
        if (itemInHand.getType().isAir()) {
            Text.sendErrorMessage(sender, "hold-item", lang, "{modify}", label);
            return true;
        }

        int maxStackSize = itemInHand.getMaxStackSize();
        if (maxStackSize <= 0) {
            if (sender instanceof Player && sender.equals(target)) {
                target.sendMessage("§cThis item cannot be stacked.");
            } else {
                sender.sendMessage("§cThis item cannot be stacked.");
            }
            return true;
        }
        itemInHand.setAmount(maxStackSize);
        target.getInventory().setItemInMainHand(itemInHand);
        if (sender instanceof Player && sender.equals(target)) {
            target.sendMessage("§aGiving you " + maxStackSize + " of the item.");
        } else {
            sender.sendMessage("§aGiving " + target.getName() + " " + maxStackSize + " of the item in their hand.");
            target.sendMessage("§aYour held item has been stacked to " + maxStackSize + " by an admin.");
        }
        return true;
    }
}