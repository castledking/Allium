package net.survivalfun.core.commands.core;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.lang.LangManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class Core implements CommandExecutor, TabCompleter {

    private final WorldDefaults worldDefaults;
    private final PluginStart plugin;
    private final FileConfiguration config;
    private final LangManager lang;

    public Core(WorldDefaults worldDefaults, PluginStart plugin, FileConfiguration config, LangManager lang) {
        this.worldDefaults = worldDefaults;
        this.plugin = plugin;
        this.config = config;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing Core command");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setgamerule":
                handleSetGameruleCommand(sender, args);
                break;


            case "reload":
                handleReloadCommand(sender);
                break;

            default:
                sender.sendMessage("§cUnknown subcommand. Use /core for help.");
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§aAvailable /core subcommands:");
        sender.sendMessage("§e/setgamerule §7- Modify and apply world defaults.");
        sender.sendMessage("§e/reload §7- Reload the plugin configuration.");
    }

    private void handleSetGameruleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("core.worlddefaults")) {
            sender.sendMessage(lang.get("no-permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /core worlddefaults <gamerule> <value>");
            return;
        }

        String gamerule = args[1].toLowerCase();
        String value = args[2];

        // Update the gamerule in the config
        if (!worldDefaults.plugin().getConfig().contains("world-defaults." + gamerule)) {
            sender.sendMessage("§cInvalid gamerule: " + gamerule);
            return;
        }

        try {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                worldDefaults.plugin().getConfig().set("world-defaults." + gamerule, Boolean.parseBoolean(value));
            } else {
                int intValue = Integer.parseInt(value);
                worldDefaults.plugin().getConfig().set("world-defaults." + gamerule, intValue);
            }

            // Save and apply the changes
            worldDefaults.plugin().saveConfig();
            worldDefaults.applyWorldDefaults();

            sender.sendMessage("§aUpdated gamerule '" + gamerule + "' to '" + value + "' and applied it to all worlds.");
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid value. Use true, false, or a number.");
        }
    }


    private void handleReloadCommand(CommandSender sender) {
        try {
            plugin.reloadConfig(); // Reloads the config.yml from disk
            plugin.getConfig();// Re-gets the FileConfiguration
            lang.reload();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error reloading configuration: ", e);
            sender.sendMessage("Error reloading configuration. Check the console for details.");
        }

        worldDefaults.plugin().reloadConfig();
        sender.sendMessage("§aConfiguration reloaded.");
    }
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("core")) {
            return null;
        }

        return getCoreSuggestions(sender, args);
    }

    private List<String> getCoreSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            // Suggest subcommands for /core
            suggestions.addAll(List.of("worlddefaults", "heal", "reload"));
        } else if (args.length > 1 && args[0].equalsIgnoreCase("worlddefaults")) {
            suggestions.addAll(getWorldDefaultsSuggestions(sender, args));
        }

        return filterSuggestions(suggestions, args[args.length - 1]);
    }

    private List<String> getWorldDefaultsSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 2) {
            // Suggest gamerules from the config.yml under "world-defaults"
            if (config.isConfigurationSection("world-defaults")) {
                suggestions.addAll(config.getConfigurationSection("world-defaults").getKeys(false));
            }
        } else if (args.length == 3) {
            // Suggest possible values for gamerules
            suggestions.addAll(List.of("true", "false", "1", "0"));
        }

        return suggestions;
    }

    private List<String> filterSuggestions(List<String> suggestions, String currentInput) {
        List<String> filtered = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(currentInput.toLowerCase())) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }
}