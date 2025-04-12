package net.survivalfun.core.commands.utils;

import net.survivalfun.core.utils.ColorUtils;
import net.survivalfun.core.utils.LoreHelper;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Lore implements CommandExecutor {

    private final JavaPlugin plugin;

    public Lore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();
        player.updateInventory();

        if (item.getType().isAir()) {
            player.sendMessage("§cYou must be holding an item to modify its lore.");
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            player.sendMessage("§cThis item cannot have lore.");
            return true;
        }

        List<String> lore = LoreHelper.getLore(item);

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Lore Command Usage:");
            player.sendMessage(ChatColor.RED + "/lore add <text> - " + ChatColor.GRAY + "Adds a line to the item's lore.");
            player.sendMessage(ChatColor.RED + "/lore remove <line> - " + ChatColor.GRAY + "Removes a line from the lore at the specified index.");
            player.sendMessage(ChatColor.RED + "/lore insert <line> <text> - " + ChatColor.GRAY + "Inserts a line into the lore at the specified index.");
            player.sendMessage(ChatColor.RED + "/lore clear - " + ChatColor.GRAY + "Clears all lore from the item.");
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "add":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /lore add <text>");
                    return true;
                }
                String lineToAdd = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                lineToAdd = ColorUtils.colorizeHex(lineToAdd);
                lineToAdd = ChatColor.translateAlternateColorCodes('&', lineToAdd);
                lore.add(lineToAdd);
                player.sendMessage("§aAdded lore line: " + lineToAdd);
                break;

            case "remove":
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /lore remove <line>");
                    return true;
                }
                try {
                    int indexToRemove = Integer.parseInt(args[1]) - 1; // Adjust for 1-based indexing
                    if (indexToRemove < 0 || indexToRemove >= lore.size()) {
                        player.sendMessage("§cInvalid lore index.");
                        return true;
                    }
                    String removedLine = lore.remove(indexToRemove);
                    player.sendMessage("§aRemoved lore line: " + removedLine);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid index format.  Must be a number.");
                    return true;
                }
                break;

            case "insert":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /lore insert <line> <text>");
                    return true;
                }
                try {
                    int indexToInsert = Integer.parseInt(args[1]) - 1; // Adjust for 1-based indexing
                    if (indexToInsert < 0 || indexToInsert > lore.size()) { // Note the `>` instead of `>=` to allow inserting at the end.
                        player.sendMessage("§cInvalid lore index.");
                        return true;
                    }
                    String lineToInsert = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    lineToInsert = ColorUtils.colorizeHex(lineToInsert);
                    lineToInsert = ChatColor.translateAlternateColorCodes('&', lineToInsert);
                    lore.add(indexToInsert, lineToInsert);
                    player.sendMessage("§aInserted lore line at index " + ((indexToInsert + 1)) + ":" + lineToInsert);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid index format. Must be a number.");
                    return true;
                }
                break;

            case "clear":
                lore.clear();
                player.sendMessage("§aLore cleared.");
                break;

            default:
                player.sendMessage(ChatColor.GOLD + "Lore Command Usage:");
                player.sendMessage("/lore add <text> - " + ChatColor.GRAY + "Adds a line to the item's lore.");
                player.sendMessage("/lore remove <line> - " + ChatColor.GRAY + "Removes a line from the lore at the specified index.");
                player.sendMessage("/lore insert <line> <text> - " + ChatColor.GRAY + "Inserts a line into the lore at the specified index.");
                player.sendMessage("/lore clear - " + ChatColor.GRAY + "Clears all lore from the item.");
                return true;
        }

        LoreHelper.setLore(item, lore);
        item.setItemMeta(meta);
        return true;
    }
}
