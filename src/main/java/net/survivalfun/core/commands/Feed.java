package net.survivalfun.core.commands;

import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Feed implements CommandExecutor {

    private final Lang lang;
    private final FileConfiguration config;
    private final Map<UUID, Long> lastFeedTime = new HashMap<>();
    private final int feedCooldownSeconds;
    private final Plugin plugin;

    public Feed(Lang lang, FileConfiguration config, Plugin plugin) {
        this.lang = lang;
        this.config = config;
        this.plugin = plugin;

        // Default cooldown is 60 seconds if not specified in config
        this.feedCooldownSeconds = config.getInt("feed.cooldown", 60);

        // Add default config values if not present (should be done in main plugin class)
        if (!config.contains("feed.cooldown")) {
            config.set("feed.cooldown", 60);
            // Note: You'd typically save the config in the main plugin class
        }
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        // Handle console sender
        if (!(sender instanceof Player)) {
            return handleConsoleCommand(sender, args);
        }

        // Handle player sender
        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("allium.feed")) {
            Text.sendErrorMessage(player, "no-permission", lang);
            return true;
        }

        // Process command based on number of arguments
        switch (args.length) {
            case 0:
                // Feed self
                feedPlayer(player);
                return true;
            case 1:
                if(!player.hasPermission("allium.feed.others")){
                    feedPlayer(player);
                    return true;
                }
                return handlePlayerFeedTarget(player, args[0]);
            default:
                player.sendMessage("§cUsage: /feed [player]");
                return true;
        }
    }

    private boolean handleConsoleCommand(CommandSender sender, String[] args) {
        switch (args.length) {
            case 0:
                String usage = lang.get("command-usage")
                        .replace("{cmd}", "feed")
                        .replace("{args}", "[player]");
                lang.sendMessage(sender, usage);
                return true;
            case 1:
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    if(Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()){
                        Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
                        return true;
                    }else{
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                        return true;
                    }
                }

                // Feed target and notify
                feedPlayer(target);
                // Send message to sender with sound
                String otherMessage = Text.parseColors(lang.get("feed.other")
                        .replace("{name}", target.getName()));
                lang.sendMessage(sender, otherMessage);
                
                // Send message to target with sound
                String selfMessage = Text.parseColors(lang.get("feed.self"));
                lang.sendMessage(target, selfMessage);
                return true;
            default:
                String usageMsg = lang.get("command-usage")
                        .replace("{cmd}", "feed")
                        .replace("{args}", "[player]");
                lang.sendMessage(sender, usageMsg);
                return false;
        }
    }

    private boolean handlePlayerFeedTarget(Player player, String targetName) {
        // Check if player is trying to feed themselves by name
        if (targetName.equalsIgnoreCase(player.getName())) {
            // Feed the player (cooldown is checked inside feedPlayer)
            feedPlayer(player);
            return true;
        }

        // Try to find the target player
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            // Feed the target (feed.self message is sent inside feedPlayer)
            feedPlayer(target);
            String otherMessage = Text.parseColors(lang.get("feed.other")
                    .replace("{name}", target.getName()));
            lang.sendMessage(player, otherMessage);
        } else {
            // Target is offline or doesn't exist
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
            if (offlineTarget.hasPlayedBefore()) {
                Text.sendErrorMessage(player, "player-not-online", lang, "{name}", targetName);
            } else {
                Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
            }
        }
        return true;
    }

    /**
     * Checks if a player can use feed command based on cooldown
     * @param player The player to check
     * @return True if player can feed, false otherwise
     */
    private boolean canFeed(Player player) {
        // Admins with permission bypass cooldown
        if (player.hasPermission("allium.feed.nocooldown")) {
            return true;
        }

        // If player hasn't used the command before, allow it
        if (!lastFeedTime.containsKey(player.getUniqueId())) {
            return true;
        }

        // Check if cooldown has passed
        long lastUsed = lastFeedTime.get(player.getUniqueId());
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = feedCooldownSeconds * 1000L;

        if (currentTime - lastUsed < cooldownMillis) {
            // Cooldown not yet passed
            int remainingSeconds = (int) ((cooldownMillis - (currentTime - lastUsed)) / 1000);

            // Format the time in a more readable way
            String formattedTime = Text.formatTime(remainingSeconds);

            // Send message about remaining cooldown time
            String cooldownMessage = lang.get("cooldown")
                    .replace("{time}", formattedTime)
                    .replace("{cmd}", "/feed");
            lang.sendMessage(player, cooldownMessage);

            return false;
        }

        return true;
    }

    private void feedPlayer(Player player) {
        // Set food level and saturation to maximum
        // Check cooldown for self-feed
        if (!canFeed(player)) {
            return;
        }
        player.setFoodLevel(20);
        player.setSaturation(20f);
        String message = lang.get("feed.self", player);
        if (!message.isEmpty()) {
            // Send the message with sound
            lang.sendMessage(player, message);
        }
        if(!player.hasPermission("allium.feed.nocooldown")){
            lastFeedTime.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Clean up the cooldown map when plugin disables to prevent memory leaks
     */
    public void cleanup() {
        lastFeedTime.clear();
    }
}