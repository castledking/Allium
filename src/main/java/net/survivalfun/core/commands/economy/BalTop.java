package net.survivalfun.core.commands.economy;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.BalanceEntry;
import net.survivalfun.core.managers.economy.Economy;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Command to display the richest players
 * Usage: /baltop [page]
 */
public class BalTop implements CommandExecutor, TabCompleter {
    private final Economy economy;
    private final Lang lang;
    private final int ENTRIES_PER_PAGE = 10;

    public BalTop(PluginStart plugin, Economy economy) {
        this.economy = economy;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        int page = 1;
        
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) {
                    page = 1;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "[page]"));
                return true;
            }
        }
        
        // Calculate how many entries to fetch based on page
        int limit = page * ENTRIES_PER_PAGE;
        
        // Get top balances
        List<BalanceEntry> topBalances = economy.getTopBalances(limit);
        
        // Calculate total pages
        int totalEntries = topBalances.size();
        int totalPages = (totalEntries + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE; // Ceiling division
        
        if (page > totalPages && totalPages > 0) {
            String message = lang.get("economy.baltop-max-pages");
            if (message.isEmpty()) {
                message = "&cThere are only {pages} pages of results.";
            }
            sender.sendMessage((message.replace("{pages}", String.valueOf(totalPages))));
            return true;
        }
        
        // Display header
        String header = lang.get("economy.baltop-header");
        if (header.isEmpty()) {
            header = "&6===== &eTop Balances &6- &ePage {page}/{total} &6=====";
        }
        sender.sendMessage((header
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(totalPages == 0 ? 1 : totalPages))));
        
        // Calculate start and end indices for this page
        int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, totalEntries);
        
        if (totalEntries == 0) {
            String message = lang.get("economy.baltop-no-players");
            if (message.isEmpty()) {
                message = "&7No players found.";
            }
            sender.sendMessage((message));
        } else {
            // Display entries for this page
            for (int i = startIndex; i < endIndex; i++) {
                BalanceEntry entry = topBalances.get(i);
                int rank = i + 1;
                String entryFormat = lang.get("economy.baltop-entry");
                if (entryFormat.isEmpty()) {
                    entryFormat = "&6#{rank} &a{player}: &e{balance}";
                }
                sender.sendMessage((entryFormat
                        .replace("{rank}", String.valueOf(rank))
                        .replace("{player}", entry.getPlayerName())
                        .replace("{balance}", economy.formatBalance(entry.getBalance()))));
            }
        }
        
        // Display footer with navigation help if there are multiple pages
        if (totalPages > 1) {
            String footer = lang.get("economy.baltop-footer");
            if (footer.isEmpty()) {
                footer = "&7Use &e/{cmd} <page> &7to view other pages.";
            }
            sender.sendMessage((footer.replace("{cmd}", label)));
        }
        
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // Suggest page numbers 1-5
            for (int i = 1; i <= 5; i++) {
                completions.add(String.valueOf(i));
            }
        }
        
        return completions;
    }
}
