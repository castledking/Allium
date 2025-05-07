package net.survivalfun.core.commands.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class Help implements CommandExecutor, Listener, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;
    private final int COMMANDS_PER_PAGE = 8;

    public Help(PluginStart plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First argument: offer page numbers and plugin names
            completions.add("1"); // Suggest first page

            // Add plugin names if sender has admin permission
            if (sender.hasPermission("core.admin")) {
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.isEnabled()) {
                        completions.add(plugin.getName());
                    }
                }
            }

            // Add command names
            if (sender instanceof Player) {
                List<String> availableCommands = getAvailableCommands((Player) sender);
                completions.addAll(availableCommands);
            }
        } else if (args.length == 2) {
            // Second argument: offer page numbers if first arg is a plugin name
            completions.add("1");
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
        if (!(sender instanceof Player)) {
            if (args.length > 0) {
                showMainHelpPage(sender, 1);
                return true;
            }
            return true;
        }

        Player player = (Player) sender;

        // If no args provided, show main help page
        if (args.length == 0) {
            showMainHelpPage(player, 1);
            return true;
        }


        return handleHelpArg(args, player);
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
            // First argument is not a number, it's likely a plugin name
            String pluginName = args[0];

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

            sendPluginSpecificHelp(player, pluginName, page);
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
        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
        sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &ePage " + page + "/" + totalPages));
        sender.sendMessage(Text.colorize("&7Commands you have access to:"));
        sender.sendMessage(Component.empty());

        // No entries available
        if (helpEntries.isEmpty()) {
            sender.sendMessage(Text.colorize("&cNo commands available."));
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
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
        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
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

            // Check if player has permission to use this command
            if ((command.getPermission() == null || sender.hasPermission(command.getPermission()))) {
                result.add(cmdName);
                processedCommands.add(cmdName);

                // Process aliases
                for (String alias : command.getAliases()) {
                    if (alias != null && !processedCommands.contains(alias) &&
                            !isVanillaCommand(alias)) {  // Also check aliases against vanilla commands
                        result.add(alias);
                        processedCommands.add(alias);
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
                "give", "help", "item", "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg",
                "op", "pardon", "pardon-ip", "particle", "place", "playsound", "recipe", "reload",
                "return", "ride", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
                "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon",
                "tag", "team", "teleport", "tellraw", "time", "title", "tm", "toggle", "tp", "trigger",
                "w", "weather", "whitelist", "worldborder", "xp", "version", "ver", "plugins", "pl", "plugin"
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
        PluginCommand cmd = Bukkit.getPluginCommand(cmdName);
        String description = (cmd != null && cmd.getDescription() != null) ? cmd.getDescription() : "No description available";
        String usage = (cmd != null && cmd.getUsage() != null) ? cmd.getUsage() : "/" + cmdName;

        // Create the hover tooltip
        Component hoverTooltip = Component.text()
                .append(Text.colorize("&6Description: &f" + description + "\n"))
                .append(Text.colorize("&6Usage: &f" + usage + "\n"))
                .append(Text.colorize("&7Click to use this command"))
                .build();

        // Create the command component with hover and click events
        Component commandComponent = Component.text(" • /")
                .color(NamedTextColor.GOLD)
                .append(Component.text(cmdName)
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(hoverTooltip))
                        .clickEvent(ClickEvent.suggestCommand("/" + cmdName + " ")));

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
        // Header - only send it here, not in the calling method
        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));

        // Get commands specific to this plugin
        Map<String, Map<String, Object>> commands = targetPlugin.getDescription().getCommands();
        if (commands == null || commands.isEmpty()) {
            // Show plugin version only to admins
            if (sender.hasPermission("core.admin")) {
                sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &e" + targetPlugin.getName() + " v"
                        + targetPlugin.getDescription().getVersion()));
            } else {
                sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &e" + targetPlugin.getName()));
            }

            sender.sendMessage("");
            sender.sendMessage(Text.colorize("&cNo commands available for this plugin."));
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
            return;
        }

        // Get available commands from this plugin
        List<String> availableCommands = new ArrayList<>();
        for (String cmdName : commands.keySet()) {
            // Check if player has access to this command
            String cmdPerm = Bukkit.getPluginCommand(cmdName).getPermission();
            if (sender.hasPermission(cmdPerm != null ? cmdPerm : "core.admin")) {
                availableCommands.add(cmdName);
            }
        }

        // Sort alphabetically
        Collections.sort(availableCommands);

        // Calculate total pages
        int totalPages = (int) Math.ceil((double) availableCommands.size() / COMMANDS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        if (totalPages == 0) totalPages = 1; // Prevent division by zero

        // Show plugin version only to admins
        if (sender.hasPermission("core.admin")) {
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &e" + targetPlugin.getName() + " v"
                    + targetPlugin.getDescription().getVersion() + " &7- &ePage " + page + "/" + totalPages));
        } else {
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &e" + targetPlugin.getName() + " &7- &ePage " + page + "/" + totalPages));
        }

        sender.sendMessage("");

        // Display commands for the current page
        if (availableCommands.isEmpty()) {
            sender.sendMessage(Text.colorize("&cYou don't have access to any commands from this plugin."));
        } else {
            sender.sendMessage(Text.colorize("&7Commands you have access to:"));
            sender.sendMessage("");

            int startIndex = (page - 1) * COMMANDS_PER_PAGE;
            int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, availableCommands.size());

            for (int i = startIndex; i < endIndex; i++) {
                sendCommandInfo(sender, availableCommands.get(i));
            }
        }

        // Footer with navigation
        sender.sendMessage("");

        // Only show navigation if there's more than one page
        if (totalPages > 1) {
            sendPluginNavigationButtons(sender, page, totalPages, targetPlugin.getName());
        }

        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
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
                    sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
                    sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));
                    sender.sendMessage("");
                    sender.sendMessage(Text.colorize("&cNo commands available."));
                    sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
                }
                return;
            }

            // If not a plugin, check if it's a command
            PluginCommand cmd = Bukkit.getPluginCommand(query);
            String cmdPerm = Bukkit.getPluginCommand(query).getPermission();

            if (cmd != null && sender.hasPermission(cmdPerm != null ? cmdPerm : "core.admin")) {
                // It's a valid command that the player has permission for, show command-specific help
                displayCommandHelp(sender, cmd);
                return;
            }


            // Display help title for unknown query
            // Header (send it here for unknown queries)
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));

            // Message with suggestion
            sender.sendMessage("");
            
            String similarCommand = findSimilarCommand(sender, query);

            if (similarCommand != null) {
                // Found a similar command
                sender.sendMessage(Text.colorize("&7Did you mean:"));
                sendCommandInfo(sender, similarCommand);
            }

            // Create clickable suggestion component
            Component helpSuggestion = Component.text("Type ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text("/help")
                            .color(NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.suggestCommand("/help "))
                            .hoverEvent(HoverEvent.showText(Text.colorize("&7Click to run this command"))))
                    .append(Component.text(" to see available commands.")
                            .color(NamedTextColor.GRAY));

            sender.sendMessage("");
            sender.sendMessage(helpSuggestion);

            // Footer (send regardless of result)
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
        } catch (Exception e) {
            // Log error and provide fallback message
            plugin.getLogger().severe("Error in sendPluginSpecificHelp: " + e.getMessage());
            e.printStackTrace();

            // Make sure we still send header and footer even if an error occurs
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for " + query));
            sender.sendMessage("");
            sender.sendMessage(Text.colorize("&cNo commands available."));
            sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
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
        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
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
        sender.sendMessage(Text.colorize("&8&m----------------------------------------"));
    }


    public void register() {
        // Register the main help command
        PluginCommand helpCommand = plugin.getCommand("help");
        if (helpCommand != null) {
            helpCommand.setExecutor(this);
            helpCommand.setTabCompleter(this);

        }

        // Register this class as an event listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

    }

}

