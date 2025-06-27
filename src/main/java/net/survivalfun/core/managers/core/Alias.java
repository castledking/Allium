package net.survivalfun.core.managers.core;

import net.survivalfun.core.PluginStart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class Alias {

    private static final Map<String, String> ALIASES = new HashMap<>();
    private static PluginStart plugin;
    private static File aliasFile;
    private static FileConfiguration aliasConfig;
    private static boolean initialized = false;

    /**
     * Initialize the alias system with the plugin instance
     * @param pluginInstance The main plugin instance
     */
    public static void initialize(PluginStart pluginInstance) {
        if (initialized) return;
        
        plugin = pluginInstance;
        setupAliasFile();
        loadAliases();
        initialized = true;
    }

    /**
     * Setup the itemdb.yml file - create it if it doesn't exist
     */
    private static void setupAliasFile() {
        aliasFile = new File(plugin.getDataFolder(), "itemdb.yml");
        
        if (!aliasFile.exists()) {
            try {
                aliasFile.getParentFile().mkdirs();
                plugin.saveResource("itemdb.yml", false);
                plugin.getLogger().info("Created itemdb.yml configuration file");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create itemdb.yml", e);
                return;
            }
        }
        
        aliasConfig = YamlConfiguration.loadConfiguration(aliasFile);
    }

    /**
     * Load aliases from the configuration file
     */
    private static void loadAliases() {
        ALIASES.clear();
        
        if (aliasConfig != null && aliasConfig.getConfigurationSection("aliases") != null) {
            for (String alias : aliasConfig.getConfigurationSection("aliases").getKeys(false)) {
                String material = aliasConfig.getString("aliases." + alias);
                if (material != null) {
                    ALIASES.put(alias.toLowerCase(), material.toUpperCase());
                }
            }
            plugin.getLogger().info("Loaded " + ALIASES.size() + " material aliases from itemdb.yml");
        } else {
            plugin.getLogger().warning("[itemdb.yml] Aliases are disabled!");
        }
    }

    /**
     * Get the alias for a given material name
     * @param alias The material name
     * @return The alias for the material
     */
    public static String getAlias(String alias) {
        return ALIASES.get(alias.toLowerCase());
    }
    
    /**
     * Get the material part from a complex alias that may contain semicolons
     * @param alias The alias to look up
     * @return Just the material part of the alias
     */
    public static String getMaterialPartFromAlias(String alias) {
        String value = ALIASES.get(alias.toLowerCase());
        if (value != null && value.contains(";")) {
            // Return just the material part (before the first semicolon)
            return value.split(";", 2)[0];
        }
        return value;
    }
    
    /**
     * Get the effects/parameter part from a complex alias
     * @param alias The alias to look up
     * @return The effects/parameters part after the semicolon, or null if there are none
     */
    public static String getParametersFromAlias(String alias) {
        String value = ALIASES.get(alias.toLowerCase());
        if (value != null && value.contains(";")) {
            String[] parts = value.split(";", 2);
            return parts.length > 1 ? parts[1] : null;
        }
        return null;
    }

    /**
     * Add an alias for a given material name
     * @param alias The material name
     * @param material The material to alias
     */
    public static void addAlias(String alias, String material) {
        ALIASES.put(alias.toLowerCase(), material.toUpperCase());
    }

    /**
     * Remove an alias for a given material name
     * @param alias The material name
     */
    public static void removeAlias(String alias) {
        ALIASES.remove(alias.toLowerCase());
    }

    /**
     * Check if an alias exists for a given material name
     * @param alias The material name
     * @return True if the alias exists, false otherwise
     */
    public static boolean hasAlias(String alias) {
        return ALIASES.containsKey(alias.toLowerCase());
    }

    /**
     * Get the material name from an alias
     * @param alias The alias to look up
     * @return The material name if found, null otherwise
     */
    public static String getMaterialFromAlias(String alias) {
        return ALIASES.get(alias.toLowerCase());
    }
}