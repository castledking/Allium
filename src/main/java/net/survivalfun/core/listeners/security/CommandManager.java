package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;

public class CommandManager implements Listener {

    private final PluginStart plugin;
    private static boolean hideNamespacedCommandsForBypass;
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
                    config.set("settings.hide-namespaced-commands-for-bypass", true);
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
        hideNamespacedCommandsForBypass = config.getBoolean("settings.hide-namespaced-commands-for-bypass", true);
        if(plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().info("Namespaced commands for bypass users will be "
                    + (hideNamespacedCommandsForBypass ? "hidden" : "shown") + " in tab completion.");
        }
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
                // Use the original commands list from the config, not already expanded ones
                tabCompletes.addAll(commands);
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
                            // Store the original command name with namespace for proper command checking
                            expandedCommands.add(entry.getKey());
                        }
                    }
                }
            } else {
                // For regular commands, resolve the command to its full name with namespace if applicable
                String fullCommandName = resolveFullCommandName(command);
                expandedCommands.add(fullCommandName);
            }
        }

        return expandedCommands;
    }

    /**
     * Resolves a command name to its full name with namespace if needed
     */
    private String resolveFullCommandName(String commandName) {
        // If the command already has a namespace, return as is
        if (commandName.contains(":")) {
            return commandName.toLowerCase();
        }

        // Try to find the command in the command map
        Command command = Bukkit.getCommandMap().getCommand(commandName);

        if (command == null) {
            // Command not found in command map, return as is
            return commandName.toLowerCase();
        }

        if (command instanceof PluginCommand) {
            // For plugin commands, include the plugin name as namespace
            PluginCommand pluginCommand = (PluginCommand) command;
            return pluginCommand.getPlugin().getName().toLowerCase() + ":" + commandName.toLowerCase();
        }

        // For built-in commands or unknown commands, return as is
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
        if (event.isCancelled()) return;

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

        // Check if player can use this command
        boolean canUseCommand = false;
        for (CommandGroup group : playerGroups) {
            // Check for wildcard permission first
            if (group.commands().contains("*") && group.whitelist()) {
                canUseCommand = true;
                break;
            }

            // Check the full command
            boolean commandInList = group.commands().contains(fullCommand);

            // If not found in the list && it's a namespaced command, check the base command
            if (!commandInList && isNamespacedCommand) {
                String baseCommand = fullCommand.substring(fullCommand.indexOf(':') + 1);
                commandInList = group.commands().contains(baseCommand);
            }

            if ((group.whitelist() && commandInList) || (!group.whitelist() && !commandInList)) {
                canUseCommand = true;
                break;
            }
        }

        // If command is not allowed, cancel the event and send appropriate message
        if (!canUseCommand) {
            event.setCancelled(true);

            if (isNamespacedCommand) {
                // Special message for namespaced commands
                Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
            } else {
                // Find a similar allowed command for suggestion
                String similar = findSimilarCommand(player, fullCommand);

                if (similar != null) {
                    Text.sendErrorMessage(player, "unknown-command-suggestion", lang,
                            "{cmd}", fullCommand, "{suggestion}", similar);
                } else {
                    Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
                }
            }
        }
    }





    /**
     * Updates the tab completion list for a specific player
     */
    public void updatePlayerTabCompletion(Player player) {
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
                plugin.getLogger().info("Updated tab completion for " + player.getName() + " with " + event.getCommands().size() + " commands");
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



    private boolean shouldShowCommand(Player player, String commandName) {
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
            event.setCancelled(true);
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
         */
        public boolean isTabCompletionAllowed(String commandName) {
            // Check for specific allowed commands first
            if (commands.contains(commandName)) {
                return whitelist;
            }

            // For namespaced commands, check base command
            if (commandName.contains(":")) {
                String baseCommand = commandName.substring(commandName.indexOf(':') + 1);
                if (commands.contains(baseCommand)) {
                    return whitelist;
                }

                // If it is the wildcard group, and we're hiding namespaced commands
                if (commands.contains("*") && hideNamespacedCommandsForBypass) {
                    return false; // Hide this namespaced command
                }
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
