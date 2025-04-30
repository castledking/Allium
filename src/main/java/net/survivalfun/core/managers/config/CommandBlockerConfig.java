package net.survivalfun.core.managers.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandBlockerConfig {

    private final JavaPlugin plugin;
    private Map<String, List<String>> groupBlockedCommands;
    private Map<String, List<String>> groupBlockedTabComplete;
    private String blockedCommandMessage;
    private File configFile;
    private FileConfiguration config;
    private boolean blockNamespace;
    private boolean blockVanillaCommands;// New field for namespace blocking

    public CommandBlockerConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.groupBlockedCommands = new HashMap<>();
        this.groupBlockedTabComplete = new HashMap<>();

        configFile = new File(plugin.getDataFolder(), "hide.yml");
        saveDefaultConfig();
        loadConfig();
    }


    public void loadConfig() {
        this.config = YamlConfiguration.loadConfiguration(configFile);


        // Load the block-namespace option
        blockNamespace = config.getBoolean("block-namespace", true);
        blockVanillaCommands = config.getBoolean("block-vanilla-commands", true);

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

                // General options
                config.set("block-namespace", true);
                config.set("block-vanilla-commands", true);

                // Messages
                config.set("messages.blocked-command", "§cThis command has been blocked by the server administrator.");

                // Group section
                config.set("groups.default.commands", List.of(
                        "gamemode", "gm", "minecraft:gamemode",
                        "fly", "god"
                ));

                config.set("groups.default.tab-complete", List.of(
                        "^"
                ));



                // Save config
                config.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create default CommandBlocker config: " + e.getMessage());
            }
        }
    }

    public boolean isCommandBlocked(Player player, String command) {
        // Check for bypass permission
        if (player.hasPermission("core.hide.bypass")) {
            return false;
        }

        // Check if namespace commands are blocked and if this is a namespace command
        if (blockNamespace && command.contains(":")) {
            return true;
        }

        // Check permissions-based command blocking
        for (String group : groupBlockedCommands.keySet()) {
            // Only check groups where the player has the specific hide permission
            if (player.hasPermission("core.hide." + group)) {
                if (groupBlockedCommands.get(group).contains(command)) {
                    return true;
                }
            }
        }

        return false;
    }


    public boolean isTabCompleteBlocked(Player player, String command) {
        // Check for bypass permission
        if (player.hasPermission("core.hide.bypass")) {
            return false;
        }

        // Check if namespace commands are blocked and if this is a namespace command
        if (blockNamespace) {
            // Block if the command already contains a namespace
            if (command.contains(":")) {
                return true;
            }

            // Block if this could be the start of a namespace command
            // This handles the case where a player is typing the namespace part but hasn't reached the colon yet
            for (String knownNamespace : getKnownNamespaces()) {
                if (knownNamespace.startsWith(command.toLowerCase())) {
                    return true;
                }
            }
        }

        // Check permissions-based tab-complete blocking
        for (String group : groupBlockedTabComplete.keySet()) {
            // Only check groups where the player has the specific hide permission
            if (player.hasPermission("core.hide." + group)) {
                List<String> blockedTabComplete = groupBlockedTabComplete.get(group);

                // Direct match
                if (blockedTabComplete.contains(command)) {
                    return true;
                }

                // Check for "^" token - include all commands blocked for this group
                if (blockedTabComplete.contains("^") &&
                        groupBlockedCommands.containsKey(group) &&
                        groupBlockedCommands.get(group).contains(command)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get a list of known plugin namespaces on the server
     * This can be cached and refreshed periodically to improve performance
     */
    private List<String> getKnownNamespaces() {
        List<String> namespaces = new ArrayList<>();

        // Add known plugin namespaces from server plugins
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            namespaces.add(plugin.getName().toLowerCase());
        }

        // Add minecraft namespace
        namespaces.add("minecraft");

        return namespaces;
    }




    public String getBlockedCommandMessage() {
        return blockedCommandMessage;
    }

    public boolean shouldBlockVanillaCommands() { return blockVanillaCommands; }


    public void reloadConfig() {
        loadConfig();
    }
}