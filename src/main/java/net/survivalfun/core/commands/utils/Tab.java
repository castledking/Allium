package net.survivalfun.core.commands.utils;

import net.survivalfun.core.utils.AliasUtils;
import net.survivalfun.core.utils.ItemUtils;
import net.survivalfun.core.utils.LegacyIDUtils;
import net.survivalfun.core.utils.LoreHelper;
import org.bukkit.Bukkit;
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
        ItemUtils.initialize(); // Ensure ItemUtils is initialized
        this.cachedMaterialNames = Arrays.stream(Material.values())
                .filter(ItemUtils::isGiveable) // Filter out non-giveable materials
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
                case "heal" -> getHealSuggestions(sender, args);
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
                default -> new ArrayList<>();
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Tab completion error: " + e.getMessage());
            return new ArrayList<>();
        }
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


    private List<String> getGamemodeSuggestions(CommandSender sender, String commandName, String[] args) {
        if (args.length == 1) {
            if (commandName.equalsIgnoreCase("gamemode") || commandName.equalsIgnoreCase("gm")) {
                List<String> suggestions = new ArrayList<>(Arrays.asList("survival", "creative", "adventure", "spectator"));
                suggestions.removeIf(s -> !s.toLowerCase().startsWith(args[0].toLowerCase()));
                return suggestions;
            } else {
                return Collections.emptyList();
            }
        } else if (args.length == 2) {
            if (commandName.equalsIgnoreCase("gamemode") || commandName.equalsIgnoreCase("gm")) {
                List<String> playerNames = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    playerNames.add(player.getName());
                }
                playerNames.removeIf(s -> !s.toLowerCase().startsWith(args[1].toLowerCase()));
                return playerNames;
            } else {
                return Collections.emptyList();
            }

        }
        return Collections.emptyList();
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
        List<String> allSuggestions = new ArrayList<>();

        if (args.length == 1) {
            // Suggest online player names
            allSuggestions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList());
            return filterSuggestions(allSuggestions, args[0]);
        } else if (args.length == 2) {
            // Suggest item names
            List<String> itemSuggestions = Arrays.stream(Material.values())
                    .map(Material::name)
                    .toList();
            allSuggestions.addAll(itemSuggestions);

            // Check for possible enchants if an item name is partially typed
            String partialItemName = args[1];
            if (!partialItemName.isEmpty()) {
                Material material = null;
                try {
                    material = Material.valueOf(partialItemName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Ignore, as the user might be typing
                }

                if (material != null) {
                    ItemStack tempItem = new ItemStack(material);
                    for (Enchantment enchantment : Enchantment.values()) {
                        if (enchantment.canEnchantItem(tempItem)) {
                            allSuggestions.add(material.name().toLowerCase() + ";" + enchantment.getKey()
                                    .getKey() + ":" + enchantment.getMaxLevel());
                        }
                    }
                    // Also suggest the item name without enchants
                    allSuggestions.add(material.name().toLowerCase() + ";");
                } else {
                    // If material is null, still suggest potential enchantments assuming a valid item will be typed
                    for (Enchantment enchantment : Enchantment.values()) {
                        allSuggestions.add("item_name;" + enchantment.getKey().getKey() + ":" + enchantment
                                .getMaxLevel());
                    }
                    allSuggestions.add("item_name;"); // Suggest with a semicolon to indicate enchants can follow
                }
            } else {
                // Show all possible materials if no partial item name is typed
                allSuggestions.addAll(itemSuggestions);
            }
            return filterSuggestions(allSuggestions, args[1]);

        } else if (args.length == 3) {
            // Suggest common amounts
            allSuggestions.addAll(Arrays.asList("1", "16", "32", "64"));
            return filterSuggestions(allSuggestions, args[2]);
        }

        return allSuggestions;
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
            material = LegacyIDUtils.getMaterialFromLegacyId(baseItemName);

            if (material == null) {
                // 3. Check Aliases
                String alias = AliasUtils.getAlias(baseItemName);
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