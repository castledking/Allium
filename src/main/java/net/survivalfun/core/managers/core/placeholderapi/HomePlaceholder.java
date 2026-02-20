package net.survivalfun.core.managers.core.placeholderapi;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.List;
import java.util.UUID;

public class HomePlaceholder extends PlaceholderExpansion {
    private final PluginStart plugin;
    private final Database database;

    public HomePlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium_home";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Towkio";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        UUID playerUUID = player.getUniqueId();
        String[] args = params.split("_");

        try {
            // Handle numeric index home placeholders: %allium_home_1_x%, %allium_home_2_y%, etc.
            if (args.length >= 2 && isNumeric(args[0])) {
                int homeIndex = Integer.parseInt(args[0]) - 1; // Convert to 0-based index
                String coordinate = args[1].toLowerCase();
                return getHomeCoordinateByIndex(playerUUID, homeIndex, coordinate);
            }
            // Handle named home placeholders: %allium_home_home_x%, %allium_home_myhome_y%, etc.
            else if (args.length >= 2) {
                // Reconstruct the home name by joining with underscores
                String homeName = args[0];
                String coordinate = args[1].toLowerCase();
                
                // If there are more parts, it's part of the home name (e.g., "my_home")
                if (args.length > 2) {
                    homeName = String.join("_", args).replace("_" + coordinate, "");
                }
                
                return getHomeCoordinateByName(playerUUID, homeName, coordinate);
            }
            // Handle max homes and homes set
            else if (params.equalsIgnoreCase("homes_max")) {
                return String.valueOf(getMaxHomes(player));
            } else if (params.equalsIgnoreCase("homes_set")) {
                return String.valueOf(database.getPlayerHomeCount(playerUUID));
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error processing home placeholder: " + e.getMessage());
        }

        return "";
    }

    private String getHomeCoordinateByIndex(UUID playerUUID, int index, String coordinate) {
        List<String> homes = database.getPlayerHomes(playerUUID);
        if (index < 0 || index >= homes.size()) {
            return "";
        }
        String homeName = homes.get(index);
        return getHomeCoordinate(playerUUID, homeName, coordinate);
    }

    private String getHomeCoordinateByName(UUID playerUUID, String homeName, String coordinate) {
        // Check if this is a location request (e.g., %allium_home_1_location%)
        if (coordinate.equals("location")) {
            return getFormattedLocation(playerUUID, homeName);
        }
        return getHomeCoordinate(playerUUID, homeName, coordinate);
    }

    private String getHomeCoordinate(UUID playerUUID, String homeName, String coordinate) {
        Location location = database.getPlayerHome(playerUUID, homeName);
        if (location == null) {
            return "";
        }

        return switch (coordinate.toLowerCase()) {
            case "w" -> location.getWorld() != null ? location.getWorld().getName() : "";
            case "x" -> String.format("%.2f", location.getX());
            case "y" -> String.format("%.2f", location.getY());
            case "z" -> String.format("%.2f", location.getZ());
            case "yaw" -> String.format("%.1f", location.getYaw());
            case "pitch" -> String.format("%.1f", location.getPitch());
            default -> "";
        };
    }

    private String getFormattedLocation(UUID playerUUID, String homeName) {
        Location location = database.getPlayerHome(playerUUID, homeName);
        if (location == null) {
            return "";
        }
        
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";
        return String.format("%s, %.1f, %.1f, %.1f, %.1f, %.1f",
                worldName,
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    private int getMaxHomes(OfflinePlayer player) {
        if (!player.isOnline()) {
            return 0; // Offline players can't have homes
        }
        
        // Check for specific home permissions (allium.sethome.1, allium.sethome.2, etc.)
        for (int i = 100; i > 0; i--) {
            if (player.getPlayer() != null && player.getPlayer().hasPermission("allium.sethome." + i)) {
                return i;
            }
        }
        
        // Default to 1 if no specific permission is found
        return 1;
    }

    private boolean isNumeric(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
