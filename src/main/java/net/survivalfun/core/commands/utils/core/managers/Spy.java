package net.survivalfun.core.commands.utils.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class Spy implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;

    // Players who have global spying enabled
    private final Set<UUID> spyingPlayers;

    // Maps players to the specific players they're spying on
    private final Map<UUID, Set<UUID>> targetedSpying;

    /**
     * Constructs a new Spy command handler.
     *
     * @param plugin The main plugin instance
     */
    public Spy(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.spyingPlayers = new HashSet<>();
        this.targetedSpying = new HashMap<>();
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label
            , String @NotNull [] args) {
        // Check permission
        if (!sender.hasPermission("allium.spy")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }

        String spyToggleMessage = lang.get("spy.toggle");
        // Use the new Lang method to get the first color code (e.g., "&a")
        String firstColorOfSpyToggle = lang.getFirstColorCode("spy.toggle");

        String trueStyle = lang.get("styles.state.true");
        String falseStyle = lang.get("styles.state.false");

        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        UUID playerUUID = player.getUniqueId();

        // Case 1: /spy - Toggle spying status (either global or all targeted)
        if (args.length == 0) {
            boolean isGloballySpying = spyingPlayers.contains(playerUUID);
            boolean hasTargetedSpy = targetedSpying.containsKey(playerUUID) && !targetedSpying.get(playerUUID).isEmpty();

            if (isGloballySpying) {
                // Turn off global spying
                spyingPlayers.remove(playerUUID);
                sender.sendMessage(spyToggleMessage
                        .replace("{state}", falseStyle + "disabled" + firstColorOfSpyToggle)
                        .replace(" {name}", ""));
                return true;
            } else if (hasTargetedSpy) {
                // Turn off all targeted spying
                targetedSpying.remove(playerUUID);
                sender.sendMessage(spyToggleMessage
                        .replace("{state}", falseStyle + "disabled" + firstColorOfSpyToggle)
                        .replace(" {name}", ""));
                return true;
            } else {
                // Turn on global spying
                spyingPlayers.add(playerUUID);
                sender.sendMessage(spyToggleMessage
                        .replace("{state}", trueStyle + "enabled" + firstColorOfSpyToggle)
                        .replace(" {name}", ""));
                return true;
            }
        }

        // Case 2: /spy <player> - Spy on a specific player only
        String targetPlayerName = args[0];
        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);

        if (targetPlayer == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetPlayerName);
            return true;
        }

        UUID targetUUID = targetPlayer.getUniqueId();

        // Don't allow spying on yourself
        if (targetUUID.equals(playerUUID)) {
            Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "spy on");
            return true;
        }

        // Check if target player has the exempt permission
        if (targetPlayer.hasPermission("allium.spy.exempt")) {
            Text.sendErrorMessage(player, "spy.exempt", lang, "{name}", targetPlayer.getName());
            return true;
        }

        // Remove from global spying if currently active
        boolean wasGloballySpying = spyingPlayers.remove(playerUUID);

        // Get existing targeted spying, if any
        Set<UUID> currentTargets = targetedSpying.get(playerUUID);
        boolean wasTargetingPlayer = currentTargets != null && currentTargets.contains(targetUUID);

        // Clear any existing targets and create a new set with just this player
        Set<UUID> newTargets = new HashSet<>();
        newTargets.add(targetUUID);
        targetedSpying.put(playerUUID, newTargets);

        // Send appropriate message
        String message;
        if (wasGloballySpying) {
            message = lang.get("spy.toggle")
                    .replace("{state}", trueStyle + "switched to" + firstColorOfSpyToggle)
                    .replace(" {name}", targetPlayerName);
        } else if (wasTargetingPlayer) {
            // If already targeting this player, toggle off
            targetedSpying.remove(playerUUID);
            message = lang.get("spy.toggle")
                    .replace("{state}", falseStyle + "disabled" + firstColorOfSpyToggle)
                    .replace("{name}", firstColorOfSpyToggle + "for " + targetPlayerName);
        } else {
            message = lang.get("spy.toggle")
                    .replace("{state}", trueStyle + "enabled" + firstColorOfSpyToggle)
                    .replace("{name}", firstColorOfSpyToggle + "for " + targetPlayerName);
        }

        sender.sendMessage(message);

        return true;

    }

    @Override
    public List<String> onTabComplete(CommandSender sender, @NotNull Command command, @NotNull String alias
            , String @NotNull [] args) {
        if (!sender.hasPermission("allium.spy")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String partialName = args[0].toLowerCase();

            // Return player names that match the partial input
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partialName))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    /**
     * Checks if a player has global message spying enabled.
     *
     * @param playerUUID The UUID of the player to check
     * @return true if the player has spying enabled, false otherwise
     */
    public boolean isGloballySpying(UUID playerUUID) {
        return spyingPlayers.contains(playerUUID);
    }

    /**
     * Checks if a player is spying on a specific target.
     *
     * @param spyUUID The UUID of the player who might be spying
     * @param targetUUID The UUID of the potential target
     * @return true if the player is spying on the target, false otherwise
     */
    public boolean isSpyingOn(UUID spyUUID, UUID targetUUID) {
        // Check global spying first
        if (spyingPlayers.contains(spyUUID)) {
            return true;
        }

        // Then check targeted spying
        Set<UUID> targets = targetedSpying.get(spyUUID);
        return targets != null && targets.contains(targetUUID);
    }

    /**
     * Broadcasts a spy message about a conversation between two players.
     * Only sends to players who are either globally spying or specifically spying on one of the participants.
     *
     * @param message The spy message to broadcast
     * @param senderUUID UUID of the message sender
     * @param recipientUUID UUID of the message recipient
     */
    public void broadcastSpyMessage(String message, UUID senderUUID, UUID recipientUUID) {
        // Create a set to track who we've already sent the message to
        Set<UUID> messagedPlayers = new HashSet<>();
        
        // First, send to global spies
        for (UUID spyUUID : new HashSet<>(spyingPlayers)) {
            // Skip if we've already messaged this player or they're a participant
            if (messagedPlayers.contains(spyUUID) || spyUUID.equals(senderUUID) || spyUUID.equals(recipientUUID)) {
                continue;
            }

            Player spy = plugin.getServer().getPlayer(spyUUID);
            if (spy != null && spy.isOnline()) {
                spy.sendMessage(message);
                messagedPlayers.add(spyUUID);
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("Sent spy message to global spy: " + spy.getName());
                }
            }
        }


        // Then check targeted spies
        for (Map.Entry<UUID, Set<UUID>> entry : new HashMap<>(targetedSpying).entrySet()) {
            UUID spyUUID = entry.getKey();
            Set<UUID> targets = entry.getValue();

            // Skip if we've already messaged this player or they're a participant
            if (messagedPlayers.contains(spyUUID) || spyUUID.equals(senderUUID) || spyUUID.equals(recipientUUID)) {
                continue;
            }

            // Check if this spy is targeting either the sender or recipient
            if (targets.contains(senderUUID) || targets.contains(recipientUUID)) {
                Player spy = plugin.getServer().getPlayer(spyUUID);
                if (spy != null && spy.isOnline()) {
                    spy.sendMessage(message);
                    messagedPlayers.add(spyUUID);
                    if (plugin.getConfig().getBoolean("debug-mode")) {
                        plugin.getLogger().info("Sent spy message to targeted spy: " + spy.getName() + 
                            " (watching " + (targets.contains(senderUUID) ? "sender" : "") + 
                            (targets.contains(recipientUUID) ? " recipient" : "") + ")");
                    }
                }
            }
        }
        
        if (plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().info("Broadcast spy message from " + senderUUID + " to " + recipientUUID + 
                " - Sent to " + messagedPlayers.size() + " spies");
        }
    }
}