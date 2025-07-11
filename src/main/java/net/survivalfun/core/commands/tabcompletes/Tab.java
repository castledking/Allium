package net.survivalfun.core.commands.tabcompletes;

import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Lore;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
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
        // Use the centralized list of giveable items
        cachedMaterialNames = Item.getGiveableItems().stream()
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
            String[] parts = args[1].split(";", -1);
            String firstPart = parts.length > 0 ? parts[0] : "";
            
            // Handle potion types with semicolon
            if (parts.length > 1 && parts[1].isEmpty() && 
                Arrays.asList("potion", "splash_potion", "lingering_potion", "tipped_arrow")
                    .contains(firstPart.toLowerCase())) {
                List<String> fullSuggestions = new ArrayList<>();
                for (String effect : getEffectSuggestions()) {
                    fullSuggestions.add(firstPart + ";" + effect);
                }
                return filterSuggestions(fullSuggestions, args[1]);
            }
            
            // Handle enchantment suggestions for materials, aliases, and legacy IDs with semicolon
            if (parts.length > 1) {
                // Check if we're at the level of adding enchantment levels (e.g., "diamond_sword;sharpness:")
                if (parts.length > 2 || (parts[1].contains(":") && !parts[1].endsWith(":"))) {
                    // Already have an enchantment with level, no more suggestions
                    return Collections.emptyList();
                } else if (parts[1].contains(":")) {
                    // We have an enchantment with a colon, suggest levels
                    String[] enchantParts = parts[1].split(":");
                    if (enchantParts.length == 2 && enchantParts[1].isEmpty()) {
                        List<String> levelSuggestions = getEnchantmentLevelSuggestions(enchantParts[0]);
                        List<String> fullSuggestions = new ArrayList<>();
                        for (String level : levelSuggestions) {
                            fullSuggestions.add(firstPart + ";" + enchantParts[0] + ":" + level);
                        }
                        return filterSuggestions(fullSuggestions, args[1]);
                    }
                    return Collections.emptyList();
                } else if (parts[1].isEmpty()) {
                    // We have a semicolon but no enchantment yet, suggest enchantments
                    String materialName = null;
                    
                    // First try to resolve as an alias
                    String aliasResolution = Alias.getMaterialFromAlias(firstPart.toLowerCase());
                    if (aliasResolution != null) {
                        materialName = aliasResolution;
                    }
                    
                    // If not an alias, try to resolve as a legacy ID
                    if (materialName == null) {
                        Material legacyMaterial = LegacyID.getMaterialFromLegacyId(firstPart);
                        if (legacyMaterial != null) {
                            materialName = legacyMaterial.name();
                        }
                    }
                    
                    // If still not found, check if it's a direct material name
                    if (materialName == null && isMaterial(firstPart)) {
                        materialName = firstPart.toUpperCase();
                    }
                    
                    // If we found a valid material, get enchantment suggestions
                    if (materialName != null) {
                        List<String> enchantmentSuggestions = getEnchantmentSuggestions(materialName);
                        List<String> fullSuggestions = new ArrayList<>();
                        for (String enchantment : enchantmentSuggestions) {
                            fullSuggestions.add(firstPart + ";" + enchantment);
                            // Also add the enchantment with a colon for level specification
                            fullSuggestions.add(firstPart + ";" + enchantment + ":");
                        }
                        return filterSuggestions(fullSuggestions, args[1]);
                    }
            }
            
            // Default material suggestions
            suggestions.addAll(cachedMaterialNames);
            return filterSuggestions(suggestions, firstPart);
        }
        else if (args.length == 3) {
            // Amount suggestions
            return Arrays.asList("1", "16", "32", "64");
        }
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

                    List<String> lore = Lore.getLore(item);
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
        String[] subArgs = args[0].split(";", -1);
        String firstPart = subArgs.length > 0 ? subArgs[0] : "";

        if (args.length == 1) {
            // Handle potion types with semicolon
            if (subArgs.length > 1 && subArgs[1].isEmpty() && 
                Arrays.asList("potion", "splash_potion", "lingering_potion", "tipped_arrow")
                    .contains(firstPart.toLowerCase())) {
                List<String> fullSuggestions = new ArrayList<>();
                for (String effect : getEffectSuggestions()) {
                    fullSuggestions.add(firstPart + ";" + effect);
                }
                return filterSuggestions(fullSuggestions, args[0]);
            }

            // Handle enchantment suggestions for materials, aliases, and legacy IDs with semicolon
            if (subArgs.length > 1) {
                // Check if we're at the level of adding enchantment levels (e.g., "diamond_sword;sharpness:")
                if (subArgs.length > 2 || (subArgs[1].contains(":") && !subArgs[1].endsWith(":"))) {
                    // Already have an enchantment with level, no more suggestions
                    return Collections.emptyList();
                } else if (subArgs[1].contains(":")) {
                    // We have an enchantment with a colon, suggest levels
                    String[] enchantParts = subArgs[1].split(":");
                    if (enchantParts.length == 2 && enchantParts[1].isEmpty()) {
                        List<String> levelSuggestions = getEnchantmentLevelSuggestions(enchantParts[0]);
                        List<String> fullSuggestions = new ArrayList<>();
                        for (String level : levelSuggestions) {
                            fullSuggestions.add(firstPart + ";" + enchantParts[0] + ":" + level);
                        }
                        return filterSuggestions(fullSuggestions, args[0]);
                    }
                    return Collections.emptyList();
                } else if (subArgs[1].isEmpty()) {
                    // We have a semicolon but no enchantment yet, suggest enchantments
                    String materialName = null;
                    
                    // First try to resolve as an alias
                    String aliasResolution = Alias.getMaterialFromAlias(firstPart.toLowerCase());
                    if (aliasResolution != null) {
                        materialName = aliasResolution;
                    }
                    
                    // If not an alias, try to resolve as a legacy ID
                    if (materialName == null) {
                        Material legacyMaterial = LegacyID.getMaterialFromLegacyId(firstPart);
                        if (legacyMaterial != null) {
                            materialName = legacyMaterial.name();
                        }
                    }
                    
                    // If still not found, check if it's a direct material name
                    if (materialName == null && isMaterial(firstPart)) {
                        materialName = firstPart.toUpperCase();
                    }
                    
                    // If we found a valid material, get enchantment suggestions
                    if (materialName != null) {
                        List<String> enchantmentSuggestions = getEnchantmentSuggestions(materialName);
                        List<String> fullSuggestions = new ArrayList<>();
                        for (String enchantment : enchantmentSuggestions) {
                            fullSuggestions.add(firstPart + ";" + enchantment);
                            // Also add the enchantment with a colon for level specification
                            fullSuggestions.add(firstPart + ";" + enchantment + ":");
                        }
                        return filterSuggestions(fullSuggestions, args[0]);
                    }
                }
            }
            // Material suggestions
            suggestions.addAll(cachedMaterialNames);
            return filterSuggestions(suggestions, firstPart);
        } else if (args.length == 2) {
            // Duration suggestions
            suggestions.addAll(getDurationSuggestions());
            return filterSuggestions(suggestions, args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> getEnchantmentLevelSuggestions(String enchantmentName) {
        List<String> levels = new ArrayList<>();
        try {
            // Try to get the enchantment by name
            NamespacedKey key = NamespacedKey.minecraft(enchantmentName.toLowerCase());
            Enchantment enchantment = Bukkit.getRegistry(Enchantment.class).get(key);
            // Fallback to legacy method if needed
            if (enchantment == null) {
                enchantment = Enchantment.getByKey(key);
            }
            
            if (enchantment != null) {
                int maxLevel = enchantment.getMaxLevel();
                // Add levels from 1 to maxLevel, and also include the maxLevel + 1 for convenience
                for (int i = 1; i <= maxLevel + 1; i++) {
                    levels.add(String.valueOf(i));
                }
            }
        } catch (Exception e) {
            // If there's any error, just return empty list
        }
        return levels;
    }

    private List<String> getEffectSuggestions() {
        return Arrays.asList(
            "water","mundane", "awkward", "thick", "night_vision", "invisibility", "jump_boost", "fire_resistance", "speed", "slowness", "turtle_master", "water_breathing", "instant_health", "instant_damage", "poison", "regeneration", "strength", "weakness", "luck", "slow_falling", "wind_charged", "weaving", "oozing", "infested", "hero_of_the_village", "glowing", "bad_omen", "blindness", "conduit_power", "dolphins_grace", "haste", "health_boost", "hunger", "levitation", "mining_fatigue", "nausea", "resistance", "saturation", "unluck", "wither"
        );
    }

    private List<String> getDurationSuggestions() {
        return Arrays.asList("30", "60", "180", "300", "600", "1200", "2400");
    }

    private List<String> getEnchantmentSuggestions(String materialName) {
        // First try to resolve alias to material
        String resolvedMaterial = Alias.getMaterialFromAlias(materialName.toLowerCase());
        if (resolvedMaterial == null) {
            resolvedMaterial = materialName.toUpperCase();
        } else {
            resolvedMaterial = resolvedMaterial.toUpperCase();
        }

        // Try to get the Material enum
        Material material;
        try {
            material = Material.valueOf(resolvedMaterial);
        } catch (IllegalArgumentException e) {
            // If material is not found, return empty list
            return Collections.emptyList();
        }

        // Get applicable enchantments for this material
        List<String> enchantments = new ArrayList<>();
        try {
            for (Enchantment enchantment : Bukkit.getServer().getRegistry(Enchantment.class)) {
                if (enchantment.canEnchantItem(new ItemStack(material))) {
                    enchantments.add(enchantment.getKey().getKey().toLowerCase());
                }
            }
        } catch (Exception e) {
            // Fallback to hardcoded enchantments if registry access fails
            enchantments.addAll(getHardcodedEnchantmentSuggestions(material));
        }

        return enchantments;
    }

    private List<String> getHardcodedEnchantmentSuggestions(Material material) {
        List<String> enchantments = new ArrayList<>();
        
        // Common enchantments for tools and weapons
        if (material.name().contains("SWORD")) {
            enchantments.addAll(Arrays.asList("sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping", "unbreaking", "mending"));
        } else if (material.name().contains("BOW")) {
            enchantments.addAll(Arrays.asList("power", "punch", "flame", "infinity", "unbreaking", "mending"));
        } else if (material.name().contains("CROSSBOW")) {
            enchantments.addAll(Arrays.asList("multishot", "piercing", "quick_charge", "unbreaking", "mending"));
        } else if (material.name().contains("PICKAXE")) {
            enchantments.addAll(Arrays.asList("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        } else if (material.name().contains("AXE")) {
            enchantments.addAll(Arrays.asList("efficiency", "fortune", "silk_touch", "unbreaking", "mending", "sharpness", "smite", "bane_of_arthropods"));
        } else if (material.name().contains("SHOVEL")) {
            enchantments.addAll(Arrays.asList("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        } else if (material.name().contains("HOE")) {
            enchantments.addAll(Arrays.asList("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        } else if (material.name().contains("HELMET")) {
            enchantments.addAll(Arrays.asList("protection", "fire_protection", "blast_protection", "projectile_protection", "respiration", "aqua_affinity", "thorns", "unbreaking", "mending"));
        } else if (material.name().contains("CHESTPLATE")) {
            enchantments.addAll(Arrays.asList("protection", "fire_protection", "blast_protection", "projectile_protection", "thorns", "unbreaking", "mending"));
        } else if (material.name().contains("LEGGINGS")) {
            enchantments.addAll(Arrays.asList("protection", "fire_protection", "blast_protection", "projectile_protection", "swift_sneak", "thorns", "unbreaking", "mending"));
        } else if (material.name().contains("BOOTS")) {
            enchantments.addAll(Arrays.asList("protection", "fire_protection", "blast_protection", "projectile_protection", "feather_falling", "depth_strider", "frost_walker", "soul_speed", "thorns", "unbreaking", "mending"));
        } else if (material == Material.TRIDENT) {
            enchantments.addAll(Arrays.asList("loyalty", "channeling", "riptide", "impaling", "unbreaking", "mending"));
        } else if (material == Material.FISHING_ROD) {
            enchantments.addAll(Arrays.asList("luck_of_the_sea", "lure", "unbreaking", "mending"));
        } else if (material == Material.SHIELD) {
            enchantments.addAll(Arrays.asList("unbreaking", "mending"));
        } else if (material == Material.ELYTRA) {
            enchantments.addAll(Arrays.asList("unbreaking", "mending"));
        }
        
        return enchantments;
    }

    private boolean isMaterial(String name) {
        String lowerName = name.toLowerCase();
        return cachedMaterialNames.stream()
                .anyMatch(mat -> mat.equalsIgnoreCase(lowerName)) ||
               cachedAliases.contains(lowerName);
    }
}