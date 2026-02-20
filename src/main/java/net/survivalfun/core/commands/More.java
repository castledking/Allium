package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.logging.Level;

public class More implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;

    public More(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            Text.sendDebugLog(ERROR, "LanguageManager not initialized when executing More command");
            return true;
        }
        
        Player target;
        if (args.length == 0) {
            // No target specified, use sender if they're a player
            if (!(sender instanceof Player)) {
                sender.sendMessage("Usage: /more <player>");
                return true;
            }
            target = (Player) sender;
        } else {
            // Target specified, find the player
            target = org.bukkit.Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
                return true;
            }
        }
        
        deliverStack(target, sender instanceof Player ? Optional.of((Player) sender) : Optional.empty());
        return true;
    }
    private void deliverStack(Player target, Optional<Player> sender) {
        // Handle case where sender is not present (console command)
        if (!sender.isPresent()) {
            if (!target.hasPermission("allium.more")) {
                Text.sendErrorMessage(target, "no-permission", lang, "{cmd}", "more");
                return;
            }
            ItemStack itemInHand = target.getInventory().getItemInMainHand();
            if (isAir(itemInHand)) {
                Text.sendErrorMessage(target, "hold-item", lang, "{modify}", "more");
                return;
            }
            if (cantStack(itemInHand)) {
                Text.sendErrorMessage(target, "can-not-stack", lang);
                return;
            }
            // Console is giving the item, so just stack it
            itemInHand.setAmount(itemInHand.getMaxStackSize());
            target.getInventory().setItemInMainHand(itemInHand);
            target.sendMessage(lang.get("more.self"));
            return;
        }
        
        // Handle case where sender is present (player command)
        Player senderPlayer = sender.get();
        
        // If sender is using command on themselves
        if (senderPlayer.equals(target)) {
            if (!senderPlayer.hasPermission("allium.more")) {
                Text.sendErrorMessage(senderPlayer, "no-permission", lang, "{cmd}", "more");
                return;
            }
            ItemStack itemInHand = target.getInventory().getItemInMainHand();
            if (isAir(itemInHand)) {
                Text.sendErrorMessage(senderPlayer, "hold-item", lang, "{modify}", "more");
                return;
            }
            if (cantStack(itemInHand)) {
                Text.sendErrorMessage(senderPlayer, "can-not-stack", lang);
                return;
            }
            itemInHand.setAmount(itemInHand.getMaxStackSize());
            target.getInventory().setItemInMainHand(itemInHand);
            target.sendMessage(lang.get("more.self"));
            return;
        }
        
        // If sender is using command on another player
        if (!senderPlayer.hasPermission("allium.more.other")) {
            Text.sendErrorMessage(senderPlayer, "no-permission", lang, "{cmd}", "more");
            return;
        }
        
        ItemStack itemInHand = target.getInventory().getItemInMainHand();
        if (isAir(itemInHand)) {
            Text.sendErrorMessage(senderPlayer, "hold-item", lang, "{modify}", "more");
            return;
        }
        if (cantStack(itemInHand)) {
            Text.sendErrorMessage(senderPlayer, "can-not-stack", lang);
            return;
        }
        
        itemInHand.setAmount(itemInHand.getMaxStackSize());
        target.getInventory().setItemInMainHand(itemInHand);
        senderPlayer.sendMessage(lang.get("more.other", "{name}", target.getName()));
        target.sendMessage(lang.get("more.self"));
    }

    private boolean cantStack(ItemStack item) {
        return item.getMaxStackSize() <= 1;
    }
    private boolean isAir(ItemStack item) {
        return item.getType().isAir();
    }
}