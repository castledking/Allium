package net.survivalfun.core.listeners.security;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class CommandManager implements Listener {

    private final PluginStart plugin;
    private static boolean hideNamespacedCommandsForBypass;
    private static boolean blockNamespacedCommands;
    private static boolean enabled;
    private File configFile;
    private FileConfiguration config;
    private final Lang lang;
    private final Map<String, CommandGroup> commandGroups = new HashMap<>();
    private final Permission vaultPermission;
    private CreativeManager creativeManager;

    public CommandManager(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.vaultPermission = setupVaultPermission();
        loadConfig();
        registerEvents();
    }
    
    public void setCreativeManager(CreativeManager creativeManager) {
        this.creativeManager = creativeManager;
    }

    private Permission setupVaultPermission() {
        RegisteredServiceProvider<Permission> rsp = plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            Text.sendDebugLog(WARN, "Vault permission service not found. Group checks will fall back to permission-based checks.");
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
                    config.set("settings.block-namespaced-commands-for-ops", false);
                    config.set("groups.admin.whitelist", true);
                    config.set("groups.admin.commands", List.of("op", "deop", "ban", "kick"));
                    config.set("groups.admin.tabcompletes", List.of("op", "deop"));

                    config.set("groups.moderator.whitelist", false);
                    config.set("groups.moderator.commands", List.of("op", "deop"));
                    config.set("groups.moderator.tabcompletes", List.of("op", "deop"));

                    config.save(configFile);
                } catch (IOException ex) {
                    Text.sendDebugLog(ERROR, "Could not create hide.yml", ex);
                }
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        hideNamespacedCommandsForBypass = config.getBoolean("settings.hide-namespaced-commands-for-ops", true);
        blockNamespacedCommands = config.getBoolean("settings.block-namespaced-commands-for-ops", false);
        enabled = config.getBoolean("settings.enabled", true);
        loadGroups();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        commandGroups.clear();
        hideNamespacedCommandsForBypass = config.getBoolean("settings.hide-namespaced-commands-for-ops", hideNamespacedCommandsForBypass);
        blockNamespacedCommands = config.getBoolean("settings.block-namespaced-commands-for-ops", blockNamespacedCommands);
        enabled = config.getBoolean("settings.enabled", enabled);
        loadGroups();
        
        // Force refresh command state for all online players (Folia-safe)
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean scheduled = false;
            try {
                // Prefer Folia entity scheduler when available
                Method getScheduler = player.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(player);

                try {
                    // Folia API variant: execute(Plugin, Runnable)
                    Method execute = entityScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
                    execute.invoke(entityScheduler, plugin, (Runnable) () -> refreshPlayerCommandState(player));
                    scheduled = true;
                } catch (NoSuchMethodException ignored) {
                    // Older Folia signature: run(Plugin, Consumer, Object)
                    try {
                        Method run = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Object.class);
                        Consumer<Object> task = (ignore) -> refreshPlayerCommandState(player);
                        run.invoke(entityScheduler, plugin, task, null);
                        scheduled = true;
                    } catch (NoSuchMethodException ignored2) {
                        // continue to other fallbacks
                    }
                }
            } catch (Throwable ignored) {
                // getScheduler not available; continue to fallbacks
            }

            if (!scheduled) {
                try {
                    // Try GlobalRegionScheduler (Folia/Paper)
                    Method getGRS = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
                    Object grs = getGRS.invoke(Bukkit.getServer());
                    Method executeGRS = grs.getClass().getMethod("execute", Plugin.class, Runnable.class);
                    executeGRS.invoke(grs, plugin, (Runnable) () -> refreshPlayerCommandState(player));
                    scheduled = true;
                } catch (Throwable ignored) {
                    // continue to legacy fallback
                }
            }

            if (!scheduled) {
                // Legacy scheduler (non-Folia)
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerCommandState(player));
                } catch (Throwable t) {
                    Text.sendDebugLog(WARN, "Failed to schedule command refresh for " + player.getName() + ": " + t.getMessage());
                }
            }
        }
    }

    private void addConfiguredCommandVariants(Set<String> allowedCommands, Map<String, Command> knownCommands, String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isEmpty()) {
            return;
        }

        String lowerCommand = configuredCommand.toLowerCase(Locale.ROOT);
        if (lowerCommand.equals("*") || lowerCommand.equals("^")) {
            return;
        }

        if (knownCommands.containsKey(lowerCommand)) {
            allowedCommands.add(lowerCommand);
        }

        String resolved = resolveFullCommandName(lowerCommand);
        if (!resolved.equals(lowerCommand) && knownCommands.containsKey(resolved)) {
            allowedCommands.add(resolved);
        }
    }

    public boolean shouldHideNamespacedCommandsForBypass() {
        return hideNamespacedCommandsForBypass;
    }

    private void loadGroups() {
        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        if (groupsSection == null) {
            Text.sendDebugLog(WARN, "No groups found in hide.yml");
            return;
        }

        Map<String, RawCommandGroup> rawGroups = new LinkedHashMap<>();
        for (String groupName : groupsSection.getKeys(false)) {
            ConfigurationSection groupSection = groupsSection.getConfigurationSection(groupName);
            if (groupSection == null) continue;

            boolean whitelist = groupSection.getBoolean("whitelist", true);
            List<String> commands = groupSection.getStringList("commands");
            List<String> tabCompletes = groupSection.getStringList("tabcompletes");
            List<String> inheritedCommands = groupSection.getStringList("inherits.commands");
            List<String> inheritedTabCompletes = groupSection.getStringList("inherits.tabcompletes");

            rawGroups.put(groupName.toLowerCase(Locale.ROOT), new RawCommandGroup(
                    groupName,
                    whitelist,
                    commands,
                    tabCompletes,
                    inheritedCommands,
                    inheritedTabCompletes
            ));
        }

        Map<String, CommandGroup> resolvedGroups = new LinkedHashMap<>();
        for (String groupName : rawGroups.keySet()) {
            CommandGroup group = resolveGroup(groupName, rawGroups, resolvedGroups, new HashSet<>());
            commandGroups.put(groupName, group);

            if (plugin.getConfig().getBoolean("debug-mode")) {
                Text.sendDebugLog(INFO, "Loaded command group: " + group.name() +
                        " (whitelist: " + group.whitelist() +
                        ", commands: " + group.commands().size() +
                        ", tabcompletes: " + group.tabCompletes().size() + ")");
            }
        }
    }

    private CommandGroup resolveGroup(String groupName,
                                      Map<String, RawCommandGroup> rawGroups,
                                      Map<String, CommandGroup> resolvedGroups,
                                      Set<String> resolving) {
        String lowerGroup = groupName.toLowerCase(Locale.ROOT);
        CommandGroup cached = resolvedGroups.get(lowerGroup);
        if (cached != null) {
            return cached;
        }

        RawCommandGroup rawGroup = rawGroups.get(lowerGroup);
        if (rawGroup == null) {
            return new CommandGroup(groupName, true, List.of(), List.of(), hideNamespacedCommandsForBypass);
        }

        if (!resolving.add(lowerGroup)) {
            Text.sendDebugLog(WARN, "Detected recursive hide.yml inheritance for group: " + groupName);
            return new CommandGroup(rawGroup.name(), rawGroup.whitelist(), List.of(), List.of(), hideNamespacedCommandsForBypass);
        }

        LinkedHashSet<String> resolvedCommands = new LinkedHashSet<>(rawGroup.commands());
        LinkedHashSet<String> resolvedTabCompletes = new LinkedHashSet<>(rawGroup.tabCompletes());

        for (String inheritedGroupName : getInheritedCommandGroupsFor(rawGroup, rawGroups.keySet())) {
            CommandGroup inheritedGroup = resolveGroup(inheritedGroupName, rawGroups, resolvedGroups, resolving);
            resolvedCommands.addAll(inheritedGroup.commands());
        }
        for (String inheritedGroupName : getInheritedTabCompleteGroupsFor(rawGroup, rawGroups.keySet())) {
            CommandGroup inheritedGroup = resolveGroup(inheritedGroupName, rawGroups, resolvedGroups, resolving);
            resolvedTabCompletes.addAll(inheritedGroup.tabCompletes());
        }

        if (resolvedTabCompletes.remove("^")) {
            resolvedTabCompletes.addAll(resolvedCommands);
        }

        List<String> expandedCommands = expandWildcardCommands(new ArrayList<>(resolvedCommands));
        List<String> expandedTabCompletes = expandWildcardCommands(new ArrayList<>(resolvedTabCompletes));

        CommandGroup resolved = new CommandGroup(
                rawGroup.name(),
                rawGroup.whitelist(),
                expandedCommands,
                expandedTabCompletes,
                hideNamespacedCommandsForBypass
        );
        resolvedGroups.put(lowerGroup, resolved);
        resolving.remove(lowerGroup);
        return resolved;
    }

    private Set<String> getInheritedCommandGroupsFor(RawCommandGroup rawGroup, Set<String> configuredGroups) {
        return getScopedInheritedGroups(rawGroup.name(), rawGroup.inheritedCommands(), configuredGroups);
    }

    private Set<String> getInheritedTabCompleteGroupsFor(RawCommandGroup rawGroup, Set<String> configuredGroups) {
        return getScopedInheritedGroups(rawGroup.name(), rawGroup.inheritedTabCompletes(), configuredGroups);
    }

    private Set<String> getScopedInheritedGroups(String groupName, List<String> explicitGroups, Set<String> configuredGroups) {
        LinkedHashSet<String> inheritedGroups = new LinkedHashSet<>();

        for (String inherited : explicitGroups) {
            if (inherited != null && !inherited.isBlank()) {
                inheritedGroups.add(inherited.toLowerCase(Locale.ROOT));
            }
        }

        int currentWeight = getGroupWeight(groupName);
        for (String parentGroup : getInheritedPermissionGroups(groupName)) {
            if (!configuredGroups.contains(parentGroup)) {
                continue;
            }
            if (getGroupWeight(parentGroup) <= currentWeight) {
                inheritedGroups.add(parentGroup);
            }
        }

        inheritedGroups.remove(groupName.toLowerCase(Locale.ROOT));
        return inheritedGroups;
    }

    private int getGroupWeight(String groupName) {
        try {
            if (groupName == null || groupName.isBlank() || groupName.equalsIgnoreCase("default")) {
                return 0;
            }
            if (plugin.getVaultChat() == null) {
                return 0;
            }
            String weightStr = plugin.getVaultChat().getGroupInfoString((String) null, groupName, "weight", "0");
            return Integer.parseInt(weightStr);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Set<String> getInheritedPermissionGroups(String groupName) {
        LinkedHashSet<String> parents = new LinkedHashSet<>();
        collectLuckPermsParentGroups(groupName.toLowerCase(Locale.ROOT), parents, new HashSet<>());
        return parents;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void collectLuckPermsParentGroups(String groupName, Set<String> output, Set<String> visited) {
        if (groupName == null || groupName.isBlank() || !visited.add(groupName)) {
            return;
        }

        try {
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckPerms = Bukkit.getServicesManager().load((Class) luckPermsClass);
            if (luckPerms == null) {
                return;
            }

            Object groupManager = luckPerms.getClass().getMethod("getGroupManager").invoke(luckPerms);
            Object group = groupManager.getClass().getMethod("getGroup", String.class).invoke(groupManager, groupName);
            if (group == null) {
                return;
            }

            Class<?> nodeTypeClass = Class.forName("net.luckperms.api.node.NodeType");
            Object inheritanceNodeType = nodeTypeClass.getField("INHERITANCE").get(null);
            Collection<?> inheritanceNodes = (Collection<?>) group.getClass()
                    .getMethod("getNodes", nodeTypeClass)
                    .invoke(group, inheritanceNodeType);

            for (Object node : inheritanceNodes) {
                String parentName = (String) node.getClass().getMethod("getGroupName").invoke(node);
                if (parentName == null || parentName.isBlank()) {
                    continue;
                }
                String lowerParent = parentName.toLowerCase(Locale.ROOT);
                if (output.add(lowerParent)) {
                    collectLuckPermsParentGroups(lowerParent, output, visited);
                }
            }
        } catch (Throwable ignored) {
            // LuckPerms not present or API changed; fall back to explicit hide.yml inheritance only.
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

    private String resolveFullCommandName(String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }

        String lowerCommand = command.toLowerCase(Locale.ROOT);
        if (lowerCommand.contains(":")) {
            return lowerCommand;
        }

        Map<String, Command> knownCommands = getKnownCommands();

        Command directCommand = knownCommands.get(lowerCommand);
        if (directCommand != null) {
            String pluginName = getPluginForCommand(directCommand, lowerCommand);
            String namespaced = pluginName + ":" + lowerCommand;
            if (knownCommands.containsKey(namespaced)) {
                return namespaced;
            }
        }

        for (String key : knownCommands.keySet()) {
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            int colonIndex = lowerKey.indexOf(':');
            if (colonIndex == -1) {
                continue;
            }
            String baseCommand = lowerKey.substring(colonIndex + 1);
            if (baseCommand.equals(lowerCommand)) {
                return lowerKey;
            }
        }

        return lowerCommand;
    }

    private String getPluginForCommand(Command command, String commandName) {
        if (command instanceof PluginIdentifiableCommand identifiableCommand) {
            Plugin owningPlugin = identifiableCommand.getPlugin();
            if (owningPlugin != null) {
                return owningPlugin.getName().toLowerCase(Locale.ROOT);
            }
        }

        Map<String, Command> knownCommands = getKnownCommands();
        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() != command || !key.contains(":")) {
                continue;
            }
            return key.substring(0, key.indexOf(':')).toLowerCase(Locale.ROOT);
        }

        Package commandPackage = command.getClass().getPackage();
        if (commandPackage != null) {
            String packageName = commandPackage.getName();
            if (packageName.startsWith("org.bukkit.command.defaults") || packageName.startsWith("net.minecraft")) {
                return "minecraft";
            }
        }

        int colonIndex = commandName.indexOf(':');
        if (colonIndex != -1) {
            return commandName.substring(0, colonIndex).toLowerCase(Locale.ROOT);
        }

        return commandName.toLowerCase(Locale.ROOT);
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
            Text.sendDebugLog(ERROR, "Failed to access server command map", e);
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
            return player.hasPermission("group." + groupName);
        }

        try {
            // Check if the player is in the specified group using Vault
            return vaultPermission.hasGroupSupport() && vaultPermission.playerInGroup(player, groupName);
        } catch (Exception e) {
            return player.hasPermission("group." + groupName);
        }
    }

    private String findSimilarCommand(Player player, String attemptedCommand) {
        Collection<String> availableCommands = new ArrayList<>();

        Bukkit.getServer().getCommandMap().getKnownCommands().keySet().stream()
                .map(String::toLowerCase)
                .filter(cmd -> shouldShowCommand(player, cmd))
                .filter(cmd -> {
                    // Filter out namespaced commands for regular players and optionally ops based on config
                    if (cmd.contains(":")) {
                        if (player.hasPermission("allium.hide.bypass") || player.isOp()) {
                            return !hideNamespacedCommandsForBypass; // hide from suggestions if configured
                        }
                        return false;
                    }
                    return true;
                })
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

        if (player.hasPermission("allium.hide.bypass")) {
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



@EventHandler(priority = EventPriority.HIGH)
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
        
        // Special handling for namespaced commands
        // If it's a namespaced command and the player can use the base command, allow it
        if (isNamespacedCommand && !canUseCommand) {
            String baseCommand = fullCommand.substring(fullCommand.indexOf(':') + 1);
            for (CommandGroup group : playerGroups) {
                if (group.commands().contains("*") && group.whitelist()) {
                    canUseCommand = true;
                    break;
                }
                
                boolean baseCommandInList = group.commands().contains(baseCommand);
                if (group.whitelist() ? baseCommandInList : !baseCommandInList) {
                    canUseCommand = true;
                    break;
                }
            }
        }

        if (!canUseCommand) {
            event.setCancelled(true);
            sendUnknownCommandMessage(player, fullCommand, isNamespacedCommand);
            return;
        }

        Map<String, Command> knownCommands = getKnownCommands();
        Command resolvedCommand = resolveCommandForPermissionCheck(knownCommands, fullCommand);

        if (resolvedCommand == null && isNamespacedCommand) {
            String baseCommandName = fullCommand.substring(fullCommand.indexOf(':') + 1);
            resolvedCommand = resolveCommandForPermissionCheck(knownCommands, baseCommandName);
        }

        if (resolvedCommand == null) {
            event.setCancelled(true);
            sendUnknownCommandMessage(player, fullCommand, isNamespacedCommand);
            return;
        }

        if (!resolvedCommand.testPermissionSilent(player)) {
            event.setCancelled(true);
            sendUnknownCommandMessage(player, fullCommand, isNamespacedCommand);
        }
    }


    /**
     * Updates the tab completion list for a specific player without touching gamemode.
     * Prefer the native API, then fall back to packet-based refresh. No GM toggles.
     */
    public void updatePlayerTabCompletion(Player player) {
        // Skip processing if command manager is disabled
        if (!enabled) {
            return;
        }

        // Build and process PlayerCommandSendEvent so our hide rules apply
        List<String> allCommands = new ArrayList<>(Bukkit.getCommandMap().getKnownCommands().keySet());
        PlayerCommandSendEvent event = new PlayerCommandSendEvent(player, allCommands);
        onCommandSend(event);

        // Try Paper/Spigot native API first
        try {
            player.updateCommands();
            if (plugin.getConfig().getBoolean("debug-mode")) {
                Text.sendDebugLog(INFO, "Updated tab completion for " + player.getName() + " (native API)");
            }
            return;
        } catch (Throwable ignored) {
            // Fall back to packet-based approach below
        }

        // Packet-based fallback
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
                Text.sendDebugLog(INFO, "Updated tab completion for " + player.getName() + " with " + event.getCommands().size() + " commands (packet)");
            }
        } catch (Exception e) {
            // As a last resort, log the failure; do NOT change gamemode
            Text.sendDebugLog(WARN, "Failed to update tab completion for " + player.getName() + ": " + e.getMessage());
        }
    }



    private void sendUnknownCommandMessage(Player player, String command, boolean isNamespacedCommand) {
        if (isNamespacedCommand) {
            Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", command);
            return;
        }

        String similar = findSimilarCommand(player, command);

        if (similar != null) {
            Text.sendErrorMessage(player, "unknown-command-suggestion", lang,
                    "{cmd}", command,
                    "{suggestion}", similar);
        } else {
            Text.sendErrorMessage(player, "unknown-command", lang, "{cmd}", command);
        }
    }

    private Command resolveCommandForPermissionCheck(Map<String, Command> knownCommands, String commandName) {
        if (commandName == null || commandName.isEmpty()) {
            return null;
        }

        Command command = knownCommands.get(commandName);
        if (command != null) {
            return command;
        }

        if (!commandName.contains(":")) {
            String lowerCommand = commandName.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }

                int colonIndex = key.indexOf(':');
                if (colonIndex == -1) {
                    continue;
                }

                String baseCommand = key.substring(colonIndex + 1);
                if (baseCommand.equals(lowerCommand)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }



    // Helper method to get NMS classes - adjust for your server version

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
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();

        if (player.hasPermission("allium.hide.bypass")) {
            if (plugin.getConfig().getBoolean("debug-mode")) {
                Text.sendDebugLog(INFO, "Bypassing command filtering for " + player.getName() + " due to allium.hide.bypass");
            }
            // Bypass users see all commands - no filtering
            return;
        }

        List<CommandGroup> groups = getPlayerGroups(player);

        if (groups.isEmpty()) {
            return;
        }

        boolean hasWhitelistGroup = groups.stream().anyMatch(CommandGroup::whitelist);

        Set<String> filteredCommands = new LinkedHashSet<>();
        for (String command : event.getCommands()) {
            if (command == null) {
                continue;
            }

            String lowerCommand = command.toLowerCase(Locale.ROOT);

            if (lowerCommand.contains(":")) {
                if (player.isOp()) {
                    if (hideNamespacedCommandsForBypass) {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            boolean allowed;
            if (hasWhitelistGroup) {
                allowed = false;
                for (CommandGroup group : groups) {
                    if (group.whitelist() && group.isCommandAllowed(lowerCommand)) {
                        allowed = true;
                        break;
                    }
                }
            } else {
                allowed = true;
                for (CommandGroup group : groups) {
                    if (!group.isCommandAllowed(lowerCommand)) {
                        allowed = false;
                        break;
                    }
                }
            }

            if (allowed) {
                filteredCommands.add(command);
            }
        }

        event.getCommands().clear();
        event.getCommands().addAll(filteredCommands);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTabComplete(TabCompleteEvent event) {
        if (event.isCancelled() || !enabled) return;
        if (!(event.getSender() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getSender();

        if (player.hasPermission("allium.hide.bypass")) {
            if (!hideNamespacedCommandsForBypass) {
                return;
            }
            // else fall through to hide namespaced entries for bypass per config
        }

        String buffer = event.getBuffer();

        if (!buffer.startsWith("/")) {
            return;
        }

        String commandString = buffer.substring(1).split(" ")[0];

        // Filter out namespaced commands for non-bypass players
        if (commandString.contains(":")) {
            if (player.isOp()) {
                if (hideNamespacedCommandsForBypass) {
                    event.setCompletions(new ArrayList<>());
                    return;
                }
            } else if (!player.hasPermission("allium.hide.bypass")) {
                event.setCompletions(new ArrayList<>());
                return;
            }
        }

        if (!shouldAllowTabComplete(player, commandString)) {
            event.setCompletions(new ArrayList<>());
        }
    }

    /**
     * Refresh a player's command visibility and tab completions, temporarily bypassing
     * inventory management side-effects. This body is safe to be executed on the
     * player's entity scheduler (Folia) or the main thread on non-Folia.
     */
    private void refreshPlayerCommandState(Player player) {
        try {
            if (creativeManager != null) {
                creativeManager.setBypassInventoryManagement(player, true);
            }

            // Trigger a command send event to refresh the player's available commands
            List<String> allCommands = new ArrayList<>(Bukkit.getCommandMap().getKnownCommands().keySet());
            PlayerCommandSendEvent refreshEvent = new PlayerCommandSendEvent(player, allCommands);
            onCommandSend(refreshEvent);

            // Also refresh tab completion to ensure immediate updates
            updatePlayerTabCompletion(player);
        } finally {
            if (creativeManager != null) {
                creativeManager.setBypassInventoryManagement(player, false);
            }
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
            if (tabCompletes.contains(commandName)) {
                return whitelist;
            }
            String baseCommand = commandName.contains(":")
                    ? commandName.substring(commandName.indexOf(':') + 1)
                    : commandName;
            if (tabCompletes.contains(baseCommand)) {
                return whitelist;
            }
            if (tabCompletes.contains("*")) {
                return whitelist;
            }
            return !whitelist;
        }
    }

    private record RawCommandGroup(String name, boolean whitelist, List<String> commands,
                                   List<String> tabCompletes, List<String> inheritedCommands,
                                   List<String> inheritedTabCompletes) {

        private RawCommandGroup(String name, boolean whitelist, List<String> commands, List<String> tabCompletes,
                                List<String> inheritedCommands, List<String> inheritedTabCompletes) {
            this.name = name;
            this.whitelist = whitelist;
            this.commands = normalize(commands);
            this.tabCompletes = normalize(tabCompletes);
            this.inheritedCommands = normalize(inheritedCommands);
            this.inheritedTabCompletes = normalize(inheritedTabCompletes);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null) {
                return List.of();
            }

            return values.stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();
        }
    }
}
