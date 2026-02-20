package net.survivalfun.core.listeners.jobs;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Barrel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Location;
import org.bukkit.World;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.Random;

/**
 * LootTableListener - Handles loot generation for dungeon chests and barrels
 * Allows custom items with CustomModelData to spawn in vanilla dungeon loot
 */
public class LootTableListener implements Listener {
    private static LootTableListener instance;
    private final Random random = new Random();

    // Configuration for loot chances (percentage 0-100)
    private static final double SILVER_SWORD_CHANCE_DUNGEON = 100.0; // 15% chance in dungeon chests
    private static final double SILVER_SWORD_CHANCE_DESERT_TEMPLE = 100.0; // 10% chance in desert temples
    private static final double SILVER_SWORD_CHANCE_JUNGLE_TEMPLE = 100.0; // 12% chance in jungle temples

    // Silver sword configuration - adjust these based on your ItemsAdder configuration
    private static final String SILVER_SWORD_MATERIAL = "IRON_SWORD"; // Base material
    private static final int SILVER_SWORD_CUSTOM_MODEL_DATA = 10005; // Your CustomModelData value

    public LootTableListener(JavaPlugin plugin) {
        instance = this;
    }

    public static LootTableListener getInstance() {
        return instance;
    }

    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        // Try to get the inventory holder which should be the chest/barrel
        if (!(event.getInventoryHolder() instanceof BlockState state)) {
            return;
        }

        Block block = state.getBlock();
        Location location = block.getLocation();
        World world = location.getWorld();

        // Only handle chests and barrels in dungeons/structures
        if (!isDungeonChestOrBarrel(state, location)) {
            return;
        }

        Text.sendDebugLog(INFO, "Processing loot generation for " + state.getType() + " at " + location);

        // Add custom items to loot
        addCustomLootToChest(event, location, world);
    }

    /**
     * Determines if a chest/barrel is in a dungeon or structure
     */
    private boolean isDungeonChestOrBarrel(BlockState state, Location location) {
        // Check if it's a chest or barrel
        if (!(state instanceof Chest || state instanceof Barrel)) {
            return false;
        }

        World world = location.getWorld();
        if (world == null) return false;

        // Get biome and structure information
        NamespacedKey biomeKey = world.getBiome(location).getKey();
        int y = location.getBlockY();

        // Check for dungeon-like structures based on location patterns
        return isDungeonLocation(location, biomeKey, y);
    }

    /**
     * Checks if location matches dungeon/structure patterns
     */
    private boolean isDungeonLocation(Location location, NamespacedKey biomeKey, int y) {
        // Zombie/Skeleton dungeons (typically Y=0 to Y=50)
        if (y >= 0 && y <= 50) {
            // Check for mossy stone bricks or cobblestone patterns around
            return hasDungeonBlocksNearby(location);
        }

        // Desert temples (desert biome, Y around 60-70)
        if (biomeKey.getKey().contains("desert") && y >= 60 && y <= 75) {
            return true;
        }

        // Jungle temples (jungle biome, Y around 60-80)
        if (biomeKey.getKey().contains("jungle") && y >= 60 && y <= 85) {
            return true;
        }

        return false;
    }

    /**
     * Checks for dungeon blocks (mossy stone bricks, cobblestone) nearby
     */
    private boolean hasDungeonBlocksNearby(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        // Check a 3x3 area around the chest for dungeon materials
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip the chest itself

                    Location checkLocation = location.clone().add(x, y, z);
                    Material material = world.getBlockAt(checkLocation).getType();

                    // Look for dungeon materials
                    if (material == Material.MOSSY_COBBLESTONE ||
                        material == Material.COBBLESTONE) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Adds custom loot items to the chest/barrel
     */
    private void addCustomLootToChest(LootGenerateEvent event, Location location, World world) {
        try {
            // Get biome for location-specific loot chances
            NamespacedKey biomeKey = world.getBiome(location).getKey();

            // Try to add silver sword based on location
            if (shouldAddSilverSword(location, biomeKey)) {
                ItemStack silverSword = createSilverSword();
                if (silverSword != null) {
                    event.getLoot().add(silverSword);
                    Text.sendDebugLog(INFO, "Added silver sword to loot at " + location);
                }
            }

            // You can add more custom items here in the future

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error adding custom loot to chest at " + location + ": " + e.getMessage());
        }
    }

    /**
     * Determines if silver sword should be added based on location and chance
     */
    private boolean shouldAddSilverSword(Location location, NamespacedKey biomeKey) {
        double chance = getSilverSwordChance(biomeKey);

        if (chance <= 0) return false;

        return random.nextDouble() * 100 < chance;
    }

    /**
     * Gets the chance percentage for silver sword based on location type
     */
    private double getSilverSwordChance(NamespacedKey biomeKey) {
        String biomeString = biomeKey.getKey();
        if (biomeString.contains("desert")) {
            return SILVER_SWORD_CHANCE_DESERT_TEMPLE;
        } else if (biomeString.contains("jungle")) {
            return SILVER_SWORD_CHANCE_JUNGLE_TEMPLE;
        } else {
            // Default dungeon chance
            return SILVER_SWORD_CHANCE_DUNGEON;
        }
    }

    /**
     * Creates a silver sword ItemStack with CustomModelData
     * This method should integrate with your ItemsAdder configuration
     */
    private ItemStack createSilverSword() {
        try {
            // Create base diamond sword
            ItemStack sword = new ItemStack(Material.valueOf(SILVER_SWORD_MATERIAL));

            // Set CustomModelData - this is the key for custom textures
            // You'll need to set this to match your ItemsAdder configuration
            sword.editMeta(meta -> {
                meta.setCustomModelData(SILVER_SWORD_CUSTOM_MODEL_DATA);
                // You can also set display name if desired
                // meta.setDisplayName("§fSilver Sword");
            });

            return sword;

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error creating silver sword: " + e.getMessage());
            return null;
        }
    }

    /**
     * Configuration methods - you can expand these for more control
     */

    public void setSilverSwordChance(String locationType, double chance) {
        // This could be expanded to use a configuration file
        Text.sendDebugLog(INFO, "Setting silver sword chance for " + locationType + " to " + chance + "%");
    }

    public void setSilverSwordCustomModelData(int customModelData) {
        // Update the CustomModelData value if needed
        Text.sendDebugLog(INFO, "Setting silver sword CustomModelData to " + customModelData);
    }
}
