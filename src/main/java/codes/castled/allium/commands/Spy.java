package codes.castled.allium.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.managers.lang.Lang;
import codes.castled.allium.util.PlayerMatcher;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.*;
import java.util.stream.Collectors;

public class Spy implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;

    // Players who have global spying enabled
    private final Set<UUID> spyingPlayers;

    /**
     * Maps players to the specific players they're spying on
     */
    private final Map<UUID, Set<UUID>> targetedSpying;

    /**
     * Gets the map of players and who they're spying on.
     * @return Map where key is the spy's UUID and value is the set of target UUIDs
     */
    public Map<UUID, Set<UUID>> getTargetedSpying() {
        return targetedSpying;
    }

    /**
     * Constructs a new Spy instance.
     * @param plugin The plugin instance
     */
    public Spy(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.spyingPlayers = new HashSet<>();
        this.targetedSpying = new HashMap<>();
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label
            , String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.spy")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }

        String firstColorOfSpyToggle = lang.getFirstColorCode("spy.toggle");

        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        UUID playerUUID = player.getUniqueId();

        // Case 1: /spy - Toggle spying status (either global or all targeted).
        // This is the only entry point that can turn global spying on.
        if (args.length == 0) {
            boolean isGloballySpying = spyingPlayers.contains(playerUUID);
            boolean hasTargetedSpy = targetedSpying.containsKey(playerUUID) && !targetedSpying.get(playerUUID).isEmpty();

            if (isGloballySpying || hasTargetedSpy) {
                // Turn off whatever is currently active (global and/or targeted)
                spyingPlayers.remove(playerUUID);
                targetedSpying.remove(playerUUID);
                String stateValue = lang.get("styles.state.false") + "disabled" + firstColorOfSpyToggle;
                lang.sendMessage(sender, "spy.toggle", "state", stateValue, "name", "");
                return true;
            } else {
                // Turn on global spying
                spyingPlayers.add(playerUUID);
                String stateValue = lang.get("styles.state.true") + "enabled" + firstColorOfSpyToggle;
                lang.sendMessage(sender, "spy.toggle", "state", stateValue, "name", "");
                return true;
            }
        }

        // Case 2: /spy <player> - Spy on a specific player only
        String targetPlayerName = args[0];
        Player targetPlayer = PlayerMatcher.match(sender, targetPlayerName);

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

        boolean hasOthersPermission = player.hasPermission("allium.spy.others");

        // /spy <player> can never leave global spying on, regardless of permission
        boolean wasGloballySpying = spyingPlayers.contains(playerUUID);
        if (wasGloballySpying) {
            spyingPlayers.remove(playerUUID);
        }

        Set<UUID> currentTargets = targetedSpying.get(playerUUID);
        boolean wasTargetingPlayer = currentTargets != null && currentTargets.contains(targetUUID);

        String trueStyle = lang.get("styles.state.true");
        String falseStyle = lang.get("styles.state.false");
        String message;

        if (wasGloballySpying) {
            // Coming out of global spy: switch to isolated targeted spying on this player
            setTarget(playerUUID, targetUUID);
            message = lang.get("spy.toggle")
                    .replace("{state}", trueStyle + "switched" + firstColorOfSpyToggle + " to")
                    .replace("{name}", targetPlayerName);
        } else if (hasOthersPermission && wasTargetingPlayer) {
            // Already targeting this player outside global spy: toggle targeted spying off
            clearTarget(playerUUID, targetUUID);
            message = lang.get("spy.toggle")
                    .replace("{state}", falseStyle + "disabled" + firstColorOfSpyToggle)
                    .replace("{name}", firstColorOfSpyToggle + "for " + targetPlayerName);
        } else {
            // Enable (or re-affirm) isolated targeted spying on this player
            setTarget(playerUUID, targetUUID);
            message = lang.get("spy.toggle")
                    .replace("{state}", trueStyle + "enabled" + firstColorOfSpyToggle)
                    .replace("{name}", firstColorOfSpyToggle + "for " + targetPlayerName);
        }

        sender.sendMessage(message);

        return true;

    }

    /**
     * Replaces the spy's targets with a single target, keeping targeted spying isolated.
     *
     * @param spyUUID The UUID of the spy
     * @param targetUUID The UUID of the player to spy on
     */
    private void setTarget(UUID spyUUID, UUID targetUUID) {
        Set<UUID> newTargets = new HashSet<>();
        newTargets.add(targetUUID);
        targetedSpying.put(spyUUID, newTargets);
    }

    /**
     * Removes a single target from the spy's targets, dropping the entry once it is empty.
     *
     * @param spyUUID The UUID of the spy
     * @param targetUUID The UUID of the player to stop spying on
     */
    private void clearTarget(UUID spyUUID, UUID targetUUID) {
        Set<UUID> targets = targetedSpying.get(spyUUID);
        if (targets == null) {
            return;
        }
        targets.remove(targetUUID);
        if (targets.isEmpty()) {
            targetedSpying.remove(spyUUID);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, @NotNull Command command, @NotNull String alias
            , String[] args) {
        if (!sender.hasPermission("allium.spy")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String partialName = args[0].toLowerCase();

            // Return player names that match the partial input, excluding the sender
            // and anyone who cannot be spied on
            return Bukkit.getOnlinePlayers().stream()
                    .filter(online -> !online.equals(sender))
                    .filter(online -> !online.hasPermission("allium.spy.exempt"))
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
                    Text.sendDebugLog(INFO, "Sent spy message to global spy: " + spy.getName());
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
                        Text.sendDebugLog(INFO, "Sent spy message to targeted spy: " + spy.getName() + 
                            " (watching " + (targets.contains(senderUUID) ? "sender" : "") + 
                            (targets.contains(recipientUUID) ? " recipient" : "") + ")");
                    }
                }
            }
        }
        
        if (plugin.getConfig().getBoolean("debug-mode")) {
            Text.sendDebugLog(INFO, "Broadcast spy message from " + senderUUID + " to " + recipientUUID + 
                " - Sent to " + messagedPlayers.size() + " spies");
        }
    }
}