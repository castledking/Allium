package net.survivalfun.core.commands.economy;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.EconomyManager;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Command to check player balance
 * Usage: /balance [player]
 * Aliases: /bal
 */
public class Balance implements CommandExecutor, TabCompleter {
    private final EconomyManager economy;
    private final PluginStart plugin;
    private final Lang lang;

    public Balance(PluginStart plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            // Check own balance
            if (!(sender instanceof Player)) {
                // Console needs to specify a player
                sender.sendMessage((
                        "&cUsage: /" + label + " <player>"));
                return true;
            }
            
            Player player = (Player) sender;
            BigDecimal balance = economy.getBalance(player);
            
            // Use language entry for self balance
            String message = plugin.getLangManager().get("economy.balance-self");
            if (message.isEmpty()) {
                message = "&aYour balance: &6{balance}";
            }
            sender.sendMessage((message.replace("{balance}", economy.formatBalance(balance))));
            return true;
        } else if (args.length == 1) {
            // Check another player's balance
            if (!sender.hasPermission("core.balance.others") && sender instanceof Player) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
                return true;
            }
            
            String targetName = args[0];
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
            
            BigDecimal balance = economy.getBalance(target);
            
            // Use language entry for other player's balance
            String otherBalanceMsg = plugin.getLangManager().get("economy.balance-other");
            if (otherBalanceMsg.isEmpty()) {
                otherBalanceMsg = "&a{player}'s balance: &6{balance}";
            }
            sender.sendMessage((otherBalanceMsg
                    .replace("{player}", target.getName())
                    .replace("{balance}", economy.formatBalance(balance))));
            return true;
        }
        
        // Show usage message
        String usageMsg = plugin.getLangManager().get("economy.balance-usage");
        if (usageMsg.isEmpty()) {
            usageMsg = "&cUsage: /" + label + " [player]";
        }
        sender.sendMessage((usageMsg));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1 && (sender.hasPermission("core.balance.others") || !(sender instanceof Player))) {
            String partialName = args[0].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName)) {
                    completions.add(player.getName());
                }
            }
        }
        
        return completions;
    }
}
