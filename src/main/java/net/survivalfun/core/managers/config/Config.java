package net.survivalfun.core.managers.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class Config {
    private final File configFile;
    private final Map<String, Object> defaultConfigValues = new HashMap<>();
    private final JavaPlugin plugin;
    private static final String CURRENT_VERSION = "0.1.3a";

    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
        File pluginFolder = plugin.getDataFolder();
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin folder");
        }

        this.configFile = new File(pluginFolder, "config.yml");
        initializeDefaultConfigValues();
        load();
    }

    private void initializeDefaultConfigValues() {
        // Basic settings
        defaultConfigValues.put("lang", "en");
        defaultConfigValues.put("server-name", "Unknown Server");
        defaultConfigValues.put("group_migration_completed", false);
        defaultConfigValues.put("player_migration_completed", false);
        
        // Discord settings
        defaultConfigValues.put("discord.escalate_webhook", "");
        defaultConfigValues.put("discord.escalate_mentions", new ArrayList<>());
        defaultConfigValues.put("discord.bot_name", "Server Escalation");
        defaultConfigValues.put("discord.bot_avatar", "");
        
        // Maintenance settings
        defaultConfigValues.put("maintenance.bypass", new ArrayList<>());
        
        // Chat formatting settings
        defaultConfigValues.put("enable-chat-formatting", true);
        defaultConfigValues.put("block-unicode", true);
        defaultConfigValues.put("chat-format.default", "<prefix> &a<player>&f: &f<message>");
        
        // Creative manager settings
        defaultConfigValues.put("creative-manager.blacklist.blocks", new ArrayList<>());
        defaultConfigValues.put("creative-manager.blacklist.entities", new ArrayList<>());
        
        // General settings
        defaultConfigValues.put("creeper-explosion-regen-speed", 5);
        defaultConfigValues.put("allow-unsafe-enchants", false);
        
        // Players to redeem settings
        List<String> defaultPlayersToRedeem = new ArrayList<>();
        defaultPlayersToRedeem.add("00000000-0000-0000-0000-000000000001:iron");
        defaultPlayersToRedeem.add("00000000-0000-0000-0000-000000000002:gold");
        defaultConfigValues.put("redeem-settings.players-to-redeem", defaultPlayersToRedeem);
        
        // Redeem settings - commands to run for each rank
        List<String> ironCommands = new ArrayList<>();
        ironCommands.add("lp user {player} parent set iron");
        ironCommands.add("minecraft:xp add {player} 100 points");
        ironCommands.add("money give {player} 500");
        defaultConfigValues.put("redeem-settings.commands-to-run.iron", ironCommands);

        List<String> goldCommands = new ArrayList<>();
        goldCommands.add("lp user {player} parent set gold");
        goldCommands.add("minecraft:xp add {player} 250 points");
        goldCommands.add("money give {player} 1500");
        defaultConfigValues.put("redeem-settings.commands-to-run.gold", goldCommands);

        List<String> emeraldCommands = new ArrayList<>();
        emeraldCommands.add("lp user {player} parent set emerald");
        emeraldCommands.add("minecraft:xp add {player} 500 points");
        emeraldCommands.add("money give {player} 3000");
        defaultConfigValues.put("redeem-settings.commands-to-run.emerald", emeraldCommands);

        List<String> netheriteCommands = new ArrayList<>();
        netheriteCommands.add("lp user {player} parent set netherite");
        netheriteCommands.add("minecraft:xp add {player} 1000 points");
        netheriteCommands.add("money give {player} 5000");
        defaultConfigValues.put("redeem-settings.commands-to-run.netherite", netheriteCommands);
        
        // Give settings
        defaultConfigValues.put("give.drop-overflow-items", true);
        defaultConfigValues.put("give.max-items-dropped", 64);
        
        // Explode command settings
        defaultConfigValues.put("explode-command.default", 2);
        defaultConfigValues.put("explode-command.max", 10);
        defaultConfigValues.put("explode-command.min", 1);
        
        // Command cooldown and settings
        defaultConfigValues.put("heal.cooldown", 60);
        defaultConfigValues.put("home.cooldown", 60);
        defaultConfigValues.put("home.show-location", false);
        defaultConfigValues.put("home.spawn-if-no-home", true);
        defaultConfigValues.put("feed.cooldown", 60);
        defaultConfigValues.put("teleport.delay", 3);
        defaultConfigValues.put("teleport.cooldown", 30);
        defaultConfigValues.put("teleport.expire", 120);
        defaultConfigValues.put("teleport.tppet-radius", 30);
        
        // Economy settings
        defaultConfigValues.put("economy.currency-symbol", "$");
        defaultConfigValues.put("economy.starting-balance", 100.00);
        defaultConfigValues.put("economy.decimal-places", 2);
        defaultConfigValues.put("economy.symbol-before-amount", true);
        defaultConfigValues.put("economy.space-between", false);
        
        // World-specific defaults
        defaultConfigValues.put("world-defaults.world.send-command-feedback", false);
        
        // Debug settings
        defaultConfigValues.put("debug-mode", true);
        
        // Version
        defaultConfigValues.put("version", CURRENT_VERSION);
    }

    public void load() {
        plugin.getLogger().info("Loading configuration...");
        if (!configFile.exists()) {
            createDefaultConfig();
            plugin.getLogger().info("Created new config.yml");
        } else {
            plugin.reloadConfig();
            updateConfig();
            handleVersionChanges();
        }
        if (plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().info("Configuration loaded from disk");
        }
    }

    private void handleVersionChanges() {
        FileConfiguration config = plugin.getConfig();
        String configVersion = config.getString("version", "0.1.3a");
        
        plugin.getLogger().info("Config version: " + configVersion + ", Current: " + CURRENT_VERSION);
        performChangesForVersion(configVersion); // Always run for testing
        
        config.set("version", CURRENT_VERSION);
        try {
            config.save(configFile);
            plugin.reloadConfig();
            plugin.getLogger().info("Config updated to version " + CURRENT_VERSION);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save version-updated config", e);
        }
    }

    /**
     * Performs version-specific changes to the configuration.
     * This method should be updated whenever a new version requires config changes.
     * 
     * @param fromVersion The version the config is being upgraded from
     */
    private void performChangesForVersion(String fromVersion) {
        FileConfiguration config = plugin.getConfig();
        boolean configChanged = false;
        
        // Handle changes for version 0.1.3a (first version)
        if ("0.1.3a".equals(CURRENT_VERSION)) {
            configChanged |= changeEcoToMoney(config);
            configChanged |= removeTeleportAutoDisable(config);
        }
        
        // Add future version handling here
        // Example:
        // if (isVersionOlderThan(fromVersion, "0.1.4")) {
        //     configChanged |= performChangesFor014(config);
        // }
        
        if (configChanged) {
            plugin.getLogger().info("Applied version-specific changes for " + CURRENT_VERSION);
        }
    }

    private boolean changeEcoToMoney(FileConfiguration config) {
        plugin.getLogger().info("Starting eco to money command conversion...");
        boolean changed = false;
        String[] ranks = {"iron", "gold", "emerald", "netherite"};
        String pathPrefix = "redeem-settings.commands-to-run."; // Only check this path
    
        for (String rank : ranks) {
            String path = pathPrefix + rank;
            List<String> commands = config.getStringList(path);
            
            plugin.getLogger().info("Checking path: " + path + ", Commands: " + commands);
            if (commands == null || commands.isEmpty()) {
                plugin.getLogger().warning("No commands found for path: " + path);
                continue;
            }
            
            List<String> updatedCommands = new ArrayList<>();
            
            for (String command : commands) {
                if (command.toLowerCase().contains("eco give")) {
                    String updatedCommand = command.replaceAll("(?i)eco give", "money give");
                    updatedCommands.add(updatedCommand);
                    changed = true;
                    plugin.getLogger().info("Updated command for " + rank + ": " + command + " -> " + updatedCommand);
                } else {
                    updatedCommands.add(command);
                }
            }
            
            if (changed) {
                config.set(path, updatedCommands);
                plugin.getLogger().info("Updated commands for " + path + ": " + updatedCommands);
            }
        }
        
        // Remove commands-to-run section if it exists
        if (config.contains("commands-to-run")) {
            config.set("commands-to-run", null);
            changed = true;
            plugin.getLogger().info("Removed deprecated commands-to-run section");
        }
        
        if (changed) {
            plugin.getLogger().info("Eco to money conversion completed with changes.");
        } else {
            plugin.getLogger().info("No eco to money changes were needed.");
        }
        
        return changed;
    }

    /**
     * Removes the teleport auto-disable section from the configuration.
     * This is specific to version 0.1.3a.
     * 
     * @param config The configuration to modify
     * @return true if any changes were made
     */
    private boolean removeTeleportAutoDisable(FileConfiguration config) {
        boolean changed = false;
        String autoDisablePath = "teleport.auto-disable";
        
        if (config.contains(autoDisablePath)) {
            config.set(autoDisablePath, null);
            changed = true;
            plugin.getLogger().info("Removed deprecated teleport.auto-disable section");
        }
        
        // Also check for individual settings in case they exist separately
        String[] autoDisableSettings = {
            "teleport.auto-disable.enabled",
            "teleport.auto-disable.pets-enabled"
        };
        
        for (String setting : autoDisableSettings) {
            if (config.contains(setting)) {
                config.set(setting, null);
                changed = true;
                plugin.getLogger().info("Removed deprecated setting: " + setting);
            }
        }
        
        return changed;
    }

    /**
     * Utility method to compare version strings.
     * This is a simple comparison - you might want to implement a more sophisticated
     * version comparison if you use semantic versioning.
     * 
     * @param version1 First version to compare
     * @param version2 Second version to compare
     * @return true if version1 is older than version2
     */
    private boolean isVersionOlderThan(String version1, String version2) {
        // Simple string comparison for now
        // You could implement proper semantic version comparison here
        return version1.compareTo(version2) < 0;
    }

    private void createDefaultConfig() {
        try {
            // First try to copy the config from the plugin's resources
            InputStream templateStream = plugin.getResource("config.yml");
            if (templateStream != null) {
                // Copy the template file to the plugin folder
                Files.copy(templateStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                templateStream.close();
                plugin.getLogger().info("Created config.yml from template with all comments preserved");
            } else {
                // If no template exists, create a basic config
                if (!configFile.createNewFile()) {
                    throw new IOException("Could not create config.yml");
                }

                // Create a new config with default values
                FileConfiguration config = new YamlConfiguration();
                for (Map.Entry<String, Object> entry : defaultConfigValues.entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }

                // Add the commented-out groups section manually
                String commentedGroups =
                        "  # Group-specific chat formats\n" +
                                "  # Uncomment and modify these as needed\n" +
                                "  groups:\n" +
                                "    #default: \"&7<player>&f: <message>\" # Default Group Format\n" +
                                "    #admin: \"&4[Admin] &c<player>&f: <message>\"\n" +
                                "    #moderator: \"&2[Mod] &a<player>&f: <message>\"\n" +
                                "    #vip: \"&6[VIP] &e<player>&f: <message>\"";

                // Save the config with a header comment
                config.options().header(
                        "############################################################\n" +
                                "# +------------------------------------------------------+ #\n" +
                                "# |                Allium Configuration                   | #\n" +
                                "# +------------------------------------------------------+ #\n" +
                                "############################################################\n\n" +
                                "# This is the main configuration file for Allium.\n" +
                                "# For more information, visit: https://github.com/castledking/Allium\n"
                );

                // Save the basic config
                config.save(configFile);

                // Now we need to manually add the commented groups section
                // Read the file, find the chat-format section, and insert our commented groups
                addCommentedSections(commentedGroups);

                plugin.getLogger().info("Created basic config.yml with default values and commented sections");
            }

            // Load the newly created config
            plugin.reloadConfig();

        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create config.yml", e);
            throw new RuntimeException("Failed to create config.yml", e);
        }
    }

    private void addCommentedSections(String commentedGroups) {
        try {
            // Read the current file
            String content = new String(Files.readAllBytes(configFile.toPath()));

            // Find the chat-format section
            int chatFormatIndex = content.indexOf("chat-format:");
            if (chatFormatIndex != -1) {
                // Find the end of the default line
                int defaultLineEnd = content.indexOf("\n", chatFormatIndex);
                if (defaultLineEnd != -1) {
                    // Insert our commented groups after the default line
                    String newContent = content.substring(0, defaultLineEnd + 1) +
                            commentedGroups +
                            content.substring(defaultLineEnd + 1);

                    // Write the modified content back to the file
                    Files.write(configFile.toPath(), newContent.getBytes());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to add commented sections to config.yml", e);
        }
    }

    public void updateConfig() {
        FileConfiguration config = plugin.getConfig();
        boolean updated = false;
    
        // Move players-to-redeem to redeem-settings.players-to-redeem
        if (config.contains("players-to-redeem")) {
            List<String> playersToRedeem = config.getStringList("players-to-redeem");
            if (!playersToRedeem.isEmpty()) {
                config.set("redeem-settings.players-to-redeem", playersToRedeem);
                plugin.getLogger().info("Moved players-to-redeem to redeem-settings.players-to-redeem");
                updated = true;
            }
            config.set("players-to-redeem", null);
            plugin.getLogger().info("Removed deprecated players-to-redeem section");
            updated = true;
        }
    
        // Force-add home settings
        config.set("home.show-location", false);
        updated = true;
        plugin.getLogger().info("Force-added config value: home.show-location");
        config.set("home.spawn-if-no-home", true);
        updated = true;
        plugin.getLogger().info("Force-added config value: home.spawn-if-no-home");
    
        // Check other missing values
        for (Map.Entry<String, Object> entry : defaultConfigValues.entrySet()) {
            if (!config.contains(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                updated = true;
                plugin.getLogger().info("Added missing config value: " + entry.getKey());
            }
        }
    
        if (updated) {
            try {
                config.save(configFile);
                FileConfiguration reloadedConfig = YamlConfiguration.loadConfiguration(configFile);
                plugin.getConfig().setDefaults(reloadedConfig);
                if (!reloadedConfig.contains("home.show-location") || !reloadedConfig.contains("home.spawn-if-no-home")) {
                    plugin.getLogger().warning("Home settings not found after save, possible file write issue");
                }
                if (!reloadedConfig.contains("redeem-settings.players-to-redeem")) {
                    plugin.getLogger().warning("redeem-settings.players-to-redeem not found after save, possible file write issue");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save updated config.yml", e);
            }
        } else {
            plugin.getLogger().info("No config updates needed.");
        }
    }

    public Object get(String key) {
        return plugin.getConfig().get(key, defaultConfigValues.get(key));
    }

    public boolean getBoolean(String key) {
        return plugin.getConfig().getBoolean(key,
                defaultConfigValues.containsKey(key) ? (Boolean) defaultConfigValues.get(key) : false);
    }

    public int getInt(String key) {
        return plugin.getConfig().getInt(key,
                defaultConfigValues.containsKey(key) ? (Integer) defaultConfigValues.get(key) : 0);
    }

    public String getString(String key, String s) {
        return plugin.getConfig().getString(key,
                defaultConfigValues.containsKey(key) ? (String) defaultConfigValues.get(key) : "");
    }

    /**
     * Gets the current config version
     * @return The current version string
     */
    public String getVersion() {
        return plugin.getConfig().getString("version", CURRENT_VERSION);
    }
}