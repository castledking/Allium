package codes.castled.allium.items.impl;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manager for Item Renamer functionality.
 * Handles:
 * - Granting permissions when item is in inventory
 * - Detecting /rename command usage
 * - Consuming item on successful rename
 */
public class ItemRenamerManager implements Listener {

    private static final String[] GRANTED_PERMISSIONS = {
        "itemedit.itemedit.rename",
        "itemedit.itemedit.rename.minimessage",
        "itemedit.itemedit.rename.format",
        "itemedit.itemedit.rename.color"
    };

    private final Plugin plugin;
    private final CustomItemRegistry registry;

    // Track permission attachments per player
    private final Map<UUID, PermissionAttachment> permissionAttachments = new HashMap<>();
    // Track players who have used /rename and are pending consumption
    private final Map<UUID, Long> pendingConsumption = new HashMap<>();

    public ItemRenamerManager(final Plugin plugin, final CustomItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;

        // Start periodic inventory check task
        SchedulerAdapter.runTimer(this::checkAllPlayersInventory, 20L, 20L);
    }

    /**
     * Check all online players' inventories for the item renamer.
     */
    private void checkAllPlayersInventory() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerPermissions(player);
        }
    }

    /**
     * Update permissions for a player based on whether they have the item in inventory.
     */
    private void updatePlayerPermissions(final Player player) {
        final boolean hasItem = hasItemRenamerInInventory(player);
        final boolean hasAttachment = permissionAttachments.containsKey(player.getUniqueId());
        final boolean hasDirectPermission = player.hasPermission("itemedit.itemedit.command.rename");

        // If player has direct permission, don't manage anything for them
        if (hasDirectPermission) {
            // Remove any existing attachment since they don't need it
            if (hasAttachment) {
                final PermissionAttachment attachment = permissionAttachments.remove(player.getUniqueId());
                if (attachment != null) {
                    player.removeAttachment(attachment);
                }
            }
            return;
        }

        if (hasItem && !hasAttachment) {
            // Grant permissions
            final PermissionAttachment attachment = player.addAttachment(plugin);
            for (final String permission : GRANTED_PERMISSIONS) {
                attachment.setPermission(permission, true);
            }
            permissionAttachments.put(player.getUniqueId(), attachment);
        } else if (!hasItem && hasAttachment) {
            // Remove permissions
            final PermissionAttachment attachment = permissionAttachments.remove(player.getUniqueId());
            if (attachment != null) {
                player.removeAttachment(attachment);
            }
        }
    }

    /**
     * Check if player has an Item Renamer in their inventory.
     */
    private boolean hasItemRenamerInInventory(final Player player) {
        final ItemRenamerItem tool = (ItemRenamerItem) registry.getItem(ItemRenamerItem.ITEM_ID);
        if (tool == null) {
            return false;
        }

        for (final ItemStack item : player.getInventory().getContents()) {
            if (item != null && tool.isThisItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find and consume one Item Renamer from player's inventory.
     * @return true if an item was consumed
     */
    private boolean consumeItemRenamer(final Player player) {
        final ItemRenamerItem tool = (ItemRenamerItem) registry.getItem(ItemRenamerItem.ITEM_ID);
        if (tool == null) {
            return false;
        }

        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack item = contents[i];
            if (item != null && tool.isThisItem(item)) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    player.getInventory().setItem(i, null);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Handle player join - check inventory immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        // Delay slightly to ensure inventory is loaded
        SchedulerAdapter.runAtEntityLater(event.getPlayer(), () -> {
            updatePlayerPermissions(event.getPlayer());
        }, 5L);
    }

    /**
     * Handle player quit - cleanup permissions.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        final PermissionAttachment attachment = permissionAttachments.remove(uuid);
        if (attachment != null) {
            try {
                event.getPlayer().removeAttachment(attachment);
            } catch (final Exception ignored) {
                // Player already disconnected
            }
        }
        pendingConsumption.remove(uuid);
    }

    /**
     * Detect /rename command usage.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(final PlayerCommandPreprocessEvent event) {
        final String message = event.getMessage().toLowerCase();

        // Check if it's a rename command
        if (!message.startsWith("/rename ") && !message.startsWith("/ie rename ")) {
            return;
        }

        final Player player = event.getPlayer();

        // If player has the direct permission, let the command pass through to commands.yml
        // The ItemRenamer should not interfere at all
        if (player.hasPermission("itemedit.itemedit.command.rename")) {
            return;
        }

        // Check if player has the item renamer
        if (!hasItemRenamerInInventory(player)) {
            player.sendMessage(ChatColor.RED + "You do not have an Item Renamer in your inventory!");
            event.setCancelled(true);
            return;
        }

        // Check if player is holding an item to rename (not the renamer itself)
        final ItemStack heldItem = player.getInventory().getItemInMainHand();
        final ItemRenamerItem tool = (ItemRenamerItem) registry.getItem(ItemRenamerItem.ITEM_ID);

        if (heldItem == null || heldItem.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "You cannot rename air!");
            event.setCancelled(true);
            return;
        }

        // Don't consume if they're trying to rename the renamer itself
        if (tool != null && tool.isThisItem(heldItem)) {
            player.sendMessage(ChatColor.RED + "You cannot rename the Item Renamer itself!");
            event.setCancelled(true);
            return;
        }

        // Mark for consumption - we'll consume after a short delay to allow the command to process
        // The ItemEdit plugin will handle the actual rename, we just consume our item
        final UUID uuid = player.getUniqueId();
        pendingConsumption.put(uuid, System.currentTimeMillis());

        // Schedule consumption check after command processes
        SchedulerAdapter.runAtEntityLater(player, () -> {
            if (pendingConsumption.containsKey(uuid)) {
                pendingConsumption.remove(uuid);
                if (consumeItemRenamer(player)) {
                    player.sendMessage(ChatColor.GREEN + "Item renamed! " + ChatColor.GRAY + "(Item Renamer consumed)");
                    // Update permissions immediately
                    updatePlayerPermissions(player);
                }
            }
        }, 2L);
    }
}
