package net.survivalfun.core.listeners.security;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;

public class CommandManager implements Listener {

    private final PluginStart plugin;
    private static boolean hideNamespacedCommandsForBypass;
    private static boolean enabled;
    private File configFile;
    private FileConfiguration config;
    private final Lang lang;
    private final Map<String, CommandGroup> commandGroups = new HashMap<>();

    public CommandManager(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        loadConfig();
        registerEvents();
    }

    private void registerEvents() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

    }

    private void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "hide.yml");

        if (!configFile.exists()) {
            try {
                configFile.getParentFile().mkdirs();
                plugin.saveResource("hide.yml", false);
            } catch (Exception e) {
                // If there's no embedded hide.yml in the plugin jar, create one
                try {
                    configFile.createNewFile();
                    config = YamlConfiguration.loadConfiguration(configFile);

                    // Create example structure
                    config.set("settings.hide-namespaced-commands-for-ops", true);
                    config.set("groups.admin.whitelist", true);
                    config.set("groups.admin.commands", List.of("op", "deop", "ban", "kick"));
                    config.set("groups.admin.tabcompletes", List.of("op", "deop"));

                    config.set("groups.moderator.whitelist", false);
                    config.set("groups.moderator.commands", List.of("op", "deop"));
                    config.set("groups.moderator.tabcompletes", List.of("op", "deop"));

                    config.save(configFile);
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Could not create hide.yml", ex);
                }
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        hideNamespacedCommandsForBypass = config.getBoolean("settings.hide-namespaced-commands-for-ops", true);
        enabled = config.getBoolean("settings.enabled", true);
        loadGroups();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        commandGroups.clear();
        loadGroups();
    }

    public boolean shouldHideNamespacedCommandsForBypass() {
        return hideNamespacedCommandsForBypass;
    }


    private void loadGroups() {
        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        if (groupsSection == null) {
            plugin.getLogger().warning("No groups found in hide.yml");
            return;
        }

        for (String groupName : groupsSection.getKeys(false)) {
            ConfigurationSection groupSection = groupsSection.getConfigurationSection(groupName);
            if (groupSection == null) continue;

            boolean whitelist = groupSection.getBoolean("whitelist", true);
            List<String> commands = groupSection.getStringList("commands");
            List<String> tabCompletes = groupSection.getStringList("tabcompletes");

            // Process wildcards in commands for plugin prefixes
            List<String> expandedCommands = expandWildcardCommands(commands);

            // Special handling for "^" in tabCompletes
            if (tabCompletes.contains("^")) {
                // Replace "^" with all commands from this group
                tabCompletes.remove("^");
                // Add both the original commands and their namespaced versions
                for (String command : commands) {
                    // Add the original command
                    tabCompletes.add(command);
                    // Add the namespaced version if it exists
                    String fullCommand = resolveFullCommandName(command);
                    if (!command.equals(fullCommand)) {
                        tabCompletes.add(fullCommand);
                    }
                }
            }

            // Now expand wildcards in tab completions
            List<String> expandedTabCompletes = expandWildcardCommands(tabCompletes);

            CommandGroup group = new CommandGroup(groupName, whitelist, expandedCommands, expandedTabCompletes, hideNamespacedCommandsForBypass);
            commandGroups.put(groupName, group);

            if(plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Loaded command group: " + groupName +
                        " (whitelist: " + whitelist +
                        ", commands: " + expandedCommands.size() +
                        ", tabcompletes: " + expandedTabCompletes.size() + ")");
            }
        }
    }



    /**
     * Expands wildcard commands like "<plugin>:*" to include all commands from that plugin
     * Strips namespace prefixes from the command names
     */
    private List<String> expandWildcardCommands(List<String> commands) {
        List<String> expandedCommands = new ArrayList<>();

        for (String command : commands) {
            if (command.equals("^")) {
                // Just pass through the special ^ symbol
                expandedCommands.add(command);
            } else if (command.endsWith(":*")) {
                // Wildcard command pattern detected
                String pluginName = command.substring(0, command.length() - 2);
                Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(pluginName);

                if (targetPlugin != null) {
                    // Find all commands from this plugin
                    Map<String, Command> knownCommands = getKnownCommands();
                    for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                        if (isCommandFromPlugin(entry.getValue(), targetPlugin)) {
                            // Store both the original command name and its namespaced version
                            String commandName = entry.getKey();
                            String baseCommand = commandName.contains(":") 
                                ? commandName.substring(commandName.indexOf(':') + 1)
                                : commandName;
                            expandedCommands.add(commandName);
                            if (!commandName.equals(baseCommand)) {
                                expandedCommands.add(baseCommand);
                            }
                        }
                    }
                }
            } else {
                // For regular commands, store both the original and namespaced versions
                String fullCommandName = resolveFullCommandName(command);
                expandedCommands.add(command);
                if (!command.equals(fullCommandName)) {
                    expandedCommands.add(fullCommandName);
                }
            }
        }

        return expandedCommands;
    }

    /**
     * Resolves a command name to its full name with namespace if needed
     */
    private String resolveFullCommandName(String commandName) {
        // First try to find the command in the command map
        Command command = Bukkit.getCommandMap().getCommand(commandName.toLowerCase());

        if (command == null) {
            // If command doesn't exist in command map, return as is
            return commandName.toLowerCase();
        }

        // Get the plugin name for this command
        String pluginName = getPluginForCommand(command, commandName);
        
        // Return the command with proper namespace if needed
        if (pluginName != null && !pluginName.isEmpty()) {
            return pluginName.toLowerCase() + ":" + commandName.toLowerCase();
        }

        // For built-in commands, return as is
        return commandName.toLowerCase();
    }

    /**
     * Get known commands from the server's command map
     */
    private Map<String, Command> getKnownCommands() {
        try {
            SimpleCommandMap commandMap = (SimpleCommandMap) Bukkit.getServer().getClass()
                    .getMethod("getCommandMap")
                    .invoke(Bukkit.getServer());

            Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);

            Map<String, Command> filteredCommands = new HashMap<>();
            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    filteredCommands.put(entry.getKey(), entry.getValue());
                }
            }

            return filteredCommands;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to access server command map", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Determine if a command belongs to a specific plugin
     */
    private boolean isCommandFromPlugin(Command command, Plugin targetPlugin) {
        // For PluginCommand instances, we can directly check the owning plugin
        if (command instanceof PluginCommand) {
            return ((PluginCommand) command).getPlugin() == targetPlugin;
        }

        // For other command types, check the name prefix or command description
        String cmdName = command.getName().toLowerCase();
        String pluginName = targetPlugin.getName().toLowerCase();

        return cmdName.startsWith(pluginName + ":") ||
                (command.getDescription() != null &&
                        command.getDescription().toLowerCase().contains(pluginName));
    }




    /**
     * Checks if a player belongs to a specific group
     * In a real implementation, this would check permissions or group membership
     */
    private boolean playerBelongsToGroup(Player player, String groupName) {
        // In a real implementation, you would check permissions like:
        return player.hasPermission("group." + groupName);
    }

    private String findSimilarCommand(Player player, String attemptedCommand) {
        // Get all available commands on the server
        Collection<String> availableCommands = new ArrayList<>();

        // Add commands from all plugins
        Bukkit.getServer().getCommandMap().getKnownCommands().keySet().stream()
                .map(String::toLowerCase)
                .filter(cmd -> shouldShowCommand(player, cmd))
                .forEach(availableCommands::add);

        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        // Find command with the lowest Levenshtein distance
        for (String cmd : availableCommands) {
            // Skip the exact same command
            if (cmd.equals(attemptedCommand)) {
                continue;
            }

            int distance = levenshteinDistance(attemptedCommand, cmd);

            // Consider it a match if the distance is less than half the length of the command
            // and better than any match found so far
            if (distance < bestDistance && distance < Math.max(attemptedCommand.length(), cmd.length()) / 2) {
                bestMatch = cmd;
                bestDistance = distance;
            }
        }

        return bestMatch;
    }


    /**
     * Calculate Levenshtein distance between two strings
     */
    private int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }

    private CommandGroup getWildcardGroup() {
        return new CommandGroup(
                "op",
                true,
                Collections.singletonList("*"),
                Collections.singletonList("*"),
                hideNamespacedCommandsForBypass // Pass the setting
        );
    }








    /**
     * Get all groups a player belongs to
     */
    private List<CommandGroup> getPlayerGroups(Player player) {
        List<CommandGroup> groups = new ArrayList<>();

        if (player.hasPermission("core.hide.bypass")) {
            CommandGroup wildcardGroup = getWildcardGroup();
            groups.add(wildcardGroup);
        }

        // First check for explicit groups
        for (Map.Entry<String, CommandGroup> entry : commandGroups.entrySet()) {
            if (playerBelongsToGroup(player, entry.getKey())) {
                groups.add(entry.getValue());
            }
        }

        // If no explicit groups, use default group
        if (groups.isEmpty() && commandGroups.containsKey("default")) {
            groups.add(commandGroups.get("default"));
        }

        return groups;
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        // Skip processing if command manager is disabled
        if (!enabled) {
            return;
        }
        
        Player player = event.getPlayer();
        List<CommandGroup> groups = getPlayerGroups(player);

        // No need to process if player has no restrictions
        if (groups.isEmpty()) {
            return;
        }

        // First check if the player has a wildcard permission in any group
        boolean hasWildcard = groups.stream()
                .anyMatch(group -> group.whitelist() && group.commands().contains("*"));

        // If player has wildcard permission and it's in a whitelist group, allow all commands
        if (hasWildcard) {
            return; // Keep all commands in the tab completion
        }

        // Otherwise, filter commands as usual
        Iterator<String> iterator = event.getCommands().iterator();
        while (iterator.hasNext()) {
            String command = iterator.next();

            // Check if this command should be shown based on player's groups
            boolean shouldShow = false;
            for (CommandGroup group : groups) {
                if (group.isTabCompletionAllowed(command)) {
                    shouldShow = true;
                    break;
                }
            }

            if (!shouldShow) {
                iterator.remove();
            }
        }
    }




    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled() || !enabled) return;

        Player player = event.getPlayer();
        String message = event.getMessage().substring(1); // Remove the leading slash

        // Get just the command part without args
        String fullCommand = message.split(" ")[0].toLowerCase();
        boolean isNamespacedCommand = fullCommand.contains(":");

        // Get player's permission groups
        List<CommandGroup> playerGroups = getPlayerGroups(player);

        // If player has no groups, deny all commands
        if (playerGroups.isEmpty()) {
            event.setCancelled(true);
            player.sendMessage("§cYou can't use any commands at this time.");
            player.sendMessage("§cContact admin if you think this is an error.");
            return;
        }

        // Check if player can use this command via groups
        boolean canUseCommand = false;
        for (CommandGroup group : playerGroups) {
            // Check for wildcard permission first
            if (group.commands().contains("*") && group.whitelist()) {
                canUseCommand = true;
                break;
            }

            // Check both the full command and its base name
            String commandToCheck = fullCommand.toLowerCase();
            String baseCommand = commandToCheck;
            
            // If it's a namespaced command, get the base command
            if (isNamespacedCommand) {
                baseCommand = commandToCheck.substring(commandToCheck.indexOf(':') + 1);
            }

            // Check if either the full command or base command is in the list
            boolean commandInList = group.commands().contains(commandToCheck) || 
                                   group.commands().contains(baseCommand);

            // For whitelist groups, command must be in list
            // For blacklist groups, command must NOT be in list
            if (group.whitelist() ? commandInList : !commandInList) {
                canUseCommand = true;
                break;
            }
        }

        if (!canUseCommand || (canUseCommand && lackPermissionForCommand(player, fullCommand))) {
            event.setCancelled(true);

            if (isNamespacedCommand) {
                // Special message for namespaced commands
                Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
            } else {
                // Find a similar allowed command for suggestion
                String similar = findSimilarCommand(player, fullCommand);

                if (similar != null) {
                    // Construct the suggestion component directly in the call
                    Component suggestionComponent = Component.text(similar)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to run: /" + similar).color(NamedTextColor.GRAY)
                            ))
                            .clickEvent(ClickEvent.suggestCommand("/" + similar));

                    // Build the entire message as a Component
                    String errorMessage = lang.get("unknown-command-suggestion"); // Get language string

                    if (errorMessage != null) {
                        // Split the string around the "{suggestion}" placeholder
                        String[] parts = errorMessage.split("\\{suggestion\\}");

                        Component finalMessage = Component.empty();

                        if (parts.length > 0) {
                            finalMessage = finalMessage.append(Component.text(parts[0].replace("{cmd}", fullCommand))); // Add the first part + replace cmd
                        }

                        finalMessage = finalMessage.append(suggestionComponent); // Add the suggestion

                        if (parts.length > 1) {
                            finalMessage = finalMessage.append(Component.text(parts[1].replace("{cmd}", fullCommand))); // Add the second part + replace cmd
                        }

                        // Send the final message using sendErrorMessage (with Component overload)
                        Text.sendErrorMessage(player, finalMessage, lang);

                    } else {
                        Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
                    }
                } else {
                    Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
                }
            }
        } else {
            // If command is allowed, allow it to proceed
            return;
        }
    }


