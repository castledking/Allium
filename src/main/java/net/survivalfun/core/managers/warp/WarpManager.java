package net.survivalfun.core.managers.warp;

import net.survivalfun.core.PluginStart;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

public class WarpManager {
    private final PluginStart plugin;
    private final Database database;
    private final Lang lang;

    public WarpManager(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.lang = plugin.getLangManager();
    }

    /**
     * Creates or updates a warp
     */
    public boolean setWarp(String warpName, Location location, Player creator) {
        return database.setWarp(warpName, location, creator.getUniqueId(), creator.getName());
    }

    public boolean validateWarpName(String warpName) {
        return warpName.length() >= 2 && !warpName.matches("\\d+");
    }

    /**
     * Gets a warp location
     */
    public Location getWarp(String warpName) {
        return database.getWarp(warpName);
    }

    /**
     * Gets warp information
     */
    public Map<String, Object> getWarpInfo(String warpName) {
        return database.getWarpInfo(warpName);
    }

    /**
     * Gets all warp names
     */
    public List<String> getAllWarpNames() {
        return database.getAllWarpNames();
    }

    /**
     * Gets all warp information
     */
    public List<Map<String, Object>> getAllWarps() {
        return database.getAllWarps();
    }

    /**
     * Checks if a warp exists
     */
    public boolean warpExists(String warpName) {
        return database.warpExists(warpName);
    }

    /**
     * Deletes a warp
     */
    public boolean deleteWarp(String warpName) {
        return database.deleteWarp(warpName);
    }

    /**
     * Gets warps that a player has permission to access
     */
    public List<String> getAccessibleWarps(CommandSender sender) {
        List<String> allWarps = getAllWarpNames();
        List<String> accessibleWarps = new ArrayList<>();

        for (String warpName : allWarps) {
            if (hasWarpPermission(sender, warpName)) {
                accessibleWarps.add(warpName);
            }
        }

        return accessibleWarps;
    }

    /**
     * Checks if a player has permission to access a specific warp
     */
    public boolean hasWarpPermission(CommandSender sender, String warpName) {
        // Console always has access
        if (!(sender instanceof Player)) {
            return true;
        }

        // Check for wildcard permission
        if (sender.hasPermission("allium.warps.*")) {
            return true;
        }

        // Check for specific warp permission
        return sender.hasPermission("allium.warps." + warpName.toLowerCase());
    }

    /**
     * Checks if a player can overwrite a specific warp
     */
    public boolean canOverwriteWarp(CommandSender sender, String warpName) {
        // Console always has access
        if (!(sender instanceof Player)) {
            return true;
        }

        // Check for wildcard overwrite permission
        if (sender.hasPermission("allium.warp.overwrite.*")) {
            return true;
        }

        // Check for specific warp overwrite permission
        return sender.hasPermission("allium.warp.overwrite." + warpName.toLowerCase());
    }

