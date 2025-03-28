package net.survivalfun.core.commands.utils;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class Tab implements TabCompleter {

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final List<String> cachedMaterialNames; // Cache material names for better performance

    public Tab(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.cachedMaterialNames = Arrays.stream(Material.values())
                .map(m -> m.name().toLowerCase(Locale.ENGLISH))
                .collect(Collectors.toList());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String @NotNull [] args) {
        try {
            return switch (alias.toLowerCase(Locale.ENGLISH)) {
                case "explode" -> getExplodeSuggestions(sender, args);
                case "give" -> getGiveSuggestions(sender, args);
                case "i" -> getISuggestions(sender, args);
                case "heal" -> getHealSuggestions(sender, args);
                default -> new ArrayList<>();
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Tab completion error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> getHealSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length != 1) return suggestions;

        String partialName = args[0].toLowerCase(Locale.ENGLISH);
        boolean hasPermission = !(sender instanceof Player) || sender.hasPermission("core.heal.others");

        if (hasPermission) {
            suggestions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ENGLISH).startsWith(partialName))
                    .toList());
        }

        return suggestions;
    }

    private List<String> getExplodeSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (!(sender instanceof Player)) return suggestions;

        int min = config.getInt("explode-command.min", 1);
        int max = config.getInt("explode-command.max", 10);
        int defaultValue = config.getInt("explode-command.default", 2);

        switch (args.length) {
            case 1:
                if (sender.hasPermission("core.explode.other")) {
                    Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));
                }
                if (sender.hasPermission("core.explode.self")) {
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;

            case 2:
                if (sender.hasPermission("core.explode.other")) {
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;
        }

        return filterSuggestions(suggestions, args.length > 0 ? args[args.length - 1] : "");
    }

    private List<String> getGiveSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (!(sender instanceof Player)) return suggestions;

        switch (args.length) {
            case 1:
                Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));
                break;

            case 2:
                suggestions.addAll(cachedMaterialNames);
                break;

            case 3:
                suggestions.addAll(List.of("1", "16", "32", "64"));
                break;
        }

        return filterSuggestions(suggestions, args.length > 0 ? args[args.length - 1] : "");
    }

    private List<String> getISuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (!(sender instanceof Player)) return suggestions;

        switch (args.length) {
            case 1:
                suggestions.addAll(cachedMaterialNames);
                break;

            case 2:
                suggestions.addAll(List.of("1", "16", "32", "64"));
                break;
        }

        return filterSuggestions(suggestions, args.length > 0 ? args[args.length - 1] : "");
    }

    private List<String> filterSuggestions(@NotNull List<String> suggestions, @NotNull String partial) {
        if (partial.isEmpty()) return suggestions;

        String lowerPartial = partial.toLowerCase(Locale.ENGLISH);
        return suggestions.stream()
                .filter(s -> s.toLowerCase(Locale.ENGLISH).startsWith(lowerPartial))
                .collect(Collectors.toList());
    }
}