/**
     * Universal method to check if a player lacks permission for a command
     * @param player The player to check
     * @param commandName The command name (without slash)
     * @return true if the player lacks permission, false if they have permission
     */
    public boolean lackPermissionForCommand(Player player, String commandName) {
        // If command manager is disabled, player doesn't lack permission
        if (!enabled) {
            return false;
        }
        
        // First check if the command is allowed in hide.yml
        List<CommandGroup> playerGroups = getPlayerGroups(player);
        boolean isCommandAllowed = false;
        
        for (CommandGroup group : playerGroups) {
            // Check if command is in the list for whitelist groups
            if (group.whitelist() && group.commands().contains(commandName.toLowerCase())) {
                isCommandAllowed = true;
                break;
            }
            // Check if command is NOT in the list for blacklist groups
            if (!group.whitelist() && !group.commands().contains(commandName.toLowerCase())) {
                isCommandAllowed = true;
                break;
            }
        }
        
        if (isCommandAllowed) {
            return false; // Command is allowed in hide.yml
        }

        // Always allow basic commands if they're not denied by hide.yml
        List<String> alwaysAllowedCommands = Arrays.asList("help", "spawn", "rules", "tpa", "tpahere", "tpaccept", "tpdeny");
        if (alwaysAllowedCommands.contains(commandName.toLowerCase())) {
            return false; // Player doesn't lack permission
        }

        // Get the actual command from the command map
        Command command = getCommandFromMap(commandName);
        if (command == null) {
            // If command doesn't exist, we can't check permission
            return false;
        }

        // Get the plugin that owns this command
        String pluginName = getPluginForCommand(command, commandName);

        // Build permission nodes to check
        List<String> permNodes = getStrings(commandName, pluginName, command);

        // Check all permission nodes
        for (String permNode : permNodes) {
            if (player.hasPermission(permNode)) {
                return false; // Player has permission
            }
        }

        // Special case: OP has all permissions
        return !player.isOp() && !player.hasPermission("*") && !player.hasPermission(pluginName + ".*"); // Player has permission

        // If we get here, player lacks permission
    }

    private static @NotNull List<String> getStrings(String commandName, String pluginName, Command command) {
        List<String> permNodes = new ArrayList<>();

        // Handle namespaced commands (plugin:command)
        String baseCommand = commandName;
        if (commandName.contains(":")) {
            baseCommand = commandName.substring(commandName.indexOf(':') + 1);
        }

        // Check if the pluginName is Allium and modify it for permission testing
        String permissionPluginName = pluginName;
        if ("Allium".equals(pluginName)) {
            permissionPluginName = "core";
        }

        // Special case for core plugin - only check core.<command>
        if ("core".equals(permissionPluginName)) {
            permNodes.add(permissionPluginName + "." + baseCommand);
        } else {
            // For other plugins, keep the standard formats
            // <plugin>.command.<command>
            permNodes.add(permissionPluginName + ".command." + baseCommand);
            // <plugin>.<command>
            permNodes.add(permissionPluginName + "." + baseCommand);
        }

        // Add command's defined permission if it has one and we're not handling core
        if (!("core".equals(permissionPluginName)) &&
                command.getPermission() != null &&
                !command.getPermission().isEmpty()) {
            permNodes.add(command.getPermission());
        }

        return permNodes;
    }



    /**
     * Get a command from the server's command map
     */
    private Command getCommandFromMap(String commandName) {
        try {
            // Get the server's command map via reflection
            final Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            Command command = getCommand(commandName, commandMapField);

            return command;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to access command map: " + e.getMessage());
            return null;
        }
    }

    private static @Nullable Command getCommand(String commandName, Field commandMapField) throws IllegalAccessException {
        CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

        // Handle namespaced commands
        String baseCommand = commandName;
        if (commandName.contains(":")) {
            baseCommand = commandName.substring(commandName.indexOf(':') + 1);
        }

        // Try to get the command by both full name and base name
        Command command = commandMap.getCommand(commandName);
        if (command == null && !baseCommand.equals(commandName)) {
            command = commandMap.getCommand(baseCommand);
        }
        return command;
    }

    /**
     * Determine which plugin provides a command
     */
    private String getPluginForCommand(Command command, String commandName) {
        String pluginName = "minecraft"; // Default to minecraft

        // Try to get plugin name from command itself
        if (command instanceof PluginCommand) {
            Plugin owningPlugin = ((PluginCommand) command).getPlugin();
            pluginName = owningPlugin.getName().toLowerCase();
        }
        // Handle namespaced commands like "minecraft:tp"
        else if (commandName.contains(":")) {
            pluginName = commandName.substring(0, commandName.indexOf(':'));
        }

        // Convert allium to core
        if ("allium".equals(pluginName)) {
            pluginName = "core";
        }

        return pluginName;
    }






    /**
     * Updates the tab completion list for a specific player
     */
    public void updatePlayerTabCompletion(Player player) {
        // Skip processing if command manager is disabled
        if (!enabled) {
            return;
        }
        
        // Create a new PlayerCommandSendEvent for this player with all available commands
        List<String> allCommands = new ArrayList<>(Bukkit.getCommandMap().getKnownCommands().keySet());
        PlayerCommandSendEvent event = new PlayerCommandSendEvent(player, allCommands);

        // Process the event using our existing handler
        onCommandSend(event);

        // Force update the client's command list using packets
        try {
            // Get the player's connection through reflection
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object playerConnection = entityPlayer.getClass().getField("playerConnection").get(entityPlayer);

            // Create a command list packet
            Constructor<?> packetConstructor = getNMSClass("PacketPlayOutCommands").getConstructor();
            Object packet = packetConstructor.newInstance();

            // Set the commands field
            Field commandsField = packet.getClass().getDeclaredField("commands");
            commandsField.setAccessible(true);
            commandsField.set(packet, event.getCommands());

            // Send the packet
            playerConnection.getClass().getMethod("sendPacket", getNMSClass("Packet")).invoke(playerConnection, packet);

            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Updated tab completion for " + player.getName() + " with " + event.getCommands()
                .size() + " commands");
            }
        } catch (Exception e) {
            // Fallback method if reflection fails
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().warning("Failed to update tab completion for "
                        + player.getName() + " using packets. Using fallback method.");
                plugin.getLogger().info("4dev: PacketEvents TBD");
            }


            // Force a temporary gamemode change to trigger client update
            GameMode originalMode = player.getGameMode();
            GameMode tempMode = originalMode == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE;

            player.setGameMode(tempMode);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.setGameMode(originalMode), 1L);
        }
    }

    // Helper method to get NMS classes - adjust for your server version
    private Class<?> getNMSClass(String className) throws ClassNotFoundException {
        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        return Class.forName("net.minecraft.server." + version + "." + className);
    }



    /**
     * Checks if a player has permission to see and use a command based on their group permissions
     * @param player The player to check
     * @param commandName The command name (without slash)
     * @return true if the player has permission to see/use the command, false otherwise
     */
    public boolean shouldShowCommand(Player player, String commandName) {
        // If command manager is disabled, show all commands
        if (!enabled) {
            return true;
        }
        
        List<CommandGroup> playerGroups = getPlayerGroups(player);

        if (playerGroups.isEmpty()) {
            return true; // If player has no groups, show all commands
        }

        // Normalize the command name to lowercase
        commandName = commandName.toLowerCase();

        // Check if this is a base command that might have a namespace in our configuration
        // Try to find its full namespaced version if needed
        String fullCommandName = commandName;
        if (!commandName.contains(":")) {
            fullCommandName = resolveFullCommandName(commandName);
        }

        // Check if any of the player's groups allow this command or its namespaced version
        for (CommandGroup group : playerGroups) {
            if (group.isCommandAllowed(commandName) || group.isCommandAllowed(fullCommandName)) {
                return true;
            }
        }

        // If no group explicitly allows the command, don't show it
        return false;
    }





    /**
     * Determine if a command should be tab-completable by a player based on their groups
     */
    private boolean shouldAllowTabComplete(Player player, String commandName) {
        List<CommandGroup> groups = getPlayerGroups(player);

        if (groups.isEmpty()) {
            return true; // No restrictions
        }

        boolean hasWhitelistGroup = groups.stream().anyMatch(CommandGroup::whitelist);

        if (hasWhitelistGroup) {
            // In whitelist mode, the command is allowed only if at least one whitelist group allows it
            for (CommandGroup group : groups) {
                if (group.whitelist() && group.isTabCompletionAllowed(commandName)) {
                    return true;
                }
            }
            return false; // No whitelist group allowed it
        } else {
            // In blacklist-only mode, the command is allowed unless a blacklist group blocks it
            for (CommandGroup group : groups) {
                if (!group.isTabCompletionAllowed(commandName)) {
                    return false;
                }
            }
            return true; // No blacklist group blocked it
        }
    }




    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        // Skip processing if command manager is disabled
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();

        if (player.hasPermission("core.hide.bypass")) {
            return; // Skip filtering for players with bypass permission
        }

        List<CommandGroup> groups = getPlayerGroups(player);

        // No need to process if player has no restrictions
        if (groups.isEmpty()) {
            return;
        }

        // For players with whitelist groups, we need to completely clear the list first
        // and then only add commands that are explicitly allowed
        boolean hasWhitelistGroup = groups.stream().anyMatch(CommandGroup::whitelist);

        // Create a map to store only the base command (without namespace) for commands that are allowed
        Map<String, String> baseCommandsMap = new HashMap<>();

        // First, determine which commands should be allowed
        List<String> currentCommands = new ArrayList<>(event.getCommands());

        if (hasWhitelistGroup) {
            Set<String> allowedBaseCommands = new HashSet<>();

            // For each command in the event, check if it's allowed by any group
            for (String command : currentCommands) {
                // Get the base command (strip namespace)
                String baseCommand = command.contains(":")
                        ? command.substring(command.indexOf(':') + 1)
                        : command;

                for (CommandGroup group : groups) {
                    if (group.isTabCompletionAllowed(command)) {
                        // Store the base command as allowed, keeping a mapping to the original for reference
                        allowedBaseCommands.add(baseCommand);
                        baseCommandsMap.put(baseCommand, command);
                        break;
                    }
                }
            }

            // Clear the original list and add only the base commands for allowed commands
            event.getCommands().clear();
            event.getCommands().addAll(allowedBaseCommands);
        } else {
            // For blacklist mode
            List<String> filteredCommands = new ArrayList<>();

            for (String command : currentCommands) {
                // Get the base command (strip namespace)
                String baseCommand = command.contains(":")
                        ? command.substring(command.indexOf(':') + 1)
                        : command;

                boolean allowed = false;
                for (CommandGroup group : groups) {
                    if (group.isTabCompletionAllowed(command)) {
                        allowed = true;
                        break;
                    }
                }

                if (allowed) {
                    filteredCommands.add(baseCommand);
                    baseCommandsMap.put(baseCommand, command);
                }
            }

            // Replace with filtered commands using base names
            event.getCommands().clear();
            event.getCommands().addAll(filteredCommands);
        }
    }





    @EventHandler(priority = EventPriority.LOWEST)
    public void onTabComplete(TabCompleteEvent event) {
        if (event.isCancelled() || !enabled) return;
        if (!(event.getSender() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getSender();

        if (player.hasPermission("core.hide.bypass")) {
            return; // Skip filtering for players with bypass permission
        }

        String buffer = event.getBuffer();

        // Only process command tab completions
        if (!buffer.startsWith("/")) {
            return;
        }

        // Get the command without the slash
        String commandString = buffer.substring(1).split(" ")[0];

        // Check if this command (or its namespaced version) should be allowed for tab completion
        if (!shouldAllowTabComplete(player, commandString)) {
            event.setCompletions(new ArrayList<>());
        }
    }


    /**
     * Class to represent a group of commands and tab completions
     */
    private record CommandGroup(String name, boolean whitelist, List<String> commands,
                                List<String> tabCompletes, boolean hideNamespacedCommandsForBypass) {

        private CommandGroup(String name, boolean whitelist, List<String> commands, List<String> tabCompletes, boolean hideNamespacedCommandsForBypass) {
            this.name = name;
            this.whitelist = whitelist;
            // Store both namespaced and non-namespaced versions of commands
            this.commands = commands.stream()
                    .map(String::toLowerCase)
                    .toList();
            this.tabCompletes = tabCompletes.stream()
                    .map(String::toLowerCase)
                    .toList();
            this.hideNamespacedCommandsForBypass = hideNamespacedCommandsForBypass;
        }

        public boolean isCommandAllowed(String commandName) {
            // Convert to lowercase for case-insensitive comparison
            commandName = commandName.toLowerCase();

            // First check if the exact command (with namespace) is in the list
            if (commands.contains(commandName)) {
                return whitelist; // Allow in whitelist, deny in blacklist
            }

            // Extract base command without namespace
            String baseCommand = commandName.contains(":")
                    ? commandName.substring(commandName.indexOf(':') + 1)
                    : commandName;

            // Check if the base command is in the list
            if (commands.contains(baseCommand)) {
                return whitelist; // Allow in whitelist, deny in blacklist
            }

            // If not found, return the opposite of whitelist mode
            return !whitelist;
        }



        /**
         * Checks if a command should be shown in tab completion for this group
         * @param commandName The command name (without slash)
         * @return true if the command should be shown in tab completion, false otherwise
         */
        public boolean isTabCompletionAllowed(String commandName) {
            // Convert to lowercase
            commandName = commandName.toLowerCase();

            // First check if the exact command (with namespace) is in the list
            if (commands.contains(commandName)) {
                return whitelist;
            }

            // Extract base command without namespace
            String baseCommand = commandName.contains(":")
                    ? commandName.substring(commandName.indexOf(':') + 1)
                    : commandName;

            // Check if the base command is in the list
            if (commands.contains(baseCommand)) {
                return whitelist;
            }

            // Check for wildcard
        if (commands.contains("*")) {
            return whitelist;
        }

        // Default case
        return !whitelist;
    }
}
}
