package net.survivalfun.core.commands.utils.core.managers;

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

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        // Handle console sender
        if (!(sender instanceof Player)) {
            return handleConsoleCommand(sender, args);
        }

        // Handle player sender
        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("core.feed")) {
            Text.sendErrorMessage(player, "no-permission", lang);
            return true;
        }

        // Process command based on number of arguments
        switch (args.length) {
            case 0:
                // Check cooldown for self-feed
                if (!canFeed(player)) {
                    return true;
                }

                // Feed self
                feedPlayer(player);
                player.sendMessage(lang.get("feed.self"));

                // Record last feed time for cooldown
                if (!player.hasPermission("core.feed.nocooldown")) {
                    lastFeedTime.put(player.getUniqueId(), System.currentTimeMillis());
                }
                return true;
            case 1:
                return handlePlayerFeedTarget(player, args[0]);
            default:
                player.sendMessage("§cUsage: /feed [player]");
                return true;
        }
    }

    private boolean handleConsoleCommand(CommandSender sender, String[] args) {
        switch (args.length) {
            case 0:
                sender.sendMessage("§cUsage: /feed <player>");
                return true;
            case 1:
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    // Check if player exists but is offline
                    if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                        sender.sendMessage("§cPlayer §e" + args[0] + " §cis currently offline.");
                    } else {
                        sender.sendMessage("§cPlayer §e" + args[0] + " §chas never joined this server.");
                    }
                    return true;
                }

                // Feed target and notify
                feedPlayer(target);
                sender.sendMessage(Text.parseColors(lang.get("feed.other")
                        .replace("{name}", target.getName())));
                target.sendMessage(Text.parseColors(lang.get("feed.self")));
                return true;
            default:
                sender.sendMessage("§cUsage: /feed <player>");
                return false;
        }
    }

    private boolean handlePlayerFeedTarget(Player player, String targetName) {
        // Check if player is trying to feed themselves by name
        if (targetName.equalsIgnoreCase(player.getName())) {
            // Check cooldown for self-feed
            if (!canFeed(player)) {
                return true;
            }

            feedPlayer(player);
            player.sendMessage(Text.parseColors(lang.get("feed.self")));

            // Record last feed time for cooldown
            if (!player.hasPermission("core.feed.nocooldown")) {
                lastFeedTime.put(player.getUniqueId(), System.currentTimeMillis());
            }
            return true;
        }

        // Check permission for feeding others
        if (!player.hasPermission("core.feed.others")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "/feed on others");
            return true;
        }

        // Try to find the target player
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            // Target is online - feed and notify
            feedPlayer(target);
            player.sendMessage(Text.parseColors(lang.get("feed.other")
                    .replace("{name}", target.getName())));
            target.sendMessage(Text.parseColors(lang.get("feed.self")));
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
        if (player.hasPermission("core.feed.nocooldown")) {
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
            player.sendMessage(cooldownMessage);

            return false;
        }

        return true;
    }

    private void feedPlayer(Player player) {
        // Set food level and saturation to maximum
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    /**
     * Clean up the cooldown map when plugin disables to prevent memory leaks
     */
    public void cleanup() {
        lastFeedTime.clear();
    }
}