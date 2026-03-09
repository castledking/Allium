package net.survivalfun.core.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventoryManager;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.items.HandcuffsItem;
import net.survivalfun.core.listeners.security.CommandManager;
import net.survivalfun.core.listeners.security.CreativeManager;
import net.survivalfun.core.managers.config.WorldDefaults;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.migration.EssentialsMigration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.json.JSONArray;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.survivalfun.core.util.SchedulerAdapter;

public class Core implements CommandExecutor, TabCompleter {

    private final WorldDefaults worldDefaults;
    private final PluginStart plugin;
    private final FileConfiguration config;
    private final Lang lang;
    private final CommandManager commandManager;
    private final CreativeManager creativeManager;
    private final InventoryManager inventoryManager;

    public Core(WorldDefaults worldDefaults, PluginStart plugin, FileConfiguration config, CommandManager commandManager
            , CreativeManager creativeManager, InventoryManager inventoryManager) {
        this.worldDefaults = worldDefaults;
        this.plugin = plugin;
        this.config = config;
        this.lang = plugin.getLangManager();
        this.commandManager = commandManager;
        this.creativeManager = creativeManager;
        this.inventoryManager = inventoryManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            Text.sendDebugLog(ERROR, "LanguageManager not initialized when executing Core command");
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

            case "rp":
                handleReloadResourcePackCommand(sender, args);
                break;

            case "debug":
                handleDebugCommand(sender, args);
                break;
            case "cmd":
            case "command": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cOnly players can run bridged commands.");
                    break;
                }
                if (args.length < 2) {
                    sender.sendMessage("§eUsage: /core cmd <id>");
                    break;
                }
                net.survivalfun.core.managers.commands.CommandBridgeManager cb = plugin.getCommandBridgeManager();
                if (cb == null) { sender.sendMessage("§cBridge system unavailable."); break; }
                String id = args[1];
                boolean ok = cb.execute((Player) sender, id);
                if (!ok) sender.sendMessage("§cUnknown or unavailable command id: " + id);
                break;
            }
            case "hideupdate":
                handleHideUpdateCommand(sender, args);
                break;
            case "hide":
                handleHideSubcommand(sender, args);
                break;
            case "escalate":
                handleEscalateCommand(sender, args);
                break;

            case "restore":
                handleRestoreCommand(sender, args);
                break;
            case "item":
                handleItemCommand(sender, args);
                break;
            case "dialog":
                handleDialogSubcommand(sender, args);
                break;

            case "delmsg":
                handleDelmsgStatusCommand(sender);
                break;

            case "migrate":
                handleMigrateCommand(sender, args);
                break;
            case "sethomes":
                handleSethomesCommand(sender, args);
                break;

            default:
                sender.sendMessage("§cUnknown subcommand. Use /core for help.");
                break;
        }

        return true;
    }

    private void handleItemCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core item");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§eUsage: /core item <give|list>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "give":
                handleItemGiveCommand(sender, args);
                break;
            case "list":
                handleItemListCommand(sender);
                break;
            default:
                sender.sendMessage("§cUnknown item subcommand. Use /core item <give|list>");
                break;
        }
    }

    private void handleItemGiveCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§eUsage: /core item give <player> <item>");
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[2]);
            return;
        }

        String itemName = args[3].toLowerCase();

        switch (itemName) {
            case "handcuffs":
                target.getInventory().addItem(HandcuffsItem.createHandcuffs());
                sender.sendMessage("§aGave handcuffs to " + target.getName());
                break;
            case "lazy_axe":
            case "tree_axe": {
                net.survivalfun.core.items.CustomItem item = CustomItemRegistry.getInstance() != null ? CustomItemRegistry.getInstance().getItem("tree_axe") : null;
                if (item != null) {
                    target.getInventory().addItem(item.createItemStack(1));
                    sender.sendMessage("§aGave Lazy Axe to " + target.getName());
                } else {
                    sender.sendMessage("§cCustom item system not available.");
                }
                break;
            }
            case "spawner_changer": {
                net.survivalfun.core.items.CustomItem item = CustomItemRegistry.getInstance() != null ? CustomItemRegistry.getInstance().getItem("spawner_changer") : null;
                if (item != null) {
                    target.getInventory().addItem(item.createItemStack(1));
                    sender.sendMessage("§aGave Spawner Changer to " + target.getName());
                } else {
                    sender.sendMessage("§cCustom item system not available.");
                }
                break;
            }
            default:
                sender.sendMessage("§cUnknown item: " + itemName);
                break;
        }
    }

    private void handleItemListCommand(CommandSender sender) {
        sender.sendMessage("§aAvailable items:");
        sender.sendMessage("§ehandcuffs §7- Handcuffs item for restraining players");
        sender.sendMessage("§elazy_axe §7- Lazy Axe (chops entire trees)");
        sender.sendMessage("§espawner_changer §7- Spawner Type Changer (right-click spawners)");
    }

    private void handleDialogSubcommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core dialog");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§eUsage: /core dialog <show|list>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "show":
                if (args.length < 4) {
                    sender.sendMessage("§eUsage: /core dialog show <player> <dialog>");
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[2]);
                    return;
                }
                String dialogName = args[3];
                
                // Use inline JSON dialog - instant application, no datapack needed
                net.survivalfun.core.managers.core.DialogManager dm = new net.survivalfun.core.managers.core.DialogManager(plugin);
                dm.loadDialogs();
                
                if (!dm.hasDialog(dialogName)) {
                    sender.sendMessage("§cDialog not found: " + dialogName);
                    sender.sendMessage("§7Use §e/core dialog list §7to see available dialogs");
                    return;
                }
                
                String inlineJson = dm.getDialogInlineJson(dialogName, target.getName());
                if (inlineJson == null) {
                    sender.sendMessage("§cFailed to generate dialog JSON");
                    return;
                }
                
                // Send as native Minecraft command with inline JSON
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + target.getName() + " " + inlineJson);
                sender.sendMessage("§aShowing dialog '" + dialogName + "' to " + target.getName());
                break;

            case "list":
                net.survivalfun.core.managers.core.DialogManager dmList = new net.survivalfun.core.managers.core.DialogManager(plugin);
                dmList.loadDialogs();
                sender.sendMessage("§6=== Available Dialogs ===");
                for (String name : dmList.getDialogs().keySet()) {
                    sender.sendMessage("§e- " + name);
                }
                sender.sendMessage("§7Use §e/core dialog show <player> <dialog> §7to show a dialog");
                break;

            default:
                sender.sendMessage("§eUsage: /core dialog <show|list>");
                break;
        }
    }
    
    private void deleteDirectory(java.io.File dir) {
        if (dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }
    
    private void copyDirectory(java.io.File src, java.io.File dest) throws java.io.IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) {
                dest.mkdirs();
            }
            java.io.File[] files = src.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    java.io.File destFile = new java.io.File(dest, file.getName());
                    copyDirectory(file, destFile);
                }
            }
        } else {
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void handleRestoreCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("allium.restore")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "core restore");
            return;
        }

        UUID targetId = player.getUniqueId();

        if (args.length > 1 && player.hasPermission("allium.restore.others")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target != null) {
                targetId = target.getUniqueId();
            } else {
                // Try to get offline player
                targetId = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            }
        }

        new net.survivalfun.core.inventory.gui.RestoreGUI(player, targetId, inventoryManager, plugin).open();
    }

    private void handleHideUpdateCommand(@NotNull CommandSender sender, String[] args) {
        if(!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        
        // Case 1: No additional arguments or "all" argument - update all players
        if(args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("all"))) {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Update tab completion without touching gamemode
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
     * Updates a player's tab completion using the CommandManager only.
     * No gamemode changes are performed.
     */
    private void updatePlayerTabCompletionWithGamemode(Player player) {
        try {
            commandManager.updatePlayerTabCompletion(player);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error updating tab completion for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        if(!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        sender.sendMessage("§aAvailable /core subcommands:");
        sender.sendMessage("§edebug §7- Toggle debug mode.");
        sender.sendMessage("§ereload §7- Reload plugin configuration.");
        sender.sendMessage("§ereload rp §7- Reload resourcepack.yml and refresh players.");
        sender.sendMessage("§ehideupdate §7- Refresh tab completion for player.");
        sender.sendMessage("§erestore §7- Open inventory restoration GUI.");
        sender.sendMessage("§eescalate §7- Escalate an issue to staff.");
        sender.sendMessage("§emigrate §7- Migrate Essentials userdata (homes, economy, warps).");
        sender.sendMessage("§esethomes §7- Set a player's max homes (staff override).");
    }

    private void handleSetGameruleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.worlddefaults")) {
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

    private void handleDelmsgStatusCommand(CommandSender sender) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang);
            return;
        }
        boolean supportsResend = plugin.getChatPacketTracker().supportsResend();
        if (supportsResend) {
            sender.sendMessage(Component.text("§a/delmsg: PacketEvents OK - full clear+resend enabled"));
        } else {
            sender.sendMessage(Component.text("§e/delmsg: PacketEvents not available - only header sent. Retrying init..."));
            plugin.retryChatPacketTrackerInitIfNeeded();
            if (plugin.getChatPacketTracker().supportsResend()) {
                sender.sendMessage(Component.text("§aRetry succeeded - full clear+resend now enabled"));
            } else {
                sender.sendMessage(Component.text("§cRetry failed. Ensure PacketEvents plugin JAR is in plugins/ and loads before Allium. Enable debug-mode in config for details."));
                net.survivalfun.core.packetevents.PacketEventsLoader.logPacketEventsStatus(plugin.getLogger());
            }
        }
    }

    private void handleMigrateCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core migrate");
            return;
        }
        // /core migrate [homes|economy|warps|all] [path]
        String type = args.length >= 2 ? args[1].toLowerCase() : "all";
        File basePath;
        if (args.length >= 3) {
            basePath = new File(args[2]);
            if (!basePath.isDirectory()) {
                sender.sendMessage(Component.text("§cPath is not a directory: " + basePath.getAbsolutePath()));
                return;
            }
        } else {
            basePath = new File(plugin.getDataFolder().getParentFile(), "Essentials");
            if (!basePath.isDirectory()) {
                sender.sendMessage(Component.text("§cEssentials folder not found. Use: /core migrate <homes|economy|warps|all> <path>"));
                return;
            }
        }
        EssentialsMigration migration = new EssentialsMigration(plugin.getLogger(), plugin.getDatabase(), basePath);
        sender.sendMessage(Component.text("§7Migrating from: " + basePath.getAbsolutePath()));

        if ("homes".equals(type) || "all".equals(type)) {
            if (!migration.hasUserdata()) {
                sender.sendMessage(Component.text("§eNo userdata folder found, skipping homes."));
            } else {
                EssentialsMigration.MigrationResult r = migration.migrateHomes();
                sender.sendMessage(Component.text("§aHomes: " + r.getSuccess().size() + " players migrated."));
                for (String e : r.getErrors()) sender.sendMessage(Component.text("§c  " + e));
                for (String s : r.getSkipped()) sender.sendMessage(Component.text("§e  " + s));
            }
        }
        if ("economy".equals(type) || "all".equals(type)) {
            if (!migration.hasUserdata()) {
                sender.sendMessage(Component.text("§eNo userdata folder found, skipping economy."));
            } else {
                EssentialsMigration.MigrationResult r = migration.migrateEconomy();
                sender.sendMessage(Component.text("§aEconomy: " + r.getSuccess().size() + " players migrated."));
                for (String e : r.getErrors()) sender.sendMessage(Component.text("§c  " + e));
            }
        }
        if ("warps".equals(type) || "all".equals(type)) {
            if (!migration.hasWarps()) {
                sender.sendMessage(Component.text("§eNo warps source found (warps/ directory or warps.yml), skipping warps."));
            } else {
                EssentialsMigration.MigrationResult r = migration.migrateWarps();
                sender.sendMessage(Component.text("§aWarps: " + r.getSuccess().size() + " warps migrated."));
                for (String e : r.getErrors()) sender.sendMessage(Component.text("§c  " + e));
            }
        }
        if (!"homes".equals(type) && !"economy".equals(type) && !"warps".equals(type) && !"all".equals(type)) {
            sender.sendMessage(Component.text("§cUsage: /core migrate <homes|economy|warps|all> [path]"));
        }
    }

    private void handleSethomesCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core sethomes");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("§eUsage: /core sethomes <player> <number>"));
            sender.sendMessage(Component.text("§7Set a player's max homes. Use -1 to clear (use permissions again)."));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p.getName() != null && p.getName().equalsIgnoreCase(args[1])) {
                    target = p;
                    break;
                }
            }
        }
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
            return;
        }
        int maxHomes;
        try {
            maxHomes = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("§cInvalid number. Use -1 to clear override."));
            return;
        }
        if (maxHomes < -1 || maxHomes > 1000) {
            sender.sendMessage(Component.text("§cNumber must be between -1 and 1000 (-1 = use permissions)."));
            return;
        }
        boolean ok = plugin.getDatabase().setPlayerMaxHomes(target.getUniqueId(), maxHomes);
        if (ok) {
            if (maxHomes < 0) {
                sender.sendMessage(Component.text("§aCleared max homes override for " + (target.getName() != null ? target.getName() : target.getUniqueId()) + ". They now use permissions."));
            } else {
                sender.sendMessage(Component.text("§aSet max homes for " + (target.getName() != null ? target.getName() : target.getUniqueId()) + " to " + maxHomes + "."));
            }
        } else {
            sender.sendMessage(Component.text("§cFailed to set max homes (check console)."));
        }
    }

    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
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
        Text.sendDebugLog(INFO, "Debug mode " + (newDebugMode ? "enabled" : "disabled") + " by " + sender.getName());
    }

    private void handleReloadCommand(CommandSender sender, Boolean isDebug) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        
        // Reload auto-restart configuration if enabled
        if (plugin.getConfig().getBoolean("auto-restart.enabled", false)) {
            try {
                plugin.getAutoRestartCommand().loadConfig();
                Text.sendDebugLog(INFO, "Auto-restart configuration reloaded by " + sender.getName());
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to reload auto-restart configuration: " + e.getMessage());
                e.printStackTrace();
            }
        }
        try {
            // Ensure database folder exists and is initialized
            Database database = plugin.getDatabase();
            if (database == null) {
                Text.sendDebugLog(ERROR, "Failed to initialize database during reload");
                Text.sendErrorMessage(sender, "Failed to initialize database. Check console for details.", lang, Collections.emptyMap());
                return;
            }

            // Reload the main config
            plugin.reloadConfig();

            // Reload the command manager
            commandManager.reload();

            // Reload the creative manager blacklists
            creativeManager.reloadBlacklists();

            // Re-initialize the ConfigManager to apply changes
            if (plugin.getConfigManager() != null) {
                plugin.getConfigManager().forceCompleteReload(plugin);
            }

            // Reload language files
            if (lang != null) {
                lang.reload();
            }
            
            // Reload item aliases and legacy IDs from itemdb.yml
            try {
                Alias.reload(); // This will reload all aliases from itemdb.yml
                LegacyID.reload(); // This will reload all legacy IDs
                // Reload tab completion cache
                if (plugin.getTabCompleter() != null) {
                    plugin.getTabCompleter().reloadAliases();
                }
                Text.sendDebugLog(INFO, "Successfully reloaded item aliases and legacy IDs from itemdb.yml");
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload itemdb.yml data: " + e.getMessage());
            }

            // Reload and regenerate dialog datapacks
            try {
                net.survivalfun.core.managers.core.DialogManager dialogManager = new net.survivalfun.core.managers.core.DialogManager(plugin);
                dialogManager.loadDialogs();
                dialogManager.generateDataPack();
                Text.sendDebugLog(INFO, "Dialog datapacks reloaded and regenerated");
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload dialog datapacks: " + e.getMessage());
            }

            // Reload world defaults
            if (worldDefaults != null) {
                worldDefaults.plugin().reloadConfig();
                worldDefaults.applyWorldDefaults();
            }

            // Reload party manager configuration
            if (plugin.getPartyManager() != null) {
                plugin.getPartyManager().reloadConfig();
                Text.sendDebugLog(INFO, "Successfully reloaded party manager configuration");
            }

            // Reload the explode command configuration
            Explode explodeCommand = plugin.getExplodeCommand();
            if (explodeCommand != null) {
                explodeCommand.reloadConfig(); 
            }
            
            // Reload dynamic permissions
            if (plugin.getDynamicPermissionManager() != null) {
                plugin.getDynamicPermissionManager().reload();
                Text.sendDebugLog(INFO, "Dynamic permissions reloaded successfully.");
            }

            // Reload creeper explosion settings
            try {
                net.survivalfun.core.listeners.jobs.CreeperExplosion listener = 
                    net.survivalfun.core.listeners.jobs.CreeperExplosion.getInstance();
                if (listener != null) {
                    listener.reloadConfig();
                    Text.sendDebugLog(INFO, "Successfully reloaded creeper explosion settings");
                } else {
                    Text.sendDebugLog(WARN, "Creeper explosion listener not found");
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload creeper explosion settings: " + e.getMessage());
                e.printStackTrace();
            }

            // Reload command bridges
            try {
                if (plugin.getCommandBridgeManager() != null) {
                    plugin.getCommandBridgeManager().load();
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload commands.yml bridges: " + e.getMessage());
            }

            // Reload chat formatter with a delay to ensure config is fully processed
            SchedulerAdapter.runLater(() -> {
                plugin.reloadChatFormatter();
            }, 1L);

            // Build success message with optional debug status
            StringBuilder successMessage = new StringBuilder("§aConfiguration reloaded successfully.");

            // Add debug status if isDebug is not null (meaning it was explicitly set)
            if (isDebug != null) {
                boolean debugMode = plugin.getConfig().getBoolean("debug-mode", false);
                successMessage.append(" (Debug: ").append(debugMode ? "§aenabled§a" : "§cdisabled§a").append(")");
            }

            sender.sendMessage(successMessage.toString());
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error reloading configuration: ", e);
            sender.sendMessage("§cError reloading configuration! Check console for details.");
        }
    }

    private void handleReloadResourcePackCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core reload rp");
            return;
        }

        net.survivalfun.core.managers.ResourcePackManager rpm = 
            net.survivalfun.core.managers.ResourcePackManager.getInstance();
        
        if (rpm == null) {
            sender.sendMessage("§cResource pack manager not initialized.");
            return;
        }

        String oldUrl = rpm.getResourcePackUrl();
        rpm.reload();
        String newUrl = rpm.getResourcePackUrl();

        boolean packChanged = rpm.hasPackChanged();

        if (!rpm.isEnabled()) {
            sender.sendMessage("§eResource pack is disabled (no URL set in resourcepack.yml)");
            return;
        }

        if (packChanged) {
            sender.sendMessage("§aResource pack URL changed from:");
            sender.sendMessage("§7  Old: " + (oldUrl.isEmpty() ? "(none)" : oldUrl));
            sender.sendMessage("§7  New: " + newUrl);
            sender.sendMessage("§aRefreshing all online players with new resource pack...");
            rpm.refreshAllPlayers();
            sender.sendMessage("§aDone! All players have been sent the new resource pack.");
        } else {
            sender.sendMessage("§eResource pack URL unchanged (§7" + newUrl + "§e)");
            sender.sendMessage("§eRefreshing all online players anyway...");
            rpm.refreshAllPlayers();
            sender.sendMessage("§aDone! All players have been sent the resource pack.");
        }
    }

    private void handleEscalateCommand(CommandSender sender, String[] args) {
        // This command is console-only
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            Text.sendErrorMessage(sender, "console-only", lang);
            return;
        }

        if (args.length < 4) {
            lang.sendMessage(sender, "command-usage", "{cmd}", "/core escalate", "{args}", "<staffmember> <target> <reason>");
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
                Text.sendDebugLog(ERROR, "Failed to send escalation to Discord", e);
                Text.sendErrorMessage(sender, "command.core.escalate.failed", lang, Collections.emptyMap());
            }
        });

        // Also send confirmation to console
        sender.sendMessage(Text.colorize("&aEscalation sent to staff!"));
    }

    private void handleHideSubcommand(@NotNull CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.admin")) {
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
                String cmd = args[cmdIndex].toLowerCase();
                List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
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
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("core")) {
            return null;
        }
        return getCoreSuggestions(sender, args);
    }

    private List<String> getCoreSuggestions(@NotNull CommandSender sender, String[] args) {
        List<String> suggestions = new ArrayList<>();
        String currentArg = args[args.length - 1].toLowerCase();

            if (args.length == 1) {
            // Suggest subcommands for /core
            if (sender.hasPermission("allium.admin")) {
                suggestions.add("reload");
                suggestions.add("debug");
                suggestions.add("hideupdate");
                suggestions.add("hide");
                suggestions.add("escalate");
                suggestions.add("cmd");
                suggestions.add("command");
                suggestions.add("dialog");
                suggestions.add("item");
                suggestions.add("rp");
                suggestions.add("migrate");
                suggestions.add("sethomes");
            }
            if (sender.hasPermission("allium.restore")) {
                suggestions.add("restore");
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("reload")) {
            if (args.length == 2) {
                suggestions.add("rp");
            }
        } else if (args.length > 1 && (args[0].equalsIgnoreCase("cmd") || args[0].equalsIgnoreCase("command"))) {
            if (args.length == 2) {
                net.survivalfun.core.managers.commands.CommandBridgeManager cb = plugin.getCommandBridgeManager();
                if (cb != null) suggestions.addAll(cb.getIds());
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
        } else if (args.length > 1 && args[0].equalsIgnoreCase("dialog")) {
            if (args.length == 2) {
                // Suggest dialog subcommands
                suggestions.add("show");
                suggestions.add("list");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("show")) {
                // Suggest player names
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("show")) {
                // Suggest dialog names
                net.survivalfun.core.managers.core.DialogManager dm = new net.survivalfun.core.managers.core.DialogManager(plugin);
                dm.loadDialogs();
                suggestions.addAll(dm.getDialogs().keySet());
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("restore")) {
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("migrate")) {
            if (args.length == 2) {
                suggestions.addAll(List.of("homes", "economy", "warps", "all"));
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("sethomes")) {
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
                for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                    if (p.getName() != null && !p.getName().isEmpty()) {
                        suggestions.add(p.getName());
                    }
                }
            } else if (args.length == 3) {
                suggestions.addAll(List.of("1", "3", "5", "10", "-1"));
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("item")) {
            if (args.length == 2) {
                suggestions.add("give");
                suggestions.add("list");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                suggestions.addAll(List.of("handcuffs", "lazy_axe", "spawner_changer"));
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

    private List<String> getWorldDefaultsSuggestions(@NotNull CommandSender sender, String[] args) {
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
