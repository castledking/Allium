package net.survivalfun.core.commands.core;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.fun.Explode;

import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter; // Added import
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;

public class Core implements CommandExecutor, TabCompleter {

    private final WorldDefaults worldDefaults;
    private final PluginStart plugin;
    private final FileConfiguration config;
    private final Lang lang;
    private final CommandManager commandManager;
    private final CreativeManager creativeManager;

        public Core(WorldDefaults worldDefaults, PluginStart plugin, FileConfiguration config, CommandManager commandManager
    , CreativeManager creativeManager) {
        this.worldDefaults = worldDefaults;
        this.plugin = plugin;
        this.config = config;
        this.lang = plugin.getLangManager();
        this.commandManager = commandManager;
        this.creativeManager = creativeManager;
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
                handleReloadCommand(sender, null);
                break;

            case "debug":
                handleDebugCommand(sender, args);
                break;
            case "hideupdate":
                handleHideUpdateCommand(sender, args);
                break;
            // modalerts case removed
            case "hide":
                handleHideSubcommand(sender, args);
                break;


            // Voucher command removed - only using redeem command

            default:
                sender.sendMessage("§cUnknown subcommand. Use /core for help.");
                break;
        }

        return true;
    }


    private void handleHideUpdateCommand(@NotNull CommandSender sender, String @NotNull [] args) {
        if(!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        if(args.length >= 1) {
            if(args[1].equalsIgnoreCase("all")) {
                // Code for updating all players
                int count = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    commandManager.updatePlayerTabCompletion(player);
                    count++;
                }
                sender.sendMessage(Component.text("Updated tab completion for " + count + " players.", NamedTextColor.GREEN));
            } else {
                // Handle other subcommands or show usage
                sender.sendMessage(Component.text("Unknown subcommand: " + args[1], NamedTextColor.RED));
            }
            return;
        }
// Code for single player update
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
            return;
        }
        commandManager.updatePlayerTabCompletion(target);
        sender.sendMessage("§aUpdated tab completion for " + target.getName());
    }

    private void sendHelpMessage(CommandSender sender) {
        if(!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        sender.sendMessage("§aAvailable /core subcommands:");
        sender.sendMessage("§edebug §7- Toggle debug mode.");
        sender.sendMessage("§ereload §7- Reload the plugin configuration.");
        sender.sendMessage("§ehideupdate §7- Refresh tab completion for player.");
        // modalerts command removed
        sender.sendMessage("§echatmod §7- Manage chat moderation actions.");


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

    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang);
            return;
        }
        // Toggle debug mode in config
        boolean currentDebugMode = plugin.getConfig().getBoolean("debug-mode", false);
        boolean newDebugMode = !currentDebugMode;

        // Update config
        plugin.getConfig().set("debug-mode", newDebugMode);
        plugin.saveConfig();
        handleReloadCommand(sender, true);



        // Log the change
        plugin.getLogger().info("Debug mode " + (newDebugMode ? "enabled" : "disabled") + " by " + sender.getName());
    }

    // modalerts command handler removed

    private void handleReloadCommand(CommandSender sender, Boolean isDebug) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        try {
            // Reload the main config
            plugin.reloadConfig();

            // Reload the command manager
            commandManager.reload();

            // Reload the creative manager blacklists
            creativeManager.reloadBlacklists();

            // Re-initialize the ConfigManager to apply changes
            if (plugin.getConfigManager() != null) {
                plugin.getConfigManager().load();
            }

            // Reload language files
            if (lang != null) {
                lang.reload();
            }

            // Reload world defaults
            if (worldDefaults != null) {
                worldDefaults.plugin().reloadConfig();
                worldDefaults.applyWorldDefaults();
            }

            // Reload the chat formatter to apply new chat format settings
            plugin.reloadChatFormatter();

            // Reload the explode command configuration
            Explode explodeCommand = plugin.getExplodeCommand();
            if (explodeCommand != null) {
                explodeCommand.reloadConfig();
            }
            
            // Reload creeper explosion settings
            try {
                net.survivalfun.core.listeners.jobs.CreeperExplosionListener listener = 
                    net.survivalfun.core.listeners.jobs.CreeperExplosionListener.getInstance();
                if (listener != null) {
                    listener.reloadConfig();
                    plugin.getLogger().info("Successfully reloaded creeper explosion settings");
                } else {
                    plugin.getLogger().warning("Creeper explosion listener not found");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to reload creeper explosion settings: " + e.getMessage());
                e.printStackTrace();
            }

            // Build success message with optional debug status
            StringBuilder successMessage = new StringBuilder("§aConfiguration reloaded successfully.");

            // Add debug status if isDebug is not null (meaning it was explicitly set)
            if (isDebug != null) {
                boolean debugMode = plugin.getConfig().getBoolean("debug-mode", false);
                successMessage.append(" (Debug: ").append(debugMode ? "§aenabled§a" : "§cdisabled§a").append(")");
            }

            sender.sendMessage(successMessage.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error reloading configuration: ", e);
            sender.sendMessage("§cError reloading configuration! Check console for details.");
        }
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
        String currentArg = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            // Suggest subcommands for /core
            if (sender.hasPermission("core.admin")) {
                suggestions.add("reload");
                suggestions.add("debug");
                suggestions.add("hideupdate");
                suggestions.add("hide");
                // modalerts command removed
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("hide")) {
            if (args.length == 2) {
                suggestions.addAll(List.of("creategroup", "deletegroup", "group"));
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("deletegroup") || args[1].equalsIgnoreCase("group"))) {
                // Suggest group names for deletegroup/group
                org.bukkit.configuration.file.YamlConfiguration hideConfig =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.File(plugin.getDataFolder(), "hide.yml"));
                if (hideConfig.isConfigurationSection("groups")) {
                    suggestions.addAll(hideConfig.getConfigurationSection("groups").getKeys(false));
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("group")) {
                suggestions.addAll(List.of("add", "remove"));
            } else if (args.length == 5 && args[1].equalsIgnoreCase("group") && args[3].equalsIgnoreCase("add")) {
                suggestions.add("-s");
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("worlddefaults")) {
            suggestions.addAll(getWorldDefaultsSuggestions(sender, args));
        } else if (args.length > 1 && args[0].equalsIgnoreCase("updatehide")) {
            suggestions.addAll(getUpdateHideSuggestions(args));
        }
        // Filter final list of completions by currentArg. This handles cases where completions were added without pre-filtering.
        // For subcommands like 'voucher' that already filter, this is a bit redundant but harmless.
        // For subcommands that add to 'suggestions' without filtering by currentArg, this is necessary.
        List<String> finalSuggestions = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(currentArg)) {
                finalSuggestions.add(suggestion);
            }
        }
        return finalSuggestions.isEmpty() && args.length > 0 ? Collections.emptyList() : finalSuggestions;
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

    private List<String> getUpdateHideSuggestions(String[] args) {
        List<String> suggestions = new ArrayList<>();
        if(args.length == 1) {
            suggestions.add("all");

            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
        }
        return suggestions;
    }

    private void handleHideSubcommand(@NotNull CommandSender sender, String @NotNull [] args) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core hide");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /core hide <creategroup|deletegroup|group>");
            return;
        }
        // Save/load hide.yml from plugins/SFCore/hide.yml
        java.io.File hideFile = new java.io.File(plugin.getDataFolder(), "hide.yml");
        org.bukkit.configuration.file.YamlConfiguration hideConfig =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(hideFile);
        String groupPath = "groups.";
        switch (args[1].toLowerCase()) {
            case "creategroup": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /core hide creategroup <name>");
                    return;
                }
                String group = args[2];
                if (hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' already exists.");
                    return;
                }
                hideConfig.set(groupPath + group + ".whitelist", true);
                hideConfig.set(groupPath + group + ".commands", new java.util.ArrayList<String>());
                hideConfig.set(groupPath + group + ".tabcompletes", new java.util.ArrayList<String>());
                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + group + "' created.");
                break;
            }
            case "deletegroup": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /core hide deletegroup <name>");
                    return;
                }
                String group = args[2];
                if (!hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' does not exist.");
                    return;
                }
                hideConfig.set(groupPath + group, null);
                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + group + "' deleted.");
                break;
            }
            case "group": {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /core hide group <name> <add [-s]|remove> <cmd>");
                    return;
                }
                String group = args[2];
                if (!hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' does not exist.");
                    return;
                }
                String action = args[3].toLowerCase();
                boolean onlyCommands = false;
                int cmdIndex = 4;
                if (action.equals("add") && args[4].equalsIgnoreCase("-s")) {
                    onlyCommands = true;
                    cmdIndex = 5;
                    if (args.length < 6) {
                        sender.sendMessage("§cUsage: /core hide group <name> add [-s] <cmd>");
                        return;
                    }
                }
                String cmd = args[cmdIndex];
                java.util.List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                java.util.List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
                if (action.equals("add")) {
                    if (!commands.contains(cmd)) {
                        commands.add(cmd);
                    }
                    if (!onlyCommands && !tabcompletes.contains(cmd)) {
                        tabcompletes.add(cmd);
                    }
                    hideConfig.set(groupPath + group + ".commands", commands);
                    if (!onlyCommands) hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                    saveHideConfig(hideConfig, hideFile);
                    handleReloadCommand(sender, null);
                    sender.sendMessage("§aAdded '" + cmd + "' to group '" + group + "'.");
                } else if (action.equals("remove")) {
                    boolean changed = false;
                    if (commands.remove(cmd)) changed = true;
                    if (tabcompletes.remove(cmd)) changed = true;
                    hideConfig.set(groupPath + group + ".commands", commands);
                    hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                    saveHideConfig(hideConfig, hideFile);
                    handleReloadCommand(sender, null);
                    if (changed) {
                        sender.sendMessage("§aRemoved '" + cmd + "' from group '" + group + "'.");
                    } else {
                        sender.sendMessage("§e'" + cmd + "' was not present in group '" + group + "'.");
                    }
                } else {
                    sender.sendMessage("§cUsage: /core hide group <name> <add [-s]|remove> <cmd>");
                }
                break;
            }
            default:
                sender.sendMessage("§cUnknown hide subcommand. Use /core hide for help.");
        }
    }

    private void saveHideConfig(org.bukkit.configuration.file.YamlConfiguration hideConfig, java.io.File file) {
        try {
            hideConfig.save(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}