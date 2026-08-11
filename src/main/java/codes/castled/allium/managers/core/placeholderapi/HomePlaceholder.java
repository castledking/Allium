package codes.castled.allium.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.managers.core.HomeLimits;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class HomePlaceholder extends PlaceholderExpansion {
    /** Optional prefix on the documented spellings, e.g. %allium_home_1_x%. */
    private static final String HOME_PREFIX = "home_";

    /** Suffixes that mark a parameter as a home lookup rather than some other expansion's. */
    private static final Set<String> HOME_FIELDS =
            Set.of("w", "world", "x", "y", "z", "yaw", "pitch", "location");

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

    /**
     * Resolves the home placeholders.
     *
     * <p>Returns null for anything that is not a home placeholder: this runs as one delegate of the
     * master %allium_% expansion, and a non-null answer stops it from trying the delegates that
     * come after this one.
     */
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }

        UUID playerUUID = player.getUniqueId();

        try {
            // Both spellings are in circulation, and neither is worth breaking.
            if (params.equalsIgnoreCase("homes_max") || params.equalsIgnoreCase("home_max")) {
                return HomeLimits.format(HomeLimits.getMaxHomes(database, player));
            }
            if (params.equalsIgnoreCase("homes_set") || params.equalsIgnoreCase("home_set")) {
                return String.valueOf(database.getPlayerHomeCount(playerUUID));
            }

            // %allium_<home>_<field>% and %allium_home_<home>_<field>%, where <home> is either a
            // 1-based index or a home name.
            int split = params.lastIndexOf('_');
            if (split <= 0 || split == params.length() - 1) {
                return null;
            }
            String field = params.substring(split + 1).toLowerCase();
            if (!HOME_FIELDS.contains(field)) {
                return null;
            }

            String target = params.substring(0, split);
            String value = resolveHomeField(playerUUID, target, field);
            if (value.isEmpty() && target.regionMatches(true, 0, HOME_PREFIX, 0, HOME_PREFIX.length())) {
                value = resolveHomeField(playerUUID, target.substring(HOME_PREFIX.length()), field);
            }
            return value;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error processing home placeholder '" + params + "': " + e.getMessage());
            return "";
        }
    }

    private String resolveHomeField(UUID playerUUID, String target, String field) {
        if (target.isEmpty()) {
            return "";
        }

        String homeName = isNumeric(target)
                ? getHomeNameByIndex(playerUUID, Integer.parseInt(target) - 1)
                : target;
        if (homeName == null) {
            return "";
        }

        return field.equals("location")
                ? getFormattedLocation(playerUUID, homeName)
                : getHomeCoordinate(playerUUID, homeName, field);
    }

    private String getHomeNameByIndex(UUID playerUUID, int index) {
        List<String> homes = database.getPlayerHomes(playerUUID);
        if (homes == null || index < 0 || index >= homes.size()) {
            return null;
        }
        return homes.get(index);
    }

    private String getHomeCoordinate(UUID playerUUID, String homeName, String coordinate) {
        Location location = database.getPlayerHome(playerUUID, homeName);
        if (location == null) {
            return "";
        }

        return switch (coordinate.toLowerCase()) {
            case "w", "world" -> location.getWorld() != null ? location.getWorld().getName() : "";
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

    private boolean isNumeric(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
