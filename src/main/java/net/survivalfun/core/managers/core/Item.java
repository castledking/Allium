package net.survivalfun.core.managers.core;

import net.survivalfun.core.PluginStart;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class Item {

    private static final List<Material> giveableItems = new ArrayList<>();
    private static PluginStart plugin;

    public static void initialize(PluginStart pluginInstance) {
        plugin = pluginInstance;
        // Clear the list in case this is a reload
        giveableItems.clear();
        
        // Iterate through all Material enum values
        for (Material material : Material.values()) {
            // Skip legacy materials as they're handled separately
            if (material.isLegacy() || material.name().startsWith("LEGACY_")) {
                continue;
            }
            
            // Skip air and other special materials
            if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                continue;
            }
            
            // Try to create an ItemStack
            try {
                new ItemStack(material, 1);
                // If successful, add to the cache
                giveableItems.add(material);
            } catch (IllegalArgumentException e) {
            }
        }
        
        if (plugin != null) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "Initialized " + giveableItems.size() + " giveable items");
            }
        }
    }

    public static List<Material> getGiveableItems() {
        return giveableItems;
    }

    public static boolean isGiveable(Material material) {
        if (material == null) {
            return false;
        }

        // First check if the material is in our giveable items list
        if (giveableItems.contains(material)) {
            return true;
        }

        // If not in the list, try to create a test ItemStack
        try {
            new ItemStack(material, 1);
            // If successful, add to the cache and return true
            giveableItems.add(material);
            return true;
        } catch (IllegalArgumentException e) {
            // Material cannot be used to create ItemStacks
            return false;
        }
    }
}