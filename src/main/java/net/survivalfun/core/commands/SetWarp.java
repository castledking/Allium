package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.warp.WarpManager;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class SetWarp implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final WarpManager warpManager;
    private final Lang lang;

    public SetWarp(PluginStart plugin, WarpManager warpManager) {
        this.plugin = plugin;
        this.warpManager = warpManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Must be a player
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        Player player = (Player) sender;

        // Check usage
        if (args.length != 1) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "setwarp")
                    .replace("{args}", "<warp>"));
            return true;
        }

        String warpName = args[0].toLowerCase();

        // Validate warp name (must be at least 2 characters and not only numbers)
        if (!warpManager.validateWarpName(warpName)) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "warp name");
            return true;
        }

        // Check base setwarp permission
        if (!player.hasPermission("allium.setwarp")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "setwarp");
            return true;
        }

        // Check if warp already exists
        boolean warpExists = warpManager.warpExists(warpName);
        
        if (warpExists) {
            // Check overwrite permission
            if (!warpManager.canOverwriteWarp(player, warpName)) {
                Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "overwrite this warp", true);
                return true;
            }
        }

        // Create the warp
        if (warpManager.setWarp(warpName, player.getLocation(), player)) {
            if (warpExists) {
                player.sendMessage(lang.get("warp.updated")
                        .replace("{warp}", warpName));
            } else {
                player.sendMessage(lang.get("warp.created")
                        .replace("{warp}", warpName));
            }
        } else {
            Text.sendErrorMessage(player, "warp.create-failed", lang, "{warp}", warpName);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("allium.setwarp")) {
            // For overwriting existing warps, suggest existing warp names
            List<String> existingWarps = warpManager.getAllWarpNames();
            String partial = args[0].toLowerCase();
            
            for (String warp : existingWarps) {
                if (warp.toLowerCase().startsWith(partial) && 
                    warpManager.canOverwriteWarp(sender, warp)) {
                    completions.add(warp);
                }
            }
        }

        return completions;
    }
}
