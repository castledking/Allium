package net.survivalfun.core.commands.utils;

import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.LoreHelper;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class Tab implements TabCompleter {

    private final JavaPlugin plugin;
    private List<String> cachedMaterialNames;
    private final Set<String> cachedAliases = new HashSet<>();
    private final List<String> potionEffects = Arrays.asList(
        "turtle_master", "speed", "slowness", "strength", "jump_boost",
        "regeneration", "fire_resistance", "water_breathing", "invisibility",
        "night_vision", "poison", "weakness", "luck"
    );

    public Tab(JavaPlugin plugin) {
        this.plugin = plugin;
        
        loadAliases();
    }

    private void loadAliases() {
        // Load material names that are giveable
        cachedMaterialNames = Arrays.stream(Material.values())
            .filter(Item::isGiveable)
            .map(Material::name)
            .map(String::toLowerCase)
            .collect(Collectors.toList());
            
        // Load and cache aliases from itemdb.yml
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "itemdb.yml"));
            ConfigurationSection aliases = config.getConfigurationSection("aliases");
            if (aliases != null) {
                aliases.getKeys(false).forEach(alias -> {
                    String aliasLower = alias.toLowerCase();
                    cachedAliases.add(aliasLower);
                    // Also add the alias to cachedMaterialNames for direct matching
                    cachedMaterialNames.add(aliasLower);
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load aliases from itemdb.yml: " + e.getMessage());
        }
    }

    private List<String> filterSuggestions(List<String> suggestions, String input) {
        if (input.isEmpty()) {
            return suggestions;
        }
        String lowerInput = input.toLowerCase();
        return suggestions.stream()
            .filter(s -> s.toLowerCase().startsWith(lowerInput))
            .collect(Collectors.toList());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                              @NotNull String alias, @NotNull String @NotNull [] args) {
        try {
            return switch (alias.toLowerCase(Locale.ENGLISH)) {
                case "give" -> getGiveSuggestions(sender, args);
                case "fly" -> getFlySuggestions(sender, args);
                case "gamemode" -> getGamemodeSuggestions(sender, alias, args);
                case "gmc" -> getGamemodeSuggestions(sender, alias, args);
                case "gms" -> getGamemodeSuggestions(sender, alias, args);
                case "gma" -> getGamemodeSuggestions(sender, alias, args);
                case "gmsp" -> getGamemodeSuggestions(sender, alias, args);
                case "creative" -> getGamemodeSuggestions(sender, alias, args);
                case "survival" -> getGamemodeSuggestions(sender, alias, args);
                case "adventure" -> getGamemodeSuggestions(sender, alias, args);
                case "spectator" -> getGamemodeSuggestions(sender, alias, args);
                case "heal" -> getHealSuggestions(sender, args);
                case "explode" -> getExplodeSuggestions(sender, args);
                case "i" -> getISuggestions(sender, args);
                case "lore" -> getLoreSuggestions(sender, args);
                case "core" -> getCoreSuggestions(sender, args);
                default -> new ArrayList<>();
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Tab completion error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> getCoreSuggestions(@NotNull CommandSender sender, @NotNull String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();
        String currentArgToFilter;
    
        if (args.length == 0) { // Should not happen with Bukkit's tab completer, but good for safety
            return suggestions;
        }
    
        // Level 1: Subcommand names (e.g., /core <subcommand>)
        if (args.length == 1) {
            currentArgToFilter = args[0].toLowerCase();
    
            // Subcommands requiring core.admin
            if (sender.hasPermission("core.admin")) {
                if ("reload".startsWith(currentArgToFilter)) {
                    suggestions.add("reload");
                }
                if ("debug".startsWith(currentArgToFilter)) {
                    suggestions.add("debug");
                }
                if ("hideupdate".startsWith(currentArgToFilter)) {
                    suggestions.add("hideupdate");
                }
            }
    
            // Modalerts subcommand available to core.staff or core.admin
            if (sender.hasPermission("core.staff") || sender.hasPermission("core.admin")) {
                if ("modalerts".startsWith(currentArgToFilter)) {
                    suggestions.add("modalerts");
                }
            }
            return suggestions;
        }
    
        String subCommand = args[0].toLowerCase();
    
        // Level 2: Arguments for subcommands (e.g., /core subcommand <arg1>)
        if (args.length == 2) {
            currentArgToFilter = args[1].toLowerCase();
    
            switch (subCommand) {
                case "reload":
                    if (sender.hasPermission("core.admin")) {
                        if ("hide".startsWith(currentArgToFilter)) {
                            suggestions.add("hide");
                        }
                    }
                    break;
                case "hideupdate":
                    if (sender.hasPermission("core.admin")) {
                        if ("all".startsWith(currentArgToFilter)) {
                            suggestions.add("all");
                        }
                        // Assuming getOnlinePlayerNames(sender) is available in your Tab class
                        for (String playerName : getOnlinePlayerNames(sender)) {
                            if (playerName.toLowerCase().startsWith(currentArgToFilter)) {
                                suggestions.add(playerName);
                            }
                        }
                    }
                    break;
                case "modalerts":
                    // For /core modalerts <arg1>
                    // arg1 can be on/off (for self if staff/admin) or a player name (if admin)
                    if (sender.hasPermission("core.staff") || sender.hasPermission("core.admin")) {
                        if ("on".startsWith(currentArgToFilter)) {
                            suggestions.add("on");
                        }
                        if ("off".startsWith(currentArgToFilter)) {
                            suggestions.add("off");
                        }
                    }
                    if (sender.hasPermission("core.admin")) {
                        // Suggest player names for admins to target others
                        // Assuming getOnlinePlayerNames(sender) is available
                        for (String playerName : getOnlinePlayerNames(sender)) {
                            if (playerName.toLowerCase().startsWith(currentArgToFilter)) {
                                suggestions.add(playerName);
                            }
                        }
                    }
                    break;
            }
            return suggestions;
        }
    
        // Level 3: Second argument for subcommands (e.g., /core subcommand arg1 <arg2>)
        if (args.length == 3) {
            currentArgToFilter = args[2].toLowerCase();
    
            if (subCommand.equals("modalerts")) {
                // For /core modalerts <player> <on|off>
                // This case is primarily for admins who have specified a player in args[1]
                if (sender.hasPermission("core.admin")) {
                    // A simple heuristic: if args[1] is not "on" or "off", it might be a player name.
                    // For robust behavior, the actual command execution would validate if args[1] is a real player.
                    boolean firstArgIsLikelyPlayer = !args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off");
                    if (firstArgIsLikelyPlayer) {
                        if ("on".startsWith(currentArgToFilter)) {
                            suggestions.add("on");
                        }
                        if ("off".startsWith(currentArgToFilter)) {
                            suggestions.add("off");
                        }
                    }
                }
            }
            return suggestions;
        }
    
        return suggestions; // Return empty list for args.length > 3 or unhandled cases
    }

    private List<String> getFlySuggestions(CommandSender sender, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Only suggest players if the sender has permission to modify *other* players' fly status.
                if (sender.hasPermission("core.fly.others")) {
                    suggestions.add(player.getName());
                } else if (sender instanceof Player && sender.getName().equalsIgnoreCase(player.getName())) {
                    // If the sender doesn't have the "others" permission, only suggest their own name.
                    suggestions.add(player.getName());
                }
            }
            suggestions.removeIf(s -> !s.toLowerCase().startsWith(args[0].toLowerCase()));
        }
        return suggestions;
    }


    private List<String> getGamemodeSuggestions(CommandSender sender, String command, String[] args) {
        // Return empty list if sender doesn't have basic gamemode permission
        if (!sender.hasPermission("core.gamemode")) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();

        // Handle specific gamemode alias commands (/creative, /survival, etc.)
        if (command.equalsIgnoreCase("creative") ||
                command.equalsIgnoreCase("survival") ||
                command.equalsIgnoreCase("adventure") ||
                command.equalsIgnoreCase("spectator")) {

            // For direct gamemode aliases, we only want to suggest player names if sender has permission
            if (args.length == 1) {
                // Check for specific permission for this gamemode
                String modePermission = "core.gamemode." + command.toLowerCase();
                if (sender.hasPermission(modePermission)) {
                    return getOnlinePlayerNames(sender);
                } else {
                    return Collections.emptyList();
                }
            }

            // No suggestions for more arguments
            return Collections.emptyList();
        }

        // Handle gamemode and gm commands
        if (command.equalsIgnoreCase("gamemode") || command.equalsIgnoreCase("gm")) {
            if (args.length == 1) {
                // Only suggest gamemodes the sender has permission for
                if (sender.hasPermission("core.gamemode.creative") || sender.hasPermission("core.gamemode.*")) {
                    suggestions.add("creative");
                    suggestions.add("1");
                }
                if (sender.hasPermission("core.gamemode.survival") || sender.hasPermission("core.gamemode.*")) {
                    suggestions.add("survival");
                    suggestions.add("0");
                }
                if (sender.hasPermission("core.gamemode.adventure") || sender.hasPermission("core.gamemode.*")) {
                    suggestions.add("adventure");
                    suggestions.add("2");
                }
                if (sender.hasPermission("core.gamemode.spectator") || sender.hasPermission("core.gamemode.*")) {
                    suggestions.add("spectator");
                    suggestions.add("3");
                }

                return filterByStart(suggestions, args[0]);
            } else if (args.length == 2) {
                // First check if they have permission for the specified gamemode
                GameMode targetMode = null;
                try {
                    targetMode = getGameModeFromArg(args[0]);
                } catch (IllegalArgumentException e) {
                    return Collections.emptyList(); // Invalid gamemode, no player suggestions
                }

                // Check permission for the specified gamemode
                String modePermission = "core.gamemode." + targetMode.name().toLowerCase();
                if (sender.hasPermission(modePermission) || sender.hasPermission("core.gamemode.*")) {
                    return getOnlinePlayerNames(sender);
                }
            }

            return Collections.emptyList();
        }

        // Handle specific gamemode shortcut commands (gmc, gms, etc.)
        if (command.equalsIgnoreCase("gmc")) {
            if (args.length == 1 && sender.hasPermission("core.gamemode.creative")) {
                return getOnlinePlayerNames(sender);
            }
        } else if (command.equalsIgnoreCase("gms")) {
            if (args.length == 1 && sender.hasPermission("core.gamemode.survival")) {
                return getOnlinePlayerNames(sender);
            }
        } else if (command.equalsIgnoreCase("gma")) {
            if (args.length == 1 && sender.hasPermission("core.gamemode.adventure")) {
                return getOnlinePlayerNames(sender);
            }
        } else if (command.equalsIgnoreCase("gmsp")) {
            if (args.length == 1 && sender.hasPermission("core.gamemode.spectator")) {
                return getOnlinePlayerNames(sender);
            }
        }

        return Collections.emptyList();
    }

    // Helper method to get GameMode from argument
    private GameMode getGameModeFromArg(String arg) {
        try {
            // Try parsing as a number first
            int modeId = Integer.parseInt(arg);
            switch (modeId) {
                case 0: return GameMode.SURVIVAL;
                case 1: return GameMode.CREATIVE;
                case 2: return GameMode.ADVENTURE;
                case 3: return GameMode.SPECTATOR;
                default: throw new IllegalArgumentException("Invalid gamemode number: " + modeId);
            }
        } catch (NumberFormatException e) {
            // Not a number, try matching by name
            if (arg.equalsIgnoreCase("s") || arg.equalsIgnoreCase("survival")) {
                return GameMode.SURVIVAL;
            } else if (arg.equalsIgnoreCase("c") || arg.equalsIgnoreCase("creative")) {
                return GameMode.CREATIVE;
            } else if (arg.equalsIgnoreCase("a") || arg.equalsIgnoreCase("adventure")) {
                return GameMode.ADVENTURE;
            } else if (arg.equalsIgnoreCase("sp") || arg.equalsIgnoreCase("spectator")) {
                return GameMode.SPECTATOR;
            } else {
                throw new IllegalArgumentException("Invalid gamemode name: " + arg);
            }
        }
    }

    // Helper method to get online player names
    private List<String> getOnlinePlayerNames(CommandSender sender) {
        List<String> playerNames = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Only include players that the sender can see
            if (sender instanceof Player && !((Player) sender).canSee(player)) {
                continue;
            }
            playerNames.add(player.getName());
        }
        return playerNames;
    }

    // Helper method to filter suggestions by prefix
    private List<String> filterByStart(List<String> list, String prefix) {
        if (prefix.isEmpty()) {
            return list;
        }

        String lowerPrefix = prefix.toLowerCase();
        return list.stream()
                .filter(str -> str.toLowerCase().startsWith(lowerPrefix))
                .collect(Collectors.toList());
    }

    private List<String> getGiveSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();
        
        if (args.length == 1) {
            // Player name suggestions
            if (sender.hasPermission("core.give.other")) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        } 
        else if (args.length == 2) {
            // Material and alias suggestions (same as i command but after player name)
            suggestions.addAll(cachedMaterialNames);
            suggestions = filterSuggestions(suggestions, args[1]);
        }
        else if (args.length == 3) {
            // Handle potion suggestions (same as i command but args offset by 1)
            String itemName = args[1];
            if (itemName.contains(";")) {
                String[] parts = itemName.split(";");
                
                if (parts.length == 1 && itemName.endsWith(";")) {
                    if (parts[0].matches("(?i)(potion|splash_potion|lingering_potion|tipped_arrow)")) {
                        return potionEffects.stream()
                            .map(effect -> itemName + effect)
                            .collect(Collectors.toList());
                    }
                }
                else if (parts.length == 2) {
                    return potionEffects.stream()
                        .filter(effect -> effect.startsWith(parts[1].toLowerCase()))
                        .map(effect -> parts[0] + ";" + effect)
                        .collect(Collectors.toList());
                }
                else if (parts.length == 2 && parts[1].equalsIgnoreCase("turtle_master")) {
                    return Arrays.asList("300", "60", "20").stream()
                        .map(duration -> itemName + ";" + duration)
                        .collect(Collectors.toList());
                }
            }
            else if (isMaterial(itemName)) {
                suggestions.add(itemName + ";");
            }
            
            suggestions = filterSuggestions(suggestions, args[2]);
        }
        else if (args.length == 4) {
            // Amount suggestions (same as i command but args offset by 1)
            try {
                String materialName = args[1].split(";")[0];
                Material material = Material.matchMaterial(materialName);
                int maxStack = material != null ? material.getMaxStackSize() : 64;
                
                List<String> amounts = new ArrayList<>();
                amounts.add("1");
                if (maxStack >= 16) amounts.add("16");
                if (maxStack >= 32) amounts.add("32");
                if (maxStack >= 64) amounts.add("64");
                
                return amounts.stream()
                    .filter(amount -> amount.startsWith(args[3]))
                    .collect(Collectors.toList());
            } catch (Exception ignored) {}
        }
        
        return suggestions;
    }

    private List<String> getLoreSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        switch (args.length) {
            case 1:
                suggestions.add("add");
                suggestions.add("remove");
                suggestions.add("insert");
                suggestions.add("clear");
                break;
            case 2:
                if (args[0].equalsIgnoreCase("add")) {
                    suggestions.add("<text>");
                } else if (args[0].equalsIgnoreCase("remove") || args[0]
                        .equalsIgnoreCase("insert")) {
                    if (!(sender instanceof Player player)) {
                        return suggestions; // Or handle the error appropriately
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();

                    List<String> lore = LoreHelper.getLore(item);
                    int loreSize = lore.size();

                    // Generate suggestions based on lore size
                    for (int i = 0; i < loreSize; i++) { // loop through each lore line
                        suggestions.add(String.valueOf(i));
                    }

                    // If there's no lore, suggest "0" as the only option for inserting at the beginning
                    if (loreSize == 0 && args[0].equalsIgnoreCase("insert")) {
                        suggestions.add("0");
                    }
                }
                break;

            case 3:
                if (args[0].equalsIgnoreCase("insert")) {
                    if (Integer.parseInt(args[1]) > 0) {
                        suggestions.add("<text>");
                    }
                }
                break;
        }
        return suggestions;
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

        int min = 1;
        int max = 10;
        int defaultValue = 2;

        switch (args.length) {
            case 1:
                // Both console and players with permission can tab complete player names
                if (sender.hasPermission("core.explode.other") || !(sender instanceof Player)) {
                    Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));
                }
                // Both console and players with permission can tab complete power values
                if (sender.hasPermission("core.explode.self") || !(sender instanceof Player)) {
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;

            case 2:
                // Both console and players with permission can tab complete power values for the second argument
                if (sender.hasPermission("core.explode.other") || !(sender instanceof Player)) {
                    suggestions.add(String.valueOf(min));
                    suggestions.add(String.valueOf(defaultValue));
                    suggestions.add(String.valueOf(max));
                }
                break;
        }

        return filterSuggestions(suggestions, args.length > 0 ? args[args.length - 1] : "");
    }

    private List<String> getISuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();
        
        if (args.length == 1) {
            // Material and alias suggestions
            suggestions.addAll(cachedMaterialNames);
            suggestions = filterSuggestions(suggestions, args[0]);
        }
        
        return suggestions;
    }

    private boolean isMaterial(String name) {
        String lowerName = name.toLowerCase();
        return cachedMaterialNames.stream()
                .anyMatch(mat -> mat.equalsIgnoreCase(lowerName)) ||
               cachedAliases.contains(lowerName);
    }
}