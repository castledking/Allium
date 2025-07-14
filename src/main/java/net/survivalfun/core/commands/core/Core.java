package net.survivalfun.core.commands.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.fun.Explode;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.managers.config.WorldDefaults;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Collectors;

public class Core implements CommandExecutor, TabCompleter {

    private final WorldDefaults worldDefaults;
    private final PluginStart plugin;
    private final FileConfiguration config;
    private final Lang lang;
    private final CommandManager commandManager;
    private final CreativeManager creativeManager;

        public Core(WorldDefaults worldDefaults, PluginStart plugin, FileConfiguration config, CommandManager commandManager
    , CreativeManager creativeManager) {
        this.worldDefaults = worldDefaults;
        this.plugin = plugin;
        this.config = config;
        this.lang = plugin.getLangManager();
        this.commandManager = commandManager;
        this.creativeManager = creativeManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing Core command");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setgamerule":
                handleSetGameruleCommand(sender, args);
                break;

            case "reload":
                handleReloadCommand(sender, null);
                break;

            case "debug":
                handleDebugCommand(sender, args);
                break;
            case "hideupdate":
                handleHideUpdateCommand(sender, args);
                break;
            // modalerts case removed
            case "hide":
                handleHideSubcommand(sender, args);
                break;
            case "escalate":
                handleEscalateCommand(sender, args);
                break;

            // Voucher command removed - only using redeem command

            default:
                sender.sendMessage("§cUnknown subcommand. Use /core for help.");
                break;
        }

