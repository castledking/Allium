package net.survivalfun.core.items.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.items.HandcuffsItem;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

public class Handcuffs implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;

    public Handcuffs(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", plugin.getLangManager());
            return true;
        }

        Player player = (Player) sender;

        // Check if no arguments - toggle for self
        if (args.length == 0) {
            if (hasHandcuffs(player)) {
                removeHandcuffs(player);
                sender.sendMessage(Text.colorize("&eRemoved handcuffs from your inventory."));
            } else {
                if (!giveHandcuffs(player, player)) {
                    Text.sendErrorMessage(sender, "inventory-full", plugin.getLangManager());
                } else {
                    sender.sendMessage(Text.colorize("&aGave handcuffs to yourself."));
                }
            }
            return true;
        }

        // Check if giving to another player
        if (args.length == 1) {
            // Check permission to give to others
            if (!player.hasPermission("allium.admin") && !player.hasPermission("allium.*") && !player.hasPermission("allium.give.handcuffs")) {
                Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "{cmd}", "handcuffs");
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", plugin.getLangManager(), "{name}", args[0]);
                return true;
            }

            if (!giveHandcuffs(target, sender)) {
                Text.sendErrorMessage(sender, "inventory-full", plugin.getLangManager());
            } else {
                sender.sendMessage(Text.colorize("&aGave handcuffs to " + target.getName()));
            }
            return true;
        }

        // Invalid usage
        sender.sendMessage(Text.colorize("&eUsage: /handcuffs [player]"));
        return true;
    }

    /**
     * Checks if the player has any handcuffs in their inventory.
     */
    private boolean hasHandcuffs(Player player) {
        for (var item : player.getInventory().getContents()) {
            if (item != null && HandcuffsItem.isHandcuffs(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives handcuffs to a target player, attempting to place in best available slot.
     * Returns true if successfully given, false if inventory is full.
     */
    private boolean giveHandcuffs(Player target, CommandSender sender) {
        PlayerInventory inventory = target.getInventory();
        var handcuffs = HandcuffsItem.createHandcuffs();

        // Try to add to main inventory first
        var leftover = inventory.addItem(handcuffs);
        if (leftover.isEmpty()) {
            return true; // Successfully added
        }

        // If still leftover, try offhand
        if (inventory.getItemInOffHand().getType().isAir()) {
            inventory.setItemInOffHand(handcuffs);
            return true;
        }

        // If still leftover, try hotbar slots closest to main hand
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot) == null || inventory.getItem(slot).getType().isAir()) {
                inventory.setItem(slot, handcuffs);
                return true;
            }
        }

        // If all slots are full, return false
        return false;
    }

    /**
     * Removes all handcuffs from the player's inventory.
     */
    private void removeHandcuffs(Player player) {
        PlayerInventory inventory = player.getInventory();
        var contents = inventory.getContents();

        // Remove from main inventory
        for (int i = 0; i < contents.length; i++) {
            var item = contents[i];
            if (item != null && HandcuffsItem.isHandcuffs(item)) {
                inventory.setItem(i, null);
            }
        }

        // Remove from offhand
        var offhand = inventory.getItemInOffHand();
        if (offhand != null && HandcuffsItem.isHandcuffs(offhand)) {
            inventory.setItemInOffHand(null);
        }

        // Remove from armor slots
        var armorContents = inventory.getArmorContents();
        for (int i = 0; i < armorContents.length; i++) {
            var item = armorContents[i];
            if (item != null && HandcuffsItem.isHandcuffs(item)) {
                inventory.setArmorContents(null);
                break; // Armor contents need special handling
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (sender instanceof Player) {
            Player player = (Player) sender;

            // Only suggest player names if the player can give to others
            if (args.length == 1 && (player.hasPermission("allium.admin") || player.hasPermission("allium.*") || player.hasPermission("allium.give.handcuffs"))) {
                String partialName = args[0].toLowerCase();
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.getName().toLowerCase().startsWith(partialName)) {
                        suggestions.add(onlinePlayer.getName());
                    }
                }
            }
        }

        return suggestions;
    }
}
