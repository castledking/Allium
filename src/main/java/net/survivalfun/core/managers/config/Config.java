package net.survivalfun.core.managers.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class Config {
    private final File configFile;
    private final JavaPlugin plugin;
    private static final String CURRENT_VERSION = "0.1.4a";
    private FileConfiguration config;
    private YamlConfiguration defaultConfig;

    private static final Map<String, List<String>> SECTION_HEADERS;
    static {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("update-checker:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Update Checker                       | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("discord:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Discord Settings                     | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("maintenance:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Maintenance Settings                 | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("dialog:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Dialog Settings                      | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("enable-chat-formatting:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Chat Settings                        | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("commands:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 General Settings                     | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("give:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                   Give Settings                      | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("explode-command:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Command Settings                     | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("economy:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Economy Settings                     | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("world-defaults:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 World Settings                       | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("handcuffs:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Item Settings                        | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        headers.put("debug-mode:", Arrays.asList(
                "############################################################",
                "# +------------------------------------------------------+ #",
                "# |                 Debug Settings                       | #",
                "# +------------------------------------------------------+ #",
                "############################################################",
                ""
        ));
        SECTION_HEADERS = Collections.unmodifiableMap(headers);
    }

    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
        File pluginFolder = plugin.getDataFolder();
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin folder");
        }

        this.configFile = new File(pluginFolder, "config.yml");
        initializeDefaultConfig();
        load();
    }

    private void initializeDefaultConfig() {
        defaultConfig = null;

        InputStream defConfigStream = plugin.getResource("config.yml");
        if (defConfigStream == null) {
            Text.sendDebugLog(WARN, "Could not find default config file (config.yml) in plugin resources!");
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)) {
            defaultConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to load default config resource", e);
        }
    }

    private void restoreSectionHeaders() {
        if (!configFile.exists()) {
            return;
        }

        try {
            String original = new String(Files.readAllBytes(configFile.toPath()));
            StringBuilder rebuilt = new StringBuilder(original.length());

            String[] lines = original.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                if (line.trim().isEmpty()) {
                    rebuilt.append(line).append('\n');
                    continue;
                }

                for (Map.Entry<String, List<String>> entry : SECTION_HEADERS.entrySet()) {
                    String key = entry.getKey();
                    if (line.startsWith(key)) {
                        List<String> headerLines = entry.getValue();
                        if (i == 0 || !lines[i - 1].contains("+------------------------------------------------------+")) {
                            if (rebuilt.length() > 0 && rebuilt.charAt(rebuilt.length() - 1) != '\n') {
                                rebuilt.append('\n');
                            }
                            for (String headerLine : headerLines) {
                                rebuilt.append(headerLine).append('\n');
                            }
                        }
                        break;
                    }
                }

                rebuilt.append(line).append('\n');

                // Remove duplicate blank lines
                while (i + 1 < lines.length && lines[i + 1].trim().isEmpty()) {
                    rebuilt.append('\n');
                    i++;
                }

            }

            Files.write(configFile.toPath(), rebuilt.toString().getBytes());
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to restore section headers", e);
        }
    }

    public void load() {
        Text.sendDebugLog(INFO, "Loading configuration...");
        Text.sendDebugLog(INFO, "Config file exists: " + configFile.exists());

        if (!configFile.exists()) {
            Text.sendDebugLog(INFO, "Creating default config...");
            createDefaultConfig();
            // Load the newly created config
            plugin.reloadConfig();
            Text.sendDebugLog(INFO, "Default config created, checking for missing values...");

            // Save default values to the config
            config = plugin.getConfig();
            boolean needsSave = false;

            if (defaultConfig != null) {
                for (String key : defaultConfig.getKeys(true)) {
                    if (!config.contains(key)) {
                        Text.sendDebugLog(INFO, "Adding missing config value: " + key + " = " + defaultConfig.get(key));
                        config.set(key, defaultConfig.get(key));
                        needsSave = true;
                    }
                }
            }

            if (needsSave) {
                try {
                    Text.sendDebugLog(INFO, "Saving updated config with default values...");
                    config.save(configFile);
                    restoreSectionHeaders();
                    Text.sendDebugLog(INFO, "Successfully saved config with default values");
                    // Reload to ensure we have the latest config
                    plugin.reloadConfig();
                    // Verify chat-hover section
                    verifyChatHoverInFile();
                } catch (IOException e) {
                    Text.sendDebugLog(ERROR, "Could not save default config to " + configFile, e);
                }
            }
        } else {
            Text.sendDebugLog(INFO, "Loading existing config file...");
            // Load existing config first
            plugin.reloadConfig();
            config = plugin.getConfig();
            // Then update it with any missing defaults
            updateConfig();
            // Handle version changes
            handleVersionChanges();
        }

        if (plugin.getConfig().getBoolean("debug-mode")) {
            Text.sendDebugLog(INFO, "Configuration loaded from disk");
            // Log all chat-related config values for debugging
            config = plugin.getConfig();
            for (String key : config.getKeys(true)) {
                if (key.startsWith("chat-") || key.startsWith("hover") || key.startsWith("click")) {
                    Text.sendDebugLog(INFO, "Config value: " + key + " = " + config.get(key));
                }
            }
        }
    }

    private void handleVersionChanges() {
        FileConfiguration config = plugin.getConfig();
        String configVersion = config.getString("version", "0.0.0");
        
        Text.sendDebugLog(INFO, "Config version: " + configVersion + ", Current: " + CURRENT_VERSION);
        boolean configChanged = performChangesForVersion(configVersion);
        // Ensure version is updated when upgrading, even if no migrations ran (e.g. updateConfig already added keys)
        if (!configChanged && configVersion.compareTo(CURRENT_VERSION) < 0) {
            configChanged = true;
            Text.sendDebugLog(INFO, "Upgrading config version from " + configVersion + " to " + CURRENT_VERSION);
        }
        
        if (configChanged) {
            try {
                config.set("version", CURRENT_VERSION);
                saveConfigWithCommentsPreserved();
                // Fallback: ensure version is written (saveConfigWithCommentsPreserved may reorder keys)
                ensureVersionInFile(CURRENT_VERSION);
                plugin.reloadConfig();
                Text.sendDebugLog(INFO, "Config updated to version " + CURRENT_VERSION);
                // Verify chat-hover section after save
                verifyChatHoverInFile();
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to save version-updated config", e);
            }
        }
    }

    /**
     * Ensures the version line in the config file is updated. Fallback for when the main save
     * doesn't persist the version correctly (e.g. key ordering, format differences).
     */
    private void ensureVersionInFile(String targetVersion) {
        try {
            if (!configFile.exists()) return;
            String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            // Match version: 0.1.3a or version: "0.1.3a" etc.
            String updated = content.replaceAll("version:\\s*[\"']?0\\.1\\.3a[\"']?\\s*", "version: " + targetVersion + "\n");
            if (!updated.equals(content)) {
                Files.write(configFile.toPath(), updated.getBytes(StandardCharsets.UTF_8));
                Text.sendDebugLog(INFO, "Ensured version " + targetVersion + " in config file (fallback)");
            }
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Could not ensure version in config file: " + e.getMessage());
        }
    }

    private boolean performChangesForVersion(String fromVersion) {
        FileConfiguration config = plugin.getConfig();
        boolean configChanged = false;

        // Always migrate legacy party-manager key (hide-non-party-members -> party-locator-bar)
        configChanged |= migrateHideNonPartyMembersToPartyLocatorBar(config);
        
        // Migrate 0.1.3a (and older) configs to 0.1.4a
        if (fromVersion.compareTo("0.1.4a") < 0 || fromVersion.equals("0.0.0")) {
            Text.sendDebugLog(INFO, "Migrating config from " + fromVersion + " to 0.1.4a...");
            configChanged |= migrate0_1_3aTo0_1_4a(config);
            configChanged |= changeEcoToMoney(config);
            configChanged |= removeTeleportAutoDisable(config);
            configChanged |= addChatHoverEntries(config);
            configChanged |= addMailCooldownEntry(config);
        }
        
        if (configChanged) {
            Text.sendDebugLog(INFO, "Applied version-specific changes for " + CURRENT_VERSION);
        } else {
            Text.sendDebugLog(INFO, "No version-specific changes needed for " + fromVersion);
        }
        
        return configChanged;
    }

    private boolean changeEcoToMoney(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Starting eco to money command conversion...");
        boolean changed = false;
        String[] ranks = {"iron", "gold", "emerald", "netherite"};
        String pathPrefix = "redeem-settings.commands-to-run.";

        for (String rank : ranks) {
            String path = pathPrefix + rank;
            List<String> commands = config.getStringList(path);
            
            Text.sendDebugLog(INFO, "Checking path: " + path + ", Commands: " + commands);
            if (commands == null || commands.isEmpty()) {
                Text.sendDebugLog(WARN, "No commands found for path: " + path);
                continue;
            }
            
            List<String> updatedCommands = new ArrayList<>();
            
            for (String command : commands) {
                if (command.toLowerCase().contains("eco give")) {
                    String updatedCommand = command.replaceAll("(?i)eco give", "money give");
                    updatedCommands.add(updatedCommand);
                    changed = true;
                    Text.sendDebugLog(INFO, "Updated command for " + rank + ": " + command + " -> " + updatedCommand);
                } else {
                    updatedCommands.add(command);
                }
            }
            
            if (changed) {
                config.set(path, updatedCommands);
                Text.sendDebugLog(INFO, "Updated commands for " + path + ": " + updatedCommands);
            }
        }
        
        if (config.contains("commands-to-run")) {
            config.set("commands-to-run", null);
            changed = true;
            Text.sendDebugLog(INFO, "Removed deprecated commands-to-run section");
        }
        
        if (changed) {
            Text.sendDebugLog(INFO, "Eco to money conversion completed with changes.");
        } else {
            Text.sendDebugLog(INFO, "No eco to money changes were needed.");
        }
        
        return changed;
    }

    private boolean removeTeleportAutoDisable(FileConfiguration config) {
        boolean changed = false;
        String autoDisablePath = "teleport.auto-disable";
        
        if (config.contains(autoDisablePath)) {
            config.set(autoDisablePath, null);
            changed = true;
            Text.sendDebugLog(INFO, "Removed deprecated teleport.auto-disable section");
        }
        
        String[] autoDisableSettings = {
            "teleport.auto-disable.enabled",
            "teleport.auto-disable.pets-enabled"
        };
        
        for (String setting : autoDisableSettings) {
            if (config.contains(setting)) {
                config.set(setting, null);
                changed = true;
                Text.sendDebugLog(INFO, "Removed deprecated setting: " + setting);
            }
        }
        try {
            Text.sendDebugLog(INFO, "Saving updated config with default values...");
            config.save(configFile);
            restoreSectionHeaders();
            Text.sendDebugLog(INFO, "Successfully saved config with default values");
            // Reload to ensure we have the latest config
            plugin.reloadConfig();
            // Verify chat-hover section
            verifyChatHoverInFile();
            // Verify immediately after save
            if (verifyChatHoverInFile()) {
                changed = true;
            } else {
                Text.sendDebugLog(WARN, "Chat-hover section not found in file after save, attempting to insert after chat-format...");
                changed |= insertChatHoverAfterChatFormat(config);
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to save config with chat-hover settings", e);
            changed = false;
        }
        
        // Ensure commented groups are added
        addCommentedChatHoverGroups();
        
        return changed;
    }

    private boolean addChatHoverEntries(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Checking for chat-hover entries...");
        boolean changed = false;

        // Legacy migration: move chat-hover.default to chat-hover.groups.default if present
        if (config.contains("chat-hover.default")) {
            Text.sendDebugLog(INFO, "Found legacy chat-hover.default section, migrating to chat-hover.groups.default...");
            ConfigurationSection legacy = config.getConfigurationSection("chat-hover.default");
            if (legacy != null) {
                for (String key : legacy.getKeys(false)) {
                    config.set("chat-hover.groups.default." + key, legacy.get(key));
                }
            }
            config.set("chat-hover.default", null);
            changed = true;
        }

        if (!config.contains("chat-hover.delete-hover-mode")) {
            config.set("chat-hover.delete-hover-mode", "prefix");
            changed = true;
        }

        // Only add missing chat-hover settings under groups.default, don't overwrite existing ones
        if (!config.contains("chat-hover.groups.default")) {
            Text.sendDebugLog(INFO, "Adding missing chat-hover.groups.default section in memory...");

            List<String> prefixHover = new ArrayList<>();
            prefixHover.add("&7{prefix} {player}");
            config.set("chat-hover.groups.default.prefix.hover", prefixHover);
            config.set("chat-hover.groups.default.prefix.action", "run-command");
            config.set("chat-hover.groups.default.prefix.click", "/help");

            List<String> nameHover = new ArrayList<>();
            nameHover.add("&7Click to message {player}");
            config.set("chat-hover.groups.default.name.hover", nameHover);
            config.set("chat-hover.groups.default.name.action", "suggest-command");
            config.set("chat-hover.groups.default.name.click", "/msg {player} ");

            changed = true;
            Text.sendDebugLog(INFO, "Added missing chat-hover.groups.default settings in config memory");
        }

        if (changed) {
            try {
                config.save(configFile);
                restoreSectionHeaders();
                Text.sendDebugLog(INFO, "Saved config with chat-hover settings to " + configFile);
            } catch (IOException e) {
                Text.sendDebugLog(ERROR, "Failed to save config with chat-hover settings", e);
                changed = false;
            }
        }

        if (!verifyChatHoverInFile()) {
            Text.sendDebugLog(WARN, "Chat-hover section not found or mis-positioned, attempting to insert after chat-format...");
            changed |= insertChatHoverAfterChatFormat(config);
        }

        addCommentedChatHoverGroups();

        return changed;
    }

    private boolean addMailCooldownEntry(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Checking for mail.cooldown entry...");
        boolean changed = false;
        
        // Only add missing mail.cooldown setting, don't overwrite existing one
        if (!config.contains("mail.cooldown")) {
            Text.sendDebugLog(INFO, "Adding missing mail.cooldown setting...");
            config.set("mail.cooldown", 15);
            changed = true;
            Text.sendDebugLog(INFO, "Added mail.cooldown = 15 to config");
            
            // Save to file to ensure changes are persisted
            try {
                config.save(configFile);
                restoreSectionHeaders();
                Text.sendDebugLog(INFO, "Saved config with mail.cooldown setting to " + configFile);
                // Verify and reposition if needed
                if (verifyMailCooldownInFile()) {
                    changed = true;
                } else {
                    Text.sendDebugLog(WARN, "mail.cooldown not found in correct position, attempting to reposition after feed.cooldown...");
                    changed |= insertMailCooldownAfterFeedCooldown(config);
                }
            } catch (IOException e) {
                Text.sendDebugLog(ERROR, "Failed to save config with mail.cooldown setting", e);
                changed = false;
            }
        } else {
            Text.sendDebugLog(INFO, "mail.cooldown already exists, preserving user customization");
        }
        
        return changed;
    }

    /**
     * Migrate 0.1.3a config to 0.1.4a - adds new sections introduced in 0.1.4a.
     * updateConfig() also adds missing defaults; this handles any 0.1.3a-specific gaps.
     */
    private boolean migrate0_1_3aTo0_1_4a(FileConfiguration config) {
        boolean changed = false;

        // Permission migration flags (core.* -> allium.*)
        if (!config.contains("group_migration_completed")) {
            config.set("group_migration_completed", false);
            changed = true;
        }
        if (!config.contains("player_migration_completed")) {
            config.set("player_migration_completed", false);
            changed = true;
        }

        // Debug scheduler option (Folia)
        if (!config.contains("debug-scheduler")) {
            config.set("debug-scheduler", false);
            changed = true;
        }

        // Update checker
        if (!config.contains("update-checker")) {
            config.set("update-checker.enabled", true);
            config.set("update-checker.notify-mode", "both");
            changed = true;
        }

        // Dialog settings
        if (!config.contains("dialog")) {
            config.set("dialog.use-datapack", true);
            config.set("dialog.auto-show-join-delay-ticks", 40);
            changed = true;
        }

        // Chat deletion resend
        if (!config.contains("chat.deletion_resend.clear_lines")) {
            config.set("chat.deletion_resend.clear_lines", 500);
            changed = true;
        }
        if (!config.contains("chat")) {
            config.set("chat.deletion_resend.header_enabled", true);
            config.set("chat.deletion_resend.header", "&8&oChat re-synced by staff; a message was deleted");
            changed = true;
        }

        // Auto-restart
        if (!config.contains("auto-restart")) {
            config.set("auto-restart.enabled", false);
            config.set("auto-restart.times", Arrays.asList("04:00", "12:00", "20:00"));
            config.set("auto-restart.pre-commands", Arrays.asList("save-all", "say Server restarting in 30 seconds!"));
            config.set("auto-restart.command-delay", 30);
            config.set("auto-restart.countdown-times", Arrays.asList(60, 30, 15, 10, 5, 4, 3, 2, 1));
            config.set("auto-restart.restart-on-crash", true);
            config.set("auto-restart.save-before-restart", true);
            changed = true;
        }

        // Commands list (allium.player permissions)
        if (!config.contains("commands")) {
            config.set("commands", Arrays.asList("help", "tpa", "spawn", "tppet", "mail", "mail.send", "mail.gift", "mail.read",
                    "msg", "reply", "home", "sethome", "delhome", "tpaccept", "tpdeny", "tptoggle", "balance", "baltop",
                    "back", "pay", "tpcancel", "tpahere", "party"));
            changed = true;
        }

        // Party manager (if missing - may exist from hide-non-party-members migration)
        if (!config.contains("party-manager.party-locator-bar")) {
            config.set("party-manager.party-locator-bar", true);
            config.set("party-manager.show-non-party-members-radius", 128);
            config.set("party-manager.sync-with-mcmmo", true);
            changed = true;
        }

        // Handcuffs
        if (!config.contains("handcuffs")) {
            config.set("handcuffs.commands-on-quit", Arrays.asList("ban {player} Logged out while restrained"));
            changed = true;
        }

        if (changed) {
            Text.sendDebugLog(INFO, "Applied 0.1.3a -> 0.1.4a config migration");
        }
        return changed;
    }

    private boolean migrateHideNonPartyMembersToPartyLocatorBar(FileConfiguration config) {
        String legacyKey = "party-manager.hide-non-party-members";
        String newKey = "party-manager.party-locator-bar";
        if (config.contains(legacyKey) && !config.contains(newKey)) {
            Text.sendDebugLog(INFO, "Migrating hide-non-party-members to party-locator-bar...");
            config.set(newKey, config.getBoolean(legacyKey, true));
            config.set(legacyKey, null);
            return true;
        }
        if (config.contains(legacyKey)) {
            config.set(legacyKey, null);
            return true;
        }
        return false;
    }

    private boolean verifyMailCooldownInFile() {
        try {
            if (!configFile.exists()) {
                Text.sendDebugLog(WARN, "Config file missing during verifyMailCooldownInFile, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                return false;
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            boolean hasMailCooldown = content.contains("mail.cooldown:");
            Text.sendDebugLog(INFO, "Verification: mail.cooldown " + (hasMailCooldown ? "found" : "not found") + " in config.yml");
            if (hasMailCooldown) {
                boolean correctPosition = verifyMailCooldownPosition(content);
                Text.sendDebugLog(INFO, "mail.cooldown position: " + (correctPosition ? "correct (after feed.cooldown)" : "incorrect (not after feed.cooldown)"));
                return correctPosition;
            }
            return hasMailCooldown;
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to verify mail.cooldown in config.yml", e);
            return false;
        }
    }

    private boolean verifyMailCooldownPosition(String content) {
        int feedCooldownIndex = content.indexOf("feed.cooldown:");
        int mailCooldownIndex = content.indexOf("mail.cooldown:");
        if (feedCooldownIndex == -1 || mailCooldownIndex == -1) {
            return false;
        }
        // Check if mail.cooldown appears after feed.cooldown
        return mailCooldownIndex > feedCooldownIndex;
    }

    private boolean insertMailCooldownAfterFeedCooldown(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Inserting mail.cooldown after feed.cooldown...");
        try {
            if (!configFile.exists()) {
                Text.sendDebugLog(WARN, "Config file missing during insertMailCooldownAfterFeedCooldown, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                return false;
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            
            // Remove existing mail.cooldown if present
            int mailCooldownStart = content.indexOf("mail.cooldown:");
            if (mailCooldownStart != -1) {
                int mailCooldownEnd = content.indexOf("\n", mailCooldownStart);
                if (mailCooldownEnd == -1) {
                    mailCooldownEnd = content.length();
                } else {
                    mailCooldownEnd++; // Include the newline
                }
                content = content.substring(0, mailCooldownStart) + content.substring(mailCooldownEnd);
                Text.sendDebugLog(INFO, "Removed existing mail.cooldown from config.yml");
            }
            
            // Find feed.cooldown and insert mail.cooldown after it
            int feedCooldownIndex = content.indexOf("feed.cooldown:");
            if (feedCooldownIndex != -1) {
                int feedCooldownEnd = content.indexOf("\n", feedCooldownIndex);
                if (feedCooldownEnd != -1) {
                    String mailCooldownLine = "\nmail.cooldown: 15";
                    String newContent = content.substring(0, feedCooldownEnd) + 
                                      mailCooldownLine + 
                                      content.substring(feedCooldownEnd);
                    
                    Files.write(configFile.toPath(), newContent.getBytes());
                    Text.sendDebugLog(INFO, "Successfully repositioned mail.cooldown after feed.cooldown");
                    return true;
                }
            }
            
            Text.sendDebugLog(WARN, "Could not find feed.cooldown to position mail.cooldown after it");
            return false;
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to reposition mail.cooldown in config.yml", e);
            return false;
        }
    }

    private boolean insertChatHoverAfterChatFormat(FileConfiguration config) {
        boolean changed = false;
        try {
            String content = Files.exists(configFile.toPath()) ? new String(Files.readAllBytes(configFile.toPath())) : "";
            
            // Check if chat-hover is already in the file
            if (!content.contains("chat-hover:")) {
                Text.sendDebugLog(INFO, "Adding chat-hover section to config.yml after chat-format...");
                String chatHoverSection =
                        "\n" +
                        "# Chat hover settings\n" +
                        "chat-hover:\n" +
                        "  delete-hover-mode: prefix\n" +
                        "  groups:\n" +
                        "    default:\n" +
                        "      prefix:\n" +
                        "        hover:\n" +
                        "          - '&7{prefix} {player}'\n" +
                        "        action: run-command\n" +
                        "        click: '/help'\n" +
                        "      name:\n" +
                        "        hover:\n" +
                        "          - '&7Click to message {player}'\n" +
                        "        action: suggest-command\n" +
                        "        click: '/msg {player} '\n" +
                        "      # suffix:\n" +
                        "      #   hover:\n" +
                        "      #     - '&7Click to message {player}'\n" +
                        "      #   action: run-command\n" +
                        "      #   click: '/msg {player}'\n" +
                        "    # Add more groups here:\n" +
                        "    # owner:\n" +
                        "    #   prefix:\n" +
                        "    #   name:\n" +
                        "    #   suffix:\n";
                
                // Try to find chat-format section
                int insertIndex = content.indexOf("chat-format:");
                if (insertIndex != -1) {
                    // Find the end of the chat-format section (next section or end of file)
                    int nextSectionIndex = content.indexOf("\n#", insertIndex);
                    if (nextSectionIndex == -1) {
                        nextSectionIndex = content.length();
                    }
                    content = content.substring(0, nextSectionIndex) + chatHoverSection + content.substring(nextSectionIndex);
                    Text.sendDebugLog(INFO, "Inserted chat-hover section after chat-format");
                } else {
                    // If chat-format not found, insert before version section
                    insertIndex = content.indexOf("version:");
                    if (insertIndex != -1) {
                        content = content.substring(0, insertIndex) + chatHoverSection + content.substring(insertIndex);
                        Text.sendDebugLog(INFO, "Inserted chat-hover section before version");
                    } else {
                        content += chatHoverSection;
                        Text.sendDebugLog(INFO, "Appended chat-hover section to end of file");
                    }
                }
                
                Files.write(configFile.toPath(), content.getBytes());
                restoreSectionHeaders();
                Text.sendDebugLog(INFO, "Successfully wrote chat-hover section to config.yml");
                
                // Verify the file content
                if (verifyChatHoverInFile()) {
                    Text.sendDebugLog(INFO, "Verified: chat-hover section present in config.yml");
                    changed = true;
                } else {
                    Text.sendDebugLog(ERROR, "Failed to verify chat-hover section in config.yml after write, attempting fallback...");
                    changed |= forceRewriteConfig(config);
                }
                
                // Reload config to ensure consistency
                plugin.reloadConfig();
            } else {
                Text.sendDebugLog(INFO, "Chat-hover section already present in config.yml");
                // Verify position
                if (!verifyChatHoverPosition(content)) {
                    Text.sendDebugLog(WARN, "Chat-hover section found but not after chat-format, attempting to reposition...");
                    changed |= repositionChatHover(config);
                }
                // Verify in-memory config matches file
                if (!config.contains("chat-hover.groups.default.prefix.hover")) {
                    Text.sendDebugLog(WARN, "Chat-hover section in file but missing in memory, forcing reload");
                    plugin.reloadConfig();
                    changed = true;
                }
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to update config.yml with chat-hover section", e);
            changed = false;
        }
        
        return changed;
    }

    private boolean verifyChatHoverInFile() {
        try {
            if (!configFile.exists()) {
                Text.sendDebugLog(WARN, "Config file missing during verification, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                return false;
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            boolean hasChatHover = content.contains("chat-hover:");
            Text.sendDebugLog(INFO, "Verification: chat-hover section " + (hasChatHover ? "found" : "not found") + " in config.yml");
            if (hasChatHover) {
                boolean correctPosition = verifyChatHoverPosition(content);
                Text.sendDebugLog(INFO, "Chat-hover section position: " + (correctPosition ? "correct (after chat-format)" : "incorrect (not after chat-format)"));
            }
            return hasChatHover;
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to verify chat-hover section in config.yml", e);
            return false;
        }
    }

    private boolean verifyChatHoverPosition(String content) {
        int chatFormatIndex = content.indexOf("chat-format:");
        int chatHoverIndex = content.indexOf("chat-hover:");
        if (chatFormatIndex == -1 || chatHoverIndex == -1) {
            return false;
        }
        // Check if chat-hover appears after chat-format but before the next section
        int nextSectionIndex = content.indexOf("\n#", chatFormatIndex);
        if (nextSectionIndex == -1) {
            nextSectionIndex = content.length();
        }
        return chatHoverIndex > chatFormatIndex && chatHoverIndex < nextSectionIndex;
    }

    private boolean repositionChatHover(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Repositioning chat-hover section to after chat-format...");
        try {
            if (!configFile.exists()) {
                Text.sendDebugLog(WARN, "Config file missing during reposition, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                return false;
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            
            // Remove existing chat-hover section
            int chatHoverStart = content.indexOf("chat-hover:");
            if (chatHoverStart != -1) {
                int chatHoverEnd = content.indexOf("\n#", chatHoverStart);
                if (chatHoverEnd == -1) {
                    chatHoverEnd = content.length();
                }
                content = content.substring(0, chatHoverStart) + content.substring(chatHoverEnd);
                Text.sendDebugLog(INFO, "Removed existing chat-hover section from config.yml");
            }
            
            // Re-insert chat-hover section after chat-format
            String chatHoverSection =
                    "\n" +
                    "# Chat hover settings\n" +
                    "chat-hover:\n" +
                    "  delete-hover-mode: prefix\n" +
                    "  groups:\n" +
                    "    default:\n" +
                    "      prefix:\n" +
                    "        hover:\n" +
                    "          - '&7{prefix} {player}'\n" +
                    "        action: run-command\n" +
                    "        click: '/help'\n" +
                    "      name:\n" +
                    "        hover:\n" +
                    "          - '&7Click to message {player}'\n" +
                    "        action: suggest-command\n" +
                    "        click: '/msg {player} '\n" +
                    "      # suffix:\n" +
                    "      #   hover:\n" +
                    "      #     - '&7Click to message {player}'\n" +
                    "      #   action: run-command\n" +
                    "      #   click: '/msg {player}'\n" +
                    "    # Add more groups here:\n" +
                    "    # owner:\n" +
                    "    #   prefix:\n" +
                    "    #   name:\n" +
                    "    #   suffix:\n";
            
            int insertIndex = content.indexOf("chat-format:");
            if (insertIndex != -1) {
                insertIndex = content.indexOf("\n", insertIndex) + 1;
                content = content.substring(0, insertIndex) + chatHoverSection + content.substring(insertIndex);
                Text.sendDebugLog(INFO, "Inserted chat-hover section after chat-format");
            } else {
                // Fallback to inserting before version
                insertIndex = content.indexOf("version:");
                if (insertIndex != -1) {
                    content = content.substring(0, insertIndex) + chatHoverSection + content.substring(insertIndex);
                    Text.sendDebugLog(INFO, "Inserted chat-hover section before version");
                } else {
                    content += chatHoverSection;
                    Text.sendDebugLog(INFO, "Appended chat-hover section to end of file");
                }
            }
            
            Files.write(configFile.toPath(), content.getBytes());
            restoreSectionHeaders();
            Text.sendDebugLog(INFO, "Successfully repositioned chat-hover section in config.yml");
            
            // Verify the file content
            if (verifyChatHoverInFile() && verifyChatHoverPosition(new String(Files.readAllBytes(configFile.toPath())))) {
                Text.sendDebugLog(INFO, "Verified: chat-hover section present in correct position in config.yml");
                return true;
            } else {
                Text.sendDebugLog(ERROR, "Failed to verify chat-hover section in correct position after repositioning, attempting fallback...");
                return forceRewriteConfig(config);
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to reposition chat-hover section in config.yml", e);
            return false;
        }
    }

    private boolean forceRewriteConfig(FileConfiguration config) {
        Text.sendDebugLog(INFO, "Attempting to force rewrite config.yml with all settings...");
        boolean changed = false;
        try {
            // Create a new YAML config with all current settings
            FileConfiguration newConfig = new YamlConfiguration();
            // Ensure chat-format is added first to control order
            newConfig.set("chat-format.default", config.get("chat-format.default", "<prefix> &a<player>&f: &f<message>"));
            
            // Add chat-hover section
            List<String> prefixHover = new ArrayList<>();
            prefixHover.add("&7{prefix} {player}");
            newConfig.set("chat-hover.delete-hover-mode", plugin.getConfig().getString("chat-hover.delete-hover-mode", "prefix"));
            newConfig.set("chat-hover.groups.default.prefix.hover", prefixHover);
            newConfig.set("chat-hover.groups.default.prefix.action", "run-command");
            newConfig.set("chat-hover.groups.default.prefix.click", "/help");
            List<String> nameHover = new ArrayList<>();
            nameHover.add("&7Click to message {player}");
            newConfig.set("chat-hover.groups.default.name.hover", nameHover);
            newConfig.set("chat-hover.groups.default.name.action", "suggest-command");
            newConfig.set("chat-hover.groups.default.name.click", "/msg {player} ");
            
            // Add remaining settings
            for (Map.Entry<String, Object> entry : config.getValues(true).entrySet()) {
                if (!entry.getKey().equals("cooldown") && !entry.getKey().startsWith("chat-hover") && !entry.getKey().startsWith("chat-format")) {
                    newConfig.set(entry.getKey(), entry.getValue());
                }
            }
            
            // Set header
            newConfig.options().header(
                    "############################################################\n" +
                    "# +------------------------------------------------------+ #\n" +
                    "# |                Allium Configuration                   | #\n" +
                    "# +------------------------------------------------------+ #\n" +
                    "############################################################\n\n" +
                    "# This is the main configuration file for Allium.\n" +
                    "# For more information, visit: https://github.com/castledking/Allium\n"
            );
            
            // Save to a temporary file first
            File tempFile = new File(plugin.getDataFolder(), "config_temp.yml");
            newConfig.save(tempFile);
            Text.sendDebugLog(INFO, "Saved temporary config to " + tempFile);
            
            // Verify temporary file
            String tempContent = new String(Files.readAllBytes(tempFile.toPath()));
            if (!tempContent.contains("chat-hover:") || !verifyChatHoverPosition(tempContent)) {
                Text.sendDebugLog(ERROR, "Failed to include chat-hover section in correct position in temporary config");
                return false;
            }
            
            // Replace original file
            Files.move(tempFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Text.sendDebugLog(INFO, "Moved temporary config to " + configFile);
            
            // Verify final file
            if (verifyChatHoverInFile() && verifyChatHoverPosition(new String(Files.readAllBytes(configFile.toPath())))) {
                Text.sendDebugLog(INFO, "Force rewrite successful: chat-hover section present in correct position in config.yml");
                changed = true;
            } else {
                Text.sendDebugLog(ERROR, "Force rewrite failed: chat-hover section missing or in incorrect position in config.yml");
            }
            
            // Reload config
            plugin.reloadConfig();
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to force rewrite config.yml", e);
            changed = false;
        }
        return changed;
    }

    private void createDefaultConfig() {
        try {
            // Ensure plugin data folder exists (may be missing if deleted during runtime)
            java.io.File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create plugin folder: " + parent);
            }
            // First try to copy the config from the plugin's resources
            InputStream templateStream = plugin.getResource("config.yml");
            if (templateStream != null) {
                Files.copy(templateStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                templateStream.close();
                Text.sendDebugLog(INFO, "Created config.yml from template with all comments preserved");
            } else {
                if (!configFile.createNewFile()) {
                    throw new IOException("Could not create config.yml");
                }

                FileConfiguration config = new YamlConfiguration();
                // Set chat-format first to ensure order
                config.set("chat-format.default", "<prefix> &a<player>&f: &f<message>");
                
                // Set chat-hover section
                List<String> prefixHover = new ArrayList<>();
                prefixHover.add("&7{prefix} {player}");
                config.set("chat-hover.delete-hover-mode", "prefix");
                config.set("chat-hover.groups.default.prefix.hover", prefixHover);
                config.set("chat-hover.groups.default.prefix.action", "run-command");
                config.set("chat-hover.groups.default.prefix.click", "/help");
                List<String> nameHover = new ArrayList<>();
                nameHover.add("&7Click to message {player}");
                config.set("chat-hover.groups.default.name.hover", nameHover);
                config.set("chat-hover.groups.default.name.action", "suggest-command");
                config.set("chat-hover.groups.default.name.click", "/msg {player} ");
                
                // Set remaining default values
                if (defaultConfig != null) {
                    for (String key : defaultConfig.getKeys(true)) {
                        if (!key.startsWith("chat-hover") && !key.startsWith("chat-format") && !config.contains(key)) {
                            config.set(key, defaultConfig.get(key));
                        }
                    }
                }
                
                String commentedGroups =
                        "  # Group-specific chat formats\n" +
                        "  # Uncomment and modify these as needed\n" +
                        "  groups:\n" +
                        "    #default: \"&7<player>&f: <message>\" # Default Group Format\n" +
                        "    #admin: \"&4[Admin] &c<player>&f: <message>\"\n" +
                        "    #moderator: \"&2[Mod] &a<player>&f: <message>\"\n" +
                        "    #vip: \"&6[VIP] &e<player>&f: <message>\"\n\n" +
                        "# Chat hover settings\n" +
                        "chat-hover:\n" +
                        "  delete-hover-mode: prefix\n" +
                        "  groups:\n" +
                        "    default:\n" +
                        "      prefix:\n" +
                        "        hover:\n" +
                        "          - '&7{prefix} {player}'\n" +
                        "        action: run-command\n" +
                        "        click: '/help'\n" +
                        "      name:\n" +
                        "        hover:\n" +
                        "          - '&7Click to message {player}'\n" +
                        "        action: suggest-command\n" +
                        "        click: '/msg {player} '";

                config.options().header(
                        "############################################################\n" +
                        "# +------------------------------------------------------+ #\n" +
                        "# |                Allium Configuration                   | #\n" +
                        "# +------------------------------------------------------+ #\n" +
                        "############################################################\n\n" +
                        "# This is the main configuration file for Allium.\n" +
                        "# For more information, visit: https://github.com/castledking/Allium\n"
                );

                config.save(configFile);
                restoreSectionHeaders();
                addCommentedSections(commentedGroups);
                Text.sendDebugLog(INFO, "Created basic config.yml with default values and commented sections");
            }

            plugin.reloadConfig();
            // Verify chat-hover section
            verifyChatHoverInFile();
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to create config.yml", e);
            throw new RuntimeException("Failed to create config.yml", e);
        }
    }

    private void addCommentedChatHoverGroups() {
        try {
            // Check if config file exists, create it if it doesn't (like on startup)
            if (!configFile.exists()) {
                Text.sendDebugLog(INFO, "Config file missing during reload, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                Text.sendDebugLog(INFO, "Default config created during reload");
            }

            String content = new String(Files.readAllBytes(configFile.toPath()));
    
            if (!content.contains("# Add more groups here:")) {
                Text.sendDebugLog(INFO, "Adding commented chat-hover groups section...");
    
                String commentedGroups =
                        "  # Add more groups here:\n" +
                        "  # owner:\n" +
                        "  #   prefix:\n" +
                        "  #   name:\n" +
                        "  #   suffix:\n" +
                        "  # moderator:\n" +
                        "  #   prefix:\n" +
                        "  #   name:\n" +
                        "  #   suffix:\n" +
                        "  # vip:\n" +
                        "  #   prefix:\n" +
                        "  #   name:\n" +
                        "  #   suffix:\n";
    
                int chatHoverIndex = content.indexOf("chat-hover:");
                if (chatHoverIndex != -1) {
                    int nextSectionIndex = content.indexOf("\n#", chatHoverIndex);
                    if (nextSectionIndex == -1) {
                        nextSectionIndex = content.length();
                    }
    
                    String newContent = content.substring(0, nextSectionIndex) +
                            commentedGroups +
                            content.substring(nextSectionIndex);
    
                    Files.write(configFile.toPath(), newContent.getBytes());
                    Text.sendDebugLog(INFO, "Successfully added commented chat-hover groups section");
                } else {
                    Text.sendDebugLog(WARN, "Could not find chat-hover section to add commented groups");
                }
            } else {
                Text.sendDebugLog(INFO, "Commented chat-hover groups section already exists");
            }
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to add commented chat-hover groups section", e);
        }
    }

    private void addCommentedSections(String commentedGroups) {
        try {
            if (!configFile.exists()) {
                Text.sendDebugLog(WARN, "Config file missing during addCommentedSections, creating default config...");
                createDefaultConfig();
                plugin.reloadConfig();
                return;
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            int chatFormatIndex = content.indexOf("chat-format:");
            if (chatFormatIndex != -1) {
                int defaultLineEnd = content.indexOf("\n", chatFormatIndex);
                if (defaultLineEnd != -1) {
                    String newContent = content.substring(0, defaultLineEnd + 1) +
                            commentedGroups +
                            content.substring(defaultLineEnd + 1);
                    Files.write(configFile.toPath(), newContent.getBytes());
                    // Verify chat-hover section after adding commented sections
                    verifyChatHoverInFile();
                }
            }
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to add commented sections to config.yml", e);
        }
    }

    public void updateConfig() {
        Text.sendDebugLog(INFO, "Updating configuration with any missing defaults...");
        initializeDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        boolean updated = false;
        int addedCount = 0;

        if (defaultConfig != null) {
            for (String key : defaultConfig.getKeys(true)) {
                if (!config.contains(key)) {
                    Text.sendDebugLog(INFO, "Adding missing config value: " + key + " = " + defaultConfig.get(key));
                    config.set(key, defaultConfig.get(key));
                    updated = true;
                    addedCount++;
                }
            }
        }
    
        if (!config.contains("home.show-location")) {
            config.set("home.show-location", false);
            updated = true;
            Text.sendDebugLog(INFO, "Force-added config value: home.show-location");
        }
        if (!config.contains("home.spawn-if-no-home")) {
            config.set("home.spawn-if-no-home", true);
            updated = true;
            Text.sendDebugLog(INFO, "Force-added config value: home.spawn-if-no-home");
        }

        if (!config.contains("economy.allow-negative-balance")) {
            config.set("economy.allow-negative-balance", false);
            updated = true;
            Text.sendDebugLog(INFO, "Force-added config value: economy.allow-negative-balance");
        }

        // Remove invalid cooldown setting if present
        if (config.contains("cooldown")) {
            config.set("cooldown", null);
            updated = true;
            Text.sendDebugLog(INFO, "Removed invalid config value: cooldown");
        }

        // Ensure chat-hover section is in memory
        if (!config.contains("chat-hover.delete-hover-mode")) {
            config.set("chat-hover.delete-hover-mode", "prefix");
            updated = true;
        }

        if (!config.contains("chat-hover.groups.default.prefix.hover")) {
            List<String> prefixHover = new ArrayList<>();
            prefixHover.add("&7{prefix} {player}");
            config.set("chat-hover.groups.default.prefix.hover", prefixHover);
            config.set("chat-hover.groups.default.prefix.action", "run-command");
            config.set("chat-hover.groups.default.prefix.click", "/help");
            List<String> nameHover = new ArrayList<>();
            nameHover.add("&7Click to message {player}");
            config.set("chat-hover.groups.default.name.hover", nameHover);
            config.set("chat-hover.groups.default.name.action", "suggest-command");
            config.set("chat-hover.groups.default.name.click", "/msg {player} ");
            updated = true;
            Text.sendDebugLog(INFO, "Force-added chat-hover section to in-memory config");
        }

        addCommentedChatHoverGroups();

        if (updated) {
            Text.sendDebugLog(INFO, "Saving config with " + addedCount + " new default values...");
            saveConfigWithCommentsPreserved();
            Text.sendDebugLog(INFO, "Successfully updated config with missing default values");
            // Verify chat-hover section after save
            verifyChatHoverInFile();

            if (plugin.getConfig().getBoolean("debug-mode", false)) {
                Text.sendDebugLog(INFO, "Current chat-related config values:");
                for (String key : config.getKeys(true)) {
                    if (key.startsWith("chat") || key.startsWith("hover") || key.startsWith("click")) {
                        Text.sendDebugLog(INFO, "  " + key + " = " + config.get(key));
                    }
                }
            }

        } else {
            Text.sendDebugLog(INFO, "No config updates needed.");
        }
    }

    /**
     * Saves the configuration while preserving comments in the original file.
     * This method reads the existing config file, preserves comments, updates only changed values,
     * and writes back the file with comments intact.
     */
    private void saveConfigWithCommentsPreserved() {
        try {
            // Read the current config content with comments preserved
            StringBuilder originalContent = new StringBuilder();
            if (configFile.exists()) {
                List<String> lines = Files.readAllLines(configFile.toPath());
                for (String line : lines) {
                    originalContent.append(line).append("\n");
                }
            }

            // Get the current in-memory configuration
            FileConfiguration currentConfig = plugin.getConfig();

            // Create a new YAML configuration with current values
            FileConfiguration newConfig = new YamlConfiguration();

            // Copy all current values to the new config
            for (String key : currentConfig.getKeys(true)) {
                Object value = currentConfig.get(key);
                if (value != null) {
                    newConfig.set(key, value);
                }
            }

            // Set the header to match the original format
            newConfig.options().header(
                    "############################################################\n" +
                    "# +------------------------------------------------------+ #\n" +
                    "# |                Allium Configuration                   | #\n" +
                    "# +------------------------------------------------------+ #\n" +
                    "############################################################\n\n" +
                    "# This is the main configuration file for Allium.\n" +
                    "# For more information, visit: https://github.com/castledking/Allium\n"
            );

            // Save to a temporary file first
            File tempFile = new File(plugin.getDataFolder(), "config_temp.yml");
            newConfig.save(tempFile);

            // Read the temporary file content
            String tempContent = new String(Files.readAllBytes(tempFile.toPath()));

            // Replace the original file with the new content
            Files.write(configFile.toPath(), tempContent.getBytes());

            // Clean up temporary file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            restoreSectionHeaders();

            Text.sendDebugLog(INFO, "Config saved with comments preserved");

        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to save config while preserving comments: " + e.getMessage(), e);
            // Fallback to regular save if comment preservation fails
            try {
                plugin.getConfig().save(configFile);
            } catch (IOException fallbackException) {
                Text.sendDebugLog(ERROR, "Fallback config save also failed: " + fallbackException.getMessage(), fallbackException);
            }
        }
    }

    public Object get(String key) {
        return plugin.getConfig().get(key, defaultConfig != null ? defaultConfig.get(key) : null);
    }

    public boolean getBoolean(String key) {
        return plugin.getConfig().getBoolean(key,
                defaultConfig != null && defaultConfig.contains(key) ? defaultConfig.getBoolean(key) : false);
    }

    public int getInt(String key) {
        return plugin.getConfig().getInt(key,
                defaultConfig != null && defaultConfig.contains(key) ? defaultConfig.getInt(key) : 0);
    }

    public String getString(String key, String s) {
        return plugin.getConfig().getString(key,
                defaultConfig != null && defaultConfig.contains(key) ? defaultConfig.getString(key) : "");
    }

    public String getVersion() {
        return plugin.getConfig().getString("version", CURRENT_VERSION);
    }

    /**
     * Forces a complete reload of the configuration, ensuring all systems that depend on
     * config values are notified to reload their settings.
     *
     * @param plugin The plugin instance to reload config for
     */
    public void forceCompleteReload(JavaPlugin plugin) {
        Text.sendDebugLog(INFO, "Performing complete configuration reload...");

        // Force reload the config from disk
        plugin.reloadConfig();

        // Force update config with any missing defaults (this ensures all defaults are present)
        updateConfig();

        // The config is now fully loaded with all current values from disk
        // Systems that depend on config should re-read their values from freshConfig

        Text.sendDebugLog(INFO, "Complete configuration reload finished");
    }
}