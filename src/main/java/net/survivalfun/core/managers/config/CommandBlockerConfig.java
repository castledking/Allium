package net.survivalfun.core.managers.config;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.survivalfun.core.managers.core.Luck;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandBlockerConfig {

    private final JavaPlugin plugin;
    private final Luck luck;
    private List<String> globalBlockedCommands;
    private List<String> globalBlockedTabComplete;
    private Map<String, List<String>> groupBlockedCommands;
    private Map<String, List<String>> groupBlockedTabComplete;
    private String blockedCommandMessage;
    private File configFile;
    private FileConfiguration config;

    public CommandBlockerConfig(JavaPlugin plugin, Luck luck) {
        this.plugin = plugin;
        this.luck = luck;
        this.groupBlockedCommands = new HashMap<>();
        this.groupBlockedTabComplete = new HashMap<>();

        // Create the config file if it doesn't exist
        this.configFile = new File(plugin.getDataFolder(), "hide.yml");
        if (!configFile.exists()) {
            plugin.saveResource("hide.yml", false);
        }

        loadConfig();
    }

    public void loadConfig() {
        this.config = YamlConfiguration.loadConfiguration(configFile);

        // Load global blocked commands
        globalBlockedCommands = config.getStringList("global.commands");
        globalBlockedTabComplete = config.getStringList("global.tab-complete");

        // Load group-specific blocked commands
        groupBlockedCommands.clear();
        groupBlockedTabComplete.clear();

        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        if (groupsSection != null) {
            for (String group : groupsSection.getKeys(false)) {
                groupBlockedCommands.put(group,
                        config.getStringList("groups." + group + ".commands"));
                groupBlockedTabComplete.put(group,
                        config.getStringList("groups." + group + ".tab-complete"));
            }
        }

        // Load messages
        blockedCommandMessage = config.getString("messages.blocked-command",
                "§cThis command is blocked.");
    }

    public void saveDefaultConfig() {
        if (!configFile.exists()) {
            try {
                // Create default config
                config = new YamlConfiguration();

                // Global section
                config.set("global.commands", List.of(
                        "op", "minecraft:op", "bukkit:op",
                        "deop", "minecraft:deop",
                        "plugins", "pl", "bukkit:plugins", "bukkit:pl",
                        "ver", "version", "about"
                ));

                config.set("global.tab-complete", List.of(
                        "op", "minecraft:op", "bukkit:op",
                        "deop", "minecraft:deop",
                        "plugins", "pl", "bukkit:plugins", "bukkit:pl"
                ));

                // Group section
                config.set("groups.default.commands", List.of(
                        "gamemode", "gm", "minecraft:gamemode",
                        "fly", "god"
                ));

                config.set("groups.default.tab-complete", List.of(
                        "gamemode", "gm", "minecraft:gamemode",
                        "fly", "god"
                ));

                config.set("groups.moderator.commands", List.of(
                        "stop", "reload"
                ));

                config.set("groups.moderator.tab-complete", List.of(
                        "stop", "reload"
                ));

                // Messages
                config.set("messages.blocked-command", "§cThis command has been blocked by the server administrator.");

                // Save config
                config.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create default CommandBlocker config: " + e.getMessage());
            }
        }
    }

    public boolean isCommandBlocked(Player player, String command) {
        // First check global blocked commands (this part should work fine)
        if (globalBlockedCommands.contains(command.toLowerCase())) {
            return true;
        }

        // Now safely check group-based blocking
        if (luck != null && luck.isLuckPermsAvailable()) {
            // Use the Luck class methods directly instead of accessing LuckPerms API
            List<String> playerGroups = luck.getPlayerGroups(player);
            for (String group : playerGroups) {
                if (groupBlockedCommands.containsKey(group) &&
                        groupBlockedCommands.get(group).contains(command.toLowerCase())) {
                    return true;
                }
            }
        } else if (luck != null) {
            // Fallback to direct LuckPerms usage if you still have the old implementation
            try {
                User user = luck.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    // Check each group the user is in
                    for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
                        String groupName = group.getName();
                        if (groupBlockedCommands.containsKey(groupName) &&
                                groupBlockedCommands.get(groupName).contains(command.toLowerCase())) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error checking groups for command blocking: " + e.getMessage());
            }
        }

        return false;
    }


    public boolean isTabCompleteBlocked(Player player, String command) {
        // Check for bypass permission
        if (player.hasPermission("core.commandblocker.bypass")) {
            return false;
        }

        // Check global blocked tab complete first
        if (globalBlockedTabComplete.contains(command)) {
            return true;
        }

        // Check group-specific blocked tab complete
        // In your CommandBlockerConfig class where you need the UserManager
        UserManager userManager = luck.getUserManager();
        if (userManager != null) {
            User user = userManager.getUser(player.getUniqueId());
            if (user != null) {
                for (String group : groupBlockedTabComplete.keySet()) {
                    if (user.getNodes().stream().anyMatch(node ->
                            node.getKey().startsWith("group." + group))) {
                        if (groupBlockedTabComplete.get(group).contains(command)) {
                            return true;
                        }
                    }
                }
            }
        }


        return false;
    }

    public String getBlockedCommandMessage() {
        return blockedCommandMessage;
    }

    public void reloadConfig() {
        loadConfig();
    }
}