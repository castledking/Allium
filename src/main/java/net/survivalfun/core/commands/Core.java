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
import net.survivalfun.core.managers.core.SecurityAlertManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.migration.EssentialsMigration;
import net.survivalfun.core.managers.chat.ChatFilterManager;
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

    private record HideScope(boolean commands, boolean tabCompletes, int nextArgIndex) {}
    private enum FilterSectionType {
        TOKEN("tokens"),
        PHRASE("phrases"),
        SUBSTRING("substrings"),
        REGEX("regex");

        private final String path;

        FilterSectionType(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }

        static FilterSectionType from(String input) {
            if (input == null) return null;
            return switch (input.toLowerCase(Locale.ROOT)) {
                case "token", "tokens" -> TOKEN;
                case "phrase", "phrases" -> PHRASE;
                case "substring", "substrings" -> SUBSTRING;
                case "regex" -> REGEX;
                default -> null;
            };
        }
    }

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
            case "alerts":
                handleAlertsCommand(sender, args);
                break;
            case "item":
                handleItemCommand(sender, args);
                break;
            case "dialog":
                handleDialogSubcommand(sender, args);
                break;
            case "filter":
                handleFilterSubcommand(sender, args);
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
        boolean canAdmin = sender.hasPermission("allium.admin");
        boolean canRestore = sender.hasPermission("allium.restore");
        boolean canAlerts = sender.hasPermission("allium.alerts");
        if (!canAdmin && !canRestore && !canAlerts) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core");
            return;
        }
        sender.sendMessage("§aAvailable /core subcommands:");
        if (canAdmin) {
            sender.sendMessage("§edebug §7- Toggle debug mode.");
            sender.sendMessage("§ereload §7- Reload plugin configuration.");
            sender.sendMessage("§ereload rp §7- Reload resourcepack.yml and refresh players.");
            sender.sendMessage("§ehideupdate §7- Refresh tab completion for player.");
            sender.sendMessage("§eescalate §7- Escalate an issue to staff.");
            sender.sendMessage("§emigrate §7- Migrate Essentials userdata (homes, economy, warps).");
            sender.sendMessage("§esethomes §7- Set a player's max homes (staff override).");
        }
        if (canRestore) {
            sender.sendMessage("§erestore §7- Open inventory restoration GUI.");
        }
        if (canAlerts) {
            sender.sendMessage("§ealerts §7- Toggle security alert feeds.");
        }
    }

    private void handleAlertsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return;
        }
        if (!player.hasPermission("allium.alerts")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "core alerts");
            return;
        }

        SecurityAlertManager alerts = plugin.getSecurityAlertManager();
        if (alerts == null) {
            player.sendMessage("§cAlert system unavailable.");
            return;
        }

        if (args.length == 1) {
            sendAlertsCenter(player, alerts);
            return;
        }

        if (!args[1].equalsIgnoreCase("gamemode")) {
            player.sendMessage("§eUsage: /core alerts [gamemode [on|off|toggle]]");
            return;
        }

        boolean enabled;
        if (args.length == 2 || args[2].equalsIgnoreCase("toggle")) {
            enabled = alerts.toggle(player, SecurityAlertManager.AlertType.GAMEMODE_AUDIT);
        } else if (args[2].equalsIgnoreCase("on")) {
            alerts.setEnabled(player, SecurityAlertManager.AlertType.GAMEMODE_AUDIT, true);
            enabled = true;
        } else if (args[2].equalsIgnoreCase("off")) {
            alerts.setEnabled(player, SecurityAlertManager.AlertType.GAMEMODE_AUDIT, false);
            enabled = false;
        } else {
            player.sendMessage("§eUsage: /core alerts [gamemode [on|off|toggle]]");
            return;
        }

        player.sendMessage("§aGamemode audit alerts " + (enabled ? "enabled" : "disabled") + "§a.");
        sendAlertsCenter(player, alerts);
    }

    private void sendAlertsCenter(Player player, SecurityAlertManager alerts) {
        boolean gamemodeEnabled = alerts.isEnabled(player, SecurityAlertManager.AlertType.GAMEMODE_AUDIT);
        player.sendMessage("§8§m-----------------");
        player.sendMessage("§e/core alerts");
        player.sendMessage("§7Gamemode audit alerts: " + (gamemodeEnabled ? "§aenabled" : "§cdisabled"));
        player.sendMessage("§7Toggle: §f/core alerts gamemode");
        player.sendMessage("§8§m-----------------");
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

        // Economy-only: alias for Vault live conversion (both economies running)
        if ("economy".equals(type)) {
            sender.sendMessage(Component.text("§7Running: /vault-convert Essentials-Economy Allium-Economy"));
            Bukkit.dispatchCommand(sender, "vault-convert Essentials-Economy Allium-Economy");
            return;
        }

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

            // Reload channel manager (chat channels and Discord settings)
            try {
                if (plugin.getChannelManager() != null) {
                    plugin.getChannelManager().reload();
                    Text.sendDebugLog(INFO, "Channel manager reloaded successfully.");
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload channel manager: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                if (plugin.getSpamBlockerManager() != null) {
                    plugin.getSpamBlockerManager().reload();
                    Text.sendDebugLog(INFO, "Spam blocker manager reloaded successfully.");
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload spam blocker manager: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                if (plugin.getChatFilterManager() != null) {
                    plugin.getChatFilterManager().reload();
                    Text.sendDebugLog(INFO, "Chat filter manager reloaded successfully.");
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to reload chat filter manager: " + e.getMessage());
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
            sender.sendMessage("§eResource pack is disabled in resourcepack.yml (enabled: false).");
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

        // Check if URL is reachable (Minecraft client often fails on redirects; use raw.githubusercontent.com for GitHub)
        String urlToCheck = newUrl;
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            boolean reachable = checkResourcePackUrlReachable(urlToCheck);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (reachable) {
                    sender.sendMessage("§aResource pack URL is reachable from the server.");
                } else {
                    sender.sendMessage("§cResource pack URL could not be reached (HTTP error or redirect). If clients fail to download, use a direct URL (e.g. raw.githubusercontent.com for GitHub).");
                }
            });
        });
    }

    /** Returns true if the URL returns 2xx when following redirects (HEAD request). */
    private boolean checkResourcePackUrlReachable(String urlString) {
        if (urlString == null || urlString.isEmpty()) return false;
        try {
            HttpURLConnection.setFollowRedirects(true);
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Allium/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Resource pack URL check failed: " + e.getMessage());
            return false;
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

    private void handleFilterSubcommand(CommandSender sender, String[] args) {
        ChatFilterManager filterManager = plugin.getChatFilterManager();
        if (filterManager == null) {
            sender.sendMessage("§cChat filter manager is not available.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§eUsage: /core filter <test|check|add|remove|set> ...");
            return;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "test" -> handleFilterTest(sender, args, filterManager);
            case "check" -> handleFilterCheck(sender, args, filterManager);
            case "add" -> handleFilterModify(sender, args, FilterModifyAction.ADD, filterManager);
            case "remove" -> handleFilterModify(sender, args, FilterModifyAction.REMOVE, filterManager);
            case "set" -> handleFilterModify(sender, args, FilterModifyAction.SET, filterManager);
            default -> sender.sendMessage("§eUsage: /core filter <test|check|add|remove|set> ...");
        }
    }

    private void handleFilterTest(CommandSender sender, String[] args, ChatFilterManager filterManager) {
        if (!sender.hasPermission("allium.filter.test") && !sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core filter test");
            return;
        }

        int messageStartIndex = 3;
        ChatFilterManager.FilterContext context = ChatFilterManager.FilterContext.PLAYER_CHAT;
        if (args.length >= 3) {
            String requestedContext = args[2].toLowerCase(Locale.ROOT);
            if (requestedContext.equals("chat")) {
                context = ChatFilterManager.FilterContext.PLAYER_CHAT;
            } else if (requestedContext.equals("command")) {
                context = ChatFilterManager.FilterContext.PLAYER_COMMAND;
            } else if (requestedContext.equals("discord")) {
                context = ChatFilterManager.FilterContext.DISCORD_TO_MINECRAFT;
            } else {
                messageStartIndex = 2;
            }
        } else {
            messageStartIndex = 2;
        }

        if (args.length <= messageStartIndex) {
            sender.sendMessage("§eUsage: /core filter test [chat|command|discord] <message>");
            return;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, messageStartIndex, args.length));
        sendFilterEvaluation(sender, filterManager.dryRun(context, message), false);
    }

    private void handleFilterCheck(CommandSender sender, String[] args, ChatFilterManager filterManager) {
        if (!sender.hasPermission("allium.filter.test") && !sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core filter check");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§eUsage: /core filter check <message>");
            return;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        sender.sendMessage("§eFilter check for: §f" + message);
        sendFilterEvaluation(sender, filterManager.dryRun(ChatFilterManager.FilterContext.PLAYER_CHAT, message), true);
        sendFilterEvaluation(sender, filterManager.dryRun(ChatFilterManager.FilterContext.PLAYER_COMMAND, message), true);
        sendFilterEvaluation(sender, filterManager.dryRun(ChatFilterManager.FilterContext.DISCORD_TO_MINECRAFT, message), true);
    }

    private enum FilterModifyAction {
        ADD,
        REMOVE,
        SET
    }

    private void handleFilterModify(CommandSender sender, String[] args, FilterModifyAction action, ChatFilterManager filterManager) {
        if (!sender.hasPermission("allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "core filter " + action.name().toLowerCase(Locale.ROOT));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§eUsage: /core filter " + action.name().toLowerCase(Locale.ROOT) + " <token|phrase|substring|regex> <value>");
            return;
        }

        FilterSectionType type = FilterSectionType.from(args[2]);
        if (type == null) {
            sender.sendMessage("§cUnknown filter type. Use token, phrase, substring, or regex.");
            return;
        }

        File filterFile = getWordPhraseFilterFile();
        org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(filterFile);
        List<Object> sectionValues = new ArrayList<>(config.getList(type.path(), new ArrayList<>()));

        switch (action) {
            case ADD -> {
                if (type == FilterSectionType.TOKEN) {
                    TokenModifySpec spec = parseTokenAddSpec(args);
                    if (spec.values().isEmpty()) {
                        sender.sendMessage("§cYou must provide at least one token to add.");
                        return;
                    }
                    List<String> added = new ArrayList<>();
                    for (String tokenValue : spec.values()) {
                        if (findFilterEntries(sectionValues, tokenValue).isEmpty()) {
                            sectionValues.add(buildTokenEntryValue(tokenValue, spec.strength(), spec.exceptions()));
                            added.add(tokenValue);
                        }
                    }
                    if (added.isEmpty()) {
                        sender.sendMessage("§eThose token entries already exist.");
                        return;
                    }
                    config.set(type.path(), sectionValues);
                    String suffix = describeTokenMetadata(spec.strength(), spec.exceptions());
                    saveAndReloadFilterConfig(sender, filterFile, config, filterManager,
                            "§aAdded token entr" + (added.size() == 1 ? "y" : "ies") + ": §f" + String.join(", ", added) + suffix);
                    return;
                }

                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                if (value.isEmpty()) {
                    sender.sendMessage("§cYou must provide a value to add.");
                    return;
                }
                if (findFilterEntries(sectionValues, value).isEmpty()) {
                    sectionValues.add(value);
                    config.set(type.path(), sectionValues);
                    saveAndReloadFilterConfig(sender, filterFile, config, filterManager, "§aAdded " + type.path() + " entry: §f" + value);
                } else {
                    sender.sendMessage("§eThat " + type.path() + " entry already exists: §f" + value);
                }
            }
            case REMOVE -> {
                if (type == FilterSectionType.TOKEN) {
                    List<String> targets = parseCsvValues(args[3]);
                    if (targets.isEmpty()) {
                        sender.sendMessage("§cYou must provide a token to remove.");
                        return;
                    }
                    List<FilterEntryRef> allMatches = new ArrayList<>();
                    for (String target : targets) {
                        allMatches.addAll(findFilterEntries(sectionValues, target));
                    }
                    if (allMatches.isEmpty()) {
                        sender.sendMessage("§cNo matching token entries found for: §f" + String.join(", ", targets));
                        return;
                    }
                    removeFilterEntries(sectionValues, allMatches);
                    config.set(type.path(), sectionValues);
                    saveAndReloadFilterConfig(sender, filterFile, config, filterManager,
                            "§aRemoved " + allMatches.size() + " token entr" + (allMatches.size() == 1 ? "y" : "ies") + " matching: §f" + String.join(", ", targets));
                    return;
                }

                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                if (value.isEmpty()) {
                    sender.sendMessage("§cYou must provide a value to remove.");
                    return;
                }
                List<FilterEntryRef> matches = findFilterEntries(sectionValues, value);
                if (matches.isEmpty()) {
                    sender.sendMessage("§cNo matching " + type.path() + " entry found for: §f" + value);
                    return;
                }
                removeFilterEntries(sectionValues, matches);
                config.set(type.path(), sectionValues);
                saveAndReloadFilterConfig(sender, filterFile, config, filterManager, "§aRemoved " + matches.size() + " " + type.path() + " entr" + (matches.size() == 1 ? "y" : "ies") + " matching: §f" + value);
            }
            case SET -> {
                if (type == FilterSectionType.TOKEN) {
                    TokenSetSpec spec = parseTokenSetSpec(args);
                    if (spec == null || spec.oldValue().isBlank() || spec.newValue().isBlank()) {
                        sender.sendMessage("§eUsage: /core filter set token <current> <new> [exact|normal|loose] [except <csv>]");
                        return;
                    }
                    List<FilterEntryRef> matches = findFilterEntries(sectionValues, spec.oldValue());
                    if (matches.isEmpty()) {
                        sender.sendMessage("§cNo matching tokens entry found for: §f" + spec.oldValue());
                        return;
                    }
                    FilterEntryRef target = matches.get(0);
                    TokenEntryData existing = getTokenEntryData(sectionValues, target);
                    TokenEntryData updated = new TokenEntryData(
                            spec.newValue(),
                            spec.strength() != null ? spec.strength() : existing.strength(),
                            spec.exceptions() != null ? spec.exceptions() : existing.exceptions()
                    );
                    setTokenEntryValue(sectionValues, target, updated);
                    config.set(type.path(), sectionValues);
                    saveAndReloadFilterConfig(sender, filterFile, config, filterManager,
                            "§aUpdated token entry: §f" + spec.oldValue() + " §7-> §f" + spec.newValue()
                                    + describeTokenMetadata(updated.strength(), updated.exceptions()));
                    if (!existing.exceptions().isEmpty() && spec.exceptions() == null) {
                        sender.sendMessage("§7Existing exceptions were preserved: §f" + String.join(", ", existing.exceptions()));
                    }
                    return;
                }

                FilterSetOperation operation = parseFilterSetOperation(args, type, sectionValues);
                if (operation == null || operation.oldValue().isBlank() || operation.newValue().isBlank()) {
                    sender.sendMessage("§eUsage: /core filter set <token|phrase|substring|regex> <current> <new>");
                    sender.sendMessage("§7Tip: for phrases, you can also use §f=>§7 as a separator.");
                    return;
                }
                List<FilterEntryRef> matches = findFilterEntries(sectionValues, operation.oldValue());
                if (matches.isEmpty()) {
                    sender.sendMessage("§cNo matching " + type.path() + " entry found for: §f" + operation.oldValue());
                    return;
                }
                FilterEntryRef target = matches.get(0);
                setFilterEntryValue(sectionValues, target, operation.newValue());
                config.set(type.path(), sectionValues);
                saveAndReloadFilterConfig(sender, filterFile, config, filterManager, "§aUpdated " + type.path() + " entry: §f" + operation.oldValue() + " §7-> §f" + operation.newValue());
            }
        }
    }

    private void sendFilterEvaluation(CommandSender sender, ChatFilterManager.EvaluationResult result, boolean verbose) {
        sender.sendMessage("§7Context: §f" + result.context().name().toLowerCase(Locale.ROOT));
        sender.sendMessage("§7Blocked: " + (result.blocked() ? "§cYES" : "§aNO"));
        sender.sendMessage("§7Normalized: §f" + result.preparedMessage().normalizedMessage());
        sender.sendMessage("§7Tokens: §f" + String.join(", ", result.preparedMessage().tokens()));
        if (!result.preparedMessage().joinedLetterRuns().isEmpty()) {
            sender.sendMessage("§7Joined letter runs: §f" + String.join(", ", result.preparedMessage().joinedLetterRuns()));
        }
        if (result.hits().isEmpty()) {
            sender.sendMessage("§7Hits: §fNone");
            return;
        }

        org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(getWordPhraseFilterFile());
        for (ChatFilterManager.MatchHit hit : result.hits()) {
            sender.sendMessage("§7Hit: §f" + hit.entryId() + " §8(" + hit.matchType().name().toLowerCase(Locale.ROOT) + "§8) §7detail=§f" + hit.detail());
            if (verbose) {
                List<String> paths = findFilterPaths(config, hit);
                if (paths.isEmpty()) {
                    sender.sendMessage("§8  location: §7not found in word-phrase-filter.yml");
                } else {
                    for (String path : paths) {
                        sender.sendMessage("§8  location: §f" + path);
                    }
                }
            }
        }
    }

    private File getWordPhraseFilterFile() {
        File file = new File(new File(plugin.getDataFolder(), "chat/modules"), "word-phrase-filter.yml");
        if (!file.exists() && plugin.getChatFilterManager() != null) {
            plugin.getChatFilterManager().reload();
        }
        return file;
    }

    private record FilterEntryRef(int index, String path, String value, boolean mapBacked) {}

    private record FilterSetOperation(String oldValue, String newValue) {}

    private record TokenModifySpec(List<String> values, String strength, List<String> exceptions) {}

    private record TokenSetSpec(String oldValue, String newValue, String strength, List<String> exceptions) {}

    private record TokenEntryData(String value, String strength, List<String> exceptions) {}

    private List<FilterEntryRef> findFilterEntries(List<Object> values, String target) {
        if (target == null) {
            return List.of();
        }
        List<FilterEntryRef> matches = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            if (raw instanceof String stringValue) {
                if (stringValue.equalsIgnoreCase(target)) {
                    matches.add(new FilterEntryRef(i, "[" + i + "]", stringValue, false));
                }
            } else if (raw instanceof Map<?, ?> mapValue) {
                Object valueObject = mapValue.containsKey("value") ? mapValue.get("value") : "";
                String value = String.valueOf(valueObject).trim();
                if (!value.isEmpty() && value.equalsIgnoreCase(target)) {
                    matches.add(new FilterEntryRef(i, "[" + i + "].value", value, true));
                }
            }
        }
        return matches;
    }

    private void removeFilterEntries(List<Object> values, List<FilterEntryRef> matches) {
        List<Integer> indices = matches.stream().map(FilterEntryRef::index).sorted(Comparator.reverseOrder()).toList();
        for (Integer index : indices) {
            values.remove((int) index);
        }
    }

    private void setFilterEntryValue(List<Object> values, FilterEntryRef ref, String newValue) {
        if (!ref.mapBacked()) {
            values.set(ref.index(), newValue);
            return;
        }
        Object raw = values.get(ref.index());
        if (raw instanceof Map<?, ?> mapValue) {
            Map<Object, Object> updated = new LinkedHashMap<>(mapValue);
            updated.put("value", newValue);
            values.set(ref.index(), updated);
        }
    }

    private TokenModifySpec parseTokenAddSpec(String[] args) {
        List<String> values = parseCsvValues(args[3]);
        String strength = null;
        List<String> exceptions = List.of();
        if (args.length >= 5) {
            String keyword = args[4].toLowerCase(Locale.ROOT);
            if (isTokenStrength(keyword)) {
                strength = keyword;
                if (args.length >= 7 && args[5].equalsIgnoreCase("except")) {
                    exceptions = parseCsvValues(args[6]);
                }
            } else if (keyword.equals("except") && args.length >= 6) {
                exceptions = parseCsvValues(args[5]);
            }
        }
        return new TokenModifySpec(values, strength, exceptions);
    }

    private TokenSetSpec parseTokenSetSpec(String[] args) {
        if (args.length < 5) {
            return null;
        }
        String oldValue = args[3].trim();
        String newValue = args[4].trim();
        String strength = null;
        List<String> exceptions = null;

        int index = 5;
        while (index < args.length) {
            String keyword = args[index].toLowerCase(Locale.ROOT);
            if (isTokenStrength(keyword)) {
                strength = keyword;
                index++;
                continue;
            }
            if (keyword.equals("except")) {
                if (index + 1 >= args.length) {
                    return null;
                }
                exceptions = parseCsvValues(args[index + 1]);
                index += 2;
                continue;
            }
            break;
        }
        return new TokenSetSpec(oldValue, newValue, strength, exceptions);
    }

    private boolean isTokenStrength(String value) {
        return value.equals("exact") || value.equals("normal") || value.equals("loose");
    }

    private List<String> parseCsvValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private Object buildTokenEntryValue(String value, String strength, List<String> exceptions) {
        if ((strength == null || strength.isBlank()) && (exceptions == null || exceptions.isEmpty())) {
            return value;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("value", value);
        if (strength != null && !strength.isBlank()) {
            entry.put("strength", strength);
        }
        if (exceptions != null && !exceptions.isEmpty()) {
            entry.put("except", new ArrayList<>(exceptions));
        }
        return entry;
    }

    private TokenEntryData getTokenEntryData(List<Object> values, FilterEntryRef ref) {
        Object raw = values.get(ref.index());
        if (raw instanceof String stringValue) {
            return new TokenEntryData(stringValue, null, List.of());
        }
        if (raw instanceof Map<?, ?> mapValue) {
            Object strengthObject = mapValue.containsKey("strength") ? mapValue.get("strength") : null;
            String strength = strengthObject == null ? null : String.valueOf(strengthObject).trim();
            List<String> exceptions = new ArrayList<>();
            Object exceptObject = mapValue.get("except");
            if (exceptObject instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null) {
                        String stringItem = String.valueOf(item).trim();
                        if (!stringItem.isEmpty()) {
                            exceptions.add(stringItem);
                        }
                    }
                }
            }
            return new TokenEntryData(ref.value(), strength, List.copyOf(exceptions));
        }
        return new TokenEntryData(ref.value(), null, List.of());
    }

    private void setTokenEntryValue(List<Object> values, FilterEntryRef ref, TokenEntryData data) {
        values.set(ref.index(), buildTokenEntryValue(data.value(), data.strength(), data.exceptions()));
    }

    private String describeTokenMetadata(String strength, List<String> exceptions) {
        List<String> parts = new ArrayList<>();
        if (strength != null && !strength.isBlank()) {
            parts.add("strength=" + strength);
        }
        if (exceptions != null && !exceptions.isEmpty()) {
            parts.add("exceptions=" + String.join(", ", exceptions));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " §7(" + String.join(" | ", parts) + ")";
    }

    private FilterSetOperation parseFilterSetOperation(String[] args, FilterSectionType type, List<Object> existingValues) {
        String joined = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (joined.isEmpty()) {
            return null;
        }

        int arrow = joined.indexOf("=>");
        if (arrow >= 0) {
            String oldValue = joined.substring(0, arrow).trim();
            String newValue = joined.substring(arrow + 2).trim();
            return new FilterSetOperation(oldValue, newValue);
        }

        List<String> candidates = new ArrayList<>();
        for (Object value : existingValues) {
            if (value instanceof String stringValue) {
                candidates.add(stringValue);
            } else if (value instanceof Map<?, ?> mapValue) {
                Object valueObject = mapValue.containsKey("value") ? mapValue.get("value") : "";
                String raw = String.valueOf(valueObject).trim();
                if (!raw.isEmpty()) {
                    candidates.add(raw);
                }
            }
        }

        String bestMatch = null;
        for (String candidate : candidates) {
            if (joined.equalsIgnoreCase(candidate) || joined.toLowerCase(Locale.ROOT).startsWith(candidate.toLowerCase(Locale.ROOT) + " ")) {
                if (bestMatch == null || candidate.length() > bestMatch.length()) {
                    bestMatch = candidate;
                }
            }
        }

        if (bestMatch != null) {
            String remainder = joined.substring(bestMatch.length()).trim();
            if (!remainder.isEmpty()) {
                return new FilterSetOperation(bestMatch, remainder);
            }
        }

        if (args.length >= 5) {
            String oldValue = args[3];
            String newValue = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).trim();
            if (!newValue.isEmpty()) {
                return new FilterSetOperation(oldValue, newValue);
            }
        }

        return null;
    }

    private void saveAndReloadFilterConfig(CommandSender sender, File filterFile,
                                           org.bukkit.configuration.file.YamlConfiguration config,
                                           ChatFilterManager filterManager, String successMessage) {
        try {
            config.save(filterFile);
            filterManager.reload();
            sender.sendMessage(successMessage);
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to save word-phrase-filter.yml", e);
            sender.sendMessage("§cFailed to save word-phrase-filter.yml. Check console.");
        }
    }

    private List<String> findFilterPaths(org.bukkit.configuration.file.YamlConfiguration config, ChatFilterManager.MatchHit hit) {
        FilterSectionType type = switch (hit.matchType()) {
            case TOKEN -> FilterSectionType.TOKEN;
            case PHRASE -> FilterSectionType.PHRASE;
            case SUBSTRING -> FilterSectionType.SUBSTRING;
            case REGEX -> FilterSectionType.REGEX;
        };

        List<Object> values = new ArrayList<>(config.getList(type.path(), new ArrayList<>()));
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            if (raw instanceof String stringValue && stringValue.equals(hit.value())) {
                paths.add(type.path() + "[" + i + "]");
            } else if (raw instanceof Map<?, ?> mapValue) {
                Object valueObject = mapValue.containsKey("value") ? mapValue.get("value") : "";
                String value = String.valueOf(valueObject).trim();
                if (value.equals(hit.value())) {
                    paths.add(type.path() + "[" + i + "].value");
                }
            }
        }
        return paths;
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
                initializeHideGroup(hideConfig, groupPath, group);
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
                java.util.List<String> inheritedCommands = hideConfig.getStringList(groupPath + oldGroup + ".inherits.commands");
                java.util.List<String> inheritedTabCompletes = hideConfig.getStringList(groupPath + oldGroup + ".inherits.tabcompletes");

                // Create new group with same settings
                hideConfig.set(groupPath + newGroup + ".whitelist", whitelist);
                hideConfig.set(groupPath + newGroup + ".commands", commands);
                hideConfig.set(groupPath + newGroup + ".tabcompletes", tabcompletes);
                hideConfig.set(groupPath + newGroup + ".inherits.commands", inheritedCommands);
                hideConfig.set(groupPath + newGroup + ".inherits.tabcompletes", inheritedTabCompletes);

                // Remove old group
                hideConfig.set(groupPath + oldGroup, null);

                saveHideConfig(hideConfig, hideFile);
                handleReloadCommand(sender, null);
                sender.sendMessage("§aGroup '" + oldGroup + "' renamed to '" + newGroup + "'.");
                break;
            }
            case "group": {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /core hide group <name> <add|remove|check|inherit> ...");
                    return;
                }
                String group = args[2];
                String action = args[3].toLowerCase();
                boolean groupExists = hideConfig.contains(groupPath + group);
                boolean createdGroup = false;

                if ((action.equals("add") || action.equals("inherit")) && !groupExists) {
                    initializeHideGroup(hideConfig, groupPath, group);
                    groupExists = true;
                    createdGroup = true;
                }

                if (!groupExists) {
                    sender.sendMessage("§cGroup '" + group + "' does not exist.");
                    return;
                }

                switch (action) {
                    case "add": {
                        HideScope scope = parseHideScope(args, 4);
                        if (scope.nextArgIndex() >= args.length) {
                            sender.sendMessage("§cUsage: /core hide group <name> add [-c|-t|-tc] <cmd[,cmd2,...]>");
                            return;
                        }

                        List<String> targets = parseHideTargets(args[scope.nextArgIndex()]);
                        if (targets.isEmpty()) {
                            sender.sendMessage("§cNo valid commands were provided.");
                            return;
                        }
                        List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                        List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
                        List<String> addedTargets = new ArrayList<>();

                        for (String cmd : targets) {
                            boolean added = false;
                            if (scope.commands() && !containsIgnoreCase(commands, cmd)) {
                                commands.add(cmd);
                                added = true;
                            }
                            if (scope.tabCompletes() && !containsIgnoreCase(tabcompletes, cmd)) {
                                tabcompletes.add(cmd);
                                added = true;
                            }
                            if (added) {
                                addedTargets.add(cmd);
                            }
                        }

                        hideConfig.set(groupPath + group + ".commands", commands);
                        hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                        saveHideConfig(hideConfig, hideFile);
                        handleReloadCommand(sender, null);
                        String targetSummary = String.join(", ", targets);
                        if (addedTargets.isEmpty()) {
                            sender.sendMessage("§eNothing changed for " + describeHideScope(scope) + " in group '" + group + "': " + targetSummary +
                                    (createdGroup ? " §7(Group created automatically.)" : ""));
                        } else {
                            sender.sendMessage("§aAdded " + String.join(", ", addedTargets) + " to " + describeHideScope(scope) + " for group '" + group + "'." +
                                    (createdGroup ? " §7(Group created automatically.)" : ""));
                        }
                        break;
                    }
                    case "remove": {
                        HideScope scope = parseHideScope(args, 4);
                        if (scope.nextArgIndex() >= args.length) {
                            sender.sendMessage("§cUsage: /core hide group <name> remove [-c|-t|-tc] <cmd[,cmd2,...]>");
                            return;
                        }

                        List<String> targets = parseHideTargets(args[scope.nextArgIndex()]);
                        if (targets.isEmpty()) {
                            sender.sendMessage("§cNo valid commands were provided.");
                            return;
                        }
                        List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                        List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
                        List<String> removedTargets = new ArrayList<>();

                        for (String cmd : targets) {
                            boolean changed = false;
                            if (scope.commands() && removeIgnoreCase(commands, cmd)) {
                                changed = true;
                            }
                            if (scope.tabCompletes() && removeIgnoreCase(tabcompletes, cmd)) {
                                changed = true;
                            }
                            if (changed) {
                                removedTargets.add(cmd);
                            }
                        }

                        hideConfig.set(groupPath + group + ".commands", commands);
                        hideConfig.set(groupPath + group + ".tabcompletes", tabcompletes);
                        saveHideConfig(hideConfig, hideFile);
                        handleReloadCommand(sender, null);

                        if (!removedTargets.isEmpty()) {
                            sender.sendMessage("§aRemoved " + String.join(", ", removedTargets) + " from " + describeHideScope(scope) + " for group '" + group + "'.");
                        } else {
                            sender.sendMessage("§eNone of " + String.join(", ", targets) + " were present in " + describeHideScope(scope) + " for group '" + group + "'.");
                        }
                        break;
                    }
                    case "inherit": {
                        if (args.length < 5) {
                            sender.sendMessage("§cUsage: /core hide group <name> inherit <parent> [-c|-t|-tc]");
                            return;
                        }

                        String parentGroup = args[4];
                        if (!hideConfig.contains(groupPath + parentGroup)) {
                            sender.sendMessage("§cParent group '" + parentGroup + "' does not exist in hide.yml.");
                            return;
                        }

                        HideScope scope = parseHideScope(args, 5);
                        List<String> inheritedCommands = hideConfig.getStringList(groupPath + group + ".inherits.commands");
                        List<String> inheritedTabCompletes = hideConfig.getStringList(groupPath + group + ".inherits.tabcompletes");

                        if (scope.commands() && !containsIgnoreCase(inheritedCommands, parentGroup)) {
                            inheritedCommands.add(parentGroup);
                        }
                        if (scope.tabCompletes() && !containsIgnoreCase(inheritedTabCompletes, parentGroup)) {
                            inheritedTabCompletes.add(parentGroup);
                        }

                        hideConfig.set(groupPath + group + ".inherits.commands", inheritedCommands);
                        hideConfig.set(groupPath + group + ".inherits.tabcompletes", inheritedTabCompletes);
                        saveHideConfig(hideConfig, hideFile);
                        handleReloadCommand(sender, null);
                        sender.sendMessage("§aGroup '" + group + "' now inherits " + describeHideScope(scope) + " from '" + parentGroup + "'." +
                                (createdGroup ? " §7(Group created automatically.)" : ""));
                        break;
                    }
                    case "check": {
                        HideScope scope = parseHideScope(args, 4);
                        if (scope.nextArgIndex() >= args.length) {
                            sender.sendMessage("§cUsage: /core hide group <name> check [-c|-t|-tc] <cmd[,cmd2,...]>");
                            return;
                        }

                        List<String> targets = parseHideTargets(args[scope.nextArgIndex()]);
                        if (targets.isEmpty()) {
                            sender.sendMessage("§cNo valid commands were provided.");
                            return;
                        }
                        List<String> commands = hideConfig.getStringList(groupPath + group + ".commands");
                        List<String> tabcompletes = hideConfig.getStringList(groupPath + group + ".tabcompletes");
                        for (String cmd : targets) {
                            boolean inCommands = containsIgnoreCase(commands, cmd);
                            boolean inTabCompletes = containsIgnoreCase(tabcompletes, cmd);

                            sender.sendMessage("§eCheck for '" + cmd + "' in group '" + group + "' (" + describeHideScope(scope) + "):");
                            if (scope.commands()) {
                                sender.sendMessage("§7commands: " + (inCommands ? "§aYES" : "§cNO"));
                            }
                            if (scope.tabCompletes()) {
                                sender.sendMessage("§7tabcompletes: " + (inTabCompletes ? "§aYES" : "§cNO"));
                            }
                        }
                        break;
                    }
                    default:
                        sender.sendMessage("§cUsage: /core hide group <name> <add|remove|check|inherit> ...");
                        break;
                }
                break;
            }
            default:
                sender.sendMessage("§cUnknown hide subcommand. Use /core hide <creategroup|deletegroup|renamegroup|group>.");
        }
    }

    private void initializeHideGroup(org.bukkit.configuration.file.YamlConfiguration hideConfig, String groupPath, String group) {
        hideConfig.set(groupPath + group + ".whitelist", true);
        hideConfig.set(groupPath + group + ".commands", new java.util.ArrayList<String>());
        hideConfig.set(groupPath + group + ".tabcompletes", new java.util.ArrayList<String>());
        hideConfig.set(groupPath + group + ".inherits.commands", new java.util.ArrayList<String>());
        hideConfig.set(groupPath + group + ".inherits.tabcompletes", new java.util.ArrayList<String>());
    }

    private void saveHideConfig(org.bukkit.configuration.file.YamlConfiguration hideConfig, java.io.File file) {
        try {
            hideConfig.save(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private HideScope parseHideScope(String[] args, int index) {
        if (index >= args.length) {
            return new HideScope(true, true, index);
        }

        return switch (args[index].toLowerCase(Locale.ROOT)) {
            case "-c", "-s" -> new HideScope(true, false, index + 1);
            case "-t" -> new HideScope(false, true, index + 1);
            case "-tc" -> new HideScope(true, true, index + 1);
            default -> new HideScope(true, true, index);
        };
    }

    private String describeHideScope(HideScope scope) {
        if (scope.commands() && scope.tabCompletes()) {
            return "commands and tabcompletes";
        }
        if (scope.commands()) {
            return "commands";
        }
        return "tabcompletes";
    }

    private List<String> parseHideTargets(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }

        return Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }

        boolean removed = false;
        Iterator<String> iterator = values.iterator();
        while (iterator.hasNext()) {
            String value = iterator.next();
            if (value != null && value.equalsIgnoreCase(target)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
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
                suggestions.add("filter");
                suggestions.add("item");
                suggestions.add("rp");
                suggestions.add("migrate");
                suggestions.add("sethomes");
            }
            if (sender.hasPermission("allium.restore")) {
                suggestions.add("restore");
            }
            if (sender.hasPermission("allium.alerts")) {
                suggestions.add("alerts");
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("alerts")) {
            if (args.length == 2) {
                suggestions.add("gamemode");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("gamemode")) {
                suggestions.addAll(List.of("on", "off", "toggle"));
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("filter")) {
            if (args.length == 2) {
                suggestions.addAll(List.of("test", "check", "add", "remove", "set"));
            } else if (args.length == 3 && args[1].equalsIgnoreCase("test")) {
                suggestions.addAll(List.of("chat", "command", "discord"));
            } else if (args.length == 3 && List.of("add", "remove", "set").contains(args[1].toLowerCase(Locale.ROOT))) {
                suggestions.addAll(List.of("token", "phrase", "substring", "regex"));
            } else if (args.length >= 4 && List.of("remove", "set").contains(args[1].toLowerCase(Locale.ROOT))) {
                FilterSectionType type = FilterSectionType.from(args[2]);
                if (type != null) {
                    org.bukkit.configuration.file.YamlConfiguration filterConfig =
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(getWordPhraseFilterFile());
                    for (Object raw : filterConfig.getList(type.path(), new ArrayList<>())) {
                        if (raw instanceof String stringValue) {
                            suggestions.add(stringValue);
                        } else if (raw instanceof Map<?, ?> mapValue) {
                            Object value = mapValue.get("value");
                            if (value != null) {
                                suggestions.add(String.valueOf(value));
                            }
                        }
                    }
                }
            } else if (args.length >= 4 && args[1].equalsIgnoreCase("add") && args[2].equalsIgnoreCase("token")) {
                if (args.length == 5) {
                    suggestions.addAll(List.of("exact", "normal", "loose", "except"));
                } else if (args.length == 6 && isTokenStrength(args[4].toLowerCase(Locale.ROOT))) {
                    suggestions.add("except");
                }
            } else if (args.length >= 5 && args[1].equalsIgnoreCase("set") && args[2].equalsIgnoreCase("token")) {
                if (args.length == 6) {
                    suggestions.addAll(List.of("exact", "normal", "loose", "except"));
                } else if (args.length == 7 && isTokenStrength(args[5].toLowerCase(Locale.ROOT))) {
                    suggestions.add("except");
                }
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
                suggestions.addAll(List.of("creategroup", "deletegroup", "renamegroup", "group"));
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("deletegroup") || args[1].equalsIgnoreCase("group"))) {
                // Suggest group names for deletegroup/group
                org.bukkit.configuration.file.YamlConfiguration hideConfig =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.File(plugin.getDataFolder(), "hide.yml"));
                if (hideConfig.isConfigurationSection("groups")) {
                    suggestions.addAll(hideConfig.getConfigurationSection("groups").getKeys(false));
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("renamegroup")) {
                org.bukkit.configuration.file.YamlConfiguration hideConfig =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.File(plugin.getDataFolder(), "hide.yml"));
                if (hideConfig.isConfigurationSection("groups")) {
                    suggestions.addAll(hideConfig.getConfigurationSection("groups").getKeys(false));
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("group")) {
                suggestions.addAll(List.of("add", "remove", "check", "inherit"));
            } else if (args.length >= 5 && args[1].equalsIgnoreCase("group")) {
                org.bukkit.configuration.file.YamlConfiguration hideConfig =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.File(plugin.getDataFolder(), "hide.yml"));
                String action = args[3].toLowerCase(Locale.ROOT);
                boolean scopeFlag = List.of("-c", "-t", "-tc", "-s").contains(args[4].toLowerCase(Locale.ROOT));

                if (args.length == 5 && (action.equals("add") || action.equals("remove") || action.equals("check"))) {
                    suggestions.addAll(List.of("-c", "-t", "-tc", "-s", "*", "^", "bukkit:*", "minecraft:*"));
                    String group = args[2];
                    suggestions.addAll(hideConfig.getStringList("groups." + group + ".commands"));
                    suggestions.addAll(hideConfig.getStringList("groups." + group + ".tabcompletes"));
                } else if (args.length == 5 && action.equals("inherit")) {
                    if (hideConfig.isConfigurationSection("groups")) {
                        suggestions.addAll(hideConfig.getConfigurationSection("groups").getKeys(false));
                    }
                } else if (args.length == 6 && (action.equals("add") || action.equals("remove") || action.equals("check")) && scopeFlag) {
                    suggestions.addAll(List.of("*", "^", "bukkit:*", "minecraft:*"));
                    String group = args[2];
                    suggestions.addAll(hideConfig.getStringList("groups." + group + ".commands"));
                    suggestions.addAll(hideConfig.getStringList("groups." + group + ".tabcompletes"));
                } else if (args.length == 6 && action.equals("inherit")) {
                    suggestions.addAll(List.of("-c", "-t", "-tc", "-s"));
                }
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
