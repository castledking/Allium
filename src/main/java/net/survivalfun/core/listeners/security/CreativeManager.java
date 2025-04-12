package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class CreativeManager implements Listener {

    private final PluginStart plugin;
    private final Database database;

    public CreativeManager(@NotNull PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("CreativeModeInventoryManager has been registered");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameMode currentGameMode = player.getGameMode();

        // First check if player has the general gamemode permission
        if (player.hasPermission("core.gamemode")) {
            // If they have the general permission, check if they have the specific gamemode permission
            if ((currentGameMode == GameMode.CREATIVE && player.hasPermission("core.gamemode.creative")) ||
                    (currentGameMode == GameMode.SPECTATOR && player.hasPermission("core.gamemode.spectator"))) {
                // Player has permission to keep their gamemode, so we'll save it
                saveCurrentInventory(player, currentGameMode);
                return;
            }
        }

        // If we get here, the player doesn't have permission to keep their gamemode
        if (currentGameMode == GameMode.CREATIVE || currentGameMode == GameMode.SPECTATOR) {
            // Save their current inventory before changing gamemode
            saveCurrentInventory(player, currentGameMode);

            // Store the player's UUID and intended gamemode in the database for reset on next login
            storeGameModeReset(player.getUniqueId(), GameMode.SURVIVAL);

            // Log the gamemode reset for audit purposes
            plugin.getLogger().info("Player " + player.getName() + " logged out with " +
                    currentGameMode.name() + " gamemode without permission to keep it. " +
                    "Their gamemode will be reset to SURVIVAL on next login.");
        }
    }

    /**
     * Stores a gamemode reset request in the database for a player
     */
    private void storeGameModeReset(UUID playerUUID, GameMode resetTo) {
        // Using H2's MERGE INTO syntax which is the equivalent of "INSERT OR REPLACE" in SQLite
        database.executeUpdate(
                "MERGE INTO gamemode_resets KEY(player_uuid) VALUES (?, ?, CURRENT_TIMESTAMP)",
                playerUUID.toString(), resetTo.name());
    }

    /**
     * Checks and applies any pending gamemode resets when a player joins
     * This method should be called from your onPlayerJoin event handler
     */
    private void checkAndApplyGameModeReset(Player player) {
        UUID playerUUID = player.getUniqueId();

        // Query the database for any pending resets
        // This is a simplified example - adapt to your actual database implementation
        String resetGameMode = database.queryString(
                "SELECT reset_gamemode FROM gamemode_resets WHERE player_uuid = ?",
                playerUUID.toString());

        if (resetGameMode != null) {
            // Apply the reset
            try {
                GameMode resetTo = GameMode.valueOf(resetGameMode);

                // Schedule the gamemode change for after the player fully joins
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(resetTo);
                    player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                            .replace("{gamemode}", resetTo.toString()));

                    // Clear the reset request from the database
                    database.executeUpdate("DELETE FROM gamemode_resets WHERE player_uuid = ?",
                            playerUUID.toString());
                }, 5L);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid gamemode reset value for player " +
                        player.getName() + ": " + resetGameMode);
            }
        }
    }



    /**
     * Handle inventory separation when a player changes game mode
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        GameMode oldGameMode = player.getGameMode();

        // Skip if player has bypass permission
        if (player.hasPermission("core.gamemode.creative.bypass.*") ||
                player.hasPermission("core.gamemode.creative.bypass.inventory")) {
            return;
        }

        // Handle switching to creative mode
        if (newGameMode == GameMode.CREATIVE && oldGameMode != GameMode.CREATIVE) {
            // Save survival inventory
            saveCurrentInventory(player, oldGameMode);

            // Clear inventory and load creative inventory
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                clearInventory(player);
                loadInventory(player, GameMode.CREATIVE);
            }, 1L);
        }

        // Handle switching from creative to any other mode
        else if (oldGameMode == GameMode.CREATIVE && newGameMode != GameMode.CREATIVE) {
            // Save creative inventory
            saveCurrentInventory(player, oldGameMode);

            // Restore survival inventory
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                clearInventory(player);
                loadInventory(player, GameMode.SURVIVAL);
            }, 1L);
        }
    }

    /**
     * Handle inventory restoration and gamemode resets when a player joins
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // First check for any pending gamemode resets
        checkAndApplyGameModeReset(player);

        // Then verify the player has permission for their current gamemode
        // This handles cases where server restarts and players retain gamemodes they shouldn't have
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            verifyGameModePermission(player);
        }, 10L);

        // Skip inventory management if player has bypass permission
        if (player.hasPermission("core.gamemode.creative.bypass.*") ||
                player.hasPermission("core.gamemode.creative.bypass.inventory")) {
            return;
        }

        // Load the appropriate inventory based on their current game mode
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            GameMode gameMode = player.getGameMode();
            loadInventory(player, gameMode == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.SURVIVAL);
        }, 20L);
    }

    /**
     * Verifies that a player has permission for their current gamemode
     * If not, resets them to survival mode
     */
    private void verifyGameModePermission(Player player) {
        GameMode currentMode = player.getGameMode();

        // Skip check for survival mode (default) and for players with bypass permission
        if (currentMode == GameMode.SURVIVAL ||
                player.hasPermission("core.gamemode.creative.bypass.*")) {
            return;
        }

        // Check if player has permission for their current gamemode
        String permissionSuffix = currentMode.name().toLowerCase();
        if (!player.hasPermission("core.gamemode." + permissionSuffix)) {
            // Player doesn't have permission for this gamemode, reset to survival
            player.setGameMode(GameMode.SURVIVAL);

            // Notify the player
            player.sendMessage(plugin.getLangManager().get("gamemode.reset")
                    .replace("{gamemode}", GameMode.SURVIVAL.toString()));

            // Log the reset
            plugin.getLogger().info("Reset " + player.getName() + "'s gamemode from " +
                    currentMode.name() + " to SURVIVAL (no permission)");
        }
    }

    /**
     * Save a player's current inventory based on their game mode
     */
    private void saveCurrentInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        // Get existing inventories from database
        Database.PlayerInventories inventories = database.getPlayerInventories(playerUUID);

        // Initialize inventory arrays
        ItemStack[] survivalInventory = null;
        ItemStack[] survivalArmor = null;
        ItemStack survivalOffhand = null;
        ItemStack[] creativeInventory = null;
        ItemStack[] creativeArmor = null;
        ItemStack creativeOffhand = null;

        // If we have existing inventories, load them
        if (inventories != null) {
            survivalInventory = inventories.getSurvivalInventory();
            survivalArmor = inventories.getSurvivalArmor();
            survivalOffhand = inventories.getSurvivalOffhand();
            creativeInventory = inventories.getCreativeInventory();
            creativeArmor = inventories.getCreativeArmor();
            creativeOffhand = inventories.getCreativeOffhand();
        }

        // Update the appropriate inventory based on game mode
        if (gameMode == GameMode.CREATIVE) {
            creativeInventory = player.getInventory().getContents();
            creativeArmor = player.getInventory().getArmorContents();
            creativeOffhand = player.getInventory().getItemInOffHand();
        } else {
            survivalInventory = player.getInventory().getContents();
            survivalArmor = player.getInventory().getArmorContents();
            survivalOffhand = player.getInventory().getItemInOffHand();
        }

        // Save to database
        database.savePlayerInventories(
                playerUUID, playerName,
                survivalInventory, survivalArmor, survivalOffhand,
                creativeInventory, creativeArmor, creativeOffhand
        );
    }

    /**
     * Load a player's inventory based on the specified game mode
     */
    private void loadInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();

        // Get inventories from database
        Database.PlayerInventories inventories = database.getPlayerInventories(playerUUID);

        if (inventories == null) {
            // No saved inventories, just clear their inventory
            clearInventory(player);
            return;
        }

        // Clear current inventory first
        clearInventory(player);

        // Load the appropriate inventory based on game mode
        if (gameMode == GameMode.CREATIVE) {
            ItemStack[] inventory = inventories.getCreativeInventory();
            ItemStack[] armor = inventories.getCreativeArmor();
            ItemStack offhand = inventories.getCreativeOffhand();

            if (inventory != null) {
                player.getInventory().setContents(inventory);
            }

            if (armor != null) {
                player.getInventory().setArmorContents(armor);
            }

            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
        } else {
            ItemStack[] inventory = inventories.getSurvivalInventory();
            ItemStack[] armor = inventories.getSurvivalArmor();
            ItemStack offhand = inventories.getSurvivalOffhand();

            if (inventory != null) {
                player.getInventory().setContents(inventory);
            }

            if (armor != null) {
                player.getInventory().setArmorContents(armor);
            }

            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
        }

        // Update the player's inventory view
        player.updateInventory();
    }

    /**
     * Clear a player's inventory completely
     */
    private void clearInventory(@NotNull Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.updateInventory();
    }
}
