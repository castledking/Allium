package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Text;
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

import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Item.isGiveable;

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    private final net.survivalfun.core.managers.config.Config configManager;
    
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

        Player target = Bukkit.getPlayer(player);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", player);
            return;
        }

        Material material = null;
        ItemMeta meta = null;
        ItemStack item = null;
        int specialPotionType = 0;

        try {
            // Extract base item name
            String[] parts = arg.split(";");
            String itemName = parts[0];

            // Check for special potion types (potion:1, potion:2, potion:3)
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
            boolean customPotion = false;

            // Parse potion arguments
            if (parts.length > 1) {
                // Check if the first argument after 'potion' is in named format (contains ':' or '=')
                boolean isNamed = parts[1].contains(":") || parts[1].contains("=");
                
                if (isNamed) {
                    // Named parameters: e.g. effect:speed;duration:300;amplifier:1
                    Map<String, String> namedParams = new HashMap<>();
                    for (int i = 1; i < parts.length; i++) {
                        String[] keyValue = parts[i].split("[:=]");
                        if (keyValue.length >= 2) {
                            String key = keyValue[0].toLowerCase();
                            String value = keyValue[1];
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
                        name = Text.parseColors(namedParams.get("name").replace('_', ' '));
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
                    for (int i = paramIndex; i < parts.length; i++) {
                        if (parts[i].startsWith("name:")) {
                            name = Text.parseColors(parts[i].substring(5).replace('_', ' '));
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

                // Find the effect type using the registry
                PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(potionEffect.toLowerCase()));
                if (effectType == null) {
                    // Fallback to direct registry lookup by key
                    effectType = Registry.EFFECT.get(NamespacedKey.fromString(potionEffect.toLowerCase()));
                }

                if (effectType == null) {
                    Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion effect");
                    return;
                }

                // Duration calculation - handles both custom and default durations
                int durationTicks;
                int finalDurationSeconds;
                plugin.getLogger().info("potionDuration before calculation: " + potionDuration); // ADDED
                if (potionDuration > 0) {
                    durationTicks = potionDuration * 20;
                    plugin.getLogger().info("durationTicks before arrow reduction: " + durationTicks); // ADDED
                    if (material == Material.TIPPED_ARROW) {
                        durationTicks = (int) Math.round(durationTicks * 8.0); // Floating-point division and rounding
                        plugin.getLogger().info("durationTicks after arrow reduction: " + durationTicks); // ADDED
                    }
                    finalDurationSeconds = durationTicks / 20; // Actual duration after arrow reduction
                    plugin.getLogger().info("finalDurationSeconds after arrow reduction: " + finalDurationSeconds); // ADDED
                } else {
                    durationTicks = DEFAULT_POTION_DURATIONS.getOrDefault(effectType, 30) * 20;
                    finalDurationSeconds = durationTicks / 20; // Use default duration
                }

// Log the duration calculation details
                plugin.getLogger().log(Level.INFO, "Potion effect: {0}, duration: {1} seconds, amplifier: {2}, material: {3}",
                        new Object[]{effectType.getKey().getKey(), finalDurationSeconds, potionAmplifier, material});

                plugin.getLogger().log(Level.INFO, "Duration in ticks: {0}", durationTicks);




// Create the potion effect
                PotionEffect effect = new PotionEffect(
                        effectType,
                        durationTicks,
                        potionAmplifier,
                        potionAmbient,
                        potionParticles,
                        potionIcon
                );


                // Clear effects and lore (let Minecraft handle default display)
                potionMeta.clearCustomEffects();
                potionMeta.lore(new ArrayList<>());
                potionMeta.addCustomEffect(effect, true);
                
                // Set proper display name
                if (name != null) {
                    // Parse the name with Text.parseColors to handle all formats including MiniMessage
                    String parsedName = Text.parseColors(name);
                    // Convert to Adventure Component and ensure no italics
                    net.kyori.adventure.text.Component component = 
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize(parsedName)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
                    potionMeta.displayName(component);
                } else {
                    // Automatically generate name based on effect and amplifier
                    String displayEffectName;
                    if (effectType.equals(PotionEffectType.SPEED)) {
                        displayEffectName = "Swiftness";
                    } else {
                        String effectName = effectType.getName().toLowerCase().replace('_', ' ');
                        displayEffectName = effectName.substring(0, 1).toUpperCase() + effectName.substring(1);
                    }
                    String amplifierStr = toRomanNumeral(potionAmplifier);
                    String displayName = "Potion of " + displayEffectName;
                    if (potionAmplifier > 0) {
                        displayName += " " + amplifierStr;
                    }
                    potionMeta.displayName(net.kyori.adventure.text.Component.text(displayName)
                        .color(net.kyori.adventure.text.format.TextColor.color(255, 255, 255))
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                
                // Set base potion type to WATER to prevent "Uncraftable" text
                potionMeta.setBasePotionType(PotionType.WATER);
                
                item.setItemMeta(potionMeta);
            }

            // Apply the name to the item if specified (for non-potion items)
            if (name != null && !isPotion) {
                // Parse the name with Text.parseColors to handle all formats including MiniMessage
                String parsedName = Text.parseColors(name);
                // Convert to Adventure Component and ensure no italics
                net.kyori.adventure.text.Component component = 
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .deserialize(parsedName)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
                meta.displayName(component);
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

    /**
     * Converts an integer to a Roman numeral string.
     * @param number The number to convert (1-10)
     * @return The Roman numeral string
     */
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
     * Converts an alias or legacy item ID to a valid Material name.
     * - If input is a number or in "number:number" format (legacy ID), it checks the legacy ID map.
     * - If input is an alias, it uses the alias map.
     * - Otherwise, it returns the input in uppercase as a fallback.
     *
     * @param itemName The item name or alias to convert
     * @param sender The command sender for error messages
     * @return The corresponding Material, or null if not found
     */
    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        if (itemName.contains(";")) {
            baseItemName = itemName.substring(0, itemName.indexOf(";"));
        }

        // First try direct material lookup (case-insensitive)
        try {
            material = Material.matchMaterial(baseItemName);
            if (material != null && isGiveable(material)) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {}

        // Check if the input contains a colon
        if (baseItemName.contains(":")) {
            String[] parts = baseItemName.split(":", 2);
            String left = parts[0];
            String right = parts[1];

            // First try to resolve the full string as an alias (e.g., "bed:1")
            String fullAlias = Alias.getAlias(baseItemName);
            if (fullAlias != null) {
                try {
                    material = Material.matchMaterial(fullAlias);
                    if (material != null && isGiveable(material)) {
                        return material;
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            // Try to resolve left as alias to legacy base id (e.g., stone -> 1)
            String alias = Alias.getAlias(left);
            String legacyBase = alias != null ? alias : left;
            String legacyId = legacyBase + ":" + right;
            material = LegacyID.getMaterialFromLegacyId(legacyId);
            if (material != null && isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as an alias
        String alias = Alias.getAlias(baseItemName);
        if (alias != null) {
            material = Material.matchMaterial(alias);
            if (material != null && isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as a legacy ID (e.g., "1" or "1:0")
        material = LegacyID.getMaterialFromLegacyId(baseItemName);
        if (material != null && isGiveable(material)) {
            return material;
        }

        // Last attempt: Try with uppercase and underscores if not already tried
        if (!baseItemName.equals(baseItemName.toUpperCase())) {
            try {
                material = Material.matchMaterial(baseItemName.toUpperCase().replace(' ', '_'));
                if (material != null && isGiveable(material)) {
                    return material;
                }
            } catch (IllegalArgumentException ignored) {}
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
    @SuppressWarnings("deprecation") // PotionEffectType.values() is deprecated but still widely used
    public static String getAvailablePotionEffects() {
        List<String> effectNames = new ArrayList<>();
        PotionEffectType[] effects = PotionEffectType.values();
        for (PotionEffectType type : effects) {
            if (type != null && type.getKey() != null) {
                effectNames.add(type.getKey().getKey().toLowerCase());
            }
        }
        return String.join(", ", effectNames);
    }

    /**
     * Gets a list of all available potion types
     * @return A formatted string of all potion types
     */
    @SuppressWarnings("deprecation") // PotionType.values() is deprecated but still widely used
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