package net.survivalfun.core.commands.utils;

import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.LoreHelper;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class Tab implements TabCompleter {

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final List<String> cachedMaterialNames; // Cache material names for better performance

    public Tab(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        Item.initialize(); // Ensure ItemUtils is initialized
        this.cachedMaterialNames = Arrays.stream(Material.values())
                .filter(Item::isGiveable) // Filter out non-giveable materials
                .map(Enum::name)
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
                case "heal", "feed" -> getHealSuggestions(sender, args);
                case "lore" -> getLoreSuggestions(sender, args);
                case "gamemode" -> getGamemodeSuggestions(sender, "gamemode", args);
                case "gm" -> getGamemodeSuggestions(sender, "gm", args);
                case "gmc" -> getGamemodeSuggestions(sender, "gmc", args);
                case "gma" -> getGamemodeSuggestions(sender, "gma", args);
                case "gms" -> getGamemodeSuggestions(sender, "gms", args);
                case "gmsp" -> getGamemodeSuggestions(sender,"gmsp", args);
                case "survival" -> getGamemodeSuggestions(sender, "survival", args);
                case "creative" -> getGamemodeSuggestions(sender, "creative", args);
                case "adventure" -> getGamemodeSuggestions(sender, "adventure", args);
                case "spectator" -> getGamemodeSuggestions(sender, "spectator", args);
                case "fly" -> getFlySuggestions(sender, args);
                case "core" -> getCoreSuggestions(sender, args);
                default -> new ArrayList<>();
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Tab completion error: " + e.getMessage());
            return new ArrayList<>();
        }
    }


    private @Nullable List<String> getCoreSuggestions(@NotNull CommandSender sender
            , @NotNull String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();
        if (!sender.hasPermission("core.admin")) {
            return suggestions;
        }
        if (args.length == 1) {
            suggestions.add("reload");
            suggestions.add("debug");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("reload")) {
                suggestions.add("hide");
            }
        }
        return suggestions;
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

        int min = config.getInt("explode-command.min", 1);
        int max = config.getInt("explode-command.max", 10);
        int defaultValue = config.getInt("explode-command.default", 2);

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


    private List<String> getGiveSuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            // Check if the input is at least 5 characters and might be a material name
            if (args[0].length() >= 5) {
                // First suggest materials that match the input
                List<String> materialSuggestions = cachedMaterialNames.stream()
                        .map(String::toLowerCase)
                        .filter(name -> name.contains(args[0].toLowerCase()))
                        .collect(Collectors.toList());

                // Add material suggestions with a special prefix to distinguish them from player names
                // You could use something like "ITEM:" or any other prefix
                suggestions.addAll(materialSuggestions);
            }

            // Then add player name suggestions (these will appear after material suggestions)
            suggestions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList());

            return suggestions;
        } else if (args.length == 2) {
            // Check if the first argument is a player name
            Player targetPlayer = Bukkit.getPlayer(args[0]);

            // If first arg isn't a player, it might be a material name that was auto-completed
            if (targetPlayer == null && isMaterial(args[0])) {
                // In this case, the second argument would be the amount
                suggestions.addAll(Arrays.asList("1", "16", "32", "64"));
                return suggestions.stream()
                        .filter(s -> s.startsWith(args[1]))
                        .collect(Collectors.toList());
            }

            // Normal material suggestion logic for second argument
            String itemName = args[1];

            // Material name suggestions (partial completion)
            if (!itemName.contains(";")) {
                List<String> materialSuggestions = cachedMaterialNames.stream()
                        .map(String::toLowerCase)
                        .filter(name -> name.startsWith(itemName.toLowerCase()))
                        .collect(Collectors.toList());

                suggestions.addAll(materialSuggestions);

                // If there is a direct match, add the semicolon option
                if (cachedMaterialNames.stream().map(String::toLowerCase).anyMatch(name -> name.equalsIgnoreCase(itemName))) {
                    Material material = getMaterial(itemName);
                    if (material != null) {
                        suggestions.add(itemName + ";");
                    }
                }
            }
            return suggestions;
        } else if (args.length == 3) {
            // Check if the first argument is a player name
            Player targetPlayer = Bukkit.getPlayer(args[0]);

            // If first arg isn't a player, it might be a material name that was auto-completed
            if (targetPlayer == null && isMaterial(args[0])) {
                // In this case, we're already at the amount, so no suggestions needed
                return suggestions;
            }

            // Normal amount suggestion logic for third argument
            suggestions.addAll(Arrays.asList("1", "16", "32", "64"));
            return suggestions.stream()
                    .filter(s -> s.startsWith(args[2]))
                    .collect(Collectors.toList());
        }

        return suggestions;
    }

    // Helper method to check if a string is a valid material name
    private boolean isMaterial(String name) {
        return cachedMaterialNames.stream()
                .anyMatch(mat -> mat.equalsIgnoreCase(name));
    }

    private Material getMaterial(String itemName) {
        Material material;
        String baseItemName = itemName;

        if (itemName.contains(";")) {
            baseItemName = itemName.substring(0, itemName.indexOf(";"));
        }

        try {
            // 1. Try direct Material name (e.g., "DIAMOND_SWORD")
            material = Material.valueOf(baseItemName.toUpperCase());

        } catch (IllegalArgumentException e) {
            // Not a direct Material name, try other methods
            // 2. Check Legacy IDs
            material = LegacyID.getMaterialFromLegacyId(baseItemName);

            if (material == null) {
                // 3. Check Aliases
                String alias = Alias.getAlias(baseItemName);
                if (alias != null) {
                    try {
                        material = Material.valueOf(alias.toUpperCase());
                    } catch (IllegalArgumentException e2) {
                        // Alias doesn't resolve to a valid Material // Or handle the error as you see fit
                        return Material.AIR;
                    }
                } else {
                    // Not a valid material, legacy ID, or alias
                    return Material.AIR;
                }
            }
        }

        return material;
    }


    private List<String> getISuggestions(@NotNull CommandSender sender, String @NotNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (!(sender instanceof Player)) return suggestions;

        switch (args.length) {
            case 1:
                String itemName = args[0];


                // Material name suggestions (partial completion)
                if (!itemName.contains(";")) {
                    List<String> materialSuggestions = cachedMaterialNames.stream()
                            .map(String::toLowerCase)
                            .filter(name -> name.startsWith(itemName.toLowerCase()))
                            .collect(Collectors.toList());

                    suggestions.addAll(materialSuggestions);

                    //If there is a direct match, add the semicolon option
                    if (cachedMaterialNames.stream().map(String::toLowerCase).anyMatch(name -> name.equalsIgnoreCase(itemName))) {
                        Material material = getMaterial(itemName);
                        if (material != null) {
                            suggestions.add(itemName + ";");
                        }


                    }

                }

                // Enchantment suggestions
                else {
                    String[] itemNameParts = itemName.split(";", -1); // Limit to -1 to include trailing empty strings
                    String itemNameWithoutEnchants = itemNameParts[0];
                    Material material = getMaterial(itemNameWithoutEnchants);

                    // Determine what's been typed so far after the last semicolon
                    String lastPart = itemNameParts[itemNameParts.length - 1];

                    if (material != null) {
                        ItemStack testItem = new ItemStack(material);

                        // If the material is an enchanted book, ensure the ItemStack has EnchantmentStorageMeta
                        if (material == Material.ENCHANTED_BOOK) {
                            // Create an enchanted book meta
                            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) Bukkit.getItemFactory().getItemMeta(Material.ENCHANTED_BOOK);
                            testItem.setItemMeta(meta);
                        }

                        for (Enchantment enchantment : Enchantment.values()) {
                            //Check if it is an enchanted book, if so, ignore the item check, otherwise, do it
                            if(material == Material.ENCHANTED_BOOK || enchantment.canEnchantItem(testItem)) {
                                String enchantName = enchantment.getKey().getKey();

                                // Check if the enchantment name (or part of it) matches what the user has typed *after* the last semicolon
                                if (enchantName.toLowerCase().startsWith(lastPart.toLowerCase())) {
                                    // Suggest only the *remaining* part of the enchantment name
                                    String remainingEnchantName = enchantName.substring(lastPart.length());
                                    String suggestion = itemName + remainingEnchantName + ":" + enchantment.getMaxLevel();

                                    suggestions.add(suggestion);
                                }
                            }
                        }
                    }
                }




                break;

            case 2:
                suggestions.addAll(List.of("1", "16", "32", "64"));
                break;
        }

        return suggestions;
    }




    private List<String> filterSuggestions(@NotNull List<String> suggestions, @NotNull String partial) {
        if (partial.isEmpty()) return suggestions;

        String lowerPartial = partial.toLowerCase(Locale.ENGLISH);
        return suggestions.stream()
                .filter(s -> s.toLowerCase(Locale.ENGLISH).startsWith(lowerPartial))
                .collect(Collectors.toList());
    }
}