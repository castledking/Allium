package net.survivalfun.core.commands.utils.core.staff;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public class NoteCommand implements CommandExecutor {
    private final PluginStart plugin;
    private final Database database;

    public NoteCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (!sender.hasPermission("allium.staff") || !sender.hasPermission("allium.note")) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("note.usage"));
            return true;
        }

        // Get target player (don't require them to be online)
        String targetName = args[0];
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();

        // Combine remaining args into note text
        String noteText = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        UUID staffUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;

        database.addPlayerNote(targetUuid, staffUuid, noteText);
        sender.sendMessage(lang.get("note.success")
                .replace("{player}", targetName)
                .replace("{note}", noteText));
        
        return true;
    }
}
