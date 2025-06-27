package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Potion {

    private static PluginStart plugin;
    private static Lang lang;

    private static final Map<PotionEffectType, Integer> DEFAULT_POTION_DURATIONS = new HashMap<>();

    static {
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SPEED, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.SLOWNESS, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HASTE, 90);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.MINING_FATIGUE, 300);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.STRENGTH, 180);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.INSTANT_HEALTH, -1);
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.INSTANT_DAMAGE, -1);
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
        DEFAULT_POTION_DURATIONS.put(PotionEffectType.HEALTH_BOOST, 30);
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

    public static void initialize(PluginStart pluginInstance) {
        plugin = pluginInstance;
        lang = plugin.getLangManager();
    }

    public static void applyPotionMeta(CommandSender sender, ItemStack item, String[] parts) {
        if (!(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return;
        }

        // Start with a clean slate to prevent any lingering effects
        potionMeta.clearCustomEffects();
        potionMeta.setBasePotionType(PotionType.WATER);

        Map<String, String> params = parsePotionArgs(parts);
        String effectName = params.get("effect");
        String displayName = params.get("name");
        int duration = params.containsKey("duration") ? parseIntOrDefault(params.get("duration"), 0) : 0;
        int amplifier = params.containsKey("amplifier") ? parseIntOrDefault(params.get("amplifier"), 0) : 0;
        boolean particles = !params.containsKey("particles") || parseBoolean(params.get("particles"));
        boolean icon = !params.containsKey("icon") || parseBoolean(params.get("icon")); // Whether to show the icon in inventory

        if (effectName == null || effectName.isEmpty()) {
            // Check if this is a semicolon with no arguments
            if (parts.length > 1 && parts[1].isEmpty()) {
                // Show usage guide instead of invalid message
                String usageMessage = lang.get("command-usage")
                    .replace("{cmd}", "i")
                    .replace("{args}", "potion;<effect>;[duration];[amplifier];[particles];[icon]");
                sender.sendMessage(usageMessage);
                sender.sendMessage("§eExample: §f/i potion;speed;60;1;true");
                sender.sendMessage("§eAlternative: §f/i potion;effect=speed;amplifier=1;duration=60");
                sender.sendMessage("§eBase Potions: §f/i potion;awkward §7| §f/i potion;mundane §7| §f/i potion;thick");
                sender.sendMessage("§eWater Bottles: §f/i splash_potion;water §7| §f/i lingering_potion;water");
            }

            potionMeta.setBasePotionType(PotionType.WATER);
            item.setItemMeta(potionMeta);
            return;
        }

        if (effectName.equalsIgnoreCase("turtle_master")) {
            applyTurtleMaster(potionMeta, displayName, item, duration, particles, icon);
            item.setItemMeta(potionMeta);
            return;
        }

        // Handle base potion types (awkward, mundane, thick)
        if (effectName.equalsIgnoreCase("awkward")) {
            potionMeta.setBasePotionType(PotionType.AWKWARD);
            if (displayName != null) {
                Meta.applyDisplayName(potionMeta, displayName);
            } else {
                String prefix;
                if (item.getType() == Material.SPLASH_POTION) {
                    prefix = "Awkward Splash Potion";
                } else if (item.getType() == Material.LINGERING_POTION) {
                    prefix = "Awkward Lingering Potion";
                } else {
                    prefix = "Awkward Potion";
                }
                potionMeta.displayName(Component.text(prefix).decoration(TextDecoration.ITALIC, false));
            }
            item.setItemMeta(potionMeta);
            return;
        }
        
        if (effectName.equalsIgnoreCase("mundane")) {
            potionMeta.setBasePotionType(PotionType.MUNDANE);
            if (displayName != null) {
                Meta.applyDisplayName(potionMeta, displayName);
            } else {
                String prefix;
                if (item.getType() == Material.SPLASH_POTION) {
                    prefix = "Mundane Splash Potion";
                } else if (item.getType() == Material.LINGERING_POTION) {
                    prefix = "Mundane Lingering Potion";
                } else {
                    prefix = "Mundane Potion";
                }
                potionMeta.displayName(Component.text(prefix).decoration(TextDecoration.ITALIC, false));
            }
            item.setItemMeta(potionMeta);
            return;
        }
        
        if (effectName.equalsIgnoreCase("thick")) {
            potionMeta.setBasePotionType(PotionType.THICK);
            if (displayName != null) {
                Meta.applyDisplayName(potionMeta, displayName);
            } else {
                String prefix;
                if (item.getType() == Material.SPLASH_POTION) {
                    prefix = "Thick Splash Potion";
                } else if (item.getType() == Material.LINGERING_POTION) {
                    prefix = "Thick Lingering Potion";
                } else {
                    prefix = "Thick Potion";
                }
                potionMeta.displayName(Component.text(prefix).decoration(TextDecoration.ITALIC, false));
            }
            item.setItemMeta(potionMeta);
            return;
        }

        if (effectName.equalsIgnoreCase("water")) {
            potionMeta.setBasePotionType(PotionType.WATER);
            if (displayName != null) {
                Meta.applyDisplayName(potionMeta, displayName);
            } else {
                String prefix;
                if (item.getType() == Material.SPLASH_POTION) {
                    prefix = "Splash Water Bottle";
                } else if (item.getType() == Material.LINGERING_POTION) {
                    prefix = "Lingering Water Bottle";
                } else {
                    prefix = "Water Bottle";
                }
                potionMeta.displayName(Component.text(prefix).decoration(TextDecoration.ITALIC, false));
            }
            item.setItemMeta(potionMeta);
            return;
        }

        PotionEffectType effectType = getPotionEffectType(effectName);
        if (effectType == null) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion effect");
            return;
        }

        int durationTicks = calculateDurationTicks(effectType, duration, item.getType());
        PotionEffect effect = new PotionEffect(effectType, durationTicks, amplifier, false, particles, icon);

        potionMeta.clearCustomEffects();
        potionMeta.lore(new ArrayList<>());
        potionMeta.addCustomEffect(effect, true);
        
        // Only add resistance if the setting is enabled (currently disabled)
        // Code removed to fix resistance being added to all potions
        
        potionMeta.setBasePotionType(PotionType.WATER);

        if (displayName != null) {
            Meta.applyDisplayName(potionMeta, displayName);
        } else {
            String prefix = "Potion of ";
            if (item.getType() == Material.SPLASH_POTION) {
                prefix = "Splash Potion of ";
            } else if (item.getType() == Material.LINGERING_POTION) {
                prefix = "Lingering Potion of ";
            } else if (item.getType() == Material.TIPPED_ARROW) {
                prefix = "Arrow of ";
            }
            String generatedName = prefix + getEffectDisplayName(effectType);
            potionMeta.displayName(Component.text(generatedName).decoration(TextDecoration.ITALIC, false));
        }

        item.setItemMeta(potionMeta);
    }

    private static Map<String, String> parsePotionArgs(String[] parts) {
        Map<String, String> params = new HashMap<>();
        if (parts.length <= 1) return params;
        
        // Process parameters - handle mixed ordered and named parameters
        String[] orderedParams = {"effect", "duration", "amplifier", "particles", "icon"};
        int orderedIndex = 0;
        
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            
            // Check if this is a named parameter (key:value or key=value)
            if (part.contains(":") || part.contains("=")) {
                String[] keyValue = part.split("[:=]", 2);
                if (keyValue.length == 2) {
                    params.put(keyValue[0].toLowerCase(), keyValue[1]);
                }
            } else {
                // This is an ordered parameter - assign it to the next available ordered slot
                if (orderedIndex < orderedParams.length) {
                    params.put(orderedParams[orderedIndex], part);
                    orderedIndex++;
                }
            }
        }
        
        return params;
    }

    private static void applyTurtleMaster(PotionMeta potionMeta, String displayName, ItemStack item, int specifiedDuration, boolean particles, boolean icon) {
        // Clear any existing effects and lore
        potionMeta.clearCustomEffects();
        potionMeta.lore(new ArrayList<>());
        
        if (specifiedDuration > 0) {
            // Option 2: Use custom effects with specific duration
            int durationTicks = specifiedDuration * 20;
            
            // Apply multiplication factors to counteract Minecraft's internal divisions
            if (item.getType() == Material.TIPPED_ARROW) {
                // Tipped arrows have effects reduced to 1/8 of their original duration
                durationTicks *= 8;
            } else if (item.getType() == Material.SPLASH_POTION) {
                // Splash potions have effects reduced to 3/4 of their original duration
                durationTicks = (int)(durationTicks * (4.0/3.0));
            } else if (item.getType() == Material.LINGERING_POTION) {
                // Lingering potions have effects reduced to 1/4 of their original duration
                durationTicks *= 4;
            }
            // Regular potions (Material.POTION) don't have their durations modified
            
            // Create custom effects with the specified duration and respect particles and icon settings
            PotionEffect slowness = new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 3, false, particles, icon);
            PotionEffect resistance = new PotionEffect(PotionEffectType.RESISTANCE, durationTicks, 2, false, particles, icon);
            
            // Add the custom effects
            potionMeta.addCustomEffect(slowness, true);
            potionMeta.addCustomEffect(resistance, true);
            potionMeta.setBasePotionType(PotionType.WATER); // Use neutral base type
        } else {
            // Option 1: Use default Turtle Master potion type
            // This will automatically include the slowness and resistance effects
            potionMeta.setBasePotionType(PotionType.TURTLE_MASTER);
        }

        String prefix = "Potion of ";
        if (item.getType() == Material.SPLASH_POTION) {
            prefix = "Splash Potion of ";
        } else if (item.getType() == Material.LINGERING_POTION) {
            prefix = "Lingering Potion of ";
        } else if (item.getType() == Material.TIPPED_ARROW) {
            prefix = "Arrow of ";
        }
        String finalName = displayName != null ? displayName : prefix + "the Turtle Master";
        Component component = Text.colorize(finalName).decoration(TextDecoration.ITALIC, false);
        potionMeta.displayName(component);
    }

    private static PotionEffectType getPotionEffectType(String name) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase()));
    }

    private static int calculateDurationTicks(PotionEffectType type, int specifiedDuration, Material material) {
        // Handle the case where duration was not specified - use appropriate defaults without multipliers
        if (specifiedDuration <= 0) {
            // For all potion types, just use the default duration from the map directly
            // Let Minecraft handle any internal adjustments based on potion type
            int defaultDuration = DEFAULT_POTION_DURATIONS.getOrDefault(type, 30);
            return defaultDuration * 20; // Convert seconds to ticks
        }
        
        // If duration was specified, convert to ticks and apply multiplication factors
        int durationTicks = specifiedDuration * 20;

        // Apply multiplication factors to counteract Minecraft's internal divisions for specified durations
        if (material == Material.TIPPED_ARROW) {
            // Tipped arrows have effects reduced to 1/8 of their original duration
            durationTicks *= 8;
        } else if (material == Material.SPLASH_POTION) {
            // Splash potions have effects reduced to 3/4 of their original duration
            durationTicks = (int)(durationTicks * (4.0/3.0));
        } else if (material == Material.LINGERING_POTION) {
            // Lingering potions have effects reduced to 1/4 of their original duration
            durationTicks *= 4;
        }
        // Regular potions (Material.POTION) don't have their durations modified
        
        return Math.max(1, durationTicks);
    }
    
    /**
     * Helper method to parse an integer with a default value if parsing fails
     */
    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Helper method to parse a boolean value
     */
    private static boolean parseBoolean(String value) {
        if (value == null) return true;
        return !value.equalsIgnoreCase("false") && 
               !value.equalsIgnoreCase("0") && 
               !value.equalsIgnoreCase("off") && 
               !value.equalsIgnoreCase("no");
    }
    
    private static String getEffectDisplayName(PotionEffectType effectType) {
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
}
