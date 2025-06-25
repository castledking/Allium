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
import java.util.HashMap;
import java.util.Map;

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
        int duration = params.containsKey("duration") ? Integer.parseInt(params.get("duration")) : 0;
        int amplifier = params.containsKey("amplifier") ? Integer.parseInt(params.get("amplifier")) : 0;

        if (effectName == null) {
            potionMeta.setBasePotionType(PotionType.WATER);
            item.setItemMeta(potionMeta);
            return;
        }

        if (effectName.equalsIgnoreCase("turtle_master")) {
            applyTurtleMaster(potionMeta, displayName, item, duration);
            item.setItemMeta(potionMeta);
            return;
        }

        PotionEffectType effectType = getPotionEffectType(effectName);
        if (effectType == null) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "potion effect");
            return;
        }

        int durationTicks = calculateDurationTicks(effectType, duration, item.getType());
        PotionEffect effect = new PotionEffect(effectType, durationTicks, amplifier, false, true, true);

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
            String generatedName = prefix + Meta.formatName(effectType.getKey().getKey());
            potionMeta.displayName(Component.text(generatedName).decoration(TextDecoration.ITALIC, false));
        }

        item.setItemMeta(potionMeta);
    }

    private static Map<String, String> parsePotionArgs(String[] parts) {
        Map<String, String> params = new HashMap<>();
        if (parts.length <= 1) return params;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            String[] keyValue = part.split("[:=]", 2);
            if (keyValue.length == 2) {
                params.put(keyValue[0].toLowerCase(), keyValue[1]);
            } else if (i == 1) {
                params.put("effect", part);
            }
        }
        return params;
    }

    private static void applyTurtleMaster(PotionMeta potionMeta, String displayName, ItemStack item, int specifiedDuration) {
        // Clear any existing effects and lore
        potionMeta.clearCustomEffects();
        potionMeta.lore(new ArrayList<>());
        
        if (specifiedDuration > 0) {
            // Option 2: Use custom effects with specific duration
            int durationTicks = specifiedDuration * 20;
            
            // Apply the same division factor for tipped arrows
            if (item.getType() == Material.TIPPED_ARROW) {
                durationTicks /= 64;
            }
            
            // Create custom effects with the specified duration
            PotionEffect slowness = new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 3, false, true, true);
            PotionEffect resistance = new PotionEffect(PotionEffectType.RESISTANCE, durationTicks, 2, false, true, true);
            
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
        Component component = Component.text(finalName)
                .decoration(TextDecoration.ITALIC, false);
        potionMeta.displayName(component);
    }

    private static PotionEffectType getPotionEffectType(String name) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase()));
    }

    private static int calculateDurationTicks(PotionEffectType type, int specifiedDuration, Material material) {
        int durationSeconds = specifiedDuration > 0 ? specifiedDuration : DEFAULT_POTION_DURATIONS.getOrDefault(type, 30);
        int durationTicks = durationSeconds * 20;

        if (material == Material.TIPPED_ARROW) {
            // Using division factor of 64 for tipped arrows as requested
            // This means for a 5-minute (300 second) effect, you need duration:19200
            durationTicks /= 64;
        }

        return Math.max(1, durationTicks);
    }
}
