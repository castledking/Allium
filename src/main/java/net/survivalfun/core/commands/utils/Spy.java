package net.survivalfun.core.commands.utils;

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
        if (!sender.hasPermission("core.spy")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
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
                sender.sendMessage(lang.get("spy.toggle")
                        .replace("{state}", Text.parseColors("&c&ndisabled&r"))
                        .replace("{name}", ""));
                return true;
            } else if (hasTargetedSpy) {
                // Turn off all targeted spying
                targetedSpying.remove(playerUUID);
                sender.sendMessage(lang.get("spy.toggle")
                        .replace("{state}", Text.parseColors("&c&ndisabled&r"))
                        .replace("{name}", ""));
                return true;
            } else {
                // Turn on global spying
                spyingPlayers.add(playerUUID);
                sender.sendMessage(lang.get("spy.toggle")
                        .replace("{state}", Text.parseColors("&c&ndisabled&r"))
                        .replace("{name}", ""));
                return true;
            }
        }

        // Case 2: /spy <player> - Toggle spying on a specific player
        String targetPlayerName = args[0];
        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);

        if (targetPlayer == null) {
            Text.sendErrorMessage(player, lang.get("player-not-found").replace("{name}", targetPlayerName), lang);
            return true;
        }

        UUID targetUUID = targetPlayer.getUniqueId();

        // Don't allow spying on yourself
        if (targetUUID.equals(playerUUID)) {
            Text.sendErrorMessage(player, lang.get("spy.self"), lang);
            return true;
        }

        // Check if target player has the exempt permission
        if (targetPlayer.hasPermission("core.spy.exempt")) {
            Text.sendErrorMessage(player, lang.get("spy.exempt").replace("{name}", targetPlayer.getName()), lang);
            return true;
        }

        // If currently globally spying, switch to targeted spying
        if (spyingPlayers.contains(playerUUID)) {
            // Remove from global spying
            spyingPlayers.remove(playerUUID);

            // Initialize targeted spying for this spy
            Set<UUID> playersBeingListenedTo = new HashSet<>();
            playersBeingListenedTo.add(targetUUID);
            targetedSpying.put(playerUUID, playersBeingListenedTo);

            // Inform the player about the change
            sender.sendMessage(lang.get("spy.toggle")
                    .replace("{state}", Text.parseColors("&a&nswitched"))
                    .replace("{name}", Text.parseColors("&ato " + targetPlayerName)));
            return true;
        }

        // Get or create the set of players being listened to by this spy
        Set<UUID> playersBeingListenedTo = targetedSpying.computeIfAbsent(playerUUID, k -> new HashSet<>());

        // Toggle listening for this player's messages
        if (playersBeingListenedTo.contains(targetUUID)) {
            // Stop listening to this player
            playersBeingListenedTo.remove(targetUUID);

            if (playersBeingListenedTo.isEmpty()) {
                // If not listening to anyone, remove the entry altogether
                targetedSpying.remove(playerUUID);
                sender.sendMessage(lang.get("spy.toggle")
                        .replace("{state}", Text.parseColors("&c&ndisabled&r"))
                        .replace("{name}", Text.parseColors("&afor " + targetPlayerName)));
            } else {
                sender.sendMessage(lang.get("spy.toggle")
                        .replace("{state}", Text.parseColors("&c&ndisabled&r"))
                        .replace("{name}", Text.parseColors("&afor " + targetPlayerName)));
            }
        } else {
            // Start listening to this player
            playersBeingListenedTo.add(targetUUID);
            sender.sendMessage(lang.get("spy.toggle")
                    .replace("{state}", Text.parseColors("&a&nenabled&r"))
                    .replace("{name}", Text.parseColors("&afor " + targetPlayerName)));
        }

        return true;

    }


    @Override
    public List<String> onTabComplete(CommandSender sender, @NotNull Command command, @NotNull String alias
            , String @NotNull [] args) {
        if (!sender.hasPermission("core.spy")) {
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
        // First, send to global spies
        for (UUID spyUUID : spyingPlayers) {
            // Don't send to participants
            if (spyUUID.equals(senderUUID) || spyUUID.equals(recipientUUID)) {
                continue;
            }

            Player spy = plugin.getServer().getPlayer(spyUUID);
            if (spy != null && spy.isOnline()) {
                spy.sendMessage(message);
            }
        }

        // Then check targeted spies
        for (Map.Entry<UUID, Set<UUID>> entry : targetedSpying.entrySet()) {
            UUID spyUUID = entry.getKey();
            Set<UUID> targets = entry.getValue();

            // Skip if the spy is one of the conversation participants
            if (spyUUID.equals(senderUUID) || spyUUID.equals(recipientUUID)) {
                continue;
            }

            // Check if this spy is targeting either the sender or recipient
            if (targets.contains(senderUUID) || targets.contains(recipientUUID)) {
                Player spy = plugin.getServer().getPlayer(spyUUID);
                if (spy != null && spy.isOnline()) {
                    spy.sendMessage(message);
                }
            }
        }
    }
}