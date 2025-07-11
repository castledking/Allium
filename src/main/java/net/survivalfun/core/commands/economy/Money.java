package net.survivalfun.core.commands.economy;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.Economy;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin command to manage player balances
 * Usage: /money <give|take|set|clear> <player> [amount]
 * Permission: core.admin
 */
public class Money implements CommandExecutor, TabCompleter {
    private final Economy economy;
    private final Lang lang;
    
    // Valid subcommands
    private final List<String> validSubcommands = Arrays.asList("give", "add", "take", "set", "clear", "reset");
    
    public Money(PluginStart plugin, Economy economy) {
        this.economy = economy;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Check permission
        if (!sender.hasPermission("core.admin") || !sender.hasPermission("core.money")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }
        
        // Check if enough arguments
        if (args.length < 2) {
            showUsage(sender, label);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        String targetName = args[1];
        
        // Validate subcommand
        if (!validSubcommands.contains(subCommand)) {
            showUsage(sender, label);
            return true;
        }
        
        // Find target player
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            // Try to find player by exact name
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(targetName)) {
                    target = offlinePlayer;
                    break;
                }
            }
            
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetName);
                return true;
            }
        }
        
        UUID targetUUID = target.getUniqueId();
        String targetDisplayName = target.getName();
        
        // Handle clear/reset subcommand (doesn't need an amount)
        if (subCommand.equals("clear") || subCommand.equals("reset")) {
            // Get default balance from economy manager
            BigDecimal defaultBalance = economy.getBalance(UUID.randomUUID()); // This will return the default balance
            
            // Set player's balance to default
            if (economy.setBalance(targetUUID, defaultBalance)) {
                String successMsg = lang.get("economy.money-reset-success");
                if (successMsg.isEmpty()) {
                    successMsg = "&aReset &e{player}'s &abalance to default: &6{balance}";
                }
                sender.sendMessage(Text.parseColors(successMsg
                        .replace("{player}", targetDisplayName)
                        .replace("{balance}", economy.formatBalance(defaultBalance))));
            } else {
                Text.sendErrorMessage(sender, "economy.money-action-failed", lang, 
                        Map.of("{player}", targetDisplayName, "{action}", "reset"));
            }
            return true;
        }
        
        // All other commands require an amount
        if (args.length < 3) {
            showUsage(sender, label);
            return true;
        }
        
        // Parse amount with support for shorthand notation (k, m, b)
        BigDecimal amount;
        try {
            // Convert shorthand notation to full number
            String processedAmount = args[2].toLowerCase();
            double multiplier = 1;
            
            if (processedAmount.endsWith("k")) {
                processedAmount = processedAmount.substring(0, processedAmount.length() - 1);
                multiplier = 1000;
            } else if (processedAmount.endsWith("m")) {
                processedAmount = processedAmount.substring(0, processedAmount.length() - 1);
                multiplier = 1000000;
            } else if (processedAmount.endsWith("b")) {
                processedAmount = processedAmount.substring(0, processedAmount.length() - 1);
                multiplier = 1000000000;
            }
            
            // Parse the base number
            double baseAmount = Double.parseDouble(processedAmount);
            amount = new BigDecimal(baseAmount * multiplier);
            
            // Round to 2 decimal places
            amount = amount.setScale(2, RoundingMode.HALF_UP);
            
            // Ensure amount is positive
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                Text.sendErrorMessage(sender, "economy.pay-negative-amount", lang);
                return true;
            }
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(sender, "economy.pay-invalid-amount", lang, "{amount}", args[2]);
            return true;
        }
        
        boolean success = false;
        String action = "";
        
        // Execute the appropriate action based on subcommand
        switch (subCommand) {
            case "give":
            case "add":
                success = economy.deposit(targetUUID, amount);
                action = "give";
                break;
            case "take":
                success = economy.withdraw(targetUUID, amount);
                action = "take";
                break;
            case "set":
                success = economy.setBalance(targetUUID, amount);
                action = "set";
                break;
        }
        
        // Send appropriate message
        if (success) {
            String successKey = "economy.money-" + action + "-success";
            String successMsg = lang.get(successKey);
            if (successMsg.isEmpty()) {
                switch (action) {
                    case "give":
                        successMsg = "&aGave &6{amount} &ato &e{player}&a.";
                        break;
                    case "take":
                        successMsg = "&aTook &6{amount} &afrom &e{player}&a.";
                        break;
                    case "set":
                        successMsg = "&aSet &e{player}'s &abalance to &6{amount}&a.";
                        break;
                }
            }
            
            // Get updated balance
            BigDecimal newBalance = economy.getBalance(targetUUID);
            
            sender.sendMessage(Text.parseColors(successMsg
                    .replace("{player}", targetDisplayName)
                    .replace("{amount}", economy.formatBalance(amount))
                    .replace("{balance}", economy.formatBalance(newBalance))));
        } else {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("{player}", targetDisplayName);
            replacements.put("{action}", action);
            replacements.put("{amount}", economy.formatBalance(amount));
            
            Text.sendErrorMessage(sender, "economy.money-action-failed", lang, replacements);
        }
        
        return true;
    }
    
    /**
     * Show command usage to sender
     */
    private void showUsage(CommandSender sender, String label) {
        sender.sendMessage(lang.get("command-usage")
        .replace("{cmd}", label)
        .replace("{args}", "<give|take|set|clear> <player> [amount]"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        // Only provide tab completions if sender has permission
        if (!sender.hasPermission("core.admin")) {
            return completions;
        }
        
        if (args.length == 1) {
            // Suggest subcommands
            String partialSubcommand = args[0].toLowerCase();
            for (String subcommand : validSubcommands) {
                if (subcommand.startsWith(partialSubcommand)) {
                    completions.add(subcommand);
                }
            }
        } else if (args.length == 2) {
            // Suggest player names
            String partialName = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName)) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 3) {
            // For amount, suggest some common values
            String subCommand = args[0].toLowerCase();
            if (!subCommand.equals("clear") && !subCommand.equals("reset")) {
                String partialAmount = args[2].toLowerCase();
                List<String> amounts = Arrays.asList(
                    "100", "1000", "10000", 
                    "1k", "10k", "100k", 
                    "1m", "10m", "100m", 
                    "1b"
                );
                for (String amount : amounts) {
                    if (amount.startsWith(partialAmount)) {
                        completions.add(amount);
                    }
                }
            }
        }
        
        return completions;
    }
}
