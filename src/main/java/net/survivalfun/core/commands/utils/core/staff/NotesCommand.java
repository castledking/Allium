package net.survivalfun.core.commands.utils.core.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.NoteEntry;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class NotesCommand implements CommandExecutor {
    private final PluginStart plugin;
    private final Database database;

    public NotesCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Lang lang = plugin.getLangManager();
        
        if (!sender.hasPermission("allium.staff") || !sender.hasPermission("allium.notes")) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("no-permission"));
            return true;
        }

        UUID playerUuid = null;
        String playerName = null;
        
        if (args.length > 0) {
            // Try to find player by name (online or offline)
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null) {
                playerUuid = target.getUniqueId();
                playerName = target.getName();
            } else {
                // Check offline players
                OfflinePlayer offlinePlayer = Arrays.stream(Bukkit.getOfflinePlayers())
                    .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(args[0]))
                    .findFirst()
                    .orElse(null);
                
                if (offlinePlayer != null) {
                    playerUuid = offlinePlayer.getUniqueId();
                    playerName = offlinePlayer.getName();
                }
            }
        }

        try {
            List<NoteEntry> notes = playerUuid != null 
                ? database.getNotesForPlayer(playerUuid) 
                : database.getAllNotes();
            
            sendNotesList(sender, notes, playerName);
            return true;
        } catch (SQLException e) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("database-error"));
            plugin.getLogger().severe("Failed to fetch notes: " + e.getMessage());
            return true;
        }
    }

    private void sendNotesList(CommandSender sender, List<NoteEntry> notes, String playerName) {
        Lang lang = plugin.getLangManager();
        
        if (notes.isEmpty()) {
            String message = playerName == null ? 
                lang.get("notes.none-found") : 
                lang.get("notes.none-for-player").replace("{player}", playerName);
            sender.sendMessage(lang.get("notes.header"));
            sender.sendMessage(message);
            return;
        }

        Component header = Component.text()
            .append(Component.text("=== ", NamedTextColor.GOLD))
            .append(Component.text(playerName == null ? "All Notes" : "Notes for " + playerName, NamedTextColor.WHITE))
            .append(Component.text(" ===", NamedTextColor.GOLD))
            .build();
        sender.sendMessage(header);

        for (NoteEntry note : notes) {
            String staffName = Bukkit.getOfflinePlayer(note.getStaffUuid()).getName();
            boolean canUnnote = sender.hasPermission("core.admin") || sender.hasPermission("core.unnote") || 
                              (sender instanceof Player && 
                               ((Player)sender).getUniqueId().equals(note.getStaffUuid()));

            TextComponent.Builder noteLine = Component.text()
                .append(Component.text("[" + note.getId() + "] ", NamedTextColor.GRAY))
                .append(Component.text(Bukkit.getOfflinePlayer(note.getPlayerUuid()).getName() + ": ", NamedTextColor.AQUA))
                .append(Component.text(note.getNote(), NamedTextColor.WHITE))
                .append(Component.text(" (Note by " + staffName + ")", NamedTextColor.GRAY))
                .hoverEvent(Component.text("Created: " + note.getCreatedAt()));
                
            if (canUnnote) {
                noteLine.append(
                    Component.text(" [x]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/unnote " + note.getId()))
                        .hoverEvent(Component.text("Click to remove this note"))
                );
            }
            
            sender.sendMessage(noteLine.build());
        }
    }
}
