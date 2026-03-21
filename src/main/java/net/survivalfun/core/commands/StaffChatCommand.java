package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * /a - Toggle between staff chat and default chat (ChatControl channels).
 * State is persisted in the database.
 */
public class StaffChatCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;
    private final Database database;

    public StaffChatCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.database = plugin.getDatabase();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can toggle staff chat.");
            return true;
        }
        if (!sender.hasPermission("allium.staffchat")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "/a");
            return true;
        }

        boolean currentlyInStaffChat = database.getStaffChatMode(player.getUniqueId());
        String playerName = player.getName();

        if (currentlyInStaffChat) {
            // Switch to default channel
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "channel join default read " + playerName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "channel join default write " + playerName);
            });
            database.setStaffChatMode(player.getUniqueId(), playerName, false);
            sender.sendMessage("§8[§bStaff Chat§8] §eYou have switched back to §fglobal chat§e.");
        } else {
            // Switch to staff-chat channel
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "channel join staff-chat read " + playerName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "channel join staff-chat write " + playerName);
            });
            database.setStaffChatMode(player.getUniqueId(), playerName, true);
            sender.sendMessage("§8[§bStaff Chat§8] §aYou are now chatting in §bstaff chat§a.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        return Collections.emptyList();
    }
}
