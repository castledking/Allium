package net.survivalfun.core.managers;

import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class ResourcePackManager implements Listener {

    private static final String CONFIG_FILE = "resourcepack.yml";
    private static ResourcePackManager instance;
    private final JavaPlugin plugin;
    private File configFile;
    private FileConfiguration config;
    private boolean enabled = false;
    private String resourcePackUrl = "";
    private String resourcePackHash = "";
    private boolean forcePack = false;
    private boolean combinePacks = true;

    private String previousUrl = "";
    private String previousHash = "";

    public ResourcePackManager(JavaPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        initialize();
    }

    private void initialize() {
        File pluginFolder = plugin.getDataFolder();
        if (!pluginFolder.exists() && !pluginFolder.mkdirs()) {
            Text.sendDebugLog(WARN, "Could not create plugin folder for resource pack manager");
            return;
        }

        configFile = new File(pluginFolder, CONFIG_FILE);
        loadConfig();
        
        if (enabled && !resourcePackUrl.isEmpty()) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            Text.sendDebugLog(INFO, "Resource pack manager initialized with URL: " + resourcePackUrl);
        } else {
            Text.sendDebugLog(INFO, "Resource pack is not configured (no URL set)");
        }
    }

    private void loadConfig() {
        if (!configFile.exists()) {
            createDefaultConfig();
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        
        previousUrl = resourcePackUrl;
        previousHash = resourcePackHash;
        
        resourcePackUrl = config.getString("url", "");
        resourcePackHash = config.getString("hash", "");
        forcePack = config.getBoolean("force", false);
        combinePacks = config.getBoolean("combine", true);

        enabled = config.getBoolean("enabled", false);
    }

    private void createDefaultConfig() {
        try {
            InputStream templateStream = plugin.getResource(CONFIG_FILE);
            if (templateStream != null) {
                java.nio.file.Files.copy(templateStream, configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                templateStream.close();
                Text.sendDebugLog(INFO, "Created resourcepack.yml from template");
            } else {
                config = new YamlConfiguration();
                config.set("enabled", false);
                config.set("url", "");
                config.set("hash", "");
                config.set("force", false);
                config.set("combine", true);
                config.save(configFile);
            }
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to create resourcepack.yml", e);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled || resourcePackUrl.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        
        try {
            if (combinePacks) {
                player.setResourcePack(resourcePackUrl, resourcePackHash.isEmpty() ? null : resourcePackHash, forcePack, null);
            } else {
                player.setResourcePack(resourcePackUrl, resourcePackHash.isEmpty() ? null : resourcePackHash);
            }
            Text.sendDebugLog(INFO, "Sent resource pack to " + player.getName());
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to send resource pack to " + player.getName(), e);
        }
    }

    public static ResourcePackManager getInstance() {
        return instance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getResourcePackUrl() {
        return resourcePackUrl;
    }

    public void reload() {
        loadConfig();
    }

    public boolean hasPackChanged() {
        return !resourcePackUrl.equals(previousUrl) || !resourcePackHash.equals(previousHash);
    }

    public void refreshAllPlayers() {
        if (!enabled || resourcePackUrl.isEmpty()) {
            Text.sendDebugLog(WARN, "Cannot refresh players: resource pack not enabled or URL empty");
            return;
        }

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (combinePacks) {
                    player.setResourcePack(resourcePackUrl, resourcePackHash.isEmpty() ? null : resourcePackHash, forcePack, null);
                } else {
                    player.setResourcePack(resourcePackUrl, resourcePackHash.isEmpty() ? null : resourcePackHash);
                }
                count++;
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to send resource pack to " + player.getName(), e);
            }
        }
        Text.sendDebugLog(INFO, "Refreshed resource pack for " + count + " players");
    }
}
