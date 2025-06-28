package net.survivalfun.core.commands.economy;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.economy.Economy;
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
import java.util.List;

/**
 * Command to pay another player
 * Usage: /pay <player> <amount>
 */
public class Pay implements CommandExecutor, TabCompleter {
    private final Economy economy;
    private final PluginStart plugin;
    private final Lang lang;

    public Pay(PluginStart plugin, Economy economy) {
        this.economy = economy;
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        if (args.length != 2) {
            String message = lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<player> <amount>");
            sender.sendMessage((message));
            return true;
        }

        Player player = (Player) sender;
        String targetName = args[0];
        String amountStr = args[1];

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

        // Check if player is trying to pay themselves
        if (player.getUniqueId().equals(target.getUniqueId())) {
            Text.sendErrorMessage(sender, "cannot-self", lang, "{action}", "pay");
            return true;
        }

        // Parse amount with support for shorthand notation (k, m, b)
        BigDecimal amount;
        try {
            // Convert shorthand notation to full number
            String processedAmount = amountStr.toLowerCase();
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
                Text.sendErrorMessage(sender, "amount-positive", lang);
                return true;
            }
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "amount");
            return true;
        }

        // Check if player has enough money
        if (!economy.hasEnough(player, amount)) {
            Text.sendErrorMessage(sender, "insufficient-funds", lang, "{balance}", economy.formatBalance(economy.getBalance(player)));
            return true;
        }

        // Transfer money
        if (economy.transfer(player, target, amount)) {
            String message = lang.get("economy.pay-success");
            if (message.isEmpty()) {
                message = "&aYou paid &6{amount} &ato &f{player}&a.";
            }
            sender.sendMessage((message
                    .replace("{amount}", economy.formatBalance(amount))
                    .replace("{player}", target.getName())));
            
            // Notify target if they're online
            Player targetPlayer = target.getPlayer();
            if (targetPlayer != null && targetPlayer.isOnline()) {
                String receiveMsg = lang.get("economy.pay-received");
                if (receiveMsg.isEmpty()) {
                    receiveMsg = "&aYou received &6{amount} &afrom &f{player}&a.";
                }
                targetPlayer.sendMessage((receiveMsg
                        .replace("{amount}", economy.formatBalance(amount))
                        .replace("{player}", player.getName())));
            }
        } else {
            String message = lang.get("economy.pay-failed");
            if (message.isEmpty()) {
                message = "&cFailed to transfer money. Please try again later.";
            }
            sender.sendMessage((message));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName) && 
                        !player.getName().equalsIgnoreCase(sender.getName())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            // Suggest some common amounts
            completions.add("10");
            completions.add("50");
            completions.add("100");
            completions.add("500");
            completions.add("1000");
        }
        
        return completions;
    }
}
