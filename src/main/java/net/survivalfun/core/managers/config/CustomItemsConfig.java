package net.survivalfun.core.managers.config;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class CustomItemsConfig {

    private static final String CONFIG_FILE = "customitems.yml";
    private static File configFile;
    private static FileConfiguration config;
    private static YamlConfiguration defaultConfig;
    private static boolean loaded = false;

    public static void initialize(JavaPlugin plugin) {
        File pluginFolder = plugin.getDataFolder();
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin folder");
        }

        configFile = new File(pluginFolder, CONFIG_FILE);
        initializeDefaultConfig(plugin);
        load();
    }

    private static void initializeDefaultConfig(JavaPlugin plugin) {
        defaultConfig = null;
        InputStream defConfigStream = plugin.getResource(CONFIG_FILE);
        if (defConfigStream == null) {
            Text.sendDebugLog(WARN, "Could not find default customitems.yml in plugin resources!");
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)) {
            defaultConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            Text.sendDebugLog(WARN, "Failed to load default customitems config", e);
        }
    }

    public static void load() {
        Text.sendDebugLog(INFO, "Loading custom items configuration...");

        if (!configFile.exists()) {
            Text.sendDebugLog(INFO, "Creating default customitems.yml...");
            createDefaultConfig();
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        if (defaultConfig != null) {
            for (String key : defaultConfig.getKeys(true)) {
                if (!config.contains(key)) {
                    Text.sendDebugLog(INFO, "Adding missing custom items config value: " + key);
                    config.set(key, defaultConfig.get(key));
                }
            }

            try {
                config.save(configFile);
            } catch (IOException e) {
                Text.sendDebugLog(ERROR, "Failed to save customitems.yml", e);
            }
        }

        loaded = true;
        Text.sendDebugLog(INFO, "Loaded " + getItemCount() + " custom items from config");
    }

    private static void createDefaultConfig() {
        try {
            InputStream templateStream = PluginStart.getInstance().getResource(CONFIG_FILE);
            if (templateStream != null) {
                java.nio.file.Files.copy(templateStream, configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                templateStream.close();
                Text.sendDebugLog(INFO, "Created customitems.yml from template");
            } else {
                config = new YamlConfiguration();
                config.set("items", new HashMap<>());
                config.save(configFile);
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to create customitems.yml", e);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static int getItemCount() {
        if (config == null || !config.contains("items")) {
            return 0;
        }
        return config.getConfigurationSection("items").getKeys(false).size();
    }

    public static Set<String> getItemIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (config != null && config.contains("items")) {
            ids.addAll(config.getConfigurationSection("items").getKeys(false));
        }
        return ids;
    }

    public static boolean hasItem(String id) {
        return config != null && config.contains("items." + id);
    }

    public static MaterialDefinition getItemDefinition(String id) {
        if (!hasItem(id)) {
            return null;
        }

        ConfigurationSection itemSection = config.getConfigurationSection("items." + id);
        if (itemSection == null) {
            return null;
        }

        String materialStr = itemSection.getString("material", "STONE");
        String name = itemSection.getString("name", "Custom Item");
        List<String> lore = itemSection.getStringList("lore");
        String textureType = itemSection.getString("texture-type", "item_model");
        
        Object itemModel = null;
        int customModelData = 0;
        
        if (textureType.equalsIgnoreCase("item_model")) {
            itemModel = itemSection.get("item_model");
        } else if (textureType.equalsIgnoreCase("custommodeldata")) {
            customModelData = itemSection.getInt("custommodeldata", 0);
        }
        
        boolean singleUse = itemSection.getBoolean("single-use", false);

        return new MaterialDefinition(id, materialStr, name, lore, textureType, itemModel, customModelData, singleUse);
    }

    public static class MaterialDefinition {
        private final String id;
        private final String material;
        private final String name;
        private final List<String> lore;
        private final String textureType;
        private final Object itemModel;
        private final int customModelData;
        private final boolean singleUse;

        public MaterialDefinition(String id, String material, String name, List<String> lore, 
                                 String textureType, Object itemModel, int customModelData, boolean singleUse) {
            this.id = id;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.textureType = textureType;
            this.itemModel = itemModel;
            this.customModelData = customModelData;
            this.singleUse = singleUse;
        }

        public String getId() { return id; }
        public String getMaterial() { return material; }
        public String getName() { return name; }
        public List<String> getLore() { return lore; }
        public String getTextureType() { return textureType; }
        public Object getItemModel() { return itemModel; }
        public int getCustomModelData() { return customModelData; }
        public boolean isSingleUse() { return singleUse; }
    }

    public static void reload() {
        loaded = false;
        load();
    }
}
