package net.survivalfun.core.listeners.security;

import net.kyori.adventure.text.Component;
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
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
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
    private final Permission vaultPermission;

    public CommandManager(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.vaultPermission = setupVaultPermission();
        loadConfig();
        registerEvents();
    }

    private Permission setupVaultPermission() {
        RegisteredServiceProvider<Permission> rsp = plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            plugin.getLogger().warning("Vault permission service not found. Group checks will fall back to permission-based checks.");
            return null;
        }
        return rsp.getProvider();
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
                try {
                    configFile.createNewFile();
                    config = YamlConfiguration.loadConfiguration(configFile);

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

            List<String> expandedCommands = expandWildcardCommands(commands);

            if (tabCompletes.contains("^")) {
                tabCompletes.remove("^");
                for (String command : commands) {
                    tabCompletes.add(command);
                    String fullCommand = resolveFullCommandName(command);
                    if (!command.equals(fullCommand)) {
                        tabCompletes.add(fullCommand);
                    }
                }
            }

            List<String> expandedTabCompletes = expandWildcardCommands(tabCompletes);

            CommandGroup group = new CommandGroup(groupName, whitelist, expandedCommands, expandedTabCompletes, hideNamespacedCommandsForBypass);
            commandGroups.put(groupName, group);

            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Loaded command group: " + groupName +
                        " (whitelist: " + whitelist +
                        ", commands: " + expandedCommands.size() +
                        ", tabcompletes: " + expandedTabCompletes.size() + ")");
            }
        }
    }

    private List<String> expandWildcardCommands(List<String> commands) {
        List<String> expandedCommands = new ArrayList<>();

        for (String command : commands) {
            if (command.equals("^")) {
                expandedCommands.add(command);
            } else if (command.endsWith(":*")) {
                String pluginName = command.substring(0, command.length() - 2);
                Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(pluginName);

                if (targetPlugin != null) {
                    Map<String, Command> knownCommands = getKnownCommands();
                    for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                        if (isCommandFromPlugin(entry.getValue(), targetPlugin)) {
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
                String fullCommandName = resolveFullCommandName(command);
                expandedCommands.add(command);
                if (!command.equals(fullCommandName)) {
                    expandedCommands.add(fullCommandName);
                }
            }
        }

        return expandedCommands;
    }

    private String resolveFullCommandName(String commandName) {
        Command command = Bukkit.getCommandMap().getCommand(commandName.toLowerCase());

        if (command == null) {
            return commandName.toLowerCase();
        }

        String pluginName = getPluginForCommand(command, commandName);

        if (pluginName != null && !pluginName.isEmpty()) {
            return pluginName.toLowerCase() + ":" + commandName.toLowerCase();
        }

        return commandName.toLowerCase();
    }

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

    private boolean isCommandFromPlugin(Command command, Plugin targetPlugin) {
        if (command instanceof PluginCommand) {
            return ((PluginCommand) command).getPlugin() == targetPlugin;
        }

        String cmdName = command.getName().toLowerCase();
        String pluginName = targetPlugin.getName().toLowerCase();

        return cmdName.startsWith(pluginName + ":") ||
                (command.getDescription() != null &&
                        command.getDescription().toLowerCase().contains(pluginName));
    }

    /**
     * Checks if a player belongs to a specific group using Vault's permission system
     */
    private boolean playerBelongsToGroup(Player player, String groupName) {
        if (vaultPermission == null) {
            // Fallback to permission-based check if Vault is not available
            plugin.getLogger().warning("Vault permission service unavailable, falling back to permission check for group: " + groupName);
            return player.hasPermission("group." + groupName);
        }

        try {
            // Check if the player is in the specified group using Vault
            return vaultPermission.hasGroupSupport() && vaultPermission.playerInGroup(player, groupName);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking group membership for " + player.getName() + " in group " + groupName + ": " + e.getMessage());
            // Fallback to permission-based check
            return player.hasPermission("group." + groupName);
        }
    }

    private String findSimilarCommand(Player player, String attemptedCommand) {
        Collection<String> availableCommands = new ArrayList<>();

        Bukkit.getServer().getCommandMap().getKnownCommands().keySet().stream()
                .map(String::toLowerCase)
                .filter(cmd -> shouldShowCommand(player, cmd))
                .forEach(availableCommands::add);

        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String cmd : availableCommands) {
            if (cmd.equals(attemptedCommand)) {
                continue;
            }

            int distance = levenshteinDistance(attemptedCommand, cmd);

            if (distance < bestDistance && distance < Math.max(attemptedCommand.length(), cmd.length()) / 2) {
                bestMatch = cmd;
                bestDistance = distance;
            }
        }

        return bestMatch;
    }

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
                hideNamespacedCommandsForBypass
        );
    }

    private List<CommandGroup> getPlayerGroups(Player player) {
        List<CommandGroup> groups = new ArrayList<>();

        if (player.hasPermission("core.hide.bypass")) {
            CommandGroup wildcardGroup = getWildcardGroup();
            groups.add(wildcardGroup);
        }

        for (Map.Entry<String, CommandGroup> entry : commandGroups.entrySet()) {
            if (playerBelongsToGroup(player, entry.getKey())) {
                groups.add(entry.getValue());
            }
        }

        if (groups.isEmpty() && commandGroups.containsKey("default")) {
            groups.add(commandGroups.get("default"));
        }

        return groups;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        if (!enabled) {
            return;
        }

        Player player = event.getPlayer();
        List<CommandGroup> groups = getPlayerGroups(player);

        if (groups.isEmpty()) {
            return;
        }

        boolean hasWildcard = groups.stream()
                .anyMatch(group -> group.whitelist() && group.commands().contains("*"));

        if (hasWildcard) {
            return;
        }

        Iterator<String> iterator = event.getCommands().iterator();
        while (iterator.hasNext()) {
            String command = iterator.next();

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
        String message = event.getMessage().substring(1);
        String fullCommand = message.split(" ")[0].toLowerCase();
        boolean isNamespacedCommand = fullCommand.contains(":");

        List<CommandGroup> playerGroups = getPlayerGroups(player);

        if (playerGroups.isEmpty()) {
            event.setCancelled(true);
            player.sendMessage("§cYou can't use any commands at this time.");
            player.sendMessage("§cContact admin if you think this is an error.");
            return;
        }

        boolean canUseCommand = false;
        for (CommandGroup group : playerGroups) {
            if (group.commands().contains("*") && group.whitelist()) {
                canUseCommand = true;
                break;
            }

            String commandToCheck = fullCommand.toLowerCase();
            String baseCommand = commandToCheck;

            if (isNamespacedCommand) {
                baseCommand = commandToCheck.substring(commandToCheck.indexOf(':') + 1);
            }

            boolean commandInList = group.commands().contains(commandToCheck) ||
                                   group.commands().contains(baseCommand);

            if (group.whitelist() ? commandInList : !commandInList) {
                canUseCommand = true;
                break;
            }
        }

        if (!canUseCommand || (canUseCommand && lackPermissionForCommand(player, fullCommand))) {
            event.setCancelled(true);

            if (isNamespacedCommand) {
                Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
            } else {
                String similar = findSimilarCommand(player, fullCommand);

                if (similar != null) {
                    Component suggestionComponent = Component.text(similar)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to run: /" + similar).color(NamedTextColor.GRAY)
                            ))
                            .clickEvent(ClickEvent.suggestCommand("/" + similar));

                    String errorMessage = lang.get("unknown-command-suggestion");

                    if (errorMessage != null) {
                        String[] parts = errorMessage.split("\\{suggestion\\}");

                        Component finalMessage = Component.empty();

                        if (parts.length > 0) {
                            finalMessage = finalMessage.append(Component.text(parts[0].replace("{cmd}", fullCommand)));
                        }

                        finalMessage = finalMessage.append(suggestionComponent);

                        if (parts.length > 1) {
                            finalMessage = finalMessage.append(Component.text(parts[1].replace("{cmd}", fullCommand)));
                        }

                        Text.sendErrorMessage(player, finalMessage, lang);
                    } else {
                        Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
                    }
                } else {
                    Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", fullCommand);
                }
            }
        }
    }

    public boolean lackPermissionForCommand(Player player, String commandName) {
        if (!enabled) {
            return false;
        }

        List<CommandGroup> playerGroups = getPlayerGroups(player);
        boolean isCommandAllowed = false;

        for (CommandGroup group : playerGroups) {
            if (group.whitelist() && group.commands().contains(commandName.toLowerCase())) {
                isCommandAllowed = true;
                break;
            }
            if (!group.whitelist() && !group.commands().contains(commandName.toLowerCase())) {
                isCommandAllowed = true;
                break;
            }
        }

        if (isCommandAllowed) {
            return false;
        }

        List<String> alwaysAllowedCommands = Arrays.asList("help", "spawn", "rules", "tpa", "tpahere", "tpaccept", "tpdeny");
        if (alwaysAllowedCommands.contains(commandName.toLowerCase())) {
            return false;
        }

        Command command = getCommandFromMap(commandName);
        if (command == null) {
            return false;
        }

        String pluginName = getPluginForCommand(command, commandName);

        List<String> permNodes = getStrings(commandName, pluginName, command);

        for (String permNode : permNodes) {
            if (player.hasPermission(permNode)) {
                return false;
            }
        }

        return !player.isOp() && !player.hasPermission("*") && !player.hasPermission(pluginName + ".*");
    }

    private static @NotNull List<String> getStrings(String commandName, String pluginName, Command command) {
        List<String> permNodes = new ArrayList<>();

        String baseCommand = commandName;
        if (commandName.contains(":")) {
            baseCommand = commandName.substring(commandName.indexOf(':') + 1);
        }

        String permissionPluginName = pluginName;
        if ("Allium".equals(pluginName)) {
            permissionPluginName = "core";
        }

        if ("core".equals(permissionPluginName)) {
            permNodes.add(permissionPluginName + "." + baseCommand);
        } else {
            permNodes.add(permissionPluginName + ".command." + baseCommand);
            permNodes.add(permissionPluginName + "." + baseCommand);
        }

        if (!("core".equals(permissionPluginName)) &&
                command.getPermission() != null &&
                !command.getPermission().isEmpty()) {
            permNodes.add(command.getPermission());
        }

        return permNodes;
    }

    private Command getCommandFromMap(String commandName) {
        try {
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

        String baseCommand = commandName;
        if (commandName.contains(":")) {
            baseCommand = commandName.substring(commandName.indexOf(':') + 1);
        }

        Command command = commandMap.getCommand(commandName);
        if (command == null && !baseCommand.equals(commandName)) {
            command = commandMap.getCommand(baseCommand);
        }
        return command;
    }

    private String getPluginForCommand(Command command, String commandName) {
        String pluginName = "minecraft";

        if (command instanceof PluginCommand) {
            Plugin owningPlugin = ((PluginCommand) command).getPlugin();
            pluginName = owningPlugin.getName().toLowerCase();
        } else if (commandName.contains(":")) {
            pluginName = commandName.substring(0, commandName.indexOf(':'));
        }

        if ("allium".equals(pluginName)) {
            pluginName = "core";
        }

        return pluginName;
    }

    public void updatePlayerTabCompletion(Player player) {
        if (!enabled) {
            return;
        }

        List<String> allCommands = new ArrayList<>(Bukkit.getCommandMap().getKnownCommands().keySet());
        PlayerCommandSendEvent event = new PlayerCommandSendEvent(player, allCommands);

        onCommandSend(event);

        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object playerConnection = entityPlayer.getClass().getField("playerConnection").get(entityPlayer);

            Constructor<?> packetConstructor = getNMSClass("PacketPlayOutCommands").getConstructor();
            Object packet = packetConstructor.newInstance();

            Field commandsField = packet.getClass().getDeclaredField("commands");
            commandsField.setAccessible(true);
            commandsField.set(packet, event.getCommands());

            playerConnection.getClass().getMethod("sendPacket", getNMSClass("Packet")).invoke(playerConnection, packet);

            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Updated tab completion for " + player.getName() + " with " + event.getCommands().size() + " commands");
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().warning("Failed to update tab completion for " + player.getName() + " using packets. Using fallback method.");
                plugin.getLogger().info("4dev: PacketEvents TBD");
            }

            GameMode originalMode = player.getGameMode();
            GameMode tempMode = originalMode == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE;

            player.setGameMode(tempMode);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.setGameMode(originalMode), 1L);
        }
    }

    private Class<?> getNMSClass(String className) throws ClassNotFoundException {
        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        return Class.forName("net.minecraft.server." + version + "." + className);
    }

    public boolean shouldShowCommand(Player player, String commandName) {
        if (!enabled) {
            return true;
        }

        List<CommandGroup> playerGroups = getPlayerGroups(player);

        if (playerGroups.isEmpty()) {
            return true;
        }

        commandName = commandName.toLowerCase();

        String fullCommandName = commandName;
        if (!commandName.contains(":")) {
            fullCommandName = resolveFullCommandName(commandName);
        }

        for (CommandGroup group : playerGroups) {
            if (group.isCommandAllowed(commandName) || group.isCommandAllowed(fullCommandName)) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldAllowTabComplete(Player player, String commandName) {
        List<CommandGroup> groups = getPlayerGroups(player);

        if (groups.isEmpty()) {
            return true;
        }

        boolean hasWhitelistGroup = groups.stream().anyMatch(CommandGroup::whitelist);

        if (hasWhitelistGroup) {
            for (CommandGroup group : groups) {
                if (group.whitelist() && group.isTabCompletionAllowed(commandName)) {
                    return true;
                }
            }
            return false;
        } else {
            for (CommandGroup group : groups) {
                if (!group.isTabCompletionAllowed(commandName)) {
                    return false;
                }
            }
            return true;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();

        if (player.hasPermission("core.hide.bypass")) {
            return;
        }

        List<CommandGroup> groups = getPlayerGroups(player);

        if (groups.isEmpty()) {
            return;
        }

        boolean hasWhitelistGroup = groups.stream().anyMatch(CommandGroup::whitelist);

        Map<String, String> baseCommandsMap = new HashMap<>();

        List<String> currentCommands = new ArrayList<>(event.getCommands());

        if (hasWhitelistGroup) {
            Set<String> allowedBaseCommands = new HashSet<>();

            for (String command : currentCommands) {
                String baseCommand = command.contains(":")
                        ? command.substring(command.indexOf(':') + 1)
                        : command;

                for (CommandGroup group : groups) {
                    if (group.isTabCompletionAllowed(command)) {
                        allowedBaseCommands.add(baseCommand);
                        baseCommandsMap.put(baseCommand, command);
                        break;
                    }
                }
            }

            event.getCommands().clear();
            event.getCommands().addAll(allowedBaseCommands);
        } else {
            List<String> filteredCommands = new ArrayList<>();

            for (String command : currentCommands) {
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
            return;
        }

        String buffer = event.getBuffer();

        if (!buffer.startsWith("/")) {
            return;
        }

        String commandString = buffer.substring(1).split(" ")[0];

        if (!shouldAllowTabComplete(player, commandString)) {
            event.setCompletions(new ArrayList<>());
        }
    }

    private record CommandGroup(String name, boolean whitelist, List<String> commands,
                               List<String> tabCompletes, boolean hideNamespacedCommandsForBypass) {

        private CommandGroup(String name, boolean whitelist, List<String> commands, List<String> tabCompletes, boolean hideNamespacedCommandsForBypass) {
            this.name = name;
            this.whitelist = whitelist;
            this.commands = commands.stream()
                    .map(String::toLowerCase)
                    .toList();
            this.tabCompletes = tabCompletes.stream()
                    .map(String::toLowerCase)
                    .toList();
            this.hideNamespacedCommandsForBypass = hideNamespacedCommandsForBypass;
        }

        public boolean isCommandAllowed(String commandName) {
            commandName = commandName.toLowerCase();

            if (commands.contains(commandName)) {
                return whitelist;
            }

            String baseCommand = commandName.contains(":")
                    ? commandName.substring(commandName.indexOf(':') + 1)
                    : commandName;

            if (commands.contains(baseCommand)) {
                return whitelist;
            }

            return !whitelist;
        }

        public boolean isTabCompletionAllowed(String commandName) {
            commandName = commandName.toLowerCase();

            if (commands.contains(commandName)) {
                return whitelist;
            }

            String baseCommand = commandName.contains(":")
                    ? commandName.substring(commandName.indexOf(':') + 1)
                    : commandName;

            if (commands.contains(baseCommand)) {
                return whitelist;
            }

            if (commands.contains("*")) {
                return whitelist;
            }

            return !whitelist;
        }
    }
}