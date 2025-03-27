package net.survivalfun.core.commands.utils;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Tab implements TabCompleter {

    private final JavaPlugin plugin;
    private final FileConfiguration config;

    // Constructor to initialize the plugin
    public Tab(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, String alias, String @NotNull [] args) {
        // Ensure we handle tab completion for specific commands/aliases
        return switch (alias.toLowerCase()) {
            case "explode" -> getExplodeSuggestions(sender, args);
            case "give" -> getGiveSuggestions(sender, args);
            case "i" -> // Explicitly handle tab completion for /i
                    getISuggestions(sender, args);
            default -> new ArrayList<>(); // Return an empty list to block default behavior
        };
    }

    private List<String> getExplodeSuggestions(CommandSender sender, String[] args) {
        List<String> suggestions = new ArrayList<>();

        // Only players can use this command
        if (!(sender instanceof Player player)) return suggestions;

        // Fetch configurable values for min, max, and default from the config file
        int min = config.getInt("explode-command.min", 1);
        int max = config.getInt("explode-command.max", 10);
        int defaultValue = config.getInt("explode-command.default", 2);

        switch (args.length) {
            case 1:
                if (player.hasPermission("core.explode.other")) {
                    // Add online player names to the suggestions
                    for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                        suggestions.add(onlinePlayer.getName());
                    }
                }
                if (player.hasPermission("core.explode.self")) {
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;
            case 2:
                if (player.hasPermission("core.explode.other")) {
                    // Add min, default, and max as suggestions
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;
            default:
                // Handle cases where args.length is not 1 or 2 (optional)
                break;
        }

        // Filter and return suggestions based on the partial argument the player has typed
        return filterSuggestions(suggestions, args[args.length - 1]);
    }


    /**
     * Provides Tab Completion suggestions for the /give command.
     */
    private List<String> getGiveSuggestions(CommandSender sender, String[] args) {
        List<String> suggestions = new ArrayList<>();

        switch (args.length) {
            case 1:
                // First argument: Suggestions for online player names
                for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                    suggestions.add(onlinePlayer.getName());
                }
                break;
            case 2:
                // Second argument: Suggestions for valid item names (Material)
                for (Material material : Material.values()) {
                    suggestions.add(material.name().toLowerCase());
                }
                break;
            case 3:
                // Third argument: Suggestions for common item amounts
                suggestions.add("1");
                suggestions.add("16");
                suggestions.add("32");
                suggestions.add("64");
                break;
            default:
                // No more suggestions after 3 arguments
                break;
        }

        return filterSuggestions(suggestions, args[args.length - 1]);
    }

    /**
     * Provides Tab Completion suggestions for the /i command (alias for self-give).
     * Suggests materials for the first argument, and common amounts for the second argument.
     */
    private List<String> getISuggestions(CommandSender sender, String[] args) {
        List<String> suggestions = new ArrayList<>();

        // Ensure only players can use `/i`, no tab completions if sender is not a player
        if (!(sender instanceof Player)) {
            return suggestions; // Empty list for non-players
        }

        switch (args.length) {
            case 1:
                // First argument: Tab complete all material names
                for (Material material : Material.values()) {
                    suggestions.add(material.name().toLowerCase());
                }
                break;
            case 2:
                // Second argument: Tab complete common stack sizes
                suggestions.add("1");
                suggestions.add("16");
                suggestions.add("32");
                suggestions.add("64");
                break;
            default:
                // No suggestions for additional arguments
                break;
        }

        return filterSuggestions(suggestions, args[args.length - 1]);
    }

    /**
     * Filters suggestions based on the partial input.
     *
     * @param suggestions The list of suggestions to filter.
     * @param partial     The partial input to filter against.
     * @return A filtered list of suggestions.
     */
    private List<String> filterSuggestions(List<String> suggestions, String partial) {
        if (partial == null || partial.isEmpty()) {
            return suggestions;
        }
        return suggestions.stream()
                .filter(suggestion -> suggestion.toLowerCase().startsWith(partial.toLowerCase()))
                .collect(Collectors.toList());
    }
}