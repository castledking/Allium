package net.survivalfun.core.commands.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.listeners.security.CommandManager;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.lang.reflect.Method;

public class Help implements CommandExecutor, Listener, TabCompleter {

    private final PluginStart plugin;
    private final CommandManager commandManager;
    private final int COMMANDS_PER_PAGE = 8;

    public Help(PluginStart plugin) {
        this.plugin = plugin;
        this.commandManager = plugin.getCommandManager();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First argument: offer page numbers and plugin names
            completions.add("1"); // Suggest first page
            completions.add("2"); // Suggest second page
            completions.add("3"); // Suggest third page

            // Add plugin names if sender has admin permission
            if (sender.hasPermission("core.admin")) {
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.isEnabled()) {
                        completions.add(plugin.getName());
                    }
                }
            }

            // Add command names for both console and players
            List<String> availableCommands = getAvailableCommands(sender);
            completions.addAll(availableCommands);
        } else if (args.length == 2) {
            // Second argument: offer page numbers if first arg is a plugin name or command
            completions.add("1");
            completions.add("2");
            completions.add("3");
        }

        // Filter based on what's already been typed
        if (args.length > 0 && args[args.length - 1] != null && !args[args.length - 1].isEmpty()) {
            String lastArg = args[args.length - 1].toLowerCase();
            return completions.stream()
                    .filter(s -> s != null && s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        }

        return completions;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // If no args provided, show main help page
        if (args.length == 0) {
            showMainHelpPage(sender, 1);
            return true;
        }

        // Handle arguments for both console and players
        if (sender instanceof Player) {
            return handleHelpArg(args, (Player)sender);
        } else {
            return handleHelpArgConsole(args, sender);
        }
    }

    private boolean handleHelpArg(String[] args, Player player) {
        // Check if the first argument is a page number
        try {
            int page = Integer.parseInt(args[0]);
            if (page < 1) page = 1;

            // If there's a second argument, it might be a plugin name with a page number
            if (args.length > 1) {
                String pluginName = args[1];
                sendPluginSpecificHelp(player, pluginName, page);
            } else {
                // Just a page number for the main help
                showMainHelpPage(player, page);
            }
            return true;
        } catch (NumberFormatException e) {
            // First argument is not a number, it's a query (plugin name or command)
            String query = args[0];

            // Check if there's a second argument that might be a page number
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException ex) {
                    // Second argument is not a page number, ignore it
                }
            }

            sendPluginSpecificHelp(player, query, page);
            return true;
        }
    }
    
    private boolean handleHelpArgConsole(String[] args, CommandSender sender) {
        // Check if the first argument is a page number
        try {
            int page = Integer.parseInt(args[0]);
            if (page < 1) page = 1;

            // If there's a second argument, it might be a plugin name with a page number
            if (args.length > 1) {
                String pluginName = args[1];
                sendPluginSpecificHelp(sender, pluginName, page);
            } else {
                // Just a page number for the main help
                showMainHelpPage(sender, page);
            }
            return true;
        } catch (NumberFormatException e) {
            // First argument is not a number, it's a query (plugin name or command)
            String query = args[0];

            // Check if there's a second argument that might be a page number
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException ex) {
                    // Second argument is not a page number, ignore it
                }
            }

            sendPluginSpecificHelp(sender, query, page);
            return true;
        }
    }


    /**
     * Shows the main help page with plugin list for admins
     */
    private void showMainHelpPage(CommandSender sender, int page) {
        // Combined list for both plugins and commands
        List<HelpEntry> helpEntries = new ArrayList<>();

        // If player has admin permission, add plugins to the list
        if (sender.hasPermission("core.admin")) {
            // Get all plugins and sort alphabetically
            List<String> pluginNames = new ArrayList<>();
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (plugin.isEnabled()) {
                    pluginNames.add(plugin.getName());
                }
            }
            Collections.sort(pluginNames);

            // Add plugins as help entries
            for (String pluginName : pluginNames) {
                helpEntries.add(new HelpEntry(pluginName, true));
            }
        }

        // Get all available commands for this player
        List<String> availableCommands = getAvailableCommands(sender);
        Collections.sort(availableCommands);

        // Add commands as help entries
        for (String cmdName : availableCommands) {
            helpEntries.add(new HelpEntry(cmdName, false));
        }

        // Calculate total pages
        int totalPages = (int) Math.ceil((double) helpEntries.size() / COMMANDS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        if (totalPages == 0) totalPages = 1; // Prevent division by zero

        // Header
        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &ePage " + page + "/" + totalPages));
        sender.sendMessage(Text.colorize("&7Commands you have access to:"));
        sender.sendMessage(Component.empty());

        // No entries available
        if (helpEntries.isEmpty()) {
            sender.sendMessage(Text.colorize("&cNo commands available."));
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            return;
        }

        // Display entries for current page
        int startIndex = (page - 1) * COMMANDS_PER_PAGE;
        int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, helpEntries.size());

        for (int i = startIndex; i < endIndex; i++) {
            HelpEntry entry = helpEntries.get(i);
            if (entry.isPlugin) {
                sendPluginInfo(sender, entry.name);
            } else {
                sendCommandInfo(sender, entry.name);
            }
        }

        // Footer
        sender.sendMessage(Component.empty());
        sendNavigationButtons(sender, page, totalPages);
        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
    }

    /**
     * Helper class to represent an entry in the help menu
     */
    private static class HelpEntry {
        String name;
        boolean isPlugin;

        public HelpEntry(String name, boolean isPlugin) {
            this.name = name;
            this.isPlugin = isPlugin;
        }
    }

    /**
     * Sends plugin info with hover and click events
     */
    private void sendPluginInfo(CommandSender sender, String pluginName) {
        // Create the hover tooltip
        Component hoverTooltip = Component.text()
                .append(Text.colorize("&ePlugin: &f" + pluginName + "\n"))
                .append(Text.colorize("&7Click to view commands for this plugin"))
                .build();

        // Create the plugin component with hover and click events
        Component pluginComponent = Component.text(" • [Plugin] ")
                .color(NamedTextColor.RED)
                .append(Component.text(pluginName)
                        .color(NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(hoverTooltip))
                        .clickEvent(ClickEvent.runCommand("/help " + pluginName)));

        // Send the component
        sender.sendMessage(pluginComponent);
    }

    private List<String> getAvailableCommands(CommandSender sender) {
        List<String> result = new ArrayList<>();
        Set<String> processedCommands = new HashSet<>(); // To avoid duplicates

        // Only apply group filtering for players
        Player player = sender instanceof Player ? (Player) sender : null;

        // Get commands directly from the server's command map
        Map<String, Command> knownCommands;
        try {
            // Use reflection to get the server's command map
            org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap)
                    Bukkit.getServer().getClass().getDeclaredMethod("getCommandMap").invoke(Bukkit.getServer());

            // Get the known commands field through reflection
            java.lang.reflect.Field knownCommandsField = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);

            // Get all known commands
            knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to access server commands: " + e.getMessage());
            return result; // Return empty list if we can't access the command map
        }

        // Process all commands from the command map
        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue; // Skip null entries
            }

            String cmdName = entry.getKey();
            Command command = entry.getValue();

            // Skip namespace commands (plugin:command) for cleaner display
            if (cmdName.contains(":")) {
                continue;
            }

            // Skip if we've already processed this command
            if (processedCommands.contains(cmdName)) {
                continue;
            }

            // Skip if it's a vanilla command and we should block those
            if (isVanillaCommand(cmdName)) {
                continue;
            }

            // Check command visibility based on group permissions
            boolean canShow = true;
            if (player != null) {
                // Use reflection to call shouldShowCommand
                try {
                    Class<?> commandManagerClass = Class.forName("net.survivalfun.core.listeners.security.CommandManager");
                    Object commandManagerInstance = plugin.getClass().getDeclaredMethod("getCommandManager").invoke(plugin);
                    Method shouldShowCommandMethod = commandManagerClass.getDeclaredMethod("shouldShowCommand", Player.class, String.class);
                    canShow = (boolean) shouldShowCommandMethod.invoke(commandManagerInstance, player, cmdName);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to check command visibility: " + e.getMessage());
                    canShow = true; // Default to showing if reflection fails
                }
            }

            // Check if player has permission and command is allowed
            if (canShow && (command.getPermission() == null || sender.hasPermission(command.getPermission()))) {
                result.add(cmdName);
                processedCommands.add(cmdName);

                // Process aliases
                for (String alias : command.getAliases()) {
                    if (alias != null && !processedCommands.contains(alias) &&
                            !isVanillaCommand(alias)) {  // Also check aliases against vanilla commands
                        // Check alias visibility as well
                        boolean aliasCanShow = true;
                        if (player != null) {
                            try {
                                Class<?> commandManagerClass = Class.forName("net.survivalfun.core.listeners.security.CommandManager");
                                Object commandManagerInstance = plugin.getClass().getDeclaredMethod("getCommandManager").invoke(plugin);
                                Method shouldShowCommandMethod = commandManagerClass.getDeclaredMethod("shouldShowCommand", Player.class, String.class);
                                aliasCanShow = (boolean) shouldShowCommandMethod.invoke(commandManagerInstance, player, alias);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to check alias visibility: " + e.getMessage());
                                aliasCanShow = true;
                            }
                        }

                        if (aliasCanShow) {
                            result.add(alias);
                            processedCommands.add(alias);
                        }
                    }
                }
            }
        }

        // Sort the result for a cleaner display
        Collections.sort(result);

        return result;
    }

    /**
     * Check if a command is likely a vanilla Minecraft command
     */
    private boolean isVanillaCommand(String command) {

        String[] vanillaCommands = {
                "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
                "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect",
                "enchant", "execute", "experience", "fill", "forceload", "function", "gamemode", "gamerule",
                "give", "item", "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg",
                "op", "pardon", "pardon-ip", "particle", "place", "playsound", "recipe", "reload",
                "return", "ride", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
                "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon",
                "tag", "team", "teleport", "tellraw", "time", "title", "tm", "toggle", "tp", "trigger",
                "w", "weather", "whitelist", "worldborder", "xp", "version", "ver", "plugins", "pl", "plugin", "callback"
        };

        for (String vanilla : vanillaCommands) {
            if (command.equalsIgnoreCase(vanilla)) {
                return true;
            }
        }

        return false;
    }



    /**
     * Sends command info with hover and click events using Kyori Adventure API
     */
    private void sendCommandInfo(CommandSender sender, String cmdName) {
        // Sanitize cmdName (remove leading '/' if present)
        String cleanCmdName = cmdName.startsWith("/") ? cmdName.substring(1) : cmdName;

        String description = "No description available";
        String usage = "/" + cleanCmdName; // Default usage

        // 1. Try to get command from SFCore's plugin.yml first
        // 'plugin' is the instance of PluginStart for SFCore
        PluginCommand sfCoreCommand = plugin.getCommand(cleanCmdName);

        if (sfCoreCommand != null) {
            // Command found in SFCore's plugin.yml
            if (sfCoreCommand.getDescription() != null && !sfCoreCommand.getDescription().isEmpty()) {
                description = sfCoreCommand.getDescription();
            }
            if (sfCoreCommand.getUsage() != null && !sfCoreCommand.getUsage().isEmpty()) {
                // plugin.yml usage might be like "/<command> [player]"
                // We need to replace /<command> with the actual command alias
                usage = sfCoreCommand.getUsage().replace("<command>", cleanCmdName);
                if (!usage.startsWith("/")) {
                    usage = "/" + usage;
                }
            }
        } else {
            // 2. Fallback: Try Bukkit.getPluginCommand for other plugins or if not in SFCore's yml
            PluginCommand bukkitCmd = Bukkit.getPluginCommand(cleanCmdName);
            if (bukkitCmd != null) {
                if (bukkitCmd.getDescription() != null && !bukkitCmd.getDescription().isEmpty()) {
                    description = bukkitCmd.getDescription();
                }
                if (bukkitCmd.getUsage() != null && !bukkitCmd.getUsage().isEmpty()) {
                    usage = bukkitCmd.getUsage();
                    // Ensure usage starts with /
                    if (!usage.startsWith("/")) {
                        usage = "/" + usage;
                    }
                }
            }
            // If still not found, defaults remain "No description available" and "/" + cleanCmdName
        }
        
        // Ensure usage string always starts with a '/' if it's not empty and not null
        if (usage != null && !usage.isEmpty() && !usage.startsWith("/")) {
            usage = "/" + usage;
        } else if (usage == null || usage.isEmpty()) { // Final fallback if usage is still undetermined
            usage = "/" + cleanCmdName;
        }

        // Create the hover tooltip
        Component hoverTooltip = Component.text()
                .append(Text.colorize("&6Description: &f" + description + "\n"))
                .append(Text.colorize("&6Usage: &f" + usage + "\n")) // Display the processed usage
                .append(Text.colorize("&7Click to use this command"))
                .build();

        // Create the command component with hover and click events
        // The clickable part is " • /<actual_command_name>"
        // The suggested command should be "/<actual_command_name> "
        Component commandComponent = Component.text(" • /")
                .color(NamedTextColor.GOLD)
                .append(Component.text(cleanCmdName) // Use cleanCmdName for the displayed command
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(hoverTooltip))
                        .clickEvent(ClickEvent.suggestCommand("/" + cleanCmdName + " "))); // Suggest the base command

        // Send the component
        sender.sendMessage(commandComponent);
    }

    /**
     * Sends navigation buttons for pagination using Kyori Adventure API
     */
    private void sendNavigationButtons(CommandSender sender, int currentPage, int totalPages) {
        // Only show navigation if there's more than one page
        if (totalPages <= 1) return;

        // Create the navigation bar component
        TextComponent.Builder navigationBar = Component.text();

        // Previous page button
        if (currentPage > 1) {
            Component previousButton = Component.text("« Previous ")
                    .color(NamedTextColor.GOLD)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText
                            (Text.colorize("&7Click to go to page " + (currentPage - 1))))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/help " + (currentPage - 1)));
            navigationBar.append(previousButton);
        } else {
            navigationBar.append(Component.text("« Previous ")
                    .color(NamedTextColor.DARK_GRAY));
        }

        // Page indicator
        navigationBar.append(Component.text("Page ", NamedTextColor.GRAY)
                .append(Component.text(currentPage, NamedTextColor.WHITE))
                .append(Component.text("/", NamedTextColor.GRAY))
                .append(Component.text(totalPages, NamedTextColor.WHITE))
                .append(Component.text(" ", NamedTextColor.GRAY)));

        // Next page button
        if (currentPage < totalPages) {
            Component nextButton = Component.text("Next »")
                    .color(NamedTextColor.GOLD)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText
                            (Text.colorize("&7Click to go to page " + (currentPage + 1))))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/help " + (currentPage + 1)));
            navigationBar.append(nextButton);
        } else {
            navigationBar.append(Component.text("Next »")
                    .color(NamedTextColor.DARK_GRAY));
        }

        // Send the navigation bar
        sender.sendMessage(navigationBar.build());
    }

    /**
     * Display help for a specific plugin with pagination
     */
    private void displayPluginHelp(CommandSender sender, Plugin targetPlugin, int page) {
        // Header
        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));

        // Get plugin version for display
        String version = targetPlugin.getDescription().getVersion();
        String pluginName = targetPlugin.getName();

        // Create a list to store all commands from this plugin
        List<Command> pluginCommands = new ArrayList<>();

        // Method 1: Get commands directly from the server's command map
        Map<String, Command> knownCommands = new HashMap<>();
        try {
            CommandMap commandMap = Bukkit.getServer().getCommandMap();
            if (commandMap != null && commandMap instanceof SimpleCommandMap) {
                // Use reflection to access the knownCommands map
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
            }
        } catch (Exception e) {
            // If reflection fails, we'll continue with other methods
            sender.sendMessage(Text.colorize("&cDebug: Failed to access command map: " + e.getMessage()));
        }

        // Check for PluginCommands that belong to this plugin
        for (Command command : knownCommands.values()) {
            if (command instanceof PluginCommand) {
                PluginCommand pluginCommand = (PluginCommand) command;
                if (pluginCommand.getPlugin() == targetPlugin) {
                    if (!pluginCommands.contains(command)) {
                        pluginCommands.add(command);
                    }
                }
            }

            // Also check for commands with plugin name prefix
            String key = command.getName().toLowerCase();
            if (key.startsWith(pluginName.toLowerCase() + ":")) {
                if (!pluginCommands.contains(command)) {
                    pluginCommands.add(command);
                }
            }
        }

        // Method 2: Get commands from plugin.yml
        Map<String, Map<String, Object>> commandsFromYml = targetPlugin.getDescription().getCommands();
        if (commandsFromYml != null) {
            for (String cmdName : commandsFromYml.keySet()) {
                PluginCommand cmd = Bukkit.getPluginCommand(cmdName);
                if (cmd != null && !pluginCommands.contains(cmd)) {
                    pluginCommands.add(cmd);
                } else if (cmd == null) {
                    // Try to find in known commands
                    Command foundCmd = knownCommands.get(cmdName.toLowerCase());
                    if (foundCmd != null && !pluginCommands.contains(foundCmd)) {
                        pluginCommands.add(foundCmd);
                    }

                    // Also try with plugin prefix
                    String prefixed = pluginName.toLowerCase() + ":" + cmdName.toLowerCase();
                    Command prefixedCmd = knownCommands.get(prefixed);
                    if (prefixedCmd != null && !pluginCommands.contains(prefixedCmd)) {
                        pluginCommands.add(prefixedCmd);
                    }
                }
            }
        }

        // Filter out duplicates and commands the sender can't access
        List<String> availableCommands = new ArrayList<>();
        Set<String> addedCommands = new HashSet<>();

        for (Command cmd : pluginCommands) {
            // Skip if the command has a permission and the sender doesn't have it
            if (cmd.getPermission() != null && !sender.hasPermission(cmd.getPermission())) {
                continue;
            }

            // For players, check additional command visibility
            if (sender instanceof Player && commandManager != null) {
                if (!commandManager.shouldShowCommand((Player) sender, cmd.getName())) {
                    continue;
                }
            }

            // Get the base command name (without namespace)
            String baseName = cmd.getName();
            if (baseName.contains(":")) {
                baseName = baseName.split(":")[1];
            }

            // Only add if we haven't added this command yet (case-insensitive check)
            if (!addedCommands.contains(baseName.toLowerCase())) {
                availableCommands.add(baseName);
                addedCommands.add(baseName.toLowerCase());
            }
        }

        // Fallback: If no commands found, directly use plugin.yml
        if (availableCommands.isEmpty()) {
            Map<String, Map<String, Object>> yml = targetPlugin.getDescription().getCommands();
            if (yml != null && !yml.isEmpty()) {
                for (String cmdName : yml.keySet()) {
                    // Check permission if available in plugin.yml
                    Map<String, Object> cmdData = yml.get(cmdName);
                    if (cmdData.containsKey("permission")) {
                        String perm = cmdData.get("permission").toString();
                        if (!perm.isEmpty() && !sender.hasPermission(perm)) {
                            continue;
                        }
                    }

                    if (!addedCommands.contains(cmdName.toLowerCase())) {
                        availableCommands.add(cmdName);
                        addedCommands.add(cmdName.toLowerCase());
                    }
                }
            }
        }

        // Sort commands alphabetically
        Collections.sort(availableCommands);

        // Calculate total pages
        int totalPages = (int) Math.ceil((double) availableCommands.size() / COMMANDS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;
        if (totalPages == 0) totalPages = 1; // Prevent division by zero

        // Display plugin header
        if (sender.hasPermission("core.admin")) {
            sender.sendMessage(Text.colorize("&6&lPlugin Help &7- &e" + pluginName +
                    (version.isEmpty() ? "" : " v" + version) + " &7- &ePage " + page + "/" + totalPages));
        } else {
            sender.sendMessage(Text.colorize("&6&lPlugin Help &7- &e" + pluginName + " &7- &ePage " + page + "/" + totalPages));
        }
        sender.sendMessage("");

        // If no commands available, show message and return
        if (availableCommands.isEmpty()) {
            sender.sendMessage(Text.colorize("&cNo commands available for this plugin."));
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            return;
        }

        // Show command count
        sender.sendMessage(Text.colorize("&7Available commands (" + availableCommands.size() + "):"));
        sender.sendMessage("");

        // Display commands for the current page
        int startIndex = (page - 1) * COMMANDS_PER_PAGE;
        int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, availableCommands.size());

        for (int i = startIndex; i < endIndex; i++) {
            sendCommandInfo(sender, availableCommands.get(i));
        }

        // Footer with navigation
        sender.sendMessage("");

        // Only show navigation if there's more than one page
        if (totalPages > 1) {
            sendPluginNavigationButtons(sender, page, totalPages, pluginName);
        }

        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
    }




    /**
     * Sends navigation buttons for plugin-specific help pagination
     */
    private void sendPluginNavigationButtons(CommandSender sender, int currentPage, int totalPages, String pluginName) {
        // Create the navigation bar component
        TextComponent.Builder navigationBar = Component.text();

        // Previous page button
        if (currentPage > 1) {
            Component previousButton = Component.text("« Previous ")
                    .color(NamedTextColor.GOLD)
                    .hoverEvent(HoverEvent.showText(Text.colorize("&7Click to go to page " + (currentPage - 1))))
                    .clickEvent(ClickEvent.runCommand("/help " + pluginName + " " + (currentPage - 1)));
            navigationBar.append(previousButton);
        } else {
            navigationBar.append(Component.text("« Previous ")
                    .color(NamedTextColor.DARK_GRAY));
        }

        // Page indicator
        navigationBar.append(Component.text("Page ", NamedTextColor.GRAY)
                .append(Component.text(currentPage, NamedTextColor.WHITE))
                .append(Component.text("/", NamedTextColor.GRAY))
                .append(Component.text(totalPages, NamedTextColor.WHITE))
                .append(Component.text(" ", NamedTextColor.GRAY)));

        // Next page button
        if (currentPage < totalPages) {
            Component nextButton = Component.text("Next »")
                    .color(NamedTextColor.GOLD)
                    .hoverEvent(HoverEvent.showText(Text.colorize("&7Click to go to page " + (currentPage + 1))))
                    .clickEvent(ClickEvent.runCommand("/help " + pluginName + " " + (currentPage + 1)));
            navigationBar.append(nextButton);
        } else {
            navigationBar.append(Component.text("Next »")
                    .color(NamedTextColor.DARK_GRAY));
        }

        // Send the navigation bar
        sender.sendMessage(navigationBar.build());
    }

    /**
     * Send help information specific to a plugin or command
     */
    private void sendPluginSpecificHelp(CommandSender sender, String query, int page) {
        try {
            // Check if it's a plugin first
            Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(query);

            if (targetPlugin != null && targetPlugin.isEnabled()) {
                if (sender.hasPermission("core.admin")) {
                    // It's a valid plugin, show plugin-specific help for admins
                    displayPluginHelp(sender, targetPlugin, page);
                } else {
                    // Non-admin trying to see plugin help, show empty plugin help
                    // Header (only send it here, not in displayPluginHelp)
                    sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                    sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));
                    sender.sendMessage("");
                    sender.sendMessage(Text.colorize("&cNo commands available."));
                    sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                }
                return;
            }

            // If not a plugin, check if it's a command
            PluginCommand cmd = Bukkit.getPluginCommand(query);
            
            // Only show command info to admins
            if (cmd != null && sender.hasPermission("core.admin")) {
                // It's a valid command and user is admin, show command-specific help
                displayCommandHelp(sender, cmd);
                return;
            }


            // Display help title for unknown query
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));
            sender.sendMessage("");
            
            // Show error message
            sender.sendMessage(Text.colorize("&cUnknown command or plugin: &e" + query));
            
            // Find similar commands and plugins
            List<String> suggestions = new ArrayList<>();
            
            // 1. Check for similar commands
            String similarCommand = findSimilarCommand(sender, query);
            if (similarCommand != null) {
                suggestions.add(similarCommand);
            }
            
            // 2. Check for similar plugins (if sender is admin)
            if (sender.hasPermission("core.admin")) {
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.isEnabled() && !plugin.getName().equalsIgnoreCase(query) && 
                        calculateLevenshteinDistance(query.toLowerCase(), plugin.getName().toLowerCase()) <= 3) {
                        suggestions.add(plugin.getName());
                    }
                }
            }
            
            // Show suggestions if any
            if (!suggestions.isEmpty()) {
                sender.sendMessage(Text.colorize("&7Did you mean one of these?"));
                for (String suggestion : suggestions) {
                    sender.sendMessage(Text.colorize("&7- &e/" + suggestion));
                }
            }

            // Footer (send regardless of result)
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        } catch (Exception e) {
            // Log error and provide fallback message
            plugin.getLogger().severe("Error in sendPluginSpecificHelp: " + e.getMessage());
            e.printStackTrace();

            // Make sure we still send header and footer even if an error occurs
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for " + query));
            sender.sendMessage("");
            sender.sendMessage(Text.colorize("&cNo commands available."));
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
    }

    private String findSimilarCommand(CommandSender sender, String query) {
        List<String> allCommands = getAvailableCommands(sender);
        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String command : allCommands) {
            if (command == null) continue; // Skip null commands

            int distance = calculateLevenshteinDistance(query.toLowerCase(), command.toLowerCase());

            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = command;
            }
        }

        // Return the best match if it is reasonably close, otherwise null.
        return (bestDistance <= 3) ? bestMatch : null;
    }


    /**
     * Calculates the Levenshtein distance (edit distance) between two strings.
     */
    private int calculateLevenshteinDistance(String str1, String str2) {
        int[][] dp = new int[str1.length() + 1][str2.length() + 1];

        for (int i = 0; i <= str1.length(); i++) {
            for (int j = 0; j <= str2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(dp[i - 1][j - 1]
                                    + (str1.charAt(i - 1) == str2.charAt(j - 1) ? 0 : 1),
                            Math.min(dp[i - 1][j] + 1,
                                    dp[i][j - 1] + 1));
                }
            }
        }

        return dp[str1.length()][str2.length()];

    }


    /**
     * Display help for a specific command
     */
    private void displayCommandHelp(CommandSender sender, PluginCommand cmd) {
        // Header
        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        sender.sendMessage(Text.colorize("&6&lCommand Help &7- &e/" + cmd.getName()));
        sender.sendMessage("");

        // Command information
        if (sender.hasPermission("core.admin")) {
            sender.sendMessage(Text.colorize("&6Plugin: &f" + cmd.getPlugin().getName()));
        }
        sender.sendMessage(Text.colorize("&6Description: &f" + (cmd.getDescription() != null ? cmd.getDescription() : "No description available")));
        sender.sendMessage(Text.colorize("&6Usage: &f" + (cmd.getUsage() != null ? cmd.getUsage() : "/" + cmd.getName())));

        // Aliases
        List<String> aliases = cmd.getAliases();
        if (aliases != null && !aliases.isEmpty() && sender.hasPermission("core.admin")) {
            sender.sendMessage(Text.colorize("&6Aliases: &f/" + String.join(", /", aliases)));
        }

        // Permission
        if (cmd.getPermission() != null) {
            sender.sendMessage(Text.colorize("&6Permission: &f" + cmd.getPermission()));
        }

        // Footer
        sender.sendMessage("");
        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
    }


    public void register() {
        // Register the main help command
        PluginCommand helpCommand = plugin.getCommand("help");
        if (helpCommand != null) {
            helpCommand.setExecutor(this);
            helpCommand.setTabCompleter(this);
            
            // Make sure to override the vanilla help command
            try {
                // Get the server's command map
                CommandMap commandMap = (CommandMap) Bukkit.getServer().getClass()
                        .getDeclaredMethod("getCommandMap").invoke(Bukkit.getServer());
                
                // Register our help command with higher priority
                commandMap.register("sfcore", helpCommand);
                
                // Log success
                plugin.getLogger().info("Successfully registered custom help command");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to override vanilla help command: " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("Could not find help command in plugin.yml");
        }

        // Register this class as an event listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

}

