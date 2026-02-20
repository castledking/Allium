package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.jobs.CancelTeleportation;
import net.survivalfun.core.managers.warp.WarpManager;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Warp implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final WarpManager warpManager;
    private final Lang lang;
    private final Map<UUID, SchedulerAdapter.TaskHandle> pendingTeleports = new HashMap<>();
    private final Map<UUID, CancelTeleportation> teleportListeners = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int warpDelaySeconds;
    private int warpCooldownSeconds;
    private double teleportLeniency;

    public Warp(PluginStart plugin, WarpManager warpManager) {
        this.plugin = plugin;
        this.warpManager = warpManager;
        this.lang = plugin.getLangManager();
        loadConfig();
    }

    private void loadConfig() {
        warpDelaySeconds = Math.max(plugin.getConfig().getInt("warp.delay", plugin.getConfig().getInt("teleport.delay", 3)), 0);
        warpCooldownSeconds = Math.max(plugin.getConfig().getInt("warp.cooldown", plugin.getConfig().getInt("teleport.cooldown", 30)), 0);
        teleportLeniency = Math.max(plugin.getConfig().getDouble("teleport.leniency", 0.5D), 0.0D);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle /warps or /warp with no arguments - show warp list
        if (args.length == 0) {
            // If invoked as /warps with no args, show page 1
            if (command.getName().equalsIgnoreCase("warps") || label.equalsIgnoreCase("warps")) {
                return handleWarpList(sender, 1);
            }
            return handleWarpList(sender);
        }

        // If invoked as /warps <page>, show that page
        if (command.getName().equalsIgnoreCase("warps") || label.equalsIgnoreCase("warps")) {
            int page = 1;
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
            if (page < 1) page = 1;
            return handleWarpList(sender, page);
        }

        String warpName = args[0];

        // Handle /warp <warp> [player]
        if (args.length == 1) {
            return handleWarpTeleport(sender, warpName);
        } else if (args.length == 2 && sender.hasPermission("allium.warp.others")) {
            return handleWarpTeleportOther(sender, warpName, args[1]);
        }

        return handleWarpTeleport(sender, warpName);
    }

    private boolean handleWarpList(CommandSender sender) {
        // Check permission
        if (!sender.hasPermission("allium.warp.list")) {
            Text.sendErrorMessage(sender, lang.get("no-permission"), lang, "{cmd}", "warp list");
            return true;
        }

        List<String> accessibleWarps = warpManager.getAccessibleWarps(sender);
        String formattedList = warpManager.formatWarpList(accessibleWarps, sender, 1, 10);
        int totalPages = warpManager.getWarpListTotalPages(accessibleWarps, 10);

        if (formattedList == null || formattedList.isEmpty()) {
            Text.sendErrorMessage(sender, "warp.no-warps-available", lang);
        } else {
            sender.sendMessage(formattedList);
            sendWarpNavigationButtons(sender, 1, totalPages);
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
        return true;
    }

    private boolean handleWarpList(CommandSender sender, int page) {
        // Check permission
        if (!sender.hasPermission("allium.warp.list")) {
            Text.sendErrorMessage(sender, lang.get("no-permission"), lang, "{cmd}", "warp list");
            return true;
        }

        List<String> accessibleWarps = warpManager.getAccessibleWarps(sender);
        String formattedList = warpManager.formatWarpList(accessibleWarps, sender, page, 10);
        int totalPages = warpManager.getWarpListTotalPages(accessibleWarps, 10);

        if (formattedList == null || formattedList.isEmpty()) {
            Text.sendErrorMessage(sender, "warp.no-warps-available", lang);
        } else {
            sender.sendMessage(formattedList);
            sendWarpNavigationButtons(sender, page, totalPages);
            sender.sendMessage(Text.colorize("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆"));
        }
        return true;
    }

    private boolean handleWarpTeleport(CommandSender sender, String warpName) {
        // Must be a player
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "warp")
                    .replace("{args}", "<warp> <player>")
            );
            return true;
        }

        Player player = (Player) sender;

        // Check base warp permission
        if (!player.hasPermission("allium.warp")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "warp");
            return true;
        }

        // Check specific warp permission
        if (!warpManager.hasWarpPermission(player, warpName)) {
            Text.sendErrorMessage(player, "no-permission", lang, "/{cmd}", "this warp", true);
            return true;
        }

        // Check if warp exists
        if (!warpManager.warpExists(warpName)) {
            Text.sendErrorMessage(player, "warp.not-found", lang, "{warp}", warpName);
            return true;
        }

        Location warpLocation = warpManager.getWarp(warpName);
        if (warpLocation == null) {
            Text.sendErrorMessage(player, "warp.teleport-failed", lang, "{warp}", warpName);
            return true;
        }

        UUID playerId = player.getUniqueId();
        cancelPendingTeleport(playerId);

        if (!player.hasPermission("allium.warp.nocooldown") && hasCooldown(playerId)) {
            long remaining = getRemainingCooldown(playerId);
            Text.sendErrorMessage(player, "cooldown", lang, "{time}", Text.formatTime((int) remaining), "{cmd}", "warp");
            return true;
        }

        if (!player.hasPermission("allium.warp.nodelay") && warpDelaySeconds > 0) {
            startDelayedWarp(player, warpName, warpLocation.clone());
        } else {
            executeWarpTeleport(player, warpName, warpLocation.clone());
        }

        return true;
    }

    private boolean handleWarpTeleportOther(CommandSender sender, String warpName, String targetPlayerName) {

        // Check specific warp permission
        if (!warpManager.hasWarpPermission(sender, warpName)) {
            Text.sendErrorMessage(sender, "no-permission", lang, "/{cmd}", "use this warp", true);
            return true;
        }

        // Check if warp exists
        if (!warpManager.warpExists(warpName)) {
            Text.sendErrorMessage(sender, "warp.not-found", lang, "{warp}", warpName);
            return true;
        }

        // Find target player
        Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
        if (targetPlayer == null) {
            if (Bukkit.getOfflinePlayer(targetPlayerName).hasPlayedBefore()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetPlayerName);
                return true;
            }
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }

        // Teleport target player to warp
        if (warpManager.teleportToWarp(targetPlayer, warpName)) {
            sender.sendMessage(lang.get("warp.teleported")
            .replace("{name}", targetPlayer.getName() + " to")
            .replace("{warp}", warpName));
            targetPlayer.sendMessage(lang.get("warp.teleported-by")
            .replace("{warp}", warpName)
            .replace("{name}", sender.getName()));
        } else {
            Text.sendErrorMessage(sender, "warp.teleport-failed", lang, "warp", warpName);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Tab complete warp names that the player has access to
            List<String> accessibleWarps = warpManager.getAccessibleWarps(sender);
            String partial = args[0].toLowerCase();
            
            for (String warp : accessibleWarps) {
                if (warp.toLowerCase().startsWith(partial)) {
                    completions.add(warp);
                }
            }
        } else if (args.length == 2 && sender.hasPermission("allium.warp.others")) {
            // Tab complete player names for teleporting others
            String partial = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        }

        return completions;
    }

    private void startDelayedWarp(Player player, String warpName, Location warpLocation) {
        UUID playerId = player.getUniqueId();
        CancelTeleportation listener = new CancelTeleportation(plugin, player, player.getLocation().clone(), teleportLeniency);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        teleportListeners.put(playerId, listener);

        player.sendMessage(lang.get("tp.delay")
                .replace("{delay}", Text.formatTime(Math.max(warpDelaySeconds, 0))));

        SchedulerAdapter.TaskHandle handle = SchedulerAdapter.runLater(() -> {
            try {
                if (!player.isOnline()) {
                    return;
                }

                if (listener.isCancelled()) {
                    if (player.isOnline()) {
                        HandlerList.unregisterAll(listener);
                    }
                    return;
                }

                SchedulerAdapter.runAtEntity(player, () -> {
                    if (!player.isOnline() || listener.isCancelled()) {
                        HandlerList.unregisterAll(listener);
                        return;
                    }

                    HandlerList.unregisterAll(listener);
                    teleportListeners.remove(playerId);
                    pendingTeleports.remove(playerId);
                    executeWarpTeleport(player, warpName, warpLocation);
                });
            } finally {
                HandlerList.unregisterAll(listener);
                teleportListeners.remove(playerId);
                pendingTeleports.remove(playerId);
            }
        }, warpDelaySeconds * 20L);

        pendingTeleports.put(playerId, handle);
    }

    private void executeWarpTeleport(Player player, String warpName, Location warpLocation) {
        SchedulerAdapter.runAtEntity(player, () -> {
            player.teleportAsync(warpLocation).thenAccept(success -> {
                SchedulerAdapter.runAtEntity(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (Boolean.TRUE.equals(success)) {
                        if (!player.hasPermission("allium.warp.nocooldown")) {
                            setCooldown(player.getUniqueId());
                        }
                        player.sendMessage(lang.get("warp.teleported")
                                .replace("{name}", "to")
                                .replace("{warp}", warpName));
                    } else {
                        Text.sendErrorMessage(player, "warp.teleport-failed", lang, "{warp}", warpName);
                    }
                });
            }).exceptionally(error -> {
                SchedulerAdapter.runAtEntity(player, () -> Text.sendErrorMessage(player, "warp.teleport-failed", lang, "{warp}", warpName));
                Text.sendDebugLog(WARN, "Failed to teleport " + player.getName() + " to warp " + warpName + ": " + error.getMessage());
                return null;
            });
        });
    }

    private void cancelPendingTeleport(UUID playerId) {
        SchedulerAdapter.TaskHandle handle = pendingTeleports.remove(playerId);
        if (handle != null) {
            handle.cancel();
        }

        CancelTeleportation listener = teleportListeners.remove(playerId);
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    private boolean hasCooldown(UUID playerId) {
        Long expiresAt = cooldowns.get(playerId);
        return expiresAt != null && System.currentTimeMillis() < expiresAt;
    }

    private long getRemainingCooldown(UUID playerId) {
        Long expiresAt = cooldowns.get(playerId);
        if (expiresAt == null) {
            return 0L;
        }
        long remainingMillis = expiresAt - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000L) + 1;
    }

    private void setCooldown(UUID playerId) {
        cooldowns.put(playerId, System.currentTimeMillis() + (warpCooldownSeconds * 1000L));
    }

    private void sendWarpNavigationButtons(CommandSender sender, int currentPage, int totalPages) {
        if (totalPages <= 1) return;

        if (sender instanceof ConsoleCommandSender) {
            StringBuilder navigationBar = new StringBuilder();
            if (currentPage > 1) {
                navigationBar.append(Text.parseColors("&6« Previous "));
            } else {
                navigationBar.append(Text.parseColors("&8« Previous "));
            }
            navigationBar.append(Text.parseColors("&7Page &f" + currentPage + "&7/&f" + totalPages + " "));
            if (currentPage < totalPages) {
                navigationBar.append(Text.parseColors("&6Next »"));
            } else {
                navigationBar.append(Text.parseColors("&8Next »"));
            }
            sender.sendMessage(navigationBar.toString());
        } else {
            TextComponent.Builder navigationBar = Component.text();
            if (currentPage > 1) {
                Component previousButton = Component.text("« Previous ")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage - 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/warps " + (currentPage - 1)));
                navigationBar.append(previousButton);
            } else {
                navigationBar.append(Component.text("« Previous ").color(NamedTextColor.DARK_GRAY));
            }
            navigationBar.append(Component.text("Page ", NamedTextColor.GRAY)
                    .append(Component.text(currentPage, NamedTextColor.WHITE))
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(totalPages, NamedTextColor.WHITE))
                    .append(Component.text(" ", NamedTextColor.GRAY)));
            if (currentPage < totalPages) {
                Component nextButton = Component.text("Next »")
                        .color(NamedTextColor.GOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to go to page " + (currentPage + 1), NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand("/warps " + (currentPage + 1)));
                navigationBar.append(nextButton);
            } else {
                navigationBar.append(Component.text("Next »").color(NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(navigationBar.build());
        }
    }
}
