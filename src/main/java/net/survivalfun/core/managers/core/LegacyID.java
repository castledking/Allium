package net.survivalfun.core.managers.core;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LegacyID {
    private static final Map<String, Material> LEGACY_IDS = new HashMap<>();
    private static final Map<Material, String> MATERIAL_TO_LEGACY = new HashMap<>();
    private static JavaPlugin plugin;
    private static boolean initialized = false;


    /**
     * Initialize the LegacyID system with plugin instance and load configuration
     * @param pluginInstance The plugin instance for file access
     */
    public static void initialize(JavaPlugin pluginInstance) {
        plugin = pluginInstance;
        loadLegacyIds();
        initialized = true;
    }

    /**
     * Load legacy IDs from itemdb.yml configuration file
     */
    private static void loadLegacyIds() {
        LEGACY_IDS.clear();
        MATERIAL_TO_LEGACY.clear();

        if (plugin == null) {
            return;
        }

        try {
            // Ensure data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            File configFile = new File(plugin.getDataFolder(), "itemdb.yml");
            
            // Create default itemdb.yml if it doesn't exist
            if (!configFile.exists()) {
                InputStream defaultConfig = plugin.getResource("itemdb.yml");
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile.toPath());
                    plugin.getLogger().info("Created default itemdb.yml configuration file");
                } else {
                    plugin.getLogger().warning("LegacyIDs are disabled!");
                    return;
                }
            }

            // Load configuration
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            
            if (config.contains("legacy-ids")) {
                Set<String> keys = config.getConfigurationSection("legacy-ids").getKeys(false);
                int loadedCount = 0;
                
                for (String legacyId : keys) {
                    String materialName = config.getString("legacy-ids." + legacyId);
                    
                    try {
                        Material material = Material.valueOf(materialName.toUpperCase());
                        addLegacyId(legacyId, material);
                        loadedCount++;
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid material '" + materialName + "' for legacy ID '" + legacyId + "' in itemdb.yml");
                    }
                }
                
                plugin.getLogger().info("Loaded " + loadedCount + " legacy ID mappings from itemdb.yml");
                
                if (loadedCount == 0) {
                    plugin.getLogger().warning("No legacy IDs loaded from itemdb.yml");
                }
            } else {
                plugin.getLogger().warning("No legacy IDs loaded from itemdb.yml");
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading itemdb.yml: " + e.getMessage());
        }
    }

    /**
     * Reload legacy IDs from configuration file
     */
    public static void reload() {
        if (plugin != null) {
            loadLegacyIds();
            plugin.getLogger().info("Reloaded legacy ID mappings from itemdb.yml");
        }
    }

    /**
     * Add runtime legacy ID mapping
     */
    public static void addLegacyId(String legacyId, Material material) {
        LEGACY_IDS.put(legacyId, material);
        MATERIAL_TO_LEGACY.put(material, legacyId);
    }

    /**
     * Remove legacy ID mapping
     */
    public static void removeLegacyId(String legacyId) {
        Material material = LEGACY_IDS.remove(legacyId);
        if (material != null) {
            MATERIAL_TO_LEGACY.remove(material);
        }
    }

    /**
     * Check if legacy ID exists
     */
    public static boolean hasLegacyId(String legacyId) {
        return LEGACY_IDS.containsKey(legacyId);
    }

    /**
     * Get all legacy IDs
     */
    public static Set<String> getAllLegacyIds() {
        return LEGACY_IDS.keySet();
    }

    /**
     * Check if the LegacyID system is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }


    public static Material getMaterialFromLegacyId(String legacyId) {
        return LEGACY_IDS.get(legacyId);
    }

    public static String getLegacyIdFromMaterial(Material material) {
        return MATERIAL_TO_LEGACY.get(material);
    }
}