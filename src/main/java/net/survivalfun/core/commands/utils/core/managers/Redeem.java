package net.survivalfun.core.commands.utils.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

public class Redeem implements CommandExecutor {

    private final PluginStart plugin;

    public Redeem(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Text.parseColors("&cOnly players can use this command.")); // Or use a lang key
            return true;
        }

        Player player = (Player) sender;
        UUID playerUuid = player.getUniqueId();
        FileConfiguration config = plugin.getConfig();
        Lang lang = plugin.getLangManager();

        List<String> playersToRedeem = config.getStringList("redeem-settings.players-to-redeem");
        String playerEntryString = null;
        String rankToRedeem = null;

        for (String entry : new ArrayList<>(playersToRedeem)) { // Iterate over a copy to allow modification
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                try {
                    UUID entryUuid = UUID.fromString(parts[0]);
                    if (entryUuid.equals(playerUuid)) {
                        playerEntryString = entry;
                        rankToRedeem = parts[1].toLowerCase();
                        break;
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID format in players-to-redeem list: " + parts[0] + " in entry '" + entry + "'");
                }
            } else {
                plugin.getLogger().warning("Invalid format in players-to-redeem list: '" + entry + "'. Expected UUID:Rank.");
            }
        }

        if (rankToRedeem != null) {
            List<String> commandsToRun = config.getStringList("redeem-settings.commands-to-run." + rankToRedeem);

            if (commandsToRun.isEmpty()) {
                Text.sendErrorMessage(player, lang.getRaw("contact-admin"), lang);
                plugin.getLogger().warning("Redeem rank '" + rankToRedeem + "' is misconfigured (no commands found in 'redeem-settings.commands-to-run').");
                return true;
            }

            // Run configured commands
            for (String cmdFormat : commandsToRun) {
                String actualCmd = cmdFormat.replace("{player}", player.getName())
                                           .replace("{uuid}", playerUuid.toString());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), actualCmd);
            }

            // Remove player from list
            playersToRedeem.remove(playerEntryString);
            config.set("redeem-settings.players-to-redeem", playersToRedeem);
            plugin.saveConfig();

            // Send success message
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("rank", rankToRedeem.substring(0, 1).toUpperCase() + rankToRedeem.substring(1));
            String rawSuccessMessage = plugin.getLangManager().getRaw("redeem.success");
            String formattedSuccessMessage = plugin.getLangManager().format(rawSuccessMessage, placeholders);
            player.sendMessage(formattedSuccessMessage);

        } else {
            // Player not found in list or rank is invalid
            Map<String, String> noPermPlaceholders = new HashMap<>();
            noPermPlaceholders.put("cmd", "redeem");
            Text.sendErrorMessage(player, "no-permission", lang, noPermPlaceholders);
        }
        return true;
    }
}
