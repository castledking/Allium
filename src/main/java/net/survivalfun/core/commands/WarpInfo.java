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
import java.util.Map;

public class WarpInfo implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final WarpManager warpManager;
    private final Lang lang;

    public WarpInfo(PluginStart plugin, WarpManager warpManager) {
        this.plugin = plugin;
        this.warpManager = warpManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.warpinfo")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "warpinfo");
            return true;
        }

        // Check usage
        if (args.length != 1) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "warpinfo")
                    .replace("{args}", "<warp>"));
            return true;
        }

        String warpName = args[0].toLowerCase();

        // Check if warp exists
        if (!warpManager.warpExists(warpName)) {
            Text.sendErrorMessage(sender, "warp.not-found", lang, "{warp}", warpName);
            return true;
        }

        // Check if player has permission to access this warp
        if (!warpManager.hasWarpPermission(sender, warpName)) {
            Text.sendErrorMessage(sender, "no-permission", lang, "/{cmd}", "this warp", true);
            return true;
        }

        // Get and display warp information
        Map<String, Object> warpInfo = warpManager.getWarpInfo(warpName);
        String formattedInfo = warpManager.formatWarpInfo(warpInfo);
        
        sender.sendMessage(formattedInfo);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("allium.warpinfo")) {
            // Tab complete warps that the player has access to
            List<String> accessibleWarps = warpManager.getAccessibleWarps(sender);
            String partial = args[0].toLowerCase();
            
            for (String warp : accessibleWarps) {
                if (warp.toLowerCase().startsWith(partial)) {
                    completions.add(warp);
                }
            }
        }

        return completions;
    }
}
