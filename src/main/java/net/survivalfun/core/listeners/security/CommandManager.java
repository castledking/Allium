package net.survivalfun.core.listeners.security;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.CommandBlockerConfig;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;

public class CommandManager implements Listener {

    private final PluginStart plugin;
    private final Lang lang;

    // Cache of command names for quick lookups
    private List<String> availableCommands;
    private Map<String, String> commandPermissions;
    private ProtocolManager protocolManager;
    private Map<String, String> commandAliases;


    public CommandManager(PluginStart plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.availableCommands = new ArrayList<>();
        this.commandPermissions = new HashMap<>();
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.commandAliases = new HashMap<>();

        // Initial load of available commands
        refreshCommandList();
        discoverCommandPermissions();
        discoverCommandAliases();

        // Register the listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, this::discoverCommandPermissions, 40L);

    }

    /**
     * Refreshes the list of available commands from the server
     */
    private void refreshCommandList() {
        availableCommands.clear();

        // Get all available commands
        Map<String, Command> knownCommands = new TreeMap<>();
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
        }

        // Add all command names to our list
        for (String cmdName : knownCommands.keySet()) {
            // Filter out namespace commands (plugin:command)
            if (!cmdName.contains(":")) {
                availableCommands.add(cmdName);
                Command cmd = knownCommands.get(cmdName);
                if (cmd != null && cmd.getPermission() != null && !cmd.getPermission().isEmpty()) {
                    commandPermissions.put(cmdName, cmd.getPermission());
                }
            }
        }
    }


    /**
     * Finds a similar command to the attempted one, only suggesting commands
     * the player has permission to use.
     *
     * @param attempted The attempted command
     * @param player The player to check permissions for
     * @return A similar command the player has permission to use, or null if none found
     */
    private String findSimilarCommand(String attempted, Player player) {
        String bestMatch = null;
        int lowestDistance = Integer.MAX_VALUE;

        for (String command : availableCommands) {
            // Skip vanilla commands
            if (isVanillaCommand(command) && !player.hasPermission("core.*")) {
                continue;
            }

            // Skip commands the player doesn't have permission to use
            if (!playerHasPermission(player, command)) {
                continue;
            }

            int distance = levenshteinDistance(attempted, command);
            if (distance < lowestDistance) {
                lowestDistance = distance;
                bestMatch = command;
            }
        }

        // Only suggest if it's a reasonably close match (adjust threshold as needed)
        return lowestDistance <= 3 ? bestMatch : null;
    }


    /**
     * Checks if a player has permission to use a command.
     *
     * @param player The player to check
     * @param command The command to check permission for
     * @return True if the player has permission, false otherwise
     */
    private boolean playerHasPermission(Player player, String command) {
        // If player is null, return false
        if (player == null) {
            return false;
        }

        // Get permission for this command
        String permission = getCommandPermission(command);

        // If no specific permission is required, return true
        if (permission == null || permission.isEmpty()) {
            return true;
        }

        // Check if player has permission
        return player.hasPermission(permission);
    }

    /**
     * Gets the permission required for a command.
     *
     * @param command The command to get permission for
     * @return The permission string, or null if no permission is required
     */
    private String getCommandPermission(String command) {
        String baseCommand = command.split(" ")[0]; // Get just the base command without arguments
        return commandPermissions.get(baseCommand.toLowerCase());
    }



    /**
     * Calculate the Levenshtein distance between two strings
     * This measures how many single-character edits are needed to change one string into another
     */
    private int levenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            costs[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;

            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }

        return costs[b.length()];
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        String message = event.getMessage().trim();

        // Check if this is a command (starts with /)
        if (!message.startsWith("/")) {
            return;
        }

        // Extract the command without the slash
        String fullCommand = message.substring(1);
        String[] parts = fullCommand.split(" ", 2);
        String command = parts[0].toLowerCase();

        // Check if this is an alias and get the primary command
        String primaryCommand = getPrimaryCommand(command);

        // Check if the command is registered (either directly or through its primary command)
        if (isCommandRegistered(command) || isCommandRegistered(primaryCommand)) {
            if (lackPermissionForCommand(player, primaryCommand)) {
                // Check if it might be targeting other players
                String basePermission = getCommandPermission(primaryCommand);
                boolean isOthersCommand = false;

                if (basePermission != null) {
                    // Check if this could be an "others" command
                    String othersPermission = basePermission + ".others";
                    String pluginName = basePermission.split("\\.")[0];
                    String commandName = primaryCommand.split(" ")[0]; // Get just the base command
                    String alternateOthersPermission = pluginName + ".command." + commandName + ".others";

                    // Check if the player has either form of the "others" permission
                    if (player.hasPermission(othersPermission) || player.hasPermission(alternateOthersPermission)) {
                        isOthersCommand = true;
                    }
                }

                if (!isOthersCommand) {
                    // Find a similar command suggestion
                    String suggestion = findSimilarCommand(command, player);

                    if (suggestion != null) {
                        // Send message with the suggestion
                        Text.sendErrorMessage(player, "unknown-command-suggestion", lang,
                                "{cmd}", command,
                                "{suggestion}", suggestion);
                    } else {
                        // No suggestion found, send regular unknown command message
                        Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", command);
                    }
                } else {
                    // Player has ".others" permission - allow the command with "to others" appended
                    String modifiedCommand = command + " to others";
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", modifiedCommand);
                }

                event.setCancelled(true);
            }


        } else {
            // Command is not registered, show suggestion or unknown command message
            String suggestion = findSimilarCommand(command, player);
            String errorMessage;

            if (suggestion != null) {
                errorMessage = lang.get("unknown-command-suggestion");
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = "&cUnknown command: /{cmd}. Did you mean: /{suggestion}?"
                            .replace("{cmd}", command)
                            .replace("{suggestion}", suggestion);
                    player.sendMessage(Text.parseColors(errorMessage));
                } else {
                    Text.sendErrorMessage(player, "unknown-command-suggestion", lang, "{cmd}",
                            command, "{suggestion}", suggestion);
                }
            } else {
                errorMessage = lang.get("unknown-command");
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = "&cUnknown command: /{cmd}. Do /help for a list of commands."
                            .replace("{cmd}", command);
                    player.sendMessage(Text.parseColors(errorMessage));
                } else {
                    Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", command);
                }
            }

            event.setCancelled(true);
        }
    }


    /**
     * Check if a player lacks permission to use a command
     *
     * @param player The player to check
     * @param command The command being attempted
     * @return true if the player doesn't have permission, false if they do
     */
    private boolean lackPermissionForCommand(Player player, String command) {
        // Skip permission check for operators if desired
        if (player.isOp()) {
            return false;
        }

        // Get the primary command if this is an alias
        String primaryCommand = getPrimaryCommand(command);

        try {
            // Try to get the plugin command instance using the primary command
            PluginCommand pluginCmd = Bukkit.getPluginCommand(primaryCommand);

            if (pluginCmd != null) {
                // 1. Check the command's explicit permission if available
                String permission = pluginCmd.getPermission();
                if (permission != null && !permission.isEmpty()) {
                    return !player.hasPermission(permission);
                }

                // 2. Check from our cached permissions map
                permission = commandPermissions.get(primaryCommand);
                if (permission != null && !permission.isEmpty()) {
                    return !player.hasPermission(permission);
                }

                // 3. Try to infer from plugin name
                String pluginName = pluginCmd.getPlugin().getName().toLowerCase();
                String[] permissionFormats = {
                        pluginName + "." + primaryCommand,
                        pluginName + ".command." + primaryCommand,
                        pluginName + ".cmd." + primaryCommand,
                        "core." + primaryCommand,
                        primaryCommand
                };

                for (String format : permissionFormats) {
                    if (!player.hasPermission(format)) {
                        // We found a permission node that matches common patterns and player doesn't have it
                        return true;
                    }
                }
            } else {
                // This might be a vanilla command

                // Check for vanilla permission pattern
                if (isVanillaCommand(primaryCommand) && !player.hasPermission("core.*")) {
                    return true;
                }

                // Check in our permissions map as fallback
                String permission = commandPermissions.get(primaryCommand);
                if (permission != null && !permission.isEmpty()) {
                    return !player.hasPermission(permission);
                }
            }
        } catch (Exception e) {
            // If any error occurs, fall back to default behavior
            plugin.getLogger().warning("Error checking permission for " + primaryCommand + ": " + e.getMessage());
        }

        // By default, assume the player has permission (let the command handler handle permissions)
        return false;
    }

    /**
     * Check if a command is likely a vanilla Minecraft command
     */
    private boolean isVanillaCommand(String command) {
        // Get the CommandBlockerConfig
        CommandBlockerConfig blockerConfig = plugin.getCommandBlockerConfig();

        // If we shouldn't block vanilla commands, return false immediately
        if (!blockerConfig.shouldBlockVanillaCommands()) {
            return false;
        }

        String[] vanillaCommands = {
                "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
                "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect",
                "enchant", "execute", "experience", "fill", "forceload", "function", "gamemode", "gamerule",
                "give", "help", "item", "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg",
                "op", "pardon", "pardon-ip", "particle", "place", "playsound", "recipe", "reload",
                "return", "ride", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
                "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon",
                "tag", "team", "teleport", "tellraw", "time", "title", "tm", "toggle", "tp", "trigger",
                "w", "weather", "whitelist", "worldborder", "xp"
        };


        for (String vanilla : vanillaCommands) {
            if (command.equalsIgnoreCase(vanilla)) {
                return true;
            }
        }

        return false;
    }


    /**
     * Check if a command is registered with the server
     */
    private boolean isCommandRegistered(String command) {
        // Refresh command list periodically to catch new commands from other plugins
        if (availableCommands.isEmpty() || Math.random() < 0.01) { // 1% chance to refresh
            refreshCommandList();
            if (Math.random() < 0.05) {
                discoverCommandAliases();
            }
        }

        // First check if the command itself is registered
        if (availableCommands.contains(command)) {
            return true;
        }

        // Check if it's an alias and the primary command is registered
        String primaryCommand = getPrimaryCommand(command);
        if (!command.equals(primaryCommand)) {
            return availableCommands.contains(primaryCommand);
        }

        return false;
    }



    /**
     * When commands are being sent to a player, refresh our command list
     */
    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        // This event fires when the server sends available commands to a player
        // It's a good time to refresh our command list
        refreshCommandList();

        // Also rediscover command permissions
        discoverCommandPermissions();
        discoverCommandAliases();

        event.getCommands().removeIf(cmd -> cmd.contains(":"));
    }
    /**
     * Discover and collect command permissions from all registered commands
     */
    public void discoverCommandPermissions() {
        plugin.getLogger().info("Discovering command permissions...");
        int found = 0;

        // First try to extract permissions from PluginCommands
        for (Plugin serverPlugin : Bukkit.getPluginManager().getPlugins()) {
            // Skip disabled plugins
            if (!serverPlugin.isEnabled()) {
                continue;
            }

            try {
                // Extract commands from plugin.yml
                Map<String, Map<String, Object>> commandsMap = serverPlugin.getDescription().getCommands();
                if (commandsMap != null) {
                    for (Map.Entry<String, Map<String, Object>> entry : commandsMap.entrySet()) {
                        String cmdName = entry.getKey().toLowerCase();
                        Map<String, Object> cmdData = entry.getValue();

                        // Check if the command has a permission defined
                        if (cmdData.containsKey("permission")) {
                            String permission = cmdData.get("permission").toString();
                            commandPermissions.put(cmdName, permission);
                            found++;
                        }
                    }
                }
            } catch (Exception e) {
                // Skip errors and continue with next plugin
            }
        }

        // Now try to get permissions from CommandMap
        try {
            org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap)
                    Bukkit.getServer().getClass().getDeclaredMethod("getCommandMap").invoke(Bukkit.getServer());

            Field knownCommandsField = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);

            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);

            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                String commandName = entry.getKey();
                Command command = entry.getValue();

                // Skip plugin namespace commands but store the base command
                if (commandName.contains(":")) {
                    commandName = commandName.split(":")[1].toLowerCase();
                }

                // Get the permission if set
                String permission = command.getPermission();
                if (permission != null && !permission.isEmpty()) {
                    commandPermissions.put(commandName, permission);
                    found++;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error discovering permissions from CommandMap: " + e.getMessage());
        }

        plugin.getLogger().info("Command permission discovery complete. Found " + found + " permissions.");
    }

    /**
     * Manually register a command permission
     * @param command The command name (without slash)
     * @param permission The permission node
     */
    public void registerCommandPermission(String command, String permission) {
        commandPermissions.put(command.toLowerCase(), permission);
    }
    public void discoverCommandAliases() {
        // Clear existing aliases to avoid stale data
        commandAliases.clear();

        // Get all plugins
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            try {
                // Get command map from each plugin
                Map<String, Map<String, Object>> commands = plugin.getDescription().getCommands();
                if (commands == null) continue;

                // Process each command
                for (Map.Entry<String, Map<String, Object>> entry : commands.entrySet()) {
                    String primaryCommand = entry.getKey().toLowerCase();
                    Map<String, Object> commandProperties = entry.getValue();

                    // Check if the command has aliases
                    if (commandProperties.containsKey("aliases")) {
                        Object aliasObj = commandProperties.get("aliases");
                        List<String> aliases = new ArrayList<>();

                        // Handle different ways aliases can be defined in plugin.yml
                        if (aliasObj instanceof List) {
                            // Handle list format: aliases: [a, b, c]
                            aliases.addAll((List<String>) aliasObj);
                        } else if (aliasObj instanceof String) {
                            // Handle string format: aliases: "a, b, c"
                            String[] splitAliases = ((String) aliasObj).split(",");
                            for (String alias : splitAliases) {
                                aliases.add(alias.trim());
                            }
                        }

                        // Register each alias
                        for (String alias : aliases) {
                            registerCommandAlias(alias.toLowerCase(), primaryCommand);

                            // Also check if the plugin has registered permission for this command
                            if (commandProperties.containsKey("permission")) {
                                String permission = (String) commandProperties.get("permission");
                                registerCommandPermission(alias.toLowerCase(), permission);
                            }
                        }

                        // Log discovery of aliases if desired
                        if (!aliases.isEmpty()) {
                            plugin.getLogger().info("Registered " + aliases.size() + " aliases for command '" +
                                    primaryCommand + "' from plugin " + plugin.getName());
                        }
                    }
                }
            } catch (Exception e) {
                // Handle exceptions gracefully
                plugin.getLogger().warning("Failed to process command aliases for plugin " +
                        plugin.getName() + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Discovered and registered " + commandAliases.size() +
                " command aliases from all plugins");
    }

    /**
     * Registers a command alias, mapping it to its primary command
     * @param alias The alias command name
     * @param primaryCommand The primary command it should map to
     */
    public void registerCommandAlias(String alias, String primaryCommand) {
        alias = alias.toLowerCase();
        primaryCommand = primaryCommand.toLowerCase();
        commandAliases.put(alias, primaryCommand);
    }

    /**
     * Gets the primary command for an alias, or returns the original if it's not an alias
     * @param command The command to check
     * @return The primary command if this is an alias, or the original command
     */
    private String getPrimaryCommand(String command) {
        return commandAliases.getOrDefault(command, command);
    }


    /**
     * For debugging: dumps all known command permissions to console
     */
    public void dumpCommandPermissions() {
        plugin.getLogger().info("=== Command Permissions ===");
        List<String> sorted = new ArrayList<>(commandPermissions.keySet());
        Collections.sort(sorted);

        for (String cmd : sorted) {
            plugin.getLogger().info(cmd + " => " + commandPermissions.get(cmd));
        }
        plugin.getLogger().info("=== " + commandPermissions.size() + " permissions mapped ===");
    }
}
