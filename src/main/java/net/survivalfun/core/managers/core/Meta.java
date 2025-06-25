package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;

import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public class Meta {

    private static PluginStart plugin;
    private static Lang lang;
    private static Config configManager;

    public static void initialize(PluginStart pluginInstance) {
        plugin = pluginInstance;
        lang = plugin.getLangManager();
        configManager = plugin.getConfigManager();
    }

    public static void applyEnchantments(CommandSender sender, ItemStack item, Map<Enchantment, Integer> enchantments) {
        if (item == null || enchantments == null || enchantments.isEmpty()) {
            return;
        }

        boolean unsafe = configManager.getBoolean("allow-unsafe-enchants");
        boolean isEnchantmentBook = item.getType() == Material.ENCHANTED_BOOK;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        Map<Enchantment, Integer> addedEnchantments = new LinkedHashMap<>(item.getEnchantments());

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            // Try to apply using EcoEnchants if available
            if (tryApplyEcoEnchant(item, enchantment, level, unsafe)) {
                continue; // Skip standard enchantment application if EcoEnchants handled it
            }
            
            // Try to apply using AdvancedEnchantments if available
            if (tryApplyAdvancedEnchant(item, enchantment, level)) {
                continue; // Skip standard enchantment application if AdvancedEnchantments handled it
            }

            if (!enchantment.canEnchantItem(item) && !unsafe) {
                Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{item}", formatName(item.getType().name()));
                continue;
            }

            if (level > enchantment.getMaxLevel() && !unsafe) {
                Text.sendErrorMessage(sender, "give.unsafe-level", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{maxLevel}", String.valueOf(enchantment.getMaxLevel()));
                level = enchantment.getMaxLevel();
            }

            boolean conflicts = false;
            if (!unsafe) {
                for (Enchantment existingEnch : addedEnchantments.keySet()) {
                    if (existingEnch.conflictsWith(enchantment)) {
                        conflicts = true;
                        Text.sendErrorMessage(sender, "give.conflicting-enchants", lang,
                                "{enchant}", formatName(existingEnch.getKey().getKey()),
                                "{enchant2}", formatName(enchantment.getKey().getKey()),
                                "{item}", formatName(item.getType().name()));
                        break;
                    }
                }
            }

            if (!conflicts) {
                addedEnchantments.put(enchantment, level);
                
                if (isEnchantmentBook) {
                    EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) meta;
                    bookMeta.addStoredEnchant(enchantment, level, unsafe);
                } else {
                    meta.addEnchant(enchantment, level, unsafe);
                }
            }
        }
        
        item.setItemMeta(meta);
    }
    
    /**
     * Attempts to apply an enchantment using EcoEnchants API
     * @return true if the enchantment was applied using EcoEnchants
     */
    private static boolean tryApplyEcoEnchant(ItemStack item, Enchantment enchantment, int level, boolean unsafe) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EcoEnchants")) {
            return false;
        }
        
        try {
            // Get the EcoEnchantment class
            Class<?> ecoEnchantmentClass = Class.forName("com.willfp.ecoenchants.enchantments.EcoEnchantment");
            
            // Check if the enchantment is an EcoEnchantment
            if (ecoEnchantmentClass.isInstance(enchantment)) {
                // Get the EnchantmentManager class
                Class<?> enchantmentManagerClass = Class.forName("com.willfp.ecoenchants.enchantments.EnchantmentManager");
                
                // Get the getEnchantmentManager method
                Method getEnchantmentManagerMethod = ecoEnchantmentClass.getMethod("getEnchantmentManager");
                
                // Call the method to get the EnchantmentManager
                Object enchantmentManager = getEnchantmentManagerMethod.invoke(enchantment);
                
                // Get the addToItem method
                Method addToItemMethod = enchantmentManagerClass.getMethod("addToItem", ItemStack.class, int.class);
                
                // Call the method to add the enchantment to the item
                addToItemMethod.invoke(enchantmentManager, item, level);
                
                return true;
            }
        } catch (Exception e) {
            // Log the error but don't crash
            Bukkit.getLogger().log(Level.WARNING, "Error applying EcoEnchant: " + enchantment.getKey(), e);
        }
        
        return false;
    }
    
    /**
     * Attempts to apply an enchantment using AdvancedEnchantments API
     * @return true if the enchantment was applied using AdvancedEnchantments
     */
    private static boolean tryApplyAdvancedEnchant(ItemStack item, Enchantment enchantment, int level) {
        if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            return false;
        }
        
        try {
            // Get the AdvancedEnchantment class
            Class<?> advEnchantClass = Class.forName("me.egg82.ae.api.AdvancedEnchantment");
            
            // Get the getByEnchantment method
            Method getByEnchantmentMethod = advEnchantClass.getMethod("getByEnchantment", Enchantment.class);
            
            // Call the method to get the Optional<AdvancedEnchantment>
            Object optionalAdvEnchant = getByEnchantmentMethod.invoke(null, enchantment);
            
            // Check if the Optional is present
            Method isPresentMethod = optionalAdvEnchant.getClass().getMethod("isPresent");
            boolean isPresent = (boolean) isPresentMethod.invoke(optionalAdvEnchant);
            
            if (isPresent) {
                // Get the value from the Optional
                Method getMethod = optionalAdvEnchant.getClass().getMethod("get");
                Object advEnchant = getMethod.invoke(optionalAdvEnchant);
                
                // Get the BukkitEnchantableItem class
                Class<?> bukkitEnchantableItemClass = Class.forName("me.egg82.ae.api.BukkitEnchantableItem");
                
                // Get the fromItemStack method
                Method fromItemStackMethod = bukkitEnchantableItemClass.getMethod("fromItemStack", ItemStack.class);
                
                // Call the method to get the BukkitEnchantableItem
                Object bukkitEnchantableItem = fromItemStackMethod.invoke(null, item);
                
                // Get the apply method
                Method applyMethod = advEnchant.getClass().getMethod("apply", bukkitEnchantableItemClass, int.class);
                
                // Call the method to apply the enchantment
                applyMethod.invoke(advEnchant, bukkitEnchantableItem, level);
                
                return true;
            }
        } catch (Exception e) {
            // Log the error but don't crash
            Bukkit.getLogger().log(Level.WARNING, "Error applying AdvancedEnchantment: " + enchantment.getKey(), e);
        }
        
        return false;
    }

    public static ItemStack createPlayerHead(CommandSender sender, String[] parts, int amount, Map<Enchantment, Integer> enchantments) {
        String owner = null;
        String base64 = null;
        String hdbCode = null;
        String displayName = null;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.toLowerCase().startsWith("owner:")) {
                owner = part.substring(6);
            } else if (part.toLowerCase().startsWith("base64:")) {
                base64 = part.substring(7);
            } else if (part.toLowerCase().startsWith("hdb:")) {
                hdbCode = part.substring(4);
            } else if (part.toLowerCase().startsWith("name:")) {
                displayName = part.substring(5);
            } else if (i == 1 && !part.contains(":")) {
                owner = part;
            }
        }

        ItemStack skull = null;
        if (hdbCode != null) {
            skull = Skull.createSkullWithTexture(hdbCode);
        } else if (base64 != null) {
            skull = Skull.createSkullWithTexture(base64);
        } else if (owner != null) {
            // First try to get the head for an online player
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(owner);
            if (offlinePlayer.isOnline() || offlinePlayer.hasPlayedBefore()) {
                skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
                if (skullMeta != null) {
                    skullMeta.setOwningPlayer(offlinePlayer);
                    skull.setItemMeta(skullMeta);
                }
            } else {
                // For non-online players, try to fetch from minecraft-heads.com
                skull = Skull.fetchPlayerHeadFromName(owner);
            }
        }

        if (skull == null) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "skull");
            return null;
        }

        skull.setAmount(amount > 0 ? amount : 1);

        ItemMeta meta = skull.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                applyDisplayName(meta, displayName);
            }
            skull.setItemMeta(meta);
        }

        applyEnchantments(sender, skull, enchantments);
        return skull;
    }

    public static void applyDisplayName(ItemMeta meta, String name) {
        if (meta == null || name == null || name.isEmpty()) {
            return;
        }
        String parsedName = Text.parseColors(name.replace('_', ' '));
        Component component = LegacyComponentSerializer.legacySection()
                .deserialize(parsedName)
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(component);
    }

    public static String formatName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String[] words = name.toLowerCase().replace('_', ' ').split(" ");
        StringBuilder formattedName = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                formattedName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return formattedName.toString().trim();
    }

    public static Map<Enchantment, Integer> getEnchantmentsFromParts(String[] parts) {
        Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        if (parts.length <= 1) {
            return enchantments;
        }

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];

            // Skip known non-enchantment meta parts
            if (part.contains(":")) {
                String key = part.substring(0, part.indexOf(':')).toLowerCase();
                if (key.equals("name") || key.equals("owner") || key.equals("base64") || key.equals("hdb") || key.equals("effect") || key.equals("duration") || key.equals("amplifier")) {
                    continue;
                }
            }

            String[] enchantParts = part.split(":");
            String enchantName = enchantParts[0].toLowerCase();
            Enchantment enchantment = null;

            // Support namespaced keys like 'minecraft:sharpness' or 'nova_structures:custom_enchant'
            if (enchantName.contains(":")) {
                // Directly support the nova_structures namespace
                String[] namespaceParts = enchantName.split(":", 2);
                if (namespaceParts.length == 2 && namespaceParts[0].equals("nova_structures")) {
                    enchantment = Enchantment.getByKey(new NamespacedKey("nova_structures", namespaceParts[1]));
                } else {
                    enchantment = Enchantment.getByKey(NamespacedKey.fromString(enchantName));
                }
            } else {
                // Try minecraft namespace first
                enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantName));
                
                // If not found, try nova_structures namespace as a fallback
                if (enchantment == null) {
                    enchantment = Enchantment.getByKey(new NamespacedKey("nova_structures", enchantName));
                }
                
                // Try EcoEnchants if available
                if (enchantment == null) {
                    enchantment = getEcoEnchant(enchantName);
                }
                
                // Try AdvancedEnchantments if available
                if (enchantment == null) {
                    enchantment = getAdvancedEnchant(enchantName);
                }
            }

            if (enchantment != null) {
                int level;
                if (enchantParts.length > 1) {
                    try {
                        level = Integer.parseInt(enchantParts[1]);
                    } catch (NumberFormatException e) {
                        continue; // Skip if level is not a valid number
                    }
                } else {
                    level = enchantment.getMaxLevel(); // Default to max level
                }
                enchantments.put(enchantment, level);
            }
        }
        return enchantments;
    }
    
    /**
     * Attempts to get an enchantment from EcoEnchants using reflection
     * @param enchantName The name of the enchantment
     * @return The Bukkit enchantment or null if not found
     */
    private static Enchantment getEcoEnchant(String enchantName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EcoEnchants")) {
            return null;
        }
        
        try {
            // Get the EcoEnchants class
            Class<?> ecoEnchantsClass = Class.forName("com.willfp.ecoenchants.enchantments.EcoEnchants");
            
            // Get the getByKey method
            Method getByKeyMethod = ecoEnchantsClass.getMethod("getByKey", String.class);
            
            // Call the method to get the EcoEnchant
            Object ecoEnchant = getByKeyMethod.invoke(null, enchantName);
            
            if (ecoEnchant != null) {
                // Get the getEnchantment method
                Method getEnchantmentMethod = ecoEnchant.getClass().getMethod("getEnchantment");
                
                // Call the method to get the Bukkit enchantment
                return (Enchantment) getEnchantmentMethod.invoke(ecoEnchant);
            }
        } catch (Exception e) {
            // Log the error but don't crash
            Bukkit.getLogger().log(Level.WARNING, "Error getting EcoEnchant: " + enchantName, e);
        }
        
        return null;
    }
    
    /**
     * Attempts to get an enchantment from AdvancedEnchantments using reflection
     * @param enchantName The name of the enchantment
     * @return The Bukkit enchantment or null if not found
     */
    private static Enchantment getAdvancedEnchant(String enchantName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            return null;
        }
        
        try {
            // Get the AdvancedEnchantment class
            Class<?> advEnchantClass = Class.forName("me.egg82.ae.api.AdvancedEnchantment");
            
            // Get the getByName method
            Method getByNameMethod = advEnchantClass.getMethod("getByName", String.class);
            
            // Call the method to get the Optional<AdvancedEnchantment>
            Object optionalAdvEnchant = getByNameMethod.invoke(null, enchantName);
            
            // Check if the Optional is present
            Method isPresentMethod = optionalAdvEnchant.getClass().getMethod("isPresent");
            boolean isPresent = (boolean) isPresentMethod.invoke(optionalAdvEnchant);
            
            if (isPresent) {
                // Get the value from the Optional
                Method getMethod = optionalAdvEnchant.getClass().getMethod("get");
                Object advEnchant = getMethod.invoke(optionalAdvEnchant);
                
                // Get the toBukkit method
                Method toBukkitMethod = advEnchant.getClass().getMethod("toBukkit");
                
                // Call the method to get the Bukkit enchantment
                return (Enchantment) toBukkitMethod.invoke(advEnchant);
            }
        } catch (Exception e) {
            // Log the error but don't crash
            Bukkit.getLogger().log(Level.WARNING, "Error getting AdvancedEnchantment: " + enchantName, e);
        }
        
        return null;
    }
}
