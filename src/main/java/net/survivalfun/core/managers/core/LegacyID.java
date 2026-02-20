package net.survivalfun.core.managers.core;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class LegacyID {
    private static final Map<String, Material> LEGACY_IDS = new HashMap<>();
    private static final Map<Material, String> MATERIAL_TO_LEGACY = new HashMap<>();
    private static final Map<String, String> LEGACY_EXTRA_DATA = new HashMap<>();
    private static JavaPlugin plugin;
    private static boolean initialized = false;
    private static final String COMPRESSED_RESOURCE = "itemdb.yml.gz";


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
        LEGACY_EXTRA_DATA.clear();

        if (plugin == null) {
            return;
        }

        File configFile = new File(plugin.getDataFolder(), "itemdb.yml");

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            if (!configFile.exists()) {
                try {
                    InputStream compressedStream = CompressedYamlLoader.getCompressedResourceStream(COMPRESSED_RESOURCE);
                    if (compressedStream != null) {
                        CompressedYamlLoader.extractCompressedResource(COMPRESSED_RESOURCE, configFile);
                        Text.sendDebugLog(INFO, "Created itemdb.yml from compressed resource for legacy IDs");
                    } else {
                        plugin.saveResource("itemdb.yml", false);
                        Text.sendDebugLog(INFO, "Created default itemdb.yml configuration file");
                    }
                } catch (Exception e) {
                    Text.sendDebugLog(WARN, "LegacyIDs are disabled! Could not create itemdb.yml: " + e.getMessage());
                    return;
                }
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

            if (config.contains("legacy-ids")) {
                loadLegacyIdsFromSection(config, "legacy-ids");
            }

            if (config.contains("aliases")) {
                loadLegacyIdsFromAliases(config);
            }

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error loading itemdb.yml: " + e.getMessage());
        }
    }

    /**
     * Load legacy IDs from a specific section
     */
    private static void loadLegacyIdsFromSection(YamlConfiguration config, String sectionName) {
        // Get all keys from the section, handling both string and numeric keys
        Set<String> keys = config.getConfigurationSection(sectionName).getKeys(false);
        int loadedCount = 0;

        for (String legacyId : keys) {
            String materialName = null;

            try {
                // Try to get the material name using the string key first
                materialName = config.getString(sectionName + "." + legacyId);

                // If that fails, try treating the key as a numeric value
                if (materialName == null) {
                    try {
                        int numericKey = Integer.parseInt(legacyId);
                        materialName = config.getString(sectionName + "." + numericKey);
                    } catch (NumberFormatException ignored) {
                        // Key is not numeric, continue with string key
                    }
                }

                if (materialName != null) {
                    Material material = resolveBaseMaterial(materialName);

                    if (material != null) {
                        addLegacyId(legacyId, material);
                        loadedCount++;
                        if (materialName.contains(";")) {
                            LEGACY_EXTRA_DATA.put(legacyId, materialName.substring(materialName.indexOf(';') + 1));
                        }
                    } else {
                        Text.sendDebugLog(WARN, "Invalid material '" + materialName + "' for legacy ID '" + legacyId + "' in itemdb.yml");
                    }
                } else {
                    Text.sendDebugLog(WARN, "Could not find material for legacy ID '" + legacyId + "' in itemdb.yml");
                }
            } catch (IllegalArgumentException e) {
                Text.sendDebugLog(WARN, "Invalid material '" + materialName + "' for legacy ID '" + legacyId + "' in itemdb.yml");
            }
        }

        Text.sendDebugLog(INFO, "Loaded " + loadedCount + " legacy ID mappings from " + sectionName + " section");
    }

    /**
     * Load legacy IDs from aliases section for backward compatibility
     */
    private static void loadLegacyIdsFromAliases(YamlConfiguration config) {
        Set<String> keys = config.getConfigurationSection("aliases").getKeys(false);
        int loadedCount = 0;

        for (String alias : keys) {
            String materialName = config.getString("aliases." + alias);

            if (materialName != null) {
                // Check if this alias is a simple material name (not containing semicolons or other parameters)
                if (!materialName.contains(";") && !materialName.contains(":")) {
                    try {
                        Material material = Material.valueOf(materialName.toUpperCase());

                        // Only add as legacy ID if the alias name looks like a number
                        try {
                            int numericId = Integer.parseInt(alias);
                            addLegacyId(alias, material);
                            loadedCount++;
                        } catch (NumberFormatException ignored) {
                            // Alias name is not numeric, skip it
                        }
                    } catch (IllegalArgumentException e) {
                        // Material name is invalid, skip it
                    }
                }
            }
        }

        if (loadedCount > 0) {
            Text.sendDebugLog(INFO, "Loaded " + loadedCount + " legacy ID mappings from aliases section (backward compatibility)");
        }
    }

    /**
     * Reload legacy IDs from configuration file
     */
    public static void reload() {
        if (plugin != null) {
            loadLegacyIds();
            Text.sendDebugLog(INFO, "Reloaded legacy ID mappings from itemdb.yml");
        }
    }

    /**
     * Add runtime legacy ID mapping
     */
    public static void addLegacyId(String legacyId, Material material) {
        LEGACY_IDS.put(legacyId, material);
        MATERIAL_TO_LEGACY.put(material, legacyId);
        LEGACY_EXTRA_DATA.remove(legacyId);
    }

    /**
     * Remove legacy ID mapping
     */
    public static void removeLegacyId(String legacyId) {
        Material material = LEGACY_IDS.remove(legacyId);
        if (material != null) {
            MATERIAL_TO_LEGACY.remove(material);
        }
        LEGACY_EXTRA_DATA.remove(legacyId);
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
        // Try direct lookup first
        Material material = LEGACY_IDS.get(legacyId);
        if (material != null) {
            return material;
        }

        // If not found and the legacyId looks like a number, try parsing it as an integer
        // This handles cases where someone types "443" but the key was stored as an integer
        try {
            int numericId = Integer.parseInt(legacyId);
            return LEGACY_IDS.get(String.valueOf(numericId));
        } catch (NumberFormatException ignored) {
            // Not a number, return null
        }

        return null;
    }

    private static Material resolveBaseMaterial(String entry) {
        String upper = entry.toUpperCase();

        try {
            return Material.valueOf(upper);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }

        if (upper.contains(";")) {
            String base = upper.split(";", 2)[0];
            try {
                return Material.valueOf(base);
            } catch (IllegalArgumentException ignored) {
                Material match = Material.matchMaterial(base);
                if (match != null) {
                    return match;
                }
            }
        }

        return Material.matchMaterial(entry);
    }

    public static String getLegacyIdFromMaterial(Material material) {
        return MATERIAL_TO_LEGACY.get(material);
    }

    public static String getExtraDataForLegacyId(String legacyId) {
        return LEGACY_EXTRA_DATA.get(legacyId);
    }
}