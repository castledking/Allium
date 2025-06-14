package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.core.LegacyID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Item.isGiveable;

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    private final net.survivalfun.core.managers.config.Config configManager;


    public Give(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.configManager = plugin.getConfigManager();
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command
            , @NotNull String label
            , String @NotNull [] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (!sender.hasPermission("core.give")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{command}", label.toLowerCase());
            return true;
        }
        try {
            if (label.equalsIgnoreCase("give")) {
                if (args.length == 3) {
                    Integer.parseInt(args[2]);
                }
            } else if (label.equalsIgnoreCase("i")) {
                if (args.length == 2) {
                    Integer.parseInt(args[1]);
                }
            }
        } catch (NumberFormatException e) {
            // Determine which argument caused the error based on the command label
            String invalidArg;
            if (label.equalsIgnoreCase("give")) {
                invalidArg = args[2]; // For /give command, the amount is the 3rd argument (index 2)
            } else {
                invalidArg = args[1]; // For /i command, the amount is the 2nd argument (index 1)
            }

            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", invalidArg);
            return true;
        }
        switch (label) {
            case "give": {
                switch (args.length) {
                    case 0 -> sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "[player] <item> [amount]"));
                    case 1 -> sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<player> <item> [amount]"));

                    case 2 -> giveItems(args[0], args[1], sender, 0, true
                            , getEnchantments(args[1])); // Default amount if not specified
                    case 3 -> giveItems(args[0], args[1], sender, Integer.parseInt(args[2])
                            , true
                            , getEnchantments(args[1]));
                }
                break;
            }
            case "i": {
                switch (args.length) {
                    case 0 -> sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<item> [amount]"));
                    case 1 -> giveItems(sender.getName(), args[0], sender, 0
                            , false
                            , getEnchantments(args[0]));
                    case 2 -> giveItems(sender.getName(), args[0], sender, Integer.parseInt(args[1])
                            , false
                            , getEnchantments(args[0]));
                }
                break;
            }
        }
        return true;
    }

    private Map<Enchantment, Integer> getEnchantments(String arg) {
        Map<Enchantment, Integer> enchantments = new HashMap<>();
        String[] enchantmentsArray = arg.split(";");
        for (String enchantmentString : enchantmentsArray) {
            String[] enchantmentParts = enchantmentString.split(":");
            if (enchantmentParts.length == 2) {
                Enchantment enchantment = Enchantment.getByKey(NamespacedKey
                        .minecraft(enchantmentParts[0]
                        .toLowerCase()));
                if (enchantment != null) {
                    int level = Integer.parseInt(enchantmentParts[1]);
                    enchantments.put(enchantment, level);
                }
            }
        }
        return enchantments;
    }

    public void applyEnchantments(CommandSender sender, ItemStack item, Material material, Map<Enchantment, Integer> enchantments) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        boolean allowUnsafe = configManager.getBoolean("allow-unsafe-enchants");
        plugin.getLogger().log(Level.INFO, "Enchantments: " + enchantments);
        boolean isEnchantedBook = meta instanceof EnchantmentStorageMeta;

        if (isEnchantedBook) {
            EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) meta;
            // Enchanted books ignore conflicts
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                Enchantment enchantment = entry.getKey();
                int level = entry.getValue();

                if (!allowUnsafe && level > enchantment.getMaxLevel()) {
                    Text.sendErrorMessage(sender, "give.unsafe-level", lang,
                            "{enchant}", formatName(enchantment.getKey().getKey()),
                            "{maxLevel}", String.valueOf(enchantment.getMaxLevel()));
                    level = enchantment.getMaxLevel();
                }

                try {
                    bookMeta.addStoredEnchant(enchantment, level, true);
                } catch (IllegalArgumentException e) {
                    Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                            "{enchant}", formatName(enchantment.getKey().getKey()),
                            "{item}", formatName(material.name()));
                }
            }
            item.setItemMeta(bookMeta);
            return;
        }

        if (allowUnsafe) {

            // Unsafe enchants allowed, directly apply everything
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                try {
                    meta.addEnchant(entry.getKey(), entry.getValue(), true);
                } catch (IllegalArgumentException e) {
                    Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                            "{enchant}", formatName(String.valueOf(entry.getKey().getKey())),
                            "{item}", formatName(material.name()));
                }
            }
            item.setItemMeta(meta);
            return;
        }

        // SAFE MODE: Proper conflict checking BEFORE applying enchantments
        Map<Enchantment, Integer> addedEnchantments = new LinkedHashMap<>();

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            // Check for enchant applicability
            if (!enchantment.canEnchantItem(item)) {
                Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{item}", formatName(material.name()));
                continue;
            }

            // Check enchant max level
            if (level > enchantment.getMaxLevel()) {
                Text.sendErrorMessage(sender, "give.unsafe-level", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{maxLevel}", String.valueOf(enchantment.getMaxLevel()));
                level = enchantment.getMaxLevel();
            }

            // Temporarily add enchant to a copy of meta to check conflict
            boolean conflicts = false;
            for (Enchantment existingEnch : addedEnchantments.keySet()) {
                if (existingEnch.conflictsWith(enchantment)) {
                    // Conflicting enchantment found
                    conflicts = true;

                    Text.sendErrorMessage(sender, "give.conflicting-enchants", lang,
                            "{enchant}", formatName(existingEnch.getKey().getKey()),
                            "{enchant2}", formatName(enchantment.getKey().getKey()),
                            "{item}", formatName(material.name()));
                    break; // Stop checking after found conflict
                }
            }

            // If no conflict, store temporarily
            if (!conflicts) {
                addedEnchantments.put(enchantment, level);
            }
        }

        // Apply only non-conflicting enchantments officially to the item's meta
        for (Map.Entry<Enchantment, Integer> allowedEnchEntry : addedEnchantments.entrySet()) {
            meta.addEnchant(allowedEnchEntry.getKey(), allowedEnchEntry.getValue(), true);
        }

        item.setItemMeta(meta);
    }

    private void giveItems(String player, String arg, CommandSender sender, int amount, boolean canConsole, Map<Enchantment, Integer> enchantments) {
        if (!canConsole && !(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return;
        }

        try {
            // Extract base item name
            String[] parts = arg.split(";");
            String itemName = parts[0];
            
            // Check for special potion types (potion:1, potion:2, potion:3)
            int specialPotionType = 0;
            if (itemName.toLowerCase().startsWith("potion:")) {
                try {
                    int potionId = Integer.parseInt(itemName.substring(7));
                    if (potionId >= 1 && potionId <= 3) {
                        // Valid special potion type
                        specialPotionType = potionId;
                        itemName = "potion";
                    } else {
                        // Invalid potion ID
                        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                        return;
                    }
                } catch (NumberFormatException e) {
                    // Not a valid number format
                    Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                    return;
                }
            }

            Material material = getMaterial(itemName, sender);
            if (material == null) {
                return;
            }
            
            // Show usage guidance for potions and tipped arrows if no parameters are provided
            if (parts.length == 1) {
                if (material == Material.POTION || material == Material.SPLASH_POTION || material == Material.LINGERING_POTION) {
                    String potionGuide = "potion;effect:<effect>;duration:<seconds>;amplifier:<level>;ambient:<true/false>;particles:<true/false>;icon:<true/false>;type:<potion_type>";
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", "give")
                            .replace("{args}", potionGuide));
                } else if (material == Material.TIPPED_ARROW) {
                    String arrowGuide = "tipped_arrow;effect:<effect>;duration:<seconds>;amplifier:<level>;ambient:<true/false>;particles:<true/false>;icon:<true/false>";
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", "give")
                            .replace("{args}", arrowGuide));
                }
            }

            // Check if this is a potion or tipped arrow item
            boolean isPotion = material == Material.POTION || material == Material.SPLASH_POTION ||
                    material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW;

            Player target = Bukkit.getPlayer(player);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", player);
                return;
            } else if (amount == 0) {
                amount = material.getMaxStackSize();
            }

            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();

            String name = null;
            String potionEffect = null;
            int potionDuration = 200; // Default 10 seconds (200 ticks)
            int potionAmplifier = 0; // Default level 1
            boolean potionAmbient = false;
            boolean potionParticles = true;
            boolean potionIcon = true;
            PotionType potionType = null;

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (part.startsWith("name=") || part.startsWith("name:")) {
                    // Extract the name part, keeping both spaces and underscores
                    name = part.substring(5);
                    
                    // Only replace underscores with spaces if they're not part of color codes
                    // This allows both actual spaces and underscores via _ in the command
                    StringBuilder processedName = new StringBuilder();
                    boolean inColorCode = false;
                    
                    for (int j = 0; j < name.length(); j++) {
                        char c = name.charAt(j);
                        
                        if (c == '&' || c == '§') {
                            inColorCode = true;
                            processedName.append(c);
                        } else if (inColorCode) {
                            inColorCode = false;
                            processedName.append(c);
                        } else if (c == '_') {
                            processedName.append(' '); // Replace underscore with space
                        } else {
                            processedName.append(c);
                        }
                    }
                    
                    name = Text.parseColors(processedName.toString());
                } else if (part.startsWith("effect=") || part.startsWith("effect:")) {
                    potionEffect = part.substring(7).toUpperCase();
                } else if (part.startsWith("duration=") || part.startsWith("duration:")) {
                    try {
                        potionDuration = Integer.parseInt(part.substring(9)) * 20; // Convert seconds to ticks
                    } catch (NumberFormatException e) {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "duration");
                    }
                } else if (part.startsWith("amplifier=") || part.startsWith("amplifier:")) {
                    try {
                        potionAmplifier = Integer.parseInt(part.substring(10));
                    } catch (NumberFormatException e) {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "amplifier");
                    }
                } else if (part.startsWith("ambient=") || part.startsWith("ambient:")) {
                    potionAmbient = Boolean.parseBoolean(part.substring(8));
                } else if (part.startsWith("particles=") || part.startsWith("particles:")) {
                    potionParticles = Boolean.parseBoolean(part.substring(10));
                } else if (part.startsWith("icon=") || part.startsWith("icon:")) {
                    potionIcon = Boolean.parseBoolean(part.substring(5));
                } else if (part.startsWith("type=") || part.startsWith("type:")) {
                    try {
                        potionType = PotionType.valueOf(part.substring(5).toUpperCase());
                    } catch (IllegalArgumentException e) {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion type");
                    }
                }
            }

            if (meta != null) {
                if (name != null) {
                    Plugin plugin = Bukkit.getPluginManager().getPlugin("SFCore");
                    if (plugin == null) {
                        sender.sendMessage("Plugin not found.");
                        return;
                    }
                    NamespacedKey key = new NamespacedKey(plugin, "display_name");
                    PersistentDataContainer dataContainer = meta.getPersistentDataContainer();
                    dataContainer.set(key, PersistentDataType.STRING, name);
                    meta.setDisplayName(name);
                }
                
                // Apply potion effects if this is a potion or tipped arrow
                if (isPotion && meta instanceof PotionMeta) {
                    PotionMeta potionMeta = (PotionMeta) meta;
                    
                    // Handle special potion types (Mundane, Thick, Awkward)
                    if (specialPotionType > 0) {
                        switch (specialPotionType) {
                            case 1:
                                potionMeta.setBasePotionData(new PotionData(PotionType.MUNDANE));
                                break;
                            case 2:
                                potionMeta.setBasePotionData(new PotionData(PotionType.THICK));
                                break;
                            case 3:
                                potionMeta.setBasePotionData(new PotionData(PotionType.AWKWARD));
                                break;
                        }
                    }
                    // Apply base potion type if specified
                    else if (potionType != null) {
                        potionMeta.setBasePotionData(new PotionData(potionType));
                    }
                    
                    // Apply custom effect if specified
                    if (potionEffect != null) {
                        try {
                            // Get potion effect type by name
                            PotionEffectType effectType = PotionEffectType.getByName(potionEffect);
                            
                            if (effectType != null) {
                                PotionEffect effect = new PotionEffect(
                                    effectType, 
                                    potionDuration, 
                                    potionAmplifier,
                                    potionAmbient,
                                    potionParticles,
                                    potionIcon
                                );
                                potionMeta.addCustomEffect(effect, true);
                                
                                // Set color based on effect (optional)
                                if (effectType.getColor() != null) {
                                    potionMeta.setColor(effectType.getColor());
                                }
                            } else {
                                Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion effect");
                            }
                        } catch (IllegalArgumentException e) {
                            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion effect");
                        }
                    }
                }
                
                item.setItemMeta(meta);
            }
            
            // Apply enchantments if any
            if (enchantments != null && !enchantments.isEmpty()) {
                applyEnchantments(sender, item, material, enchantments);
            }
            
            // Give the item to the player
            PlayerInventory inventory = target.getInventory();
            inventory.addItem(item);
            
            // Generate a proper display name for potions and tipped arrows
            String itemDisplayName;
            if (isPotion && meta instanceof PotionMeta) {
                String potionTypeStr = "";
                String effectStr = "";
                
                // Get potion type prefix (splash, lingering, etc.)
                if (material == Material.SPLASH_POTION) {
                    potionTypeStr = "Splash ";
                } else if (material == Material.LINGERING_POTION) {
                    potionTypeStr = "Lingering ";
                } else if (material == Material.TIPPED_ARROW) {
                    potionTypeStr = "Arrow of ";
                }
                
                // Get effect name and create the display name
                if (specialPotionType > 0) {
                    // Special potion types
                    switch (specialPotionType) {
                        case 1:
                            itemDisplayName = potionTypeStr + "Mundane Potion";
                            break;
                        case 2:
                            itemDisplayName = potionTypeStr + "Thick Potion";
                            break;
                        case 3:
                            itemDisplayName = potionTypeStr + "Awkward Potion";
                            break;
                        default:
                            itemDisplayName = potionTypeStr + "Water Bottle";
                    }
                } else if (potionEffect != null) {
                    effectStr = formatName(potionEffect);
                    if (material == Material.TIPPED_ARROW) {
                        itemDisplayName = potionTypeStr + effectStr;
                    } else {
                        itemDisplayName = potionTypeStr + "Potion of " + effectStr;
                    }
                } else if (potionType != null) {
                    effectStr = formatName(potionType.name());
                    if (material == Material.TIPPED_ARROW) {
                        itemDisplayName = potionTypeStr + effectStr;
                    } else {
                        itemDisplayName = potionTypeStr + "Potion of " + effectStr;
                    }
                } else {
                    // Default water bottle
                    if (material == Material.TIPPED_ARROW) {
                        itemDisplayName = "Arrow of Water";
                    } else {
                        itemDisplayName = potionTypeStr + "Water Bottle";
                    }
                }
                
                // Set the display name on the item itself
                ItemMeta updatedMeta = item.getItemMeta();
                if (updatedMeta != null) {
                    updatedMeta.setDisplayName(ChatColor.RESET + itemDisplayName);
                    item.setItemMeta(updatedMeta);
                }
            } else {
                itemDisplayName = formatName(material.name());
            }
            
            // Send success message
            if (sender.getName().equals(target.getName())) {
                // Self-give
                sender.sendMessage(lang.get("give.success")
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{item}", itemDisplayName));
            } else {
                // Giving to another player
                sender.sendMessage(lang.get("give.success-other")
                        .replace("{name}", target.getName())
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{item}", itemDisplayName));
                
                // Send message to receiver
                target.sendMessage(lang.get("give.receive")
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{item}", itemDisplayName));
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error giving items", e);
            Text.sendErrorMessage(sender, "give.error", lang);
        }
    }




    /**
     * Converts an alias or legacy item ID to a valid Material name.
     * - If input is a number or in "number:number" format (legacy ID), it checks the legacy ID map.
     * - If input is an alias, it uses the alias map.
     * - Otherwise, it returns the input in uppercase as a fallback.
     */
    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        if (itemName.contains(";")) {
            baseItemName = itemName.substring(0, itemName.indexOf(";"));
        }

        // Check if the input contains a colon
        if (baseItemName.contains(":")) {
            String[] parts = baseItemName.split(":", 2);
            String left = parts[0];
            String right = parts[1];

            // First try to resolve the full string as an alias (e.g., "bed:1")
            String fullAlias = Alias.getAlias(baseItemName);
            if (fullAlias != null) {
                try {
                    material = Material.valueOf(fullAlias.toUpperCase());
                    if (!isGiveable(material)) {
                        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                        return null;
                    }
                    return material;
                } catch (IllegalArgumentException ignored) {}
            }

            // Try to resolve left as alias to legacy base id (e.g., stone -> 1)
            String alias = Alias.getAlias(left);
            String legacyBase = alias != null ? alias : left;
            String legacyId = legacyBase + ":" + right;
            material = LegacyID.getMaterialFromLegacyId(legacyId);
            if (material != null) {
                if (!isGiveable(material)) {
                    Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                    return null;
                }
                return material;
            }

            // If full legacy ID fails, try alias as material
            if (alias != null) {
                try {
                    material = Material.valueOf(alias.toUpperCase());
                    if (!isGiveable(material)) {
                        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                        return null;
                    }
                    return material;
                } catch (IllegalArgumentException ignored) {}
            }

            // fallback: try left as material
            try {
                material = Material.valueOf(left.toUpperCase());
                if (!isGiveable(material)) {
                    Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                    return null;
                }
                return material;
            } catch (IllegalArgumentException ignored) {}

            // If none worked, error
            Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
            return null;
        }

        // 1. Try direct Material name (e.g., "DIAMOND_SWORD")
        try {
            material = Material.valueOf(baseItemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Not a direct Material name, try other methods
        }

        // 2. Check Legacy IDs
        if (material == null) {
            material = LegacyID.getMaterialFromLegacyId(baseItemName);
        }

        // 3. Check Aliases
        if (material == null) {
            String alias = Alias.getAlias(baseItemName);
            if (alias != null) {
                try {
                    material = Material.valueOf(alias.toUpperCase());
                } catch (IllegalArgumentException e2) {
                    // Alias doesn't resolve to a valid Material
                    Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                    return null; // Indicate failure
                }
            }
        }

        if (material == null) {
            // Not a valid material, legacy ID, or alias
            Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
            return null; // Indicate failure
        }

        if (!isGiveable(material)) {
            Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
            return null; // Indicate failure
        }

        return material;
    }
    
    /**
     * Formats a material or enchantment name to be more readable
     * @param name The name to format
     * @return The formatted name
     */
    private String formatName(String name) {
        String lowerCaseName = name.toLowerCase().replace("_", " ");
        String[] words = lowerCaseName.split(" ");
        StringBuilder formattedName = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.equalsIgnoreCase("of")) {
                formattedName.append("of"); // Ensure "of" is always lowercase
            } else {
                if (!word.isEmpty()) { // Prevent issues with empty words
                    formattedName.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
                }
            }
            if (i < words.length - 1) {
                formattedName.append(" "); // Add space between words
            }
        }

        return formattedName.toString();
    }
    
    /**
     * Gets a list of all available potion effect types
     * @return A formatted string of all potion effect types
     */
    private String getAvailablePotionEffects() {
        StringBuilder sb = new StringBuilder();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null) {
                sb.append(type.getName()).append(", ");
            }
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2); // Remove the last comma and space
        }
        return sb.toString();
    }
    
    /**
     * Gets a list of all available potion types
     * @return A formatted string of all potion types
     */
    private String getAvailablePotionTypes() {
        StringBuilder sb = new StringBuilder();
        for (PotionType type : PotionType.values()) {
            sb.append(type.name()).append(", ");
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2); // Remove the last comma and space
        }
        return sb.toString();
    }
}