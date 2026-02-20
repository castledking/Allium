package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventorySnapshot;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class Freeze implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, UUID> frozenBy = new HashMap<>();
    private boolean freezeAllActive = false;
    private String freezeAllReason = "Server frozen for safety reasons. Please wait for a few seconds.";

    public Freeze(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("freeze") || command.getName().equalsIgnoreCase("unfreeze")) {
            boolean isFreeze = command.getName().equalsIgnoreCase("freeze");

            if (isFreeze) {
                return handleFreeze(sender, args);
            } else {
                return handleUnfreeze(sender, args);
            }
        }
        return false;
    }

    private boolean handleFreeze(CommandSender sender, String[] args) {
        // Check basic freeze permission
        if (!sender.hasPermission("allium.freeze")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "/freeze");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "/freeze")
                    .replace("{args}", "<player> [reason]"));
            return true;
        }

        String targetArg = args[0].toLowerCase();

        // Handle freeze all
        if (targetArg.equals("all") || targetArg.equals("*") || targetArg.equals("@a")) {
            if (!sender.hasPermission("allium.freeze.all")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "/{cmd}", "/freeze all");
                return true;
            }

            // Set custom reason if provided
            if (args.length > 1) {
                freezeAllReason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }

            freezeAllPlayers(sender);
            return true;
        }

        // Handle individual player freeze
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        // Check if player has freeze resist permission
        if (target.hasPermission("allium.freeze.resist")) {
            Text.sendErrorMessage(sender, "freeze.resist", lang, "{player}", target.getName());
            return true;
        }

        // Check if already frozen
        if (frozenPlayers.contains(target.getUniqueId())) {
            sender.sendMessage(lang.get("freeze.already-frozen")
                    .replace("{player}", target.getName()));
            return true;
        }

        freezePlayer(sender, target);
        return true;
    }

    private boolean handleUnfreeze(CommandSender sender, String[] args) {
        // Check basic freeze permission
        if (!sender.hasPermission("allium.freeze")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "/unfreeze");
            return true;
        }

        if (args.length == 0) {
            // Context-based unfreezing
            return handleContextUnfreeze(sender);
        }

        // Handle specific player unfreeze
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        if (!frozenPlayers.contains(target.getUniqueId())) {
            sender.sendMessage(lang.get("freeze.not-frozen")
                    .replace("{player}", target.getName()));
            return true;
        }

        unfreezePlayer(sender, target);
        return true;
    }

    private boolean handleContextUnfreeze(CommandSender sender) {
        // If freeze all is active, unfreeze all
        if (freezeAllActive) {
            unfreezeAllPlayers(sender);
            return true;
        }

        // Count frozen players
        int frozenCount = (int) frozenPlayers.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .count();

        if (frozenCount == 0) {
            sender.sendMessage(lang.get("freeze.no-frozen-players"));
            return true;
        }

        if (frozenCount == 1) {
            // Only one player frozen, unfreeze them
            Player frozenPlayer = frozenPlayers.stream()
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            if (frozenPlayer != null) {
                unfreezePlayer(sender, frozenPlayer);
            }
            return true;
        }

        // Multiple players frozen, show list and prompt for specific player
        sender.sendMessage(lang.get("freeze.multiple-frozen"));
        List<Player> frozenPlayerList = frozenPlayers.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        for (int i = 0; i < frozenPlayerList.size(); i++) {
            Player player = frozenPlayerList.get(i);
            sender.sendMessage("  " + (i + 1) + ". " + player.getName());
        }

        sender.sendMessage(lang.get("freeze.specify-player"));
        return true;
    }

    public void freezePlayer(CommandSender freezer, Player target) {
        UUID targetUUID = target.getUniqueId();

        // Create inventory snapshot
        try {
            new InventorySnapshot(target, "FREEZE");
        } catch (Exception e) {
            Text.sendDebugLog(Text.DebugSeverity.ERROR, "Failed to create freeze snapshot for " + target.getName(), e);
        }

        // Apply freeze effects
        target.setGameMode(GameMode.ADVENTURE);
        target.setAllowFlight(false);
        target.setFlying(false);
        target.setWalkSpeed(0.0f);
        target.setFlySpeed(0.0f);
        target.setCollidable(false);

        // Store freeze data
        frozenPlayers.add(targetUUID);
        frozenBy.put(targetUUID, freezer instanceof Player ? ((Player) freezer).getUniqueId() : null);

        // Send messages
        target.sendMessage(lang.get("freeze.frozen-message"));
        freezer.sendMessage(lang.get("freeze.frozen")
                .replace("{player}", target.getName()));

        Text.sendDebugLog(Text.DebugSeverity.INFO, "Player " + target.getName() + " frozen by " + freezer.getName());
    }

    void unfreezePlayer(CommandSender unfreezer, Player target) {
        UUID targetUUID = target.getUniqueId();

        // Restore player state
        target.setGameMode(GameMode.SURVIVAL);
        target.setWalkSpeed(0.2f);
        target.setFlySpeed(0.1f);
        target.setCollidable(true);

        // Remove from freeze tracking
        frozenPlayers.remove(targetUUID);
        frozenBy.remove(targetUUID);

        // Send messages
        target.sendMessage(lang.get("freeze.unfrozen-message"));
        unfreezer.sendMessage(lang.get("freeze.unfrozen")
                .replace("{player}", target.getName()));

        Text.sendDebugLog(Text.DebugSeverity.INFO, "Player " + target.getName() + " unfrozen by " + unfreezer.getName());
    }

    private void freezeAllPlayers(CommandSender freezer) {
        freezeAllActive = true;

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip players with resist permission
            if (player.hasPermission("allium.freeze.resist")) {
                continue;
            }

            // Skip if already frozen
            if (frozenPlayers.contains(player.getUniqueId())) {
                continue;
            }

            freezePlayer(freezer, player);
        }

        // Send messages
        Bukkit.broadcastMessage(lang.get("freeze.freeze-all-message")
                .replace("{reason}", freezeAllReason));

        if (freezer instanceof Player) {
            ((Player) freezer).sendMessage(lang.get("freeze.freeze-all-success")
                    .replace("{reason}", freezeAllReason));
        }

        Text.sendDebugLog(Text.DebugSeverity.INFO, "Freeze all activated by " + freezer.getName() + " with reason: " + freezeAllReason);
    }

    private void unfreezeAllPlayers(CommandSender unfreezer) {
        freezeAllActive = false;
        freezeAllReason = "Server frozen for safety reasons. Please wait for a few seconds.";

        List<Player> playersToUnfreeze = new ArrayList<>();
        for (UUID frozenUUID : frozenPlayers) {
            Player player = Bukkit.getPlayer(frozenUUID);
            if (player != null) {
                playersToUnfreeze.add(player);
            }
        }

        for (Player player : playersToUnfreeze) {
            unfreezePlayer(unfreezer, player);
        }

        // Send messages
        Bukkit.broadcastMessage(lang.get("freeze.unfreeze-all-message"));

        if (unfreezer instanceof Player) {
            ((Player) unfreezer).sendMessage(lang.get("freeze.unfreeze-all-success"));
        }

        Text.sendDebugLog(Text.DebugSeverity.INFO, "Freeze all deactivated by " + unfreezer.getName());
    }

    public void handlePlayerJoin(Player player) {
        // If freeze all is active and player doesn't have resist permission, freeze them
        if (freezeAllActive && !player.hasPermission("allium.freeze.resist")) {
            freezePlayer(Bukkit.getConsoleSender(), player);
        }
    }

    public void handlePlayerQuit(Player player) {
        // If player quits while frozen, unfreeze them (but keep data for potential restoration)
        if (frozenPlayers.contains(player.getUniqueId())) {
            frozenPlayers.remove(player.getUniqueId());
            frozenBy.remove(player.getUniqueId());
            Text.sendDebugLog(Text.DebugSeverity.INFO, "Player " + player.getName() + " logged out while frozen - unfrozen");
        }
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    public boolean isFreezeAllActive() {
        return freezeAllActive;
    }

    public Set<UUID> getFrozenPlayers() {
        return new HashSet<>(frozenPlayers);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (!sender.hasPermission("allium.freeze")) {
            return suggestions;
        }

        if (args.length == 1) {
            // Suggest players and special targets
            String input = args[0].toLowerCase();

            // Add online players
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) {
                    suggestions.add(player.getName());
                }
            }

            // Add special targets
            if ("all".startsWith(input)) suggestions.add("all");
            if ("*".startsWith(input)) suggestions.add("*");
            if ("@a".startsWith(input)) suggestions.add("@a");
        }

        return suggestions;
    }
}
