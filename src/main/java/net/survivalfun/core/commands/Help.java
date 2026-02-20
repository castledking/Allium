package net.survivalfun.core.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.listeners.security.CommandManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class Help implements CommandExecutor, Listener, TabCompleter {

    private final PluginStart plugin;
    private final CommandManager commandManager;
    private final int COMMANDS_PER_PAGE = 8;
    private final Lang lang;

    public Help(PluginStart plugin) {
        this.plugin = plugin;
        this.commandManager = plugin.getCommandManager();
        this.lang = plugin.getLangManager();
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
            if (sender.hasPermission("allium.admin")) {
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
        // Base permission check
        if (sender instanceof Player) {
            if (!sender.hasPermission("allium.help")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "help");
                return true;
            }
        }
        
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


    private void showMainHelpPage(CommandSender sender, int page) {
        // Combined list for both plugins and commands
        List<HelpEntry> helpEntries = new ArrayList<>();

        // If player has admin permission, add plugins to the list
        if (sender.hasPermission("allium.admin")) {
            List<String> pluginNames = new ArrayList<>();
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (plugin.isEnabled()) {
                    pluginNames.add(plugin.getName());
                }
            }
            Collections.sort(pluginNames);
            for (String pluginName : pluginNames) {
                helpEntries.add(new HelpEntry(pluginName, true));
            }
        }

        // Get all available commands for this sender
        List<String> availableCommands = getAvailableCommands(sender);
        Collections.sort(availableCommands);
        for (String cmdName : availableCommands) {
            helpEntries.add(new HelpEntry(cmdName, false));
        }

        // Calculate total pages
        int totalPages = (int) Math.ceil((double) helpEntries.size() / COMMANDS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        if (totalPages == 0) totalPages = 1;

        // Console vs Player output
        if (sender instanceof ConsoleCommandSender) {
            // Console output with legacy color codes
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
            sender.sendMessage(ChatColor.GOLD + "" +ChatColor.BOLD + "Help Menu " + ChatColor.GRAY + "- " + ChatColor.YELLOW + "Page " + page + "/" + totalPages);
            sender.sendMessage(ChatColor.GRAY + "Commands you have access to:");
            sender.sendMessage("");

            if (helpEntries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No commands available.");
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                return;
            }

            int startIndex = (page - 1) * COMMANDS_PER_PAGE;
            int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, helpEntries.size());

            for (int i = startIndex; i < endIndex; i++) {
                HelpEntry entry = helpEntries.get(i);
                if (entry.isPlugin) {
                    sender.sendMessage(ChatColor.RED + " • [Plugin] " + ChatColor.YELLOW + entry.name);
                } else {
                    sender.sendMessage(ChatColor.GOLD + " • /" + entry.name);
                }
            }

            sender.sendMessage("");
            sendNavigationButtons(sender, page, totalPages);
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
        } else {
            // Player output with Adventure API
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &ePage " + page + "/" + totalPages));
            sender.sendMessage(Text.colorize("&7Commands you have access to:"));
            sender.sendMessage(Component.empty());

            if (helpEntries.isEmpty()) {
                sender.sendMessage(Text.colorize("&cNo commands available."));
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                return;
            }

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

            sender.sendMessage(Component.empty());
            sendNavigationButtons(sender, page, totalPages);
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
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
     * Sends plugin info with hover and click events for players, plain text for console
     */
    private void sendPluginInfo(CommandSender sender, String pluginName) {
        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            sender.sendMessage(ChatColor.RED + " • [Plugin] " + ChatColor.YELLOW + pluginName);
        } else {
            // Player: Adventure API with hover and click events
            Component hoverTooltip = Component.text()
                    .append(Component.text("Plugin: ", NamedTextColor.YELLOW)
                            .append(Component.text(pluginName, NamedTextColor.WHITE))
                            .append(Component.newline()))
                    .append(Component.text("Click to view commands for this plugin", NamedTextColor.GRAY))
                    .build();

            Component pluginComponent = Component.text(" • [Plugin] ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(pluginName)
                            .color(NamedTextColor.YELLOW)
                            .hoverEvent(HoverEvent.showText(hoverTooltip))
                            .clickEvent(ClickEvent.runCommand("/help " + pluginName)));

            sender.sendMessage(pluginComponent);
        }
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
            Text.sendDebugLog(WARN, "Failed to access server commands: " + e.getMessage());
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
            if (player != null && commandManager != null) {
                canShow = commandManager.shouldShowCommand(player, cmdName);
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
                        if (player != null && commandManager != null) {
                            aliasCanShow = commandManager.shouldShowCommand(player, alias);
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
     * Check if a command is a vanilla Minecraft command
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
     * Sends command info with hover and click events for players, plain text for console
     */
    private void sendCommandInfo(CommandSender sender, String cmdName) {
        String cleanCmdName = cmdName.startsWith("/") ? cmdName.substring(1) : cmdName;
        String description = "No description available";
        String usage = "/" + cleanCmdName;

        String displayPrefix = "/";
        String suggestPrefix = "/";

        PluginCommand coreCommand = plugin.getCommand(cleanCmdName);
        if (coreCommand != null) {
            if (coreCommand.getDescription() != null && !coreCommand.getDescription().isEmpty()) {
                description = coreCommand.getDescription();
            }
            if (coreCommand.getUsage() != null && !coreCommand.getUsage().isEmpty()) {
                usage = coreCommand.getUsage().replace("<command>", cleanCmdName);
                if (!usage.startsWith("/")) {
                    usage = "/" + usage;
                }
            }
        } else {
            PluginCommand bukkitCmd = Bukkit.getPluginCommand(cleanCmdName);
            if (bukkitCmd != null) {
                if (bukkitCmd.getDescription() != null && !bukkitCmd.getDescription().isEmpty()) {
                    description = bukkitCmd.getDescription();
                }
                if (bukkitCmd.getUsage() != null && !bukkitCmd.getUsage().isEmpty()) {
                    usage = bukkitCmd.getUsage();
                    if (!usage.startsWith("/")) {
                        usage = "/" + usage;
                    }
                }
            }
        }
        if (usage != null && !usage.isEmpty() && !usage.startsWith("/")) {
            usage = "/" + usage;
        } else if (usage == null || usage.isEmpty()) {
            usage = "/" + cleanCmdName;
        }

        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            sender.sendMessage(ChatColor.GOLD + " • " + displayPrefix + cleanCmdName);
        } else {
            // Player: Adventure API with hover and click events
            Component descriptionComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(description);
            Component usageComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(usage);

            Component hoverTooltip = Component.text()
                    .append(Component.text("Description: ", NamedTextColor.GOLD)
                            .append(descriptionComponent)
                            .append(Component.newline()))
                    .append(Component.text("Usage: ", NamedTextColor.GOLD)
                            .append(usageComponent)
                            .append(Component.newline()))
                    .append(Component.text("Click to use this command", NamedTextColor.GRAY))
                    .build();

            Component commandComponent = Component.text(" • ")
                    .color(NamedTextColor.GOLD)
                    .append(Component.text(displayPrefix)
                            .color(NamedTextColor.GOLD)
                            .append(Component.text(cleanCmdName)
                                    .color(NamedTextColor.GOLD)
                                    .hoverEvent(HoverEvent.showText(hoverTooltip))
                                    .clickEvent(ClickEvent.suggestCommand(suggestPrefix + cleanCmdName + " "))));

            sender.sendMessage(commandComponent);
        }
    }

    /**
     * Sends navigation buttons for pagination
     */
    private void sendNavigationButtons(CommandSender sender, int currentPage, int totalPages) {
        if (totalPages <= 1) return;

        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            StringBuilder navigationBar = new StringBuilder();
            navigationBar.append(
                ChatColor.DARK_GRAY + "{" + 
                ChatColor.GRAY + "Page " + 
                ChatColor.WHITE + currentPage + 
                ChatColor.GRAY + "/" + 
                ChatColor.WHITE + totalPages + 
                ChatColor.DARK_GRAY + "]" + 
                ChatColor.RESET + "Do " + 
                ChatColor.YELLOW + "/help " + 
                ChatColor.GRAY + "<n> " + 
                ChatColor.RESET + "to navigate.");
            sender.sendMessage(navigationBar.toString());
        } else {
            // Player: Adventure API with hover and click events
            TextComponent.Builder navigationBar = Component.text();
            if (currentPage > 1) {
                Component previousButton = Component.text("« Previous ")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage - 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/help " + (currentPage - 1)));
                navigationBar.append(previousButton);
            } else {
                navigationBar.append(Component.text("« Previous ").color(NamedTextColor.DARK_GRAY));
            }
            navigationBar.append(Component.text("Page ", NamedTextColor.GRAY)
                    .append(Component.text(currentPage, NamedTextColor.WHITE))
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(totalPages, NamedTextColor.WHITE))
                    .append(Component.text(" ", NamedTextColor.GRAY)));
            if (currentPage < totalPages) {
                Component nextButton = Component.text("Next »")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage + 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/help " + (currentPage + 1)));
                navigationBar.append(nextButton);
            } else {
                navigationBar.append(Component.text("Next »").color(NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(navigationBar.build());
        }
    }

    /**
     * Display help for a specific plugin with pagination
     */
    private void displayPluginHelp(CommandSender sender, Plugin targetPlugin, int page) {
        String version = targetPlugin.getDescription().getVersion();
        String pluginName = targetPlugin.getName();
        List<Command> pluginCommands = new ArrayList<>();
        Map<String, Command> knownCommands = new HashMap<>();
        try {
            CommandMap commandMap = Bukkit.getServer().getCommandMap();
            if (commandMap != null && commandMap instanceof SimpleCommandMap) {
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Debug: Failed to access command map: " + e.getMessage());
        }

        for (Command command : knownCommands.values()) {
            if (command instanceof PluginCommand) {
                PluginCommand pluginCommand = (PluginCommand) command;
                if (pluginCommand.getPlugin() == targetPlugin) {
                    if (!pluginCommands.contains(command)) {
                        pluginCommands.add(command);
                    }
                }
            }
            String key = command.getName().toLowerCase();
            if (key.startsWith(pluginName.toLowerCase() + ":")) {
                if (!pluginCommands.contains(command)) {
                    pluginCommands.add(command);
                }
            }
        }

        Map<String, Map<String, Object>> commandsFromYml = targetPlugin.getDescription().getCommands();
        if (commandsFromYml != null) {
            for (String cmdName : commandsFromYml.keySet()) {
                PluginCommand cmd = Bukkit.getPluginCommand(cmdName);
                if (cmd != null && !pluginCommands.contains(cmd)) {
                    pluginCommands.add(cmd);
                } else if (cmd == null) {
                    Command foundCmd = knownCommands.get(cmdName.toLowerCase());
                    if (foundCmd != null && !pluginCommands.contains(foundCmd)) {
                        pluginCommands.add(foundCmd);
                    }
                    String prefixed = pluginName.toLowerCase() + ":" + cmdName.toLowerCase();
                    Command prefixedCmd = knownCommands.get(prefixed);
                    if (prefixedCmd != null && !pluginCommands.contains(prefixedCmd)) {
                        pluginCommands.add(prefixedCmd);
                    }
                }
            }
        }

        List<String> availableCommands = new ArrayList<>();
        Set<String> addedCommands = new HashSet<>();
        
        // First, check registered commands
        for (Command cmd : pluginCommands) {
            if (cmd.getPermission() != null && !sender.hasPermission(cmd.getPermission())) {
                continue;
            }
            if (sender instanceof Player && commandManager != null) {
                if (!commandManager.shouldShowCommand((Player) sender, cmd.getName())) {
                    continue;
                }
            }
            String baseName = cmd.getName();
            if (baseName.contains(":")) {
                baseName = baseName.split(":")[1];
            }
            if (!addedCommands.contains(baseName.toLowerCase())) {
                availableCommands.add(baseName);
                addedCommands.add(baseName.toLowerCase());
            }
        }

        // Also check plugin.yml for commands that might not be registered through Bukkit
        Map<String, Map<String, Object>> yml = targetPlugin.getDescription().getCommands();
        if (yml != null && !yml.isEmpty()) {
            for (String cmdName : yml.keySet()) {
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

        Collections.sort(availableCommands);
        int totalPages = (int) Math.ceil((double) availableCommands.size() / COMMANDS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;
        if (totalPages == 0) totalPages = 1;

        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
            if (sender.hasPermission("allium.admin")) {
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Plugin Help " + ChatColor.GRAY + "- " +
                        ChatColor.YELLOW + pluginName + (version.isEmpty() ? "" : " v" + version) + ChatColor.GRAY + " - " +
                        ChatColor.YELLOW + "Page " + page + "/" + totalPages);
            } else {
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Plugin Help " + ChatColor.GRAY + "- " +
                        ChatColor.YELLOW + pluginName + ChatColor.GRAY + " - " + ChatColor.YELLOW + "Page " + page + "/" + totalPages);
            }
            sender.sendMessage("");
            if (availableCommands.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No commands available for this plugin.");
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                return;
            }
            sender.sendMessage(ChatColor.GRAY + "Available commands (" + availableCommands.size() + "):");
            sender.sendMessage("");

            int startIndex = (page - 1) * COMMANDS_PER_PAGE;
            int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, availableCommands.size());
            for (int i = startIndex; i < endIndex; i++) {
                sendCommandInfo(sender, availableCommands.get(i));
            }
            sender.sendMessage("");
            if (totalPages > 1) {
                sendPluginNavigationButtons(sender, page, totalPages, pluginName);
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
        } else {
            // Player: Adventure API with Text.colorize for headers
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            if (sender.hasPermission("allium.admin")) {
                sender.sendMessage(Text.colorize("&6&lPlugin Help &7- &e" + pluginName +
                        (version.isEmpty() ? "" : " v" + version) + " &7- &ePage " + page + "/" + totalPages));
            } else {
                sender.sendMessage(Text.colorize("&6&lPlugin Help &7- &e" + pluginName + " &7- &ePage " + page + "/" + totalPages));
            }
            sender.sendMessage("");
            if (availableCommands.isEmpty()) {
                sender.sendMessage(Text.colorize("&cNo commands available for this plugin."));
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                return;
            }
            sender.sendMessage(Text.colorize("&7Available commands (" + availableCommands.size() + "):"));
            sender.sendMessage("");
            int startIndex = (page - 1) * COMMANDS_PER_PAGE;
            int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, availableCommands.size());
            for (int i = startIndex; i < endIndex; i++) {
                sendCommandInfo(sender, availableCommands.get(i));
            }
            sender.sendMessage("");
            if (totalPages > 1) {
                sendPluginNavigationButtons(sender, page, totalPages, pluginName);
            }
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
    }




    /**
     * Sends navigation buttons for plugin-specific help pagination
     */
    private void sendPluginNavigationButtons(CommandSender sender, int currentPage, int totalPages, String pluginName) {
        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            StringBuilder navigationBar = new StringBuilder();
            if (currentPage > 1) {
                navigationBar.append(ChatColor.GOLD + "« Previous ");
            } else {
                navigationBar.append(ChatColor.DARK_GRAY + "« Previous ");
            }
            navigationBar.append(ChatColor.GRAY + "Page " + ChatColor.WHITE + currentPage + ChatColor.GRAY + "/" + ChatColor.WHITE + totalPages + ChatColor.GRAY + " ");
            if (currentPage < totalPages) {
                navigationBar.append(ChatColor.GOLD + "Next »");
            } else {
                navigationBar.append(ChatColor.DARK_GRAY + "Next »");
            }
            sender.sendMessage(navigationBar.toString());
        } else {
            // Player: Adventure API with hover and click events
            TextComponent.Builder navigationBar = Component.text();
            if (currentPage > 1) {
                Component previousButton = Component.text("« Previous ")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage - 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/help " + pluginName + " " + (currentPage - 1)));
                navigationBar.append(previousButton);
            } else {
                navigationBar.append(Component.text("« Previous ").color(NamedTextColor.DARK_GRAY));
            }
            navigationBar.append(Component.text("Page ", NamedTextColor.GRAY)
                    .append(Component.text(currentPage, NamedTextColor.WHITE))
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(totalPages, NamedTextColor.WHITE))
                    .append(Component.text(" ", NamedTextColor.GRAY)));
            if (currentPage < totalPages) {
                Component nextButton = Component.text("Next »")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage + 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/help " + pluginName + " " + (currentPage + 1)));
                navigationBar.append(nextButton);
            } else {
                navigationBar.append(Component.text("Next »").color(NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(navigationBar.build());
        }
    }

    /**
     * Send help information specific to a plugin or command
     */
    private void sendPluginSpecificHelp(CommandSender sender, String query, int page) {
        try {
            Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(query);
            if (targetPlugin != null && targetPlugin.isEnabled()) {
                if (sender.hasPermission("allium.admin")) {
                    displayPluginHelp(sender, targetPlugin, page);
                } else {
                    if (sender instanceof ConsoleCommandSender) {
                        sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Help Menu " + ChatColor.GRAY + "- " + ChatColor.YELLOW + "Help for " + query);
                        sender.sendMessage("");
                        sender.sendMessage(ChatColor.RED + "No commands available.");
                        sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                    } else {
                        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                        sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));
                        sender.sendMessage("");
                        sender.sendMessage(Text.colorize("&cNo commands available."));
                        sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                    }
                }
                return;
            }

            PluginCommand cmd = Bukkit.getPluginCommand(query);
            if (cmd != null && sender.hasPermission("allium.admin")) {
                displayCommandHelp(sender, cmd);
                return;
            }

            if (sender instanceof ConsoleCommandSender) {
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Help Menu " + ChatColor.GRAY + "- " + ChatColor.YELLOW + "Help for " + query);
                sender.sendMessage("");
                sender.sendMessage(ChatColor.RED + "Unknown command or plugin: " + ChatColor.YELLOW + query);
                List<String> suggestions = new ArrayList<>();
                String similarCommand = findSimilarCommand(sender, query);
                if (similarCommand != null) {
                    suggestions.add(similarCommand);
                }
                if (sender.hasPermission("allium.admin")) {
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        if (plugin.isEnabled() && !plugin.getName().equalsIgnoreCase(query) &&
                                calculateLevenshteinDistance(query.toLowerCase(), plugin.getName().toLowerCase()) <= 3) {
                            suggestions.add(plugin.getName());
                        }
                    }
                }
                if (!suggestions.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Did you mean one of these?");
                    for (String suggestion : suggestions) {
                        sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.YELLOW + "/" + suggestion);
                    }
                }
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
            } else {
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for &6" + query));
                sender.sendMessage("");
                sender.sendMessage(Text.colorize("&cUnknown command or plugin: &e" + query));
                List<String> suggestions = new ArrayList<>();
                String similarCommand = findSimilarCommand(sender, query);
                if (similarCommand != null) {
                    suggestions.add(similarCommand);
                }
                if (sender.hasPermission("allium.admin")) {
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        if (plugin.isEnabled() && !plugin.getName().equalsIgnoreCase(query) &&
                                calculateLevenshteinDistance(query.toLowerCase(), plugin.getName().toLowerCase()) <= 3) {
                            suggestions.add(plugin.getName());
                        }
                    }
                }
                if (!suggestions.isEmpty()) {
                    sender.sendMessage(Text.colorize("&7Did you mean one of these?"));
                    for (String suggestion : suggestions) {
                        sender.sendMessage(Text.colorize("&7- &e/" + suggestion));
                    }
                }
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error in sendPluginSpecificHelp: " + e.getMessage());
            e.printStackTrace();
            if (sender instanceof ConsoleCommandSender) {
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Help Menu " + ChatColor.GRAY + "- " + ChatColor.YELLOW + "Help for " + query);
                sender.sendMessage("");
                sender.sendMessage(ChatColor.RED + "No commands available.");
                sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
            } else {
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
                sender.sendMessage(Text.colorize("&6&lHelp Menu &7- &eHelp for " + query));
                sender.sendMessage("");
                sender.sendMessage(Text.colorize("&cNo commands available."));
                sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            }
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
        if (sender instanceof ConsoleCommandSender) {
            // Console: plain text with legacy colors
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Command Help " + ChatColor.GRAY + "- " + ChatColor.YELLOW + "/" + cmd.getName());
            sender.sendMessage("");
            if (sender.hasPermission("allium.admin")) {
                sender.sendMessage(ChatColor.GOLD + "Plugin: " + ChatColor.WHITE + cmd.getPlugin().getName());
            }
            sender.sendMessage(ChatColor.GOLD + "Description: " + ChatColor.WHITE + 
                    (cmd.getDescription() != null ? cmd.getDescription() : "No description available"));
            sender.sendMessage(ChatColor.GOLD + "Usage: " + ChatColor.WHITE + 
                    (cmd.getUsage() != null ? cmd.getUsage() : "/" + cmd.getName()));
            List<String> aliases = cmd.getAliases();
            if (aliases != null && !aliases.isEmpty() && sender.hasPermission("allium.admin")) {
                sender.sendMessage(ChatColor.GOLD + "Aliases: " + ChatColor.WHITE + "/" + String.join(", /", aliases));
            }
            if (cmd.getPermission() != null) {
                sender.sendMessage(ChatColor.GOLD + "Permission: " + ChatColor.WHITE + cmd.getPermission());
            }
            sender.sendMessage("");
            sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "----------------------------------------------------");
        } else {
            // Player: Adventure API with Text.colorize
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
            sender.sendMessage(Text.colorize("&6&lCommand Help &7- &e/" + cmd.getName()));
            sender.sendMessage("");
            if (sender.hasPermission("allium.admin")) {
                sender.sendMessage(Text.colorize("&6Plugin: &f" + cmd.getPlugin().getName()));
            }
            sender.sendMessage(Text.colorize("&6Description: &f" + (cmd.getDescription() != null ? cmd.getDescription() : "No description available")));
            sender.sendMessage(Text.colorize("&6Usage: &f" + (cmd.getUsage() != null ? cmd.getUsage() : "/" + cmd.getName())));
            List<String> aliases = cmd.getAliases();
            if (aliases != null && !aliases.isEmpty() && sender.hasPermission("allium.admin")) {
                sender.sendMessage(Text.colorize("&6Aliases: &f/" + String.join(", /", aliases)));
            }
            if (cmd.getPermission() != null) {
                sender.sendMessage(Text.colorize("&6Permission: &f" + cmd.getPermission()));
            }
            sender.sendMessage("");
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
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
                commandMap.register("allium", helpCommand);
                
                // Log success
                Text.sendDebugLog(INFO, "Successfully registered custom help command");
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to override vanilla help command: " + e.getMessage());
            }
        } else {
            Text.sendDebugLog(WARN, "Could not find help command in plugin.yml");
        }

        // Register this class as an event listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

}
