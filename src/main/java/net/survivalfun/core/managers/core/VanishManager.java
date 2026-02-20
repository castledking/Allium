package net.survivalfun.core.managers.core;

import net.survivalfun.core.managers.DB.VanishState;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Manages vanish functionality including visibility levels and state persistence
 */
public class VanishManager {

    private final PluginStart plugin;
    private final Database database;
    private final Map<UUID, Boolean> vanishedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> vanishLevels = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> preVanishGameMode = new ConcurrentHashMap<>();

    public VanishManager(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        loadVanishStatesFromDatabase();
    }

    /**
     * Checks if a player is vanished
     */
    public boolean isVanished(Player player) {
        return isVanished(player.getUniqueId());
    }

    /**
     * Checks if a player is vanished by UUID
     */
    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.getOrDefault(uuid, false);
    }

    /**
     * Gets the vanish level of a player (0 = not vanished, higher numbers = higher access)
     */
    public int getVanishLevel(Player player) {
        return getVanishLevel(player.getUniqueId());
    }

    /**
     * Gets the vanish level of a player by UUID
     */
    public int getVanishLevel(UUID uuid) {
        return vanishLevels.getOrDefault(uuid, 0);
    }

    /**
     * Checks if a player can see another vanished player based on vanish levels
     * Higher vanish levels can see all lower levels, but lower levels cannot see higher levels
     */
    public boolean canSee(Player viewer, Player target) {
        if (!isVanished(target)) {
            return true; // Non-vanished players are always visible
        }

        int viewerLevel = getVanishLevel(viewer);
        int targetLevel = getVanishLevel(target);

        // Higher levels can see lower levels, but lower levels cannot see higher levels
        // Level 0 (non-vanished) cannot see any vanished players unless they have bypass permission
        if (viewerLevel == 0) {
            return viewer.hasPermission("allium.vanish.bypass");
        }

        return viewerLevel >= targetLevel;
    }

    /**
     * Vanishes a player with the specified level
     */
    public boolean vanishPlayer(Player player, int level) {
        if (!player.hasPermission("allium.vanish")) {
            return false;
        }

        // Check if they have permission for this specific level
        if (!player.hasPermission("allium.vanish." + level) && level > 0) {
            return false;
        }

        UUID uuid = player.getUniqueId();

        // Save current gamemode before vanishing
        preVanishGameMode.put(uuid, player.getGameMode());

        // Set vanished state
        vanishedPlayers.put(uuid, true);
        vanishLevels.put(uuid, level);

        // Hide player from all other players initially
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            if (!otherPlayer.equals(player)) {
                otherPlayer.hidePlayer(plugin, player);
            }
        }

        // Show player to players who can see them based on vanish levels
        updateVisibility(player);

        // Save to database
        saveVanishStateToDatabase(uuid, level, player.getGameMode());

        // Play dragon wing sound to the vanished player
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.0f);

        Text.sendDebugLog(INFO, "Player " + player.getName() + " vanished with level " + level);
        return true;
    }

    /**
     * Unvanishes a player
     */
    public boolean unvanishPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (!isVanished(uuid)) {
            return false;
        }

        // Remove vanished state
        vanishedPlayers.remove(uuid);
        vanishLevels.remove(uuid);

        // Restore previous gamemode if it was changed
        GameMode previousGameMode = preVanishGameMode.remove(uuid);
        if (previousGameMode != null) {
            player.setGameMode(previousGameMode);
        }

        // Show player to all other players
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            if (!otherPlayer.equals(player)) {
                otherPlayer.showPlayer(plugin, player);
            }
        }

        // Remove from database
        removeVanishStateFromDatabase(uuid);

        // Play dragon wing sound to the unvanished player
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.8f);

        Text.sendDebugLog(INFO, "Player " + player.getName() + " unvanished");
        return true;
    }

    /**
     * Updates visibility for a vanished player based on current vanish levels
     */
    public void updateVisibility(Player vanishedPlayer) {
        if (!isVanished(vanishedPlayer)) {
            return;
        }

        UUID vanishedUUID = vanishedPlayer.getUniqueId();
        int vanishedLevel = getVanishLevel(vanishedUUID);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(vanishedPlayer)) {
                continue; // Players always see themselves
            }

            boolean shouldBeVisible = canSee(viewer, vanishedPlayer);

            if (shouldBeVisible) {
                viewer.showPlayer(plugin, vanishedPlayer);
            } else {
                viewer.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    /**
     * Updates visibility for all vanished players when a player joins or their permissions change
     */
    public void updateAllVisibility() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateVisibility(player);
        }
    }

    /**
     * Updates visibility when a player's vanish level changes
     */
    public void updateVisibilityForLevelChange(Player player, int oldLevel) {
        UUID uuid = player.getUniqueId();
        int newLevel = getVanishLevel(uuid);

        // Update visibility for this player if they're vanished
        if (isVanished(uuid)) {
            updateVisibility(player);
        }

        // Update visibility of this player to others
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) {
                boolean wasVisible = oldLevel > 0 && viewer.hasPermission("allium.vanish." + oldLevel) ||
                                   viewer.hasPermission("allium.vanish.bypass");
                boolean isVisible = canSee(viewer, player);

                if (wasVisible && !isVisible) {
                    viewer.hidePlayer(plugin, player);
                } else if (!wasVisible && isVisible) {
                    viewer.showPlayer(plugin, player);
                }
            }
        }
    }

    /**
     * Gets all vanished players that a viewer can see
     */
    public List<Player> getVisibleVanishedPlayers(Player viewer) {
        List<Player> visible = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player) && canSee(viewer, player)) {
                visible.add(player);
            }
        }
        return visible;
    }

    /**
     * Gets all vanished players
     */
    public List<Player> getAllVanishedPlayers() {
        List<Player> vanished = new ArrayList<>();
        for (UUID uuid : vanishedPlayers.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                vanished.add(player);
            }
        }
        return vanished;
    }

    /**
     * Loads vanish states from database on startup
     */
    private void loadVanishStatesFromDatabase() {
        try {
            for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                VanishState state = database.loadVanishState(player.getUniqueId());
                if (state.isVanished()) {
                    vanishedPlayers.put(player.getUniqueId(), true);
                    vanishLevels.put(player.getUniqueId(), state.getVanishLevel());
                    preVanishGameMode.put(player.getUniqueId(), state.getGameMode());

                    Text.sendDebugLog(INFO, "Loaded vanished player " + player.getName() + " with level " + state.getVanishLevel());
                }
            }
        } catch (java.sql.SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to load vanish states from database", e);
        }
    }

    /**
     * Saves vanish state to database
     */
    private void saveVanishStateToDatabase(UUID uuid, int level, org.bukkit.GameMode gameMode) {
        try {
            database.saveVanishState(uuid, level, gameMode);
        } catch (java.sql.SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to save vanish state to database", e);
        }
    }

    /**
     * Removes vanish state from database
     */
    private void removeVanishStateFromDatabase(UUID uuid) {
        try {
            database.removeVanishState(uuid);
        } catch (java.sql.SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to remove vanish state from database", e);
        }
    }

    /**
     * Handles player quit - cleans up vanish state if player was vanished
     */
    public void handlePlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        if (isVanished(uuid)) {
            // Save current state to database for when they rejoin
            saveVanishStateToDatabase(uuid, getVanishLevel(uuid), player.getGameMode());
        }
    }

    /**
     * Handles player join - restores vanish state if they were vanished
     */
    public void handlePlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (isVanished(uuid)) {
            // Restore vanished state
            int level = getVanishLevel(uuid);
            GameMode previousGameMode = preVanishGameMode.get(uuid);

            // Re-vanish the player
            vanishPlayer(player, level);

            // Restore previous gamemode if available
            if (previousGameMode != null) {
                player.setGameMode(previousGameMode);
            }

            Text.sendDebugLog(INFO, "Restored vanish state for " + player.getName() + " with level " + level);
        }
    }

    /**
     * Gets the effective vanish level for a player based on their permissions
     * Returns the highest vanish level they have permission for
     */
    public int getEffectiveVanishLevel(Player player) {
        int maxLevel = 0;

        // Check for specific vanish level permissions
        for (int i = 100; i > 0; i--) { // Check from highest to lowest
            if (player.hasPermission("allium.vanish." + i)) {
                return i;
            }
        }

        // Check for basic vanish permission (level 1)
        if (player.hasPermission("allium.vanish")) {
            return 1;
        }

        return 0; // No vanish permission
    }

    /**
     * Cleans up vanish data for offline players
     */
    public void cleanupOfflinePlayers() {
        Set<UUID> onlineUUIDs = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineUUIDs.add(player.getUniqueId());
        }

        // Remove vanished state for offline players
        vanishedPlayers.keySet().removeIf(uuid -> !onlineUUIDs.contains(uuid));
        vanishLevels.keySet().removeIf(uuid -> !onlineUUIDs.contains(uuid));
        preVanishGameMode.keySet().removeIf(uuid -> !onlineUUIDs.contains(uuid));
    }
}
