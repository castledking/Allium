package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.items.CustomItem;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class CoreItemCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;

    public CoreItemCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("allium.coreitem.give")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            showUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "give":
                handleGiveCommand(sender, args);
                break;
            default:
                showUsage(sender);
                break;
        }

        return true;
    }

    private void handleGiveCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /core item give <item> [amount]");
            sender.sendMessage("§cUsage: /core item give <player> <item> [amount]");
            return;
        }

        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null) {
            sender.sendMessage("§cCustom item system is not initialized.");
            return;
        }

        Player targetPlayer;
        String itemName;
        int amount = 1;

        Player potentialPlayer = Bukkit.getPlayer(args[1]);
        
        if (potentialPlayer != null && args.length >= 3) {
            targetPlayer = potentialPlayer;
            itemName = args[2];
            if (args.length >= 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount: " + args[3]);
                    return;
                }
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cConsole must specify a player.");
                return;
            }
            targetPlayer = (Player) sender;
            itemName = args[1];
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount: " + args[2]);
                    return;
                }
            }
        }

        if (amount < 1 || amount > 64) {
            sender.sendMessage("§cAmount must be between 1 and 64.");
            return;
        }

        CustomItem customItem = registry.getItem(itemName);
        if (customItem == null) {
            sender.sendMessage("§cUnknown custom item: " + itemName);
            sender.sendMessage("§7Available items: " + String.join(", ", registry.getAllItemIds()));
            return;
        }

        ItemStack itemStack = customItem.createItemStack(amount);
        targetPlayer.getInventory().addItem(itemStack);

        sender.sendMessage("§aGave §e" + amount + "x §6" + customItem.getDisplayName() + " §ato " + (targetPlayer.equals(sender) ? "yourself" : targetPlayer.getName()));
        
        if (!targetPlayer.equals(sender)) {
            targetPlayer.sendMessage("§aYou received §e" + amount + "x §6" + customItem.getDisplayName());
        }
    }

    private void showUsage(CommandSender sender) {
        sender.sendMessage("§6§l=== Core Item Commands ===");
        sender.sendMessage("§e/core item give <item> [amount] §7- Give a custom item to yourself");
        sender.sendMessage("§e/core item give <player> <item> [amount] §7- Give a custom item to a player");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("allium.coreitem.give")) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("give");
        } else if (args.length == 2) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
            
            CustomItemRegistry registry = CustomItemRegistry.getInstance();
            if (registry != null) {
                completions.addAll(registry.getAllItemIds());
            }
        } else if (args.length == 3) {
            Player potentialPlayer = Bukkit.getPlayer(args[1]);
            if (potentialPlayer != null) {
                CustomItemRegistry registry = CustomItemRegistry.getInstance();
                if (registry != null) {
                    completions.addAll(registry.getAllItemIds());
                }
            } else {
                completions.add("1");
                completions.add("5");
                completions.add("10");
                completions.add("64");
            }
        } else if (args.length == 4) {
            completions.add("1");
            completions.add("5");
            completions.add("10");
            completions.add("64");
        }

        String lastArg = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(lastArg))
                .toList();
    }
}
