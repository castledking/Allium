package codes.castled.allium.listeners.security;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavioral detector for Freecam mod.
 * Detects Freecam by monitoring for rotation-only movement (spinning camera while standing still).
 */
public class FreecamDetector implements Listener {

    private final PluginStart plugin;
    private final boolean enabled;
    private final int rotationThreshold; // Degrees of rotation per second while stationary
    private final int stationaryTicksThreshold; // Ticks before considering player "stationary"
    
    // Track player movement data
    private final Map<UUID, MovementTracker> playerMovementData = new ConcurrentHashMap<>();
    
    // Track flagged players
    private final Set<UUID> flaggedPlayers = ConcurrentHashMap.newKeySet();
    
    public FreecamDetector(PluginStart plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mod-detection.freecam-behavioral-detection", true);
        this.rotationThreshold = plugin.getConfig().getInt("mod-detection.freecam-rotation-threshold", 360); // 360°/sec while still
        this.stationaryTicksThreshold = plugin.getConfig().getInt("mod-detection.freecam-stationary-ticks", 20); // 1 second
        
        plugin.getLogger().info("[FreecamDetector] Constructor called, enabled=" + enabled + ", rotationThreshold=" + rotationThreshold);
        
        if (enabled) {
            // Start the checker task
            SchedulerAdapter.runTimer(this::checkPlayers, 20L, 20L); // Check every second
            plugin.getLogger().info("[FreecamDetector] Enabled behavioral detection (rotation > " + rotationThreshold + "°/sec while stationary for " + stationaryTicksThreshold + " ticks)");
        } else {
            plugin.getLogger().warning("[FreecamDetector] DISABLED in config");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled) return;
        
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Get movement details
        Location from = event.getFrom();
        Location to = event.getTo();
        
        if (to == null) return;
        
        // Check if position changed (not just rotation)
        boolean positionChanged = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        
        // Check if rotation changed
        boolean rotationChanged = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();
        
        // Get or create tracker
        MovementTracker tracker = playerMovementData.computeIfAbsent(uuid, k -> new MovementTracker());
        
        long now = System.currentTimeMillis();
        
        if (positionChanged) {
            // Player moved positionally - reset stationary tracking
            tracker.recordPositionMove(now, to.getYaw(), to.getPitch());
        } else if (rotationChanged) {
            // Only rotation changed (camera movement)
            tracker.recordRotationOnly(now, to.getYaw(), to.getPitch());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Reset tracking on teleport
        playerMovementData.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerMovementData.remove(uuid);
        flaggedPlayers.remove(uuid);
    }

    /**
     * Periodic check for suspicious patterns
     */
    private void checkPlayers() {
        long now = System.currentTimeMillis();
        
        for (Map.Entry<UUID, MovementTracker> entry : playerMovementData.entrySet()) {
            UUID uuid = entry.getKey();
            MovementTracker tracker = entry.getValue();
            
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            
            // Check if player has been stationary but rotating significantly
            if (tracker.isSuspicious(now, rotationThreshold, stationaryTicksThreshold)) {
                if (!flaggedPlayers.contains(uuid)) {
                    flaggedPlayers.add(uuid);
                    
                    double rotationPerSecond = tracker.getRotationRate();
                    int stationarySeconds = tracker.getStationarySeconds();
                    
                    plugin.getLogger().warning("[FreecamDetector] Detected Freecam-like behavior from " + player.getName() + 
                        " (" + String.format("%.1f", rotationPerSecond) + "°/sec rotation while stationary for " + stationarySeconds + "s)");
                    
                    // Kick if configured
                    if (plugin.getConfig().getBoolean("mod-detection.auto-kick-freecam", false)) {
                        String message = plugin.getConfig().getString("mod-detection.kick-message", 
                            "§cYou are using a prohibited mod: §e{mod}\n\n§7Please remove this mod to join the server.").replace("{mod}", "freecam");
                        
                        SchedulerAdapter.run(() -> player.kick(net.kyori.adventure.text.Component.text(message)));
                    }
                    
                    // Notify staff
                    if (plugin.getConfig().getBoolean("mod-detection.broadcast-kicks", true)) {
                        String broadcastMsg = "§8[§cFreecamDetector§8] §e" + player.getName() + " §7detected using §cFreecam §7(camera rotation while stationary)";
                        Bukkit.getOnlinePlayers().stream()
                            .filter(p -> p.hasPermission("allium.moddetection.notify"))
                            .forEach(p -> p.sendMessage(broadcastMsg));
                    }
                }
            }
        }
    }

    /**
     * Get detection status for a player
     */
    public boolean isFlagged(UUID playerUUID) {
        return flaggedPlayers.contains(playerUUID);
    }

    /**
     * Inner class to track movement patterns
     */
    private static class MovementTracker {
        private long lastPositionMove = System.currentTimeMillis();
        private long totalRotationWhileStationary = 0;
        private float lastYaw = 0;
        private boolean hasInitialYaw = false;
        
        void recordPositionMove(long timestamp, float yaw, float pitch) {
            lastPositionMove = timestamp;
            totalRotationWhileStationary = 0; // Reset rotation tracking when player moves
            lastYaw = yaw;
        }
        
        void recordRotationOnly(long timestamp, float yaw, float pitch) {
            if (!hasInitialYaw) {
                lastYaw = yaw;
                hasInitialYaw = true;
                return;
            }
            
            // Calculate rotation delta (handle wrap-around at -180/180)
            float delta = Math.abs(yaw - lastYaw);
            if (delta > 180) delta = 360 - delta;
            
            totalRotationWhileStationary += delta;
            lastYaw = yaw;
        }
        
        boolean isSuspicious(long now, int rotationThreshold, int stationaryTicks) {
            long stationaryTime = now - lastPositionMove;
            int stationarySeconds = (int) (stationaryTime / 1000);
            
            // Must be stationary for at least threshold seconds
            if (stationarySeconds < (stationaryTicks / 20)) return false;
            
            // Check rotation rate
            double rotationPerSecond = totalRotationWhileStationary / Math.max(1, stationarySeconds);
            return rotationPerSecond >= rotationThreshold;
        }
        
        double getRotationRate() {
            long stationaryTime = System.currentTimeMillis() - lastPositionMove;
            int stationarySeconds = (int) (stationaryTime / 1000);
            return stationarySeconds > 0 ? (double) totalRotationWhileStationary / stationarySeconds : 0;
        }
        
        int getStationarySeconds() {
            return (int) ((System.currentTimeMillis() - lastPositionMove) / 1000);
        }
    }
}
