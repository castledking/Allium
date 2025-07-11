package net.survivalfun.core.commands.utils.core.staff;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.NoteEntry;
import net.survivalfun.core.managers.lang.Lang;
import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class UnnoteCommand implements CommandExecutor {
    private final PluginStart plugin;
    private final Database database;

    public UnnoteCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (!sender.hasPermission("core.staff")) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("no-permission"));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("unnote.usage"));
            return true;
        }

        try {
            int noteId = Integer.parseInt(args[0]);
            UUID staffUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
            
            // Get note first to get player name
            NoteEntry note = database.getNoteById(noteId);
            if (note == null) {
                sender.sendMessage(lang.get("error-prefix") + lang.get("unnote.not-found"));
                return true;
            }
            
            String targetName = Bukkit.getOfflinePlayer(note.getPlayerUuid()).getName();
            
            if (!database.deleteNote(noteId, staffUuid, sender.hasPermission("core.admin") || sender.hasPermission("core.unnote"))) {
                sender.sendMessage(lang.get("error-prefix") + lang.get("unnote.not-found"));
                return true;
            }

            sender.sendMessage(lang.get("unnote.success")
                .replace("{id}", String.valueOf(noteId))
                .replace("{player}", targetName != null ? targetName : "Unknown"));
            return true;
        } catch (NumberFormatException e) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("unnote.invalid-id"));
            return true;
        } catch (SQLException e) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("database-error"));
            plugin.getLogger().severe("Failed to delete note: " + e.getMessage());
            return true;
        }
    }
}
