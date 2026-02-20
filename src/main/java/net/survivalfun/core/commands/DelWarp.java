package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.warp.WarpManager;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class DelWarp implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final WarpManager warpManager;
    private final Lang lang;

    public DelWarp(PluginStart plugin, WarpManager warpManager) {
        this.plugin = plugin;
        this.warpManager = warpManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.delwarp")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "delwarp");
            return true;
        }

        // Check usage
        if (args.length != 1) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "delwarp")
                    .replace("{args}", "<warp>"));
            return true;
        }

        String warpName = args[0].toLowerCase();

        // Check if warp exists
        if (!warpManager.warpExists(warpName)) {
            Text.sendErrorMessage(sender, "warp.not-found", lang, "{warp}", warpName);
            return true;
        }

        // Delete the warp
        if (warpManager.deleteWarp(warpName)) {
            sender.sendMessage(lang.get("warp.deleted")
                    .replace("{warp}", warpName));
        } else {
            Text.sendErrorMessage(sender, "warp.delete-failed", lang, "{warp}", warpName);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("allium.delwarp")) {
            // Tab complete existing warp names
            List<String> existingWarps = warpManager.getAllWarpNames();
            String partial = args[0].toLowerCase();
            
            for (String warp : existingWarps) {
                if (warp.toLowerCase().startsWith(partial)) {
                    completions.add(warp);
                }
            }
        }

        return completions;
    }
}