    /**
     * Teleports a player to a warp
     */
    public boolean teleportToWarp(Player player, String warpName) {
        Location warpLocation = getWarp(warpName);
        if (warpLocation == null) {
            return false;
        }

        try {
            // Use async teleport to comply with region threading requirements on Paper
            player.teleportAsync(warpLocation).whenComplete((success, throwable) -> {
                if (throwable != null) {
                    Text.sendDebugLog(WARN, "Failed to teleport " + player.getName() + " to warp " + warpName + ": " + throwable.getMessage());
                    return;
                }
                if (success == null || !success) {
                    Text.sendDebugLog(WARN, "Teleport to warp '" + warpName + "' for " + player.getName() + " did not succeed.");
                }
            });
            // Return true to indicate the teleport was initiated
            return true;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to initiate async teleport for " + player.getName() + " to warp " + warpName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Formats warp list for display
     */
    public String formatWarpList(List<String> warps, CommandSender sender) {
        // Default to page 1 with 10 entries per page
        return formatWarpList(warps, sender, 1, 10);
    }

    /**
     * Formats warp list for display (inline, paginated)
     */
    public String formatWarpList(List<String> warps, CommandSender sender, int page, int perPage) {
        if (warps == null || warps.isEmpty()) {
            return "";
        }

        // Sort alphabetically for consistent paging
        List<String> sorted = new ArrayList<>(warps);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);

        // Build comma-separated lines that wrap by character length
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // Choose a conservative width that fits Minecraft chat without client-side wrapping
        final int maxCharsPerLine = 72; // keeps full warp names intact before wrapping

        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i);
            String toAppend = (current.length() == 0 ? "" : ", ") + name;
            if (current.length() + toAppend.length() > maxCharsPerLine) {
                // commit current line and start new
                lines.add(current.toString());
                current.setLength(0);
                current.append(name);
            } else {
                current.append(toAppend);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }

        // perPage here represents max number of lines per page (default 10)
        if (perPage <= 0) perPage = 10;
        int totalPages = (int) Math.ceil(lines.size() / (double) perPage);
        if (totalPages <= 0) totalPages = 1;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int startLine = (page - 1) * perPage;
        int endLine = Math.min(startLine + perPage, lines.size());
        List<String> slice = lines.subList(startLine, endLine);

        StringBuilder sb = new StringBuilder();
        // Header styled like Help menu
        sb.append(Text.parseColors("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆")).append("\n");
        sb.append(Text.parseColors("&6&lWarp List &7- &ePage " + page + "/" + totalPages)).append("\n");
        // Show total known warps count before the list for each page
        sb.append(Text.parseColors("&7" + sorted.size() + " known warps:")).append("\n\n");

        // Body: 1 line per wrapped set, no leading dash
        for (String line : slice) {
            sb.append(Text.parseColors("&e" + line)).append("\n");
        }

        return sb.toString();
    }

    /**
     * Calculates the total number of pages for the given warp list using the same
     * wrapping and pagination rules as formatWarpList().
     * perPage represents the number of wrapped lines per page.
     */
    public int getWarpListTotalPages(List<String> warps, int perPage) {
        if (warps == null || warps.isEmpty()) {
            return 1;
        }
        // Sort alphabetically for consistent paging
        List<String> sorted = new ArrayList<>(warps);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);

        // Build wrapped lines by character length just like formatWarpList
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // keep in sync with formatWarpList
        final int maxCharsPerLine = 72;

        for (String name : sorted) {
            String toAppend = (current.length() == 0 ? "" : ", ") + name;
            if (current.length() + toAppend.length() > maxCharsPerLine) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(name);
            } else {
                current.append(toAppend);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }

        if (perPage <= 0) perPage = 10;
        int totalPages = (int) Math.ceil(lines.size() / (double) perPage);
        return Math.max(totalPages, 1);
    }

    /**
     * Formats warp information for display
     */
    public String formatWarpInfo(Map<String, Object> warpInfo) {
        if (warpInfo == null) {
            return lang.get("warp.not-found");
        }

        StringBuilder sb = new StringBuilder();
        // Top header styled similarly to the warp list
        sb.append(Text.parseColors("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆")).append("\n");
        // Title line – keep using lang header for localization
        sb.append(lang.get("warp.info-header")
                .replace("{warp}", (String) warpInfo.get("name")))
          .append("\n\n");

        sb.append(lang.get("warp.info-location")
                .replace("{world}", (String) warpInfo.get("world"))
                .replace("{x}", String.format("%.1f", (Double) warpInfo.get("x")))
                .replace("{y}", String.format("%.1f", (Double) warpInfo.get("y")))
                .replace("{z}", String.format("%.1f", (Double) warpInfo.get("z"))))
          .append("\n");
        sb.append(lang.get("warp.info-creator")
                .replace("{creator}", (String) warpInfo.get("creator_name")))
          .append("\n");
        sb.append(lang.get("warp.info-created")
                .replace("{created}", warpInfo.get("created_at").toString()))
          .append("\n");

        // Footer line
        sb.append(Text.parseColors("&8&m᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆᠆")).append("\n");

        return sb.toString();
    }
}