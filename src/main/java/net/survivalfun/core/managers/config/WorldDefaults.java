package net.survivalfun.core.managers.config;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

public record WorldDefaults(JavaPlugin plugin) {

    /**
     * Constructor for the WorldDefaults manager.
     *
     * @param plugin The plugin instance
     */
    public WorldDefaults {
    }

    /**
     * Applies all world defaults from the config.yml to all worlds.
     */
    public void applyWorldDefaults() {
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection worldDefaultsSection = config.getConfigurationSection("world-defaults");

        if (worldDefaultsSection == null) {
            plugin.getLogger().info("No world-defaults section found in config.yml.");
            return;
        }

        Set<String> worldKeys = worldDefaultsSection.getKeys(false); // Get direct children (world names)

        for (String worldKey : worldKeys) {
            World world = Bukkit.getWorld(worldKey);
            if (world == null) {
                plugin.getLogger().warning("World '" + worldKey + "' not found.");
                continue;
            }

            ConfigurationSection worldSection = worldDefaultsSection.getConfigurationSection(worldKey);
            if (worldSection == null) {
                plugin.getLogger().warning("No configuration section found for world '" + worldKey + "'.");
                continue;
            }

            for (String key : worldSection.getKeys(false)) {
                String configPath = "world-defaults." + worldKey + "." + key;
                if (key.equalsIgnoreCase("send-command-feedback")) {
                    boolean value = config.getBoolean(configPath);
                    world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, value);
                } else if (key.equalsIgnoreCase("spawn-radius")) {
                    int value = config.getInt(configPath);
                    world.setGameRule(GameRule.SPAWN_RADIUS, value);
                } else {
                    plugin.getLogger().warning("Unknown world default '" + key + "' in world '" + worldKey + "'.");
                }
            }
        }
    }

    /**
     * Gets the plugin instance.
     *
     * @return The plugin instance.
     */
    @Override
    public JavaPlugin plugin() {
        return plugin;
    }
}