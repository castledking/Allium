package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.listeners.security.SpectatorTeleport;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.Map;

public class LocationPlaceholder extends PlaceholderExpansion {
    
    private final PluginStart plugin;
    private final SpectatorTeleport spectatorTeleport;

    public LocationPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        this.spectatorTeleport = plugin.getSpectatorTeleport();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
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
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Get the database instance from the plugin
        Database database = plugin.getDatabase();
        if (database == null) {
            return "Database not available";
        }

        if (params.equalsIgnoreCase("spawn_location")) {
            // Get the server's spawn location from the database
            Map<String, Object> locationData = database.getSpawnLocation();
            
            if (locationData == null || locationData.isEmpty()) {
                return "No spawn location set";
            }

            try {
                // Convert the location data to a Location object
                World world = plugin.getServer().getWorld((String) locationData.get("world"));
                if (world == null) {
                    return "Invalid spawn world";
                }
                
                double x = ((Number) locationData.get("x")).doubleValue();
                double y = ((Number) locationData.get("y")).doubleValue();
                double z = ((Number) locationData.get("z")).doubleValue();
                float yaw = ((Number) locationData.get("yaw")).floatValue();
                float pitch = ((Number) locationData.get("pitch")).floatValue();
                
                Location location = new Location(world, x, y, z, yaw, pitch);
                return formatLocation(location);
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error formatting spawn location: " + e.getMessage());
                return "Error getting spawn location";
            }
        }
        
        if (player == null) {
            return null;
        }

        if (params.equalsIgnoreCase("spectator_location")) {
            if (spectatorTeleport == null) {
                return "Spectator teleport not available";
            }

            // Get the saved survival location for the player
            Location location = spectatorTeleport.getSurvivalLocations().get(player.getUniqueId());
            
            if (location == null) {
                return "No saved location found";
            }

            return formatLocation(location);
        } 
        else if (params.equalsIgnoreCase("logout_location")) {
            // Get the player's logout location from the database
            Location location = database.getPlayerLocation(player.getUniqueId(), Database.LocationType.LOGOUT);
            
            if (location == null) {
                return "No logout location found";
            }

            return formatLocation(location);
        }

        return null;
    }

    /**
     * Formats a location into a readable string
     * @param location The location to format
     * @return Formatted location string: world,x.xx,y.yy,z.zz,yaw.yy,pitch.pp
     */
    private String formatLocation(Location location) {
        return String.format(
            "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch()
        );
    }
}
