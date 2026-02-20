package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.core.VanishManager;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.bukkit.command.TabCompleter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Vanish implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final VanishManager vanishManager;
    private final Lang lang;

    public Vanish(PluginStart plugin, VanishManager vanishManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle both /v and /vanish commands
        if (command.getName().equalsIgnoreCase("vanish") || command.getName().equalsIgnoreCase("v")) {

            // Check if sender has vanish permission
            if (!sender.hasPermission("allium.vanish")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
                return true;
            }

            if (args.length == 0) {
                // /vanish or /v (toggle self)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "[level]"));
                    return true;
                }

                handleVanishToggle(player, sender, label);
            } else if (args.length == 1) {
                String targetArg = args[0];

                // Check if targeting another player or specifying a level
                if (targetArg.matches("\\d+")) {
                    // /vanish <level> (vanish self with specific level)
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(lang.get("command-usage")
                                .replace("{cmd}", label)
                                .replace("{args}", "[level]"));
                        return true;
                    }

                    int level;
                    try {
                        level = Integer.parseInt(targetArg);
                    } catch (NumberFormatException e) {
                        Text.sendErrorMessage(sender, "invalid-number", lang, "{number}", targetArg);
                        return true;
                    }

                    handleVanishLevel(player, sender, label, level);
                } else {
                    // /vanish <player> (toggle another player)
                    if (!sender.hasPermission("allium.vanish.others")) {
                        Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " others");
                        return true;
                    }

                    Player target = Bukkit.getPlayer(targetArg);
                    if (target == null) {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetArg);
                        return true;
                    }

                    handleVanishOther(sender, label, target);
                }
            } else if (args.length == 2) {
                // /vanish <player> <level> (vanish another player with specific level)
                if (!sender.hasPermission("allium.vanish.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " others");
                    return true;
                }

                String playerName = args[0];
                Player target = Bukkit.getPlayer(playerName);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", playerName);
                    return true;
                }

                int level;
                try {
                    level = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    Text.sendErrorMessage(sender, "invalid-number", lang, "{number}", args[1]);
                    return true;
                }

                handleVanishOtherWithLevel(sender, label, target, level);
            } else {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[level|<player> [level]]"));
                return true;
            }

            return true;
        }
        return false;
    }

    private void handleVanishToggle(Player player, CommandSender sender, String label) {
        if (vanishManager.isVanished(player)) {
            // Unvanish
            if (vanishManager.unvanishPlayer(player)) {
                String message = lang.get("vanish.unvanished")
                        .replace("{player}", player.getName());
                player.sendMessage(message);

                if (!sender.equals(player)) {
                    sender.sendMessage(lang.get("vanish.unvanished-other")
                            .replace("{player}", player.getName()));
                }
            } else {
                Text.sendErrorMessage(sender, "vanish.failed", lang, "{player}", player.getName());
            }
        } else {
            // Vanish with default level
            int effectiveLevel = vanishManager.getEffectiveVanishLevel(player);
            if (effectiveLevel > 0) {
                if (vanishManager.vanishPlayer(player, effectiveLevel)) {
                    String message = lang.get("vanish.vanished")
                            .replace("{player}", player.getName())
                            .replace("{level}", String.valueOf(effectiveLevel));
                    player.sendMessage(message);

                    if (!sender.equals(player)) {
                        sender.sendMessage(lang.get("vanish.vanished-other")
                                .replace("{player}", player.getName())
                                .replace("{level}", String.valueOf(effectiveLevel)));
                    }
                } else {
                    Text.sendErrorMessage(sender, "vanish.failed", lang, "{player}", player.getName());
                }
            } else {
                Text.sendErrorMessage(sender, "vanish.no-level", lang);
            }
        }
    }

    private void handleVanishLevel(Player player, CommandSender sender, String label, int level) {
        if (vanishManager.isVanished(player)) {
            // Change vanish level
            int currentLevel = vanishManager.getVanishLevel(player);
            if (vanishManager.vanishPlayer(player, level)) {
                String message = lang.get("vanish.level-changed")
                        .replace("{level}", String.valueOf(level))
                        .replace("{old_level}", String.valueOf(currentLevel));
                player.sendMessage(message);

                if (!sender.equals(player)) {
                    sender.sendMessage(lang.get("vanish.level-changed-other")
                            .replace("{player}", player.getName())
                            .replace("{level}", String.valueOf(level))
                            .replace("{old_level}", String.valueOf(currentLevel)));
                }

                // Update visibility for all players
                vanishManager.updateAllVisibility();
            } else {
                Text.sendErrorMessage(sender, "vanish.level-failed", lang, "{level}", String.valueOf(level));
            }
        } else {
            // Vanish with specific level
            if (vanishManager.vanishPlayer(player, level)) {
                String message = lang.get("vanish.vanished")
                        .replace("{player}", player.getName())
                        .replace("{level}", String.valueOf(level));
                player.sendMessage(message);

                if (!sender.equals(player)) {
                    sender.sendMessage(lang.get("vanish.vanished-other")
                            .replace("{player}", player.getName())
                            .replace("{level}", String.valueOf(level)));
                }
            } else {
                Text.sendErrorMessage(sender, "vanish.level-failed", lang, "{level}", String.valueOf(level));
            }
        }
    }

    private void handleVanishOther(CommandSender sender, String label, Player target) {
        if (vanishManager.isVanished(target)) {
            // Unvanish target
            if (vanishManager.unvanishPlayer(target)) {
                sender.sendMessage(lang.get("vanish.unvanished-other")
                        .replace("{player}", target.getName()));

                target.sendMessage(lang.get("vanish.unvanished")
                        .replace("{player}", target.getName()));
            } else {
                Text.sendErrorMessage(sender, "vanish.failed", lang, "{player}", target.getName());
            }
        } else {
            // Vanish target with their effective level
            int effectiveLevel = vanishManager.getEffectiveVanishLevel(target);
            if (effectiveLevel > 0) {
                if (vanishManager.vanishPlayer(target, effectiveLevel)) {
                    sender.sendMessage(lang.get("vanish.vanished-other")
                            .replace("{player}", target.getName())
                            .replace("{level}", String.valueOf(effectiveLevel)));

                    target.sendMessage(lang.get("vanish.vanished")
                            .replace("{player}", target.getName())
                            .replace("{level}", String.valueOf(effectiveLevel)));
                } else {
                    Text.sendErrorMessage(sender, "vanish.failed", lang, "{player}", target.getName());
                }
            } else {
                Text.sendErrorMessage(sender, "vanish.no-level", lang);
            }
        }
    }

    private void handleVanishOtherWithLevel(CommandSender sender, String label, Player target, int level) {
        if (vanishManager.isVanished(target)) {
            // Change vanish level
            int currentLevel = vanishManager.getVanishLevel(target);
            if (vanishManager.vanishPlayer(target, level)) {
                sender.sendMessage(lang.get("vanish.level-changed-other")
                        .replace("{player}", target.getName())
                        .replace("{level}", String.valueOf(level))
                        .replace("{old_level}", String.valueOf(currentLevel)));

                target.sendMessage(lang.get("vanish.level-changed")
                        .replace("{level}", String.valueOf(level))
                        .replace("{old_level}", String.valueOf(currentLevel)));

                // Update visibility for all players
                vanishManager.updateAllVisibility();
            } else {
                Text.sendErrorMessage(sender, "vanish.level-failed", lang, "{level}", String.valueOf(level));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (!sender.hasPermission("allium.vanish")) {
            return suggestions;
        }

        switch (args.length) {
            case 1:
                // /vanish <player or level>
                if (sender.hasPermission("allium.vanish.others")) {
                    // Suggest online players that the sender can see
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (sender instanceof Player && !((Player) sender).canSee(player)) {
                            continue; // Skip players that sender can't see
                        }
                        if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                            suggestions.add(player.getName());
                        }
                    }
                }

                // Also suggest vanish levels if input looks like a number
                if (args[0].matches("\\d*")) {
                    // Suggest common vanish levels
                    for (int i = 1; i <= 10; i++) {
                        if (sender.hasPermission("allium.vanish." + i) &&
                            String.valueOf(i).startsWith(args[0])) {
                            suggestions.add(String.valueOf(i));
                        }
                    }
                }
                break;

            case 2:
                // /vanish <player> <level>
                if (sender.hasPermission("allium.vanish.others")) {
                    // Suggest vanish levels for the second argument
                    for (int i = 1; i <= 10; i++) {
                        if (sender.hasPermission("allium.vanish." + i) &&
                            String.valueOf(i).startsWith(args[1])) {
                            suggestions.add(String.valueOf(i));
                        }
                    }
                }
                break;
        }

        return suggestions;
    }
}
