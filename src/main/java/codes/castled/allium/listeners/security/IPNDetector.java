package codes.castled.allium.listeners.security;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavioral detector for Inventory Profiles Next (IPN) mod.
 * Detects IPN by monitoring inventory click patterns that indicate automation.
 */
public class IPNDetector implements Listener {

    private final PluginStart plugin;
    private final boolean enabled;
    private final int clickThreshold; // Clicks per second threshold
    private final double slotDistanceThreshold; // Average slot distance threshold
    
    // Track click data per player
    private final Map<UUID, ClickTracker> playerClickData = new ConcurrentHashMap<>();
    
    // Track if player was flagged
    private final Set<UUID> flaggedPlayers = ConcurrentHashMap.newKeySet();
    
    public IPNDetector(PluginStart plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mod-detection.ipn-behavioral-detection", true);
        this.clickThreshold = plugin.getConfig().getInt("mod-detection.ipn-click-threshold", 8); // 8+ clicks/sec is suspicious
        this.slotDistanceThreshold = plugin.getConfig().getDouble("mod-detection.ipn-slot-distance", 4.0); // Avg distance > 4 slots
        
        plugin.getLogger().info("[IPNDetector] Constructor called, enabled=" + enabled + ", threshold=" + clickThreshold);
        
        if (enabled) {
            // Cleanup task
            Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupOldData, 1200L, 1200L);
            plugin.getLogger().info("[IPNDetector] Enabled behavioral detection (clicks/sec > " + clickThreshold + ", distance > " + slotDistanceThreshold + ")");
        } else {
            plugin.getLogger().warning("[IPNDetector] DISABLED in config");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // Only track clicks in the player's own inventory (not chests, etc.)
        // Check if the clicked inventory belongs to the player
        if (event.getClickedInventory() != player.getInventory()) return;
        
        UUID uuid = player.getUniqueId();
        ClickTracker tracker = playerClickData.computeIfAbsent(uuid, k -> new ClickTracker());
        
        long now = System.currentTimeMillis();
        int slot = event.getSlot(); // Use getSlot() instead of getRawSlot() for player inventory
        
        // Debug logging
        if (plugin.getConfig().getBoolean("mod-detection.debug-ipn", false)) {
            plugin.getLogger().info("[IPNDetector] Click from " + player.getName() + " in slot " + slot);
        }
        
        // Record the click
        tracker.recordClick(now, slot);
        
        // Check for IPN patterns
        if (tracker.isSuspicious(clickThreshold, slotDistanceThreshold)) {
            if (!flaggedPlayers.contains(uuid)) {
                flaggedPlayers.add(uuid);
                String modName = "inventoryprofilesnext";
                
                plugin.getLogger().warning("[IPNDetector] Detected IPN-like behavior from " + player.getName() + 
                    " (" + tracker.getRecentClickCount() + " clicks/sec, avg distance: " + String.format("%.1f", tracker.getAverageSlotDistance()) + ")");
                
                // Kick if configured
                if (plugin.getConfig().getBoolean("mod-detection.auto-kick-ipn", false)) {
                    String message = plugin.getConfig().getString("mod-detection.kick-message", 
                        "§cYou are using a prohibited mod: §e{mod}\n\n§7Please remove this mod to join the server.").replace("{mod}", modName);
                    
                    Bukkit.getScheduler().runTask(plugin, () -> player.kick(net.kyori.adventure.text.Component.text(message)));
                }
                
                // Notify staff
                if (plugin.getConfig().getBoolean("mod-detection.broadcast-kicks", true)) {
                    String broadcastMsg = "§8[§cIPNDetector§8] §e" + player.getName() + " §7detected using §cInventoryProfilesNext §7(auto-sort behavior)";
                    Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("allium.moddetection.notify"))
                        .forEach(p -> p.sendMessage(broadcastMsg));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        
        // Reset click data when inventory opens (fresh session)
        playerClickData.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerClickData.remove(uuid);
        flaggedPlayers.remove(uuid);
    }

    /**
     * Get detection status for a player
     */
    public boolean isFlagged(UUID playerUUID) {
        return flaggedPlayers.contains(playerUUID);
    }

    /**
     * Manually flag a player (for testing)
     */
    public void flagPlayer(UUID playerUUID) {
        flaggedPlayers.add(playerUUID);
    }

    private void cleanupOldData() {
        long cutoff = System.currentTimeMillis() - 60000; // Remove data older than 60 seconds
        playerClickData.values().forEach(tracker -> tracker.cleanupOldClicks(cutoff));
        
        // Remove entries for offline players
        playerClickData.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
    }

    /**
     * Inner class to track click patterns for a player
     */
    private static class ClickTracker {
        private final List<ClickRecord> clicks = new ArrayList<>();
        private int lastSlot = -1;
        private double totalSlotDistance = 0;
        private int slotMoves = 0;

        void recordClick(long timestamp, int slot) {
            clicks.add(new ClickRecord(timestamp, slot));
            
            if (lastSlot != -1 && slot != lastSlot) {
                double distance = Math.abs(slot - lastSlot);
                totalSlotDistance += distance;
                slotMoves++;
            }
            lastSlot = slot;
        }

        int getRecentClickCount() {
            long oneSecondAgo = System.currentTimeMillis() - 1000;
            return (int) clicks.stream().filter(c -> c.timestamp > oneSecondAgo).count();
        }

        double getAverageSlotDistance() {
            if (slotMoves == 0) return 0;
            return totalSlotDistance / slotMoves;
        }

        boolean isSuspicious(int clickThreshold, double distanceThreshold) {
            int recentClicks = getRecentClickCount();
            double avgDistance = getAverageSlotDistance();
            
            // IPN characteristics:
            // 1. Very fast clicking (8+ clicks per second)
            // 2. Large average slot distances (moving items across inventory quickly)
            // 3. Consistent timing (not human-like variations)
            
            return recentClicks >= clickThreshold && avgDistance >= distanceThreshold;
        }

        void cleanupOldClicks(long cutoff) {
            clicks.removeIf(c -> c.timestamp < cutoff);
            if (clicks.isEmpty()) {
                totalSlotDistance = 0;
                slotMoves = 0;
                lastSlot = -1;
            }
        }

        private record ClickRecord(long timestamp, int slot) {}
    }
}
