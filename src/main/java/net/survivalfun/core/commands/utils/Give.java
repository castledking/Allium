package net.survivalfun.core.commands.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Text;

import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Item.isGiveable;

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    private final Config configManager;
    
    // Default durations in seconds for potion effects when not specified
    private static final Map<PotionEffectType, Integer> DEFAULT_POTION_DURATIONS = new HashMap<>();
    
    static {
        // Set default durations for common potion effects (in seconds)
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SPEED, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SLOWNESS, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HASTE, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.MINING_FATIGUE, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.STRENGTH, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HEALTH_BOOST, 30);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.INSTANT_DAMAGE, -1);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.INSTANT_HEALTH, -1);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.JUMP_BOOST, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.NAUSEA, 15);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.REGENERATION, 45);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.RESISTANCE, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.FIRE_RESISTANCE, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.WATER_BREATHING, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.INVISIBILITY, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.BLINDNESS, 15);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.NIGHT_VISION, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HUNGER, 30);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.WEAKNESS, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.POISON, 45);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.WITHER, 7);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.ABSORPTION, 120);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SATURATION, 45);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.GLOWING, 30);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.LEVITATION, 10);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.LUCK, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.UNLUCK, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SLOW_FALLING, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.CONDUIT_POWER, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.DOLPHINS_GRACE, 30);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.BAD_OMEN, 60);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HERO_OF_THE_VILLAGE, 60);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.DARKNESS, 10);
        // Turtle Master effects
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SLOWNESS, 20);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.RESISTANCE, 20);
    }

    public Give(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
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
                Enchantment enchantment = getEnchantment(enchantmentParts[0].toLowerCase());
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

    @SuppressWarnings("deprecation")
    private static Enchantment getEnchantment(String name) {
        return Enchantment.getByKey(NamespacedKey.minecraft(name.toLowerCase().replace(" ", "_")));
    }

    private int calculatePotionDurationTicks(int potionDuration, Material material, PotionEffectType effectType) {
        int ticks;
        if (potionDuration > 0) {
            ticks = potionDuration * 20;
            if (material == Material.TIPPED_ARROW) {
                ticks = (int) Math.round(ticks * 8.0);
            }
        } else {
            // Default duration (apply 8x division for arrows)
            ticks = DEFAULT_POTION_DURATIONS.getOrDefault(effectType, 30) * 20;
            if (material == Material.TIPPED_ARROW) {
                ticks = (int) Math.round(ticks / 8.0);
            }
        }
        return ticks;
    }
    
    private PotionEffect createPotionEffect(PotionEffectType type, int ticks, int amplifier, 
                                          boolean ambient, boolean particles, boolean icon) {
        return new PotionEffect(type, ticks, amplifier, ambient, particles, icon);
    }
    
    private Component generatePotionDisplayName(PotionEffectType effectType, Material material, int amplifier) {
        String displayEffectName = getEffectDisplayName(effectType);
        String itemType = getPotionItemType(material);
        String amplifierStr = toRomanNumeral(amplifier);
        
        String displayName = itemType + displayEffectName;
        if (amplifier > 0) {
            displayName += " " + amplifierStr;
        }
        return createAdventureComponent(displayName);
    }
    
    private String getEffectDisplayName(PotionEffectType effectType) {
        String effectName = effectType.getKey().getKey(); // Use getKey() instead of deprecated getName()
        switch (effectName) {
            case "speed": return "Swiftness";
            case "slowness": return "Turtle Master";
            case "resistance": return "Turtle Master";
            case "jump_boost": return "Leaping";
            case "instant_health": return "Healing";
            case "instant_damage": return "Harming";
            case "weaving": return "Weaving";
            case "wind_charged": return "Wind Charging";
            case "infested": return "Infestation";
            default: 
                return Arrays.stream(effectName.split("_"))
                    .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                    .collect(Collectors.joining(" "));
        }
    }
    
    private String getPotionItemType(Material material) {
        switch (material) {
            case SPLASH_POTION: return "Splash Potion of ";
            case LINGERING_POTION: return "Lingering Potion of ";
            case TIPPED_ARROW: return "Arrow of ";
            default: return "Potion of ";
        }
    }
    
    private Component createAdventureComponent(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String displayName = text.replace('_', ' ');
        System.out.println("Creating component from: " + displayName);
        System.out.println("Detected format: " + Text.detectColorFormat(displayName));
        Component component = Text.colorize(displayName);
        if (component.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            component = component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        return component;
    }

    private void giveItems(String player, String arg, CommandSender sender, int amount, boolean canConsole, Map<Enchantment, Integer> enchantments) {
        if (!canConsole && !(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return;
        }

        Player target = Bukkit.getPlayer(player);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", player);
            return;
        }

        Material material = null;
        ItemMeta meta = null;
        ItemStack item = null;

        try {
            // First, check if the argument contains a gradient tag
            boolean hasGradient = arg.contains("<gradient");
            
            // Split the arguments, but be careful with gradient tags
            String[] parts;
            if (hasGradient) {
                // If there's a gradient tag, we need to be more careful with splitting
                // to avoid breaking the gradient syntax
                parts = arg.split(";(?![^<]*>)");
            } else {
                // No gradient, safe to split normally
                parts = arg.split(";");
            }
            
            String itemName = parts[0];

            // Handle Turtle Master specifically
            if (parts.length > 1 && (parts[1].equalsIgnoreCase("turtle_master") || parts[1].startsWith("turtle_master"))) {
                // Determine material based on first part
                material = Material.POTION;
                if (parts[0].equalsIgnoreCase("splash_potion")) {
                    material = Material.SPLASH_POTION;
                } else if (parts[0].equalsIgnoreCase("lingering_potion")) {
                    material = Material.LINGERING_POTION;
                } else if (parts[0].equalsIgnoreCase("tipped_arrow")) {
                    material = Material.TIPPED_ARROW;
                }
                
                item = new ItemStack(material, amount > 0 ? amount : 1);
                PotionMeta potionMeta = (PotionMeta) item.getItemMeta();
                
                if (potionMeta != null) {
                    // Default values
                    int duration = 20;
                    String customName = null;
                    
                    // Parse named parameters if present
                    if (parts[1].contains(":") || parts[1].contains("=")) {
                        // Handle named parameters format
                        Map<String, String> namedParams = new HashMap<>();
                        for (int i = 1; i < parts.length; i++) {
                            String part = parts[i];
                            // Check if this part might contain a gradient tag that was split
                            if (part.contains("<gradient") && !part.contains(">")) {
                                // Reconstruct the gradient tag by combining with next parts
                                StringBuilder gradientValue = new StringBuilder(part);
                                while (i + 1 < parts.length && !gradientValue.toString().contains(">")) {
                                    gradientValue.append(";").append(parts[++i]);
                                }
                                part = gradientValue.toString();
                            }
                            
                            // Split on first occurrence of : or =
                            int splitIndex = Math.min(
                                part.indexOf(':') > 0 ? part.indexOf(':') : Integer.MAX_VALUE,
                                part.indexOf('=') > 0 ? part.indexOf('=') : Integer.MAX_VALUE
                            );
                            
                            if (splitIndex != Integer.MAX_VALUE) {
                                String key = part.substring(0, splitIndex).toLowerCase();
                                String value = part.substring(splitIndex + 1);
                                namedParams.put(key, value);
                            }
                        }
                        
                        // Extract values from named parameters
                        if (namedParams.containsKey("duration")) {
                            try {
                                duration = Integer.parseInt(namedParams.get("duration"));
                            } catch (NumberFormatException e) {
                                // Use default duration if parsing fails
                            }
                        }
                        
                        if (namedParams.containsKey("name")) {
                            String nameValue = namedParams.get("name");
                            // For gradient tags, we need to ensure they're properly formatted
                            if (nameValue.contains("<gradient")) {
                                // If the gradient tag is incomplete, try to reconstruct it
                                if (!nameValue.contains(">")) {
                                    // Look for the closing tag in subsequent parts
                                    for (int k = 1; k < parts.length; k++) {
                                        nameValue += ";" + parts[k];
                                        if (parts[k].contains(">")) {
                                            // We've found the end of the gradient tag
                                            break;
                                        }
                                    }
                                }
                                // Ensure proper MiniMessage format
                                nameValue = nameValue.replace('_', ' ');
                                customName = Text.parseColors(nameValue);
                            } else {
                                // No gradient, just parse normally
                                customName = Text.parseColors(nameValue.replace('_', ' '));
                            }
                        }
                    } else if (parts.length > 2) {
                        // Handle legacy positional parameters
                        try {
                            duration = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException e) {
                            // It's not a number, so we leave duration as default
                        }

                        // Now, look for a name parameter in any of the parts from index 2 onwards
                        for (int i = 2; i < parts.length; i++) {
                            if (parts[i].startsWith("name:")) {
                                customName = parts[i].substring(5);
                                break;
                            }
                        }
                    }
                    
                    // Calculate duration ticks
                    int durationTicks = calculatePotionDurationTicks(duration, material, PotionEffectType.SLOWNESS);
                    
                    // Add both Turtle Master effects
                    PotionEffect slowness = createPotionEffect(
                        PotionEffectType.SLOWNESS,
                        durationTicks,
                        3,   // Slowness IV
                        false,
                        true,
                        true
                    );
                    
                    PotionEffect resistance = createPotionEffect(
                        PotionEffectType.RESISTANCE,
                        durationTicks,
                        2,   // Resistance III
                        false,
                        true,
                        true
                    );
                    
                    potionMeta.addCustomEffect(slowness, true);
                    potionMeta.addCustomEffect(resistance, true);
                    
                    // Set the display name
                    Component displayName;
                    if (customName != null) {
                        // Parse the custom name with gradient support
                        displayName = Text.colorize(customName.replace('_', ' '));
                    } else {
                        displayName = Component.text("Potion of the Turtle Master")
                            .color(TextColor.color(255, 255, 255));
                    }
                    
                    // Ensure no italics by default
                    potionMeta.displayName(displayName.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                    
                    item.setItemMeta(potionMeta);
                    
                    // Give the item to the player
                    target.getInventory().addItem(item);
                    target.updateInventory();
                    
                    return;
                }
            }

            // Check for special potion types (potion:1, potion:2, potion:3)
            if (itemName.toLowerCase().startsWith("potion:")) {
                try {
                    int potionId = Integer.parseInt(itemName.substring(7));
                    if (potionId >= 1 && potionId <= 3) {
                        // Valid special potion type
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

            material = getMaterial(itemName, sender);
            if (material == null) {
                return;
            }

            // Check if this is a potion or tipped arrow item
            boolean isPotion = material == Material.POTION || material == Material.SPLASH_POTION || 
                material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW;

            // Show usage guidance for potions and tipped arrows if no parameters are provided
            if (parts.length == 1 && isPotion) {
                String guide = material == Material.TIPPED_ARROW ? 
                    "tipped_arrow;<effect> OR tipped_arrow;effect:<effect>;duration:<seconds>;amplifier:<level>;ambient:<true/false>;particles:<true/false>;icon:<true/false>" :
                    "potion;<effect> OR potion;effect:<effect>;duration:<seconds>;amplifier:<level>;ambient:<true/false>;particles:<true/false>;icon:<true/false>;type:<potion_type>";
                
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", "give")
                        .replace("{args}", guide));
                return;
            }

            if (amount == 0) {
                amount = material.getMaxStackSize();
            }

            item = new ItemStack(material, amount);
            meta = item.getItemMeta();

            String name = null;
            String potionEffect = null;
            int potionAmplifier = 0;
            int potionDuration = 0; // seconds
            boolean potionAmbient = false;
            boolean potionParticles = true;
            boolean potionIcon = true;

            // Parse potion arguments
            if (parts.length > 1) {
                // Check if the first argument after 'potion' is in named format (contains ':' or '=')
                boolean isNamed = parts[1].contains(":") || parts[1].contains("=");
                
                if (isNamed) {
                    // Named parameters: e.g. effect:speed;duration:300;amplifier:1
                    Map<String, String> namedParams = new HashMap<>();
                    for (int i = 1; i < parts.length; i++) {
                        String part = parts[i];
                        // Check if this part contains a gradient tag
                        if (part.contains("<gradient") && !part.contains(">")) {
                            // This is a gradient tag that was split, combine with next parts until we find the closing tag
                            StringBuilder gradientValue = new StringBuilder(part);
                            while (i + 1 < parts.length && !gradientValue.toString().contains(">")) {
                                gradientValue.append(";").append(parts[++i]);
                            }
                            part = gradientValue.toString();
                        }
                        
                        // Split on first occurrence of : or =
                        int splitIndex = Math.min(
                            part.indexOf(':') > 0 ? part.indexOf(':') : Integer.MAX_VALUE,
                            part.indexOf('=') > 0 ? part.indexOf('=') : Integer.MAX_VALUE
                        );
                        
                        if (splitIndex != Integer.MAX_VALUE) {
                            String key = part.substring(0, splitIndex).toLowerCase();
                            String value = part.substring(splitIndex + 1);
                            namedParams.put(key, value);
                        }
                    }
                    
                    // Extract values
                    potionEffect = namedParams.get("effect");
                    if (namedParams.containsKey("duration")) {
                        potionDuration = Integer.parseInt(namedParams.get("duration"));
                    }
                    if (namedParams.containsKey("amplifier")) {
                        potionAmplifier = Integer.parseInt(namedParams.get("amplifier"));
                    }
                    if (namedParams.containsKey("name")) {
                        String nameValue = namedParams.get("name");
                        // For gradient tags, we need to ensure they're properly formatted
                        if (nameValue.contains("<gradient")) {
                            // If the gradient tag is incomplete, try to reconstruct it
                            if (!nameValue.contains(">")) {
                                // Look for the closing tag in subsequent parts
                                for (int k = 1; k < parts.length; k++) {
                                    nameValue += ";" + parts[k];
                                    if (parts[k].contains(">")) {
                                        // We've found the end of the gradient tag
                                        break;
                                    }
                                }
                            }
                            // Ensure proper MiniMessage format
                            nameValue = nameValue.replace('_', ' ');
                            name = Text.parseColors(nameValue);
                        } else {
                            // No gradient, just parse normally
                            name = Text.parseColors(nameValue.replace('_', ' '));
                        }
                    }
                } else {
                    // Positional parameters: effect, duration, amplifier, [name]
                    int paramIndex = 1;
                    if (parts.length > paramIndex && !parts[paramIndex].contains(":")) {
                        potionEffect = parts[paramIndex++];
                    }
                    if (parts.length > paramIndex && !parts[paramIndex].contains(":")) {
                        try {
                            potionDuration = Integer.parseInt(parts[paramIndex++]);
                        } catch (NumberFormatException e) {
                            // If it's not a number, it might be a name parameter without value
                            if (parts[paramIndex].startsWith("name:")) {
                                name = Text.parseColors(parts[paramIndex++].substring(5).replace('_', ' '));
                            }
                        }
                    }
                    if (parts.length > paramIndex && !parts[paramIndex].contains(":")) {
                        try {
                            potionAmplifier = Integer.parseInt(parts[paramIndex++]);
                        } catch (NumberFormatException e) {
                            // If it's not a number, it might be a name parameter without value
                            if (parts[paramIndex].startsWith("name:")) {
                                name = Text.parseColors(parts[paramIndex++].substring(5).replace('_', ' '));
                            }
                        }
                    }
                    // Check for name parameter in any remaining parts
                    for (int j = paramIndex; j < parts.length; j++) {
                        if (parts[j].startsWith("name:")) {
                            String nameValue = parts[j].substring(5);
                            // Check if the name contains a gradient tag that might have been split
                            if (nameValue.contains("<gradient") && !nameValue.contains(">")) {
                                // Reconstruct the gradient tag by combining with remaining parts
                                StringBuilder gradientName = new StringBuilder(nameValue);
                                for (int k = j + 1; k < parts.length; k++) {
                                    gradientName.append(" ").append(parts[k]);
                                    if (parts[k].contains(">")) {
                                        j = k; // Skip the parts we've already processed
                                        break;
                                    }
                                }
                                nameValue = gradientName.toString();
                            }
                            name = Text.parseColors(nameValue.replace('_', ' '));
                            break;
                        }
                    }
                }
            }

            // Handle potion metadata if this is a potion
            if (material != null && (material == Material.POTION || material == Material.SPLASH_POTION || 
                material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW) && meta instanceof PotionMeta) {
                PotionMeta potionMeta = (PotionMeta) meta;

                // If no effect specified but we have a potion, use default
                if (potionEffect == null) {
                    potionMeta.setBasePotionType(PotionType.WATER);
                    item.setItemMeta(potionMeta);
                    // Skip the rest of the potion handling
                    return;
                }

                // Find potion effect type using modern API
                final PotionEffectType effectType = getPotionEffectType(potionEffect);
                
                if (effectType == null) {
                    sender.sendMessage(Text.parseColors(lang.get("commands.give.invalid-potion-effect")
                        .replace("{effect}", potionEffect)));
                    return;
                }
                
                int durationTicks = calculatePotionDurationTicks(potionDuration, material, effectType);
                
                PotionEffect effect = createPotionEffect(
                    effectType, durationTicks, potionAmplifier, 
                    potionAmbient, potionParticles, potionIcon
                );
                
                potionMeta.clearCustomEffects();
                potionMeta.lore(new ArrayList<>());
                
                if (PotionEffectType.RESISTANCE.equals(effectType)) {
                    potionMeta.addCustomEffect(createPotionEffect(
                        PotionEffectType.RESISTANCE, durationTicks, 
                        potionAmplifier, potionAmbient, potionParticles, potionIcon
                    ), true);
                } else {
                    potionMeta.addCustomEffect(effect, true);
                }
                
                // Set display name for potion
                if (name != null) {
                    potionMeta.setDisplayName(Text.parseColors(name));
                } else {
                    // Generate default potion name if no custom name provided
                    String displayName = getPotionItemType(material) + getEffectDisplayName(effectType);
                    if (potionAmplifier > 0) {
                        displayName += " " + toRomanNumeral(potionAmplifier);
                    }
                    potionMeta.setDisplayName(Text.parseColors(displayName));
                }
                
                // Set base potion type to WATER to prevent "Uncraftable" text
                potionMeta.setBasePotionType(PotionType.WATER);
                
                item.setItemMeta(potionMeta);
            }

            // Apply the name to the item if specified (for non-potion items)
            if (name != null && !isPotion) {
                meta.setDisplayName(Text.parseColors(name));
            }

            item.setItemMeta(meta);
            
        } catch (Exception e) {
            // Log the error and notify the sender
            plugin.getLogger().log(Level.SEVERE, "Error while giving item: " + e.getMessage(), e);
            Text.sendErrorMessage(sender, "An error occurred while processing the give command: {0}", lang, "error", e.getMessage());
            return;
        }

        // Apply enchantments if any
        if (enchantments != null && !enchantments.isEmpty()) {
            applyEnchantments(sender, item, material, enchantments);
        }

        // Give the item to the player
        PlayerInventory inventory = target.getInventory();
        inventory.addItem(item);

        // Send success message
        String finalItemName = formatName(material.name());
        if (sender.getName().equals(target.getName())) {
            // Self-give
            sender.sendMessage(lang.get("give.success")
                    .replace("{amount}", String.valueOf(amount))
                    .replace("{item}", finalItemName));
        } else {
            // Giving to another player
            sender.sendMessage(lang.get("give.success-other")
                    .replace("{name}", target.getName())
                    .replace("{amount}", String.valueOf(amount))
                    .replace("{item}", finalItemName));
            
            // Send message to receiver
            target.sendMessage(lang.get("give.receive")
                    .replace("{amount}", String.valueOf(amount))
                    .replace("{item}", finalItemName));
        }
    }

    private static PotionEffectType getPotionEffectType(String name) {
        final PotionEffectType potionEffect = Arrays.stream(PotionEffectType.values())
            .filter(type -> type != null && type.getKey() != null 
                && type.getKey().getKey().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
        return potionEffect;
    }

    private String toRomanNumeral(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(number);
        };
    }
    

    /**
     * Converts an alias or legacy item ID to a valid Material.
     * @param itemName The item name or alias to convert
     * @param sender The command sender for error messages
     * @return The corresponding Material, or null if not found
     */
    private Material getMaterial(String itemName, CommandSender sender) {
    Material material = null;
    String baseItemName = itemName;

    // Handle NBT data after semicolon if present
    if (itemName.contains(";")) {
        baseItemName = itemName.substring(0, itemName.indexOf(";"));
    }

    // First try direct material match (e.g., "STONE")
    try {
        material = Material.matchMaterial(baseItemName);
        if (material != null && isGiveable(material)) {
            return material;
        }
    } catch (IllegalArgumentException ignored) {}

    // Handle colon syntax (e.g., "stone:1" or "1:1")
    if (baseItemName.contains(":")) {
        String[] parts = baseItemName.split(":", 2);
        String left = parts[0];
        String right = parts[1];

        // Try to resolve the left part through aliases first
        String resolvedLeft = Alias.getAlias(left.toLowerCase());
        if (resolvedLeft == null) {
            resolvedLeft = left; // Use as-is if no alias found
        }

        // Try to get the material directly if the left part is a material name
        Material baseMaterial = Material.matchMaterial(resolvedLeft);
        if (baseMaterial != null) {
            // Get the legacy ID for this material
            String legacyId = LegacyID.getLegacyIdFromMaterial(baseMaterial);
            if (legacyId != null) {
                // If legacy ID has its own data, use it as is
                if (legacyId.contains(":")) {
                    material = LegacyID.getMaterialFromLegacyId(legacyId);
                } else {
                    // Otherwise combine with the provided data
                    material = LegacyID.getMaterialFromLegacyId(legacyId + ":" + right);
                }
                if (material != null && isGiveable(material)) {
                    return material;
                }
            }
        }

        // Try the direct legacy ID lookup with the original parts
        material = LegacyID.getMaterialFromLegacyId(baseItemName);
        if (material != null && isGiveable(material)) {
            return material;
        }
    }

    // Try alias lookup for the full item name
    String alias = Alias.getAlias(baseItemName.toLowerCase());
    if (alias != null) {
        material = Material.matchMaterial(alias);
        if (material != null && isGiveable(material)) {
            return material;
        }
        // If alias didn't resolve to a material, try it as a legacy ID
        material = LegacyID.getMaterialFromLegacyId(alias);
        if (material != null && isGiveable(material)) {
            return material;
        }
    }

    // Try direct legacy ID lookup
    material = LegacyID.getMaterialFromLegacyId(baseItemName);
    if (material != null && isGiveable(material)) {
        return material;
    }

    // Try uppercase version as a last resort
    try {
        material = Material.matchMaterial(baseItemName.toUpperCase());
        if (material != null && isGiveable(material)) {
            return material;
        }
    } catch (IllegalArgumentException ignored) {

    }

        // If we get here, the material couldn't be found
        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
        return null;
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
    public static String getAvailablePotionEffects() {
        List<String> effectNames = Arrays.stream(PotionEffectType.values())
            .filter(Objects::nonNull)
            .map(type -> type.getKey().getKey())
            .sorted()
            .collect(Collectors.toList());
        return String.join(", ", effectNames);
    }

    /**
     * Gets a list of all available potion types
     * @return A formatted string of all potion types
     */
    public static String getAvailablePotionTypes() {
        List<String> typeNames = new ArrayList<>();
        PotionType[] types = PotionType.values();
        // Get all potion types
        for (PotionType type : types) {
            if (type != null && type.name() != null) {
                typeNames.add(type.name().toLowerCase());
            }
        }
        return String.join(", ", typeNames);
    }
}