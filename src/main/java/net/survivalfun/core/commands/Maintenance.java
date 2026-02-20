package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Maintenance implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;

    public Maintenance(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", 
            "maintenance");
            return true;
        }

        // Show current status if no arguments
        if (args.length == 0) {
            if (isMaintenanceEnabled()) {
                sender.sendMessage("Maintenance mode is currently ON. Reason: " + getMaintenanceReason());
            } else {
                sender.sendMessage("Maintenance mode is OFF.");
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();
        if (subCommand.equals("on")) {
            // Set maintenance mode on
            String reason = "Server Maintenance";
            if (args.length > 1) {
                reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
            setMaintenance(true, reason);
            sender.sendMessage("Maintenance mode enabled.");
        } else if (subCommand.equals("off")) {
            // Set maintenance mode off
            setMaintenance(false, "");
            sender.sendMessage("Maintenance mode disabled.");
        } else {
            // Invalid argument
            sender.sendMessage("Usage: /maintenance [on|off] [reason]");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }
    
    /**
     * Check if maintenance mode is enabled
     * @return true if maintenance mode is enabled, false otherwise
     */
    private boolean isMaintenanceEnabled() {
        return plugin.getConfig().getBoolean("maintenance.enabled", false);
    }
    
    /**
     * Get the maintenance reason
     * @return The maintenance reason or empty string if not set
     */
    private String getMaintenanceReason() {
        return plugin.getConfig().getString("maintenance.reason", "");
    }
    
    /**
     * Set maintenance mode status and reason
     * @param enabled Whether maintenance mode should be enabled
     * @param reason The reason for maintenance mode
     */
    private void setMaintenance(boolean enabled, String reason) {
        plugin.getConfig().set("maintenance.enabled", enabled);
        if (enabled) {
            plugin.getConfig().set("maintenance.reason", reason);
        } else {
            plugin.getConfig().set("maintenance.reason", "");
        }
        plugin.saveConfig();
    }
}