        return true;
    }


    private void handleHideUpdateCommand(@NotNull CommandSender sender, String @NotNull [] args) {
        if(!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        
        // Case 1: No additional arguments or "all" argument - update all players
        if(args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("all"))) {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Force tab completion update by temporarily changing gamemode
                updatePlayerTabCompletionWithGamemode(player);
                count++;
            }
            sender.sendMessage(Component.text("Updated tab completion for " + count + " players.", NamedTextColor.GREEN));
            return;
        }
        
        // Case 2: Specific player argument
        if(args.length == 2) {
            
            // Handle specific player
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                return;
            }
            updatePlayerTabCompletionWithGamemode(target);
            sender.sendMessage(Component.text("Updated tab completion for " + target.getName() + ".", NamedTextColor.GREEN));
            return;
        }
        
        // Case 3: Too many arguments
        sender.sendMessage(Component.text("Usage: /core hideupdate [player|all]", NamedTextColor.RED));
    }
    
    /**
     * Updates a player's tab completion by temporarily changing their gamemode
     * This forces the client to refresh tab completions
     * 
     * @param player The player to update
     */
    private void updatePlayerTabCompletionWithGamemode(Player player) {
        // Store original gamemode
        org.bukkit.GameMode originalMode = player.getGameMode();
        
        try {
            // Enable bypass for this player
            creativeManager.setBypassInventoryManagement(player, true);
            
            // Only switch if not already in creative
            if (originalMode != org.bukkit.GameMode.CREATIVE) {
                // Switch to creative and back to force tab completion update
                player.setGameMode(org.bukkit.GameMode.CREATIVE);
                
                // Schedule task to switch back after a short delay (1 tick)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(originalMode);
                    // Update tab completion through command manager
                    commandManager.updatePlayerTabCompletion(player);
                    // Disable bypass after we're done
                    creativeManager.setBypassInventoryManagement(player, false);
                }, 1L);
            } else {
                // If already in creative, switch to survival and back
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                
                // Schedule task to switch back after a short delay (1 tick)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(originalMode);
                    // Update tab completion through command manager
                    commandManager.updatePlayerTabCompletion(player);
                    // Disable bypass after we're done
                    creativeManager.setBypassInventoryManagement(player, false);
                }, 1L);
            }
        } catch (Exception e) {
            // Ensure bypass is disabled even if an error occurs
            creativeManager.setBypassInventoryManagement(player, false);
            plugin.getLogger().warning("Error updating tab completion for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        if(!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        sender.sendMessage("§aAvailable /core subcommands:");
        sender.sendMessage("§edebug §7- Toggle debug mode.");
        sender.sendMessage("§ereload §7- Reload the plugin configuration.");
        sender.sendMessage("§ehideupdate §7- Refresh tab completion for player.");
        // modalerts command removed
        sender.sendMessage("§echatmod §7- Manage chat moderation actions.");
        sender.sendMessage("§eescalate §7- Escalate an issue to staff.");


    }

    private void handleSetGameruleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("core.worlddefaults")) {
            sender.sendMessage(lang.get("no-permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /core worlddefaults <gamerule> <value>");
            return;
        }

        String gamerule = args[1].toLowerCase();
        String value = args[2];

        // Update the gamerule in the config
        if (!worldDefaults.plugin().getConfig().contains("world-defaults." + gamerule)) {
            sender.sendMessage("§cInvalid gamerule: " + gamerule);
            return;
        }

        try {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                worldDefaults.plugin().getConfig().set("world-defaults." + gamerule, Boolean.parseBoolean(value));
            } else {
                int intValue = Integer.parseInt(value);
                worldDefaults.plugin().getConfig().set("world-defaults." + gamerule, intValue);
            }

            // Save and apply the changes
            worldDefaults.plugin().saveConfig();
            worldDefaults.applyWorldDefaults();

            sender.sendMessage("§aUpdated gamerule '" + gamerule + "' to '" + value + "' and applied it to all worlds.");
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid value. Use true, false, or a number.");
        }
    }

    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang);
            return;
        }
        // Toggle debug mode in config
        boolean currentDebugMode = plugin.getConfig().getBoolean("debug-mode", false);
        boolean newDebugMode = !currentDebugMode;

        // Update config
        plugin.getConfig().set("debug-mode", newDebugMode);
        plugin.saveConfig();
        handleReloadCommand(sender, true);



        // Log the change
        plugin.getLogger().info("Debug mode " + (newDebugMode ? "enabled" : "disabled") + " by " + sender.getName());
    }

    // modalerts command handler removed

    private void handleReloadCommand(CommandSender sender, Boolean isDebug) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        try {
            // Reload the main config
            plugin.reloadConfig();

            // Reload the command manager
            commandManager.reload();

            // Reload the creative manager blacklists
            creativeManager.reloadBlacklists();

            // Re-initialize the ConfigManager to apply changes
            if (plugin.getConfigManager() != null) {
                plugin.getConfigManager().load();
            }

            // Reload language files
            if (lang != null) {
                lang.reload();
            }
            
            // Reload item aliases and legacy IDs from itemdb.yml
            try {
                Alias.initialize(plugin); // This will reinitialize and reload all aliases
                LegacyID.reload(); // This will reload all legacy IDs
                plugin.getLogger().info("Successfully reloaded item aliases and legacy IDs from itemdb.yml");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to reload itemdb.yml data: " + e.getMessage());
            }

            // Reload world defaults
            if (worldDefaults != null) {
                worldDefaults.plugin().reloadConfig();
                worldDefaults.applyWorldDefaults();
            }

            // Reload the chat formatter to apply new chat format settings
            plugin.reloadChatFormatter();

            // Reload the explode command configuration
            Explode explodeCommand = plugin.getExplodeCommand();
            if (explodeCommand != null) {
                explodeCommand.reloadConfig();
            }
            
            // Reload creeper explosion settings
            try {
                net.survivalfun.core.listeners.jobs.CreeperExplosionListener listener = 
                    net.survivalfun.core.listeners.jobs.CreeperExplosionListener.getInstance();
                if (listener != null) {
                    listener.reloadConfig();
                    plugin.getLogger().info("Successfully reloaded creeper explosion settings");
                } else {
                    plugin.getLogger().warning("Creeper explosion listener not found");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to reload creeper explosion settings: " + e.getMessage());
                e.printStackTrace();
            }

            // Build success message with optional debug status
            StringBuilder successMessage = new StringBuilder("§aConfiguration reloaded successfully.");

            // Add debug status if isDebug is not null (meaning it was explicitly set)
            if (isDebug != null) {
                boolean debugMode = plugin.getConfig().getBoolean("debug-mode", false);
                successMessage.append(" (Debug: ").append(debugMode ? "§aenabled§a" : "§cdisabled§a").append(")");
            }

            sender.sendMessage(successMessage.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error reloading configuration: ", e);
            sender.sendMessage("§cError reloading configuration! Check console for details.");
        }
    }

    private void handleEscalateCommand(CommandSender sender, String[] args) {
        // This command is console-only
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            Text.sendErrorMessage(sender, "command.console-only", lang, Collections.emptyMap());
            return;
        }
        
        if (args.length < 4) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "escalate").replace("{args}", "<staffmember> <target> <reason>"));
            return;
        }
        
        String staffMember = args[1];
        String targetPlayer = args[2];
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).replace("\\n", "\n");
        String webhookUrl = plugin.getConfig().getString("discord.escalate_webhook");
        
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            Text.sendErrorMessage(sender, "command.core.escalate.not-configured", lang, Collections.emptyMap());
            return;
        }
        
        String serverName = plugin.getConfig().getString("server-name", "Unknown Server");
        
        List<String> roleMentions = plugin.getConfig().getStringList("discord.escalate_mentions");
        String mentionString = roleMentions.stream().map(id -> "<@&" + id + ">").collect(Collectors.joining(" "));
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JSONObject embed = new JSONObject();
                embed.put("title", "Escalation Request");
                embed.put("description", mentionString + "\n**Escalation Target:** " + targetPlayer + "\n**Reason:** " + reason);
                
                JSONObject footer = new JSONObject();
                footer.put("text", "From " + staffMember + " on " + serverName);
                embed.put("footer", footer);
                
                JSONObject payload = new JSONObject();
                payload.put("username", plugin.getConfig().getString("discord.bot_name", "Server Escalation"));
                payload.put("avatar_url", plugin.getConfig().getString("discord.bot_avatar"));
                payload.put("embeds", new JSONArray().put(embed));
                
                HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP response code: " + responseCode);
                }
                
                // Send confirmation to the staff member
                Player staffPlayer = Bukkit.getPlayer(staffMember);
                if (staffPlayer != null) {
                    staffPlayer.sendMessage(Text.colorize("&aEscalation sent to staff!"));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to send escalation to Discord", e);
                Text.sendErrorMessage(sender, "command.core.escalate.failed", lang, Collections.emptyMap());
            }
        });
        
        // Also send confirmation to console
        sender.sendMessage(Text.colorize("&aEscalation sent to staff!"));
    }

    private void handleHideSubcommand(@NotNull CommandSender sender, String @NotNull [] args) {
        if (!sender.hasPermission("core.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core hide");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /core hide <creategroup|deletegroup|renamegroup|group>");
            return;
        }
        // Save/load hide.yml from plugins/Allium/hide.yml
        java.io.File hideFile = new java.io.File(plugin.getDataFolder(), "hide.yml");
        org.bukkit.configuration.file.YamlConfiguration hideConfig =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(hideFile);
        String groupPath = "groups.";
        switch (args[1].toLowerCase()) {
            case "creategroup": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /core hide creategroup <name>");
                    return;
                }
                String group = args[2];
                if (hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' already exists.");
                    return;
                }
                hideConfig.set(groupPath + group + ".whitelist", true);
                hideConfig.set(groupPath + group + ".commands", new java.util.ArrayList<String>());
                hideConfig.set(groupPath + group + ".tabcompletes", new java.util.ArrayList<String>());
                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + group + "' created.");
                break;
            }
            case "deletegroup": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /core hide deletegroup <name>");
                    return;
                }
                String group = args[2];
                if (!hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' does not exist.");
                    return;
                }
                hideConfig.set(groupPath + group, null);
                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + group + "' deleted.");
                break;
            }
            case "renamegroup": {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /core hide renamegroup <oldname> <newname>");
                    return;
                }
                String oldGroup = args[2];
                String newGroup = args[3];
                
                // Check if old group exists
                if (!hideConfig.contains(groupPath + oldGroup)) {
                    sender.sendMessage("§cGroup '" + oldGroup + "' does not exist.");
                    return;
                }
                
                // Check if new group name already exists
                if (hideConfig.contains(groupPath + newGroup)) {
                    sender.sendMessage("§cGroup '" + newGroup + "' already exists.");
                    return;
                }
                
                // Copy all settings from old group to new group
                boolean whitelist = hideConfig.getBoolean(groupPath + oldGroup + ".whitelist");
                java.util.List<String> commands = hideConfig.getStringList(groupPath + oldGroup + ".commands");
                java.util.List<String> tabcompletes = hideConfig.getStringList(groupPath + oldGroup + ".tabcompletes");
                
                // Create new group with same settings
                hideConfig.set(groupPath + newGroup + ".whitelist", whitelist);
                hideConfig.set(groupPath + newGroup + ".commands", commands);
                hideConfig.set(groupPath + newGroup + ".tabcompletes", tabcompletes);
                
                // Remove old group
                hideConfig.set(groupPath + oldGroup, null);
                
                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + oldGroup + "' renamed to '" + newGroup + "'.");
                break;
            }
            case "group": {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /core hide group <name> <add [-s]|remove> <cmd>");
                    return;
                }
                String group = args[2];
                if (!hideConfig.contains(groupPath + group)) {
                    sender.sendMessage("§cGroup '" + group + "' does not exist.");
                    return;
                }
                String action = args[3].toLowerCase();
                boolean onlyCommands = false;
                int cmdIndex = 4;
                if (action.equals("add") && args[4].equalsIgnoreCase("-s")) {
                    onlyCommands = true;
                    cmdIndex = 5;
                    if (args.length < 6) {
                        sender.sendMessage("§cUsage: /core hide group <name> add [-s] <cmd>");
                        return;
                    }
                }
                String cmd = args[cmdIndex];
                java.util.List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                java.util.List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
                if (action.equals("add")) {
                    if (!commands.contains(cmd)) {
                        commands.add(cmd);
                    }
                    if (!onlyCommands && !tabcompletes.contains(cmd)) {
                        tabcompletes.add(cmd);
                    }
                    hideConfig.set(groupPath + group + ".commands", commands);
                    if (!onlyCommands) hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                    saveHideConfig(hideConfig, hideFile);
                    handleReloadCommand(sender, null);
                    sender.sendMessage("§aAdded '" + cmd + "' to group '" + group + "'.");
                } else if (action.equals("remove")) {
                    boolean changed = false;
                    if (commands.remove(cmd)) changed = true;
                    if (tabcompletes.remove(cmd)) changed = true;
                    hideConfig.set(groupPath + group + ".commands", commands);
                    hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                    saveHideConfig(hideConfig, hideFile);
                    handleReloadCommand(sender, null);
                    if (changed) {
                        sender.sendMessage("§aRemoved '" + cmd + "' from group '" + group + "'.");
                    } else {
                        sender.sendMessage("§e'" + cmd + "' was not present in group '" + group + "'.");
                    }
                } else {
                    sender.sendMessage("§cUsage: /core hide group <name> <add [-s]|remove> <cmd>");
                }
                break;
            }
            default:
                sender.sendMessage("§cUnknown hide subcommand. Use /core hide <creategroup|deletegroup|renamegroup|group>.");
        }
    }

    private void saveHideConfig(org.bukkit.configuration.file.YamlConfiguration hideConfig, java.io.File file) {
        try {
            hideConfig.save(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("core")) {
            return null;
        }
        return getCoreSuggestions(sender, args);
    }

    private List<String> getCoreSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();
        String currentArg = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            // Suggest subcommands for /core
            if (sender.hasPermission("core.admin")) {
                suggestions.add("reload");
                suggestions.add("debug");
                suggestions.add("hideupdate");
                suggestions.add("hide");
                suggestions.add("escalate");
                // modalerts command removed
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("hide")) {
            if (args.length == 2) {
                suggestions.addAll(List.of("creategroup", "deletegroup", "group"));
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("deletegroup") || args[1].equalsIgnoreCase("group"))) {
                // Suggest group names for deletegroup/group
                org.bukkit.configuration.file.YamlConfiguration hideConfig =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.File(plugin.getDataFolder(), "hide.yml"));
                if (hideConfig.isConfigurationSection("groups")) {
                    suggestions.addAll(hideConfig.getConfigurationSection("groups").getKeys(false));
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("group")) {
                suggestions.addAll(List.of("add", "remove"));
            } else if (args.length == 5 && args[1].equalsIgnoreCase("group") && args[3].equalsIgnoreCase("add")) {
                suggestions.add("-s");
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("worlddefaults")) {
            suggestions.addAll(getWorldDefaultsSuggestions(sender, args));
        } else if (args.length > 1 && args[0].equalsIgnoreCase("updatehide")) {
            suggestions.addAll(getUpdateHideSuggestions(args));
        } else if (args.length > 1 && args[0].equalsIgnoreCase("escalate")) {
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
            }
        }
        // Filter final list of completions by currentArg. This handles cases where completions were added without pre-filtering.
        // For subcommands like 'voucher' that already filter, this is a bit redundant but harmless.
        // For subcommands that add to 'suggestions' without filtering by currentArg, this is necessary.
        List<String> finalSuggestions = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(currentArg)) {
                finalSuggestions.add(suggestion);
            }
        }
        return finalSuggestions.isEmpty() && args.length > 0 ? Collections.emptyList() : finalSuggestions;
    }

    private List<String> getWorldDefaultsSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 2) {
            // Suggest gamerules from the config.yml under "world-defaults"
            if (config.isConfigurationSection("world-defaults")) {
                suggestions.addAll(config.getConfigurationSection("world-defaults").getKeys(false));
            }
        } else if (args.length == 3) {
            // Suggest possible values for gamerules
            suggestions.addAll(List.of("true", "false", "1", "0"));
        }

        return suggestions;
    }

    private List<String> getUpdateHideSuggestions(String[] args) {
        List<String> suggestions = new ArrayList<>();
        if(args.length == 1) {
            suggestions.add("all");

            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
        }
        return suggestions;
    }
}