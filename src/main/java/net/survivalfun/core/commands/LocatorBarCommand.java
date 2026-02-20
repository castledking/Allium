package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocatorBarCommand implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final PartyManager partyManager;
    private final Lang lang;

    public LocatorBarCommand(PluginStart plugin, PartyManager partyManager) {
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "toggle":
                handleToggle(player);
                break;

            case "show":
                if (args.length < 3) {
                    player.sendMessage(Text.colorize("&cUsage: /locatorbar show <player> <time>"));
                    return true;
                }
                handleShow(player, args[1], args[2]);
                break;

            case "hide":
                if (args.length < 3) {
                    player.sendMessage(Text.colorize("&cUsage: /locatorbar hide <player> <time>"));
                    return true;
                }
                handleHide(player, args[1], args[2]);
                break;

            case "reset":
                if (args.length < 2) {
                    player.sendMessage(Text.colorize("&cUsage: /locatorbar reset <player>"));
                    return true;
                }
                handleReset(player, args[1]);
                break;

            default:
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void handleToggle(Player player) {
        player.getWorld().setGameRule(GameRule.LOCATOR_BAR, !player.getWorld().getGameRuleValue(GameRule.LOCATOR_BAR));
        boolean sendCommandFeedback = player.getWorld().getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK);
        if (!sendCommandFeedback) {
            player.sendMessage(lang.get("locatorbar.toggle"));
        }
    }

    private void handleShow(Player player, String targetName, String timeString) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetName);
            return;
        }

        long durationSeconds = parseTimeString(timeString);
        if (durationSeconds <= 0) {
            player.sendMessage(Text.colorize("&cInvalid time format. Use format like 30, 1m, 2h, 1d"));
            return;
        }

        // Force show the target player temporarily
        partyManager.forceShowPlayer(player, target, durationSeconds);
        player.sendMessage(Text.colorize("&aForcing " + target.getName() + " to be visible for " + formatDuration(durationSeconds) + "."));

        if (!target.equals(player)) {
            target.sendMessage(Text.colorize("&e" + player.getName() + " has made you visible on the locator bar for " + formatDuration(durationSeconds) + "."));
        }
    }

    private void handleHide(Player player, String targetName, String timeString) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetName);
            return;
        }

        long durationSeconds = parseTimeString(timeString);
        if (durationSeconds <= 0) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "&ctime format.", "{syntax}", "Use format like 30, 1m, 2h, 1d");
            return;
        }

        // Force hide the target player temporarily
        partyManager.forceHidePlayer(player, target, durationSeconds);
        player.sendMessage(Text.colorize("&aForcing " + target.getName() + " to be hidden for " + formatDuration(durationSeconds) + "."));

        if (!target.equals(player)) {
            target.sendMessage(Text.colorize("&e" + player.getName() + " has hidden you from the locator bar for " + formatDuration(durationSeconds) + "."));
        }
    }

    private void handleReset(Player player, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetName);
            return;
        }

        if (target.equals(player)) {
            player.sendMessage(Text.colorize("&cYou cannot modify your own visibility."));
            return;
        }

        // Reset any forced visibility overrides for the target player from this player
        partyManager.resetPlayer(player, target);
        player.sendMessage(Text.colorize("&aReset visibility overrides for " + target.getName() + "."));

        if (!target.equals(player)) {
            target.sendMessage(Text.colorize("&e" + player.getName() + " has reset your locator bar visibility."));
        }
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(Text.colorize("&6Locator Bar Commands:"));
        player.sendMessage(Text.colorize("&e/locatorbar toggle &7- Toggle locator bar"));
        player.sendMessage(Text.colorize("&e/locatorbar show <player> <time> &7- Force show a player"));
        player.sendMessage(Text.colorize("&e/locatorbar hide <player> <time> &7- Force hide a player"));
        player.sendMessage(Text.colorize("&e/locatorbar reset <player> &7- Reset visibility overrides"));
        player.sendMessage(Text.colorize("&7Time formats: 30 (seconds), 1m (minutes), 2h (hours), 1d (days)"));
    }

    /**
     * Parse time string into seconds
     * Supports formats like: 30, 1m, 2h, 1d
     */
    private long parseTimeString(String timeString) {
        if (timeString == null || timeString.isEmpty()) {
            return -1;
        }

        // Check if it's just a number (raw seconds)
        try {
            return Long.parseLong(timeString);
        } catch (NumberFormatException e) {
            // Not a raw number, try parsing with units
        }

        Pattern pattern = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(timeString);

        if (!matcher.matches()) {
            return -1; // Invalid format
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();

        return switch (unit) {
            case "s" -> value; // seconds
            case "m" -> value * 60; // minutes
            case "h" -> value * 3600; // hours
            case "d" -> value * 86400; // days
            default -> -1;
        };
    }

    /**
     * Format duration in seconds to a readable string
     */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours + " hour" + (hours != 1 ? "s" : "");
        } else {
            long days = seconds / 86400;
            return days + " day" + (days != 1 ? "s" : "");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String partial = args[0].toLowerCase();
            for (String sub : new String[]{"toggle", "show", "hide", "reset"}) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
            return completions;
        } else if (args.length == 2 && ("show".equalsIgnoreCase(args[0]) || "hide".equalsIgnoreCase(args[0]) || "reset".equalsIgnoreCase(args[0]))) {
            // Suggest online players
            List<String> completions = new ArrayList<>();
            String partial = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
            return completions;
        } else if (args.length == 3 && ("show".equalsIgnoreCase(args[0]) || "hide".equalsIgnoreCase(args[0]))) {
            // Suggest time formats
            return Arrays.asList("30", "60", "1m", "5m", "1h", "1d");
        }

        return Collections.emptyList();
    }
}
