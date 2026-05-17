package codes.castled.allium.listeners.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts End Gateway teleports near the main End island (0,0)
 * and replaces vanilla "highest block" behavior with safe teleport logic.
 * Prioritizes same-Y-level locations around the gateway to avoid "roof" issues.
 */
public class EndGatewayListener implements Listener {

    private final PluginStart plugin;
    private final int maxDistanceFromCenter;
    private final int searchRadius;
    private final boolean debugMode;
    
    // Cooldown tracking to prevent spam (player UUID -> last use time)
    private final ConcurrentHashMap<UUID, Long> gatewayCooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 500; // 500ms cooldown between gateway uses

    // Unsafe block types - must have solid ground, not these
    private static final Set<Material> UNSAFE_GROUND_TYPES = EnumSet.of(
        Material.LAVA,
        Material.CACTUS,
        Material.CAMPFIRE,
        Material.FIRE,
        Material.MAGMA_BLOCK,
        Material.SOUL_CAMPFIRE,
        Material.SOUL_FIRE,
        Material.SWEET_BERRY_BUSH,
        Material.WITHER_ROSE
    );

    // Blocks that are passable (air-like) for player feet/head
    private static final Set<Material> PASSABLE_TYPES = EnumSet.of(
        Material.AIR,
        Material.CAVE_AIR,
        Material.VOID_AIR,
        Material.WATER,
        Material.LADDER,
        Material.VINE,
        Material.SCAFFOLDING,
        Material.NETHER_PORTAL,
        Material.END_PORTAL
    );

    static {
        // Add additional passable types by name pattern (signs, buttons, etc.)
        for (Material mat : Material.values()) {
            String name = mat.name();
            if (name.contains("SIGN") || 
                name.contains("BUTTON") || 
                name.contains("PRESSURE_PLATE") ||
                name.contains("TORCH") ||
                name.contains("RAIL") ||
                (name.contains("DOOR") && !name.contains("BLOCK")) ||
                name.contains("TRAPDOOR") ||
                name.contains("GATE") ||
                name.contains("SLAB") ||
                name.contains("STAIRS")) {
                PASSABLE_TYPES.add(mat);
            }
        }
    }

    public EndGatewayListener(PluginStart plugin) {
        this.plugin = plugin;
        this.maxDistanceFromCenter = plugin.getConfig().getInt("end-gateway.max-distance-from-center", 512);
        this.searchRadius = plugin.getConfig().getInt("end-gateway.search-radius", 5);
        this.debugMode = plugin.getConfig().getBoolean("debug-mode", false);
        
        Text.sendDebugLog(INFO, "EndGatewayListener initialized (distance: " + maxDistanceFromCenter + ", radius: " + searchRadius + ")");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        TeleportCause cause = event.getCause();
        Location to = event.getTo();
        
        // Log ALL teleport events for this player when debug is on
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Teleport event: player=" + player.getName() + 
                ", cause=" + cause + ", to=" + (to != null ? formatLocation(to) : "null"));
        }
        
        // Handle End Gateway teleports AND Ender Pearl teleports in The End
        // (Ender pearls through gateways have cause=ENDER_PEARL but still need safe landing)
        boolean isGateway = cause == TeleportCause.END_GATEWAY;
        boolean isEnderPearlInEnd = cause == TeleportCause.ENDER_PEARL && 
                                     to != null && 
                                     to.getWorld() != null && 
                                     to.getWorld().getEnvironment() == World.Environment.THE_END;
        
        if (!isGateway && !isEnderPearlInEnd) {
            return;
        }

        if (to == null) {
            if (debugMode) {
                Text.sendDebugLog(WARN, "[EndGateway] Destination is null, skipping");
            }
            return;
        }

        World world = to.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            if (debugMode) {
                Text.sendDebugLog(INFO, "[EndGateway] Not in THE_END (world=" + 
                    (world != null ? world.getName() + ", env=" + world.getEnvironment() : "null") + 
                    ", cause=" + cause + "), skipping");
            }
            return;
        }

        // Check if within range of center (0,0)
        double distanceXZ = Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
        if (distanceXZ > maxDistanceFromCenter) {
            if (debugMode) {
                Text.sendDebugLog(INFO, "[EndGateway] Outside safe zone (distance: " + (int) distanceXZ + 
                    " > " + maxDistanceFromCenter + "), allowing vanilla behavior");
            }
            return;
        }

        String teleportType = isGateway ? "END_GATEWAY" : "ENDER_PEARL";
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Processing " + teleportType + " teleport for " + player.getName() + 
                " at " + formatLocation(to) + " (distance from 0,0: " + (int) distanceXZ + ")");
        }
        
        // Check cooldown
        if (isOnCooldown(player.getUniqueId())) {
            if (debugMode) {
                Text.sendDebugLog(INFO, "[EndGateway] Player " + player.getName() + " is on cooldown, allowing vanilla");
            }
            return;
        }
        setCooldown(player.getUniqueId());

        // Ensure chunk is loaded before safety checks
        Block targetBlock = to.getBlock();
        if (!targetBlock.getChunk().isLoaded()) {
            targetBlock.getChunk().load();
        }

        // Find safe location around the gateway
        Location safeLocation = findSafeLocationAround(to);

        if (safeLocation == null) {
            if (debugMode) {
                Text.sendDebugLog(WARN, "[EndGateway] No safe location found for " + player.getName() + 
                    " at " + formatLocation(to) + " - letting vanilla place on roof");
            }
            // Fail gracefully - let vanilla handle it
            return;
        }

        // Cancel vanilla teleport and use our safe location
        event.setCancelled(true);
        
        // Teleport with PLUGIN cause to distinguish from vanilla
        player.teleport(safeLocation, TeleportCause.PLUGIN);
        
        if (debugMode) {
            Text.sendDebugLog(INFO, "Safe End Gateway teleport: " + player.getName() + " from " + 
                formatLocation(to) + " to " + formatLocation(safeLocation));
        }
    }

    /**
     * Find a safe location around the given base location.
     * Prioritizes same-Y-level positions to avoid "roof" issues.
     * 
     * @param base The gateway destination location
     * @return A safe Location, or null if none found
     */
    private Location findSafeLocationAround(Location base) {
        World world = base.getWorld();
        if (world == null) {
            if (debugMode) {
                Text.sendDebugLog(WARN, "[EndGateway] World is null in findSafeLocationAround");
            }
            return null;
        }

        int centerX = base.getBlockX();
        int centerY = base.getBlockY();
        int centerZ = base.getBlockZ();
        
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Searching for safe location around (" + 
                centerX + ", " + centerY + ", " + centerZ + ") in " + world.getName());
            // Log what blocks are at the target location
            Block ground = world.getBlockAt(centerX, centerY - 1, centerZ);
            Block feet = world.getBlockAt(centerX, centerY, centerZ);
            Block head = world.getBlockAt(centerX, centerY + 1, centerZ);
            Text.sendDebugLog(INFO, "[EndGateway] Target blocks - ground: " + ground.getType() + 
                ", feet: " + feet.getType() + ", head: " + head.getType());
        }

        // PRIORITY 1: Search at same Y level first (prevents "roof" issue)
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Priority 1: Searching at same Y level (Y=" + centerY + ")");
        }
        Location sameY = searchAtLevel(world, centerX, centerY, centerZ, worldMinY, worldMaxY);
        if (sameY != null) {
            if (debugMode) {
                Text.sendDebugLog(INFO, "[EndGateway] Found same-Y location: " + formatLocation(sameY));
            }
            return sameY;
        }

        // PRIORITY 2: Search at nearby Y levels (±3 blocks) while keeping X/Z close
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Priority 2: Searching nearby Y levels (±3)");
        }
        for (int yOffset = 1; yOffset <= 3; yOffset++) {
            // Try above first
            Location above = searchAtLevel(world, centerX, centerY + yOffset, centerZ, worldMinY, worldMaxY);
            if (above != null) {
                if (debugMode) {
                    Text.sendDebugLog(INFO, "[EndGateway] Found location above (Y+" + yOffset + "): " + formatLocation(above));
                }
                return above;
            }
            // Then below
            Location below = searchAtLevel(world, centerX, centerY - yOffset, centerZ, worldMinY, worldMaxY);
            if (below != null) {
                if (debugMode) {
                    Text.sendDebugLog(INFO, "[EndGateway] Found location below (Y-" + yOffset + "): " + formatLocation(below));
                }
                return below;
            }
        }

        // PRIORITY 3: Search in expanding square pattern at various Y levels
        if (debugMode) {
            Text.sendDebugLog(INFO, "[EndGateway] Priority 3: Expanding square search (radius up to " + searchRadius + ")");
        }
        for (int radius = 1; radius <= searchRadius; radius++) {
            // Search perimeter at same Y level first
            Location perimeterSameY = searchPerimeterAtLevel(world, centerX, centerY, centerZ, radius);
            if (perimeterSameY != null) {
                if (debugMode) {
                    Text.sendDebugLog(INFO, "[EndGateway] Found perimeter location at radius " + radius + ": " + formatLocation(perimeterSameY));
                }
                return perimeterSameY;
            }
            
            // Then try perimeters at nearby Y levels
            for (int yOffset = 1; yOffset <= 2; yOffset++) {
                Location perimeterAbove = searchPerimeterAtLevel(world, centerX, centerY + yOffset, centerZ, radius);
                if (perimeterAbove != null) {
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "[EndGateway] Found perimeter above at radius " + radius + ": " + formatLocation(perimeterAbove));
                    }
                    return perimeterAbove;
                }
                Location perimeterBelow = searchPerimeterAtLevel(world, centerX, centerY - yOffset, centerZ, radius);
                if (perimeterBelow != null) {
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "[EndGateway] Found perimeter below at radius " + radius + ": " + formatLocation(perimeterBelow));
                    }
                    return perimeterBelow;
                }
            }
        }

        if (debugMode) {
            Text.sendDebugLog(WARN, "[EndGateway] No safe location found after all searches");
        }
        return null;
    }

    /**
     * Search for safe location at a specific Y level, starting from center and expanding outward.
     */
    private Location searchAtLevel(World world, int centerX, int centerY, int centerZ, int worldMinY, int worldMaxY) {
        if (centerY < worldMinY || centerY >= worldMaxY - 1) {
            return null;
        }

        // Check center first
        if (isSafeLocation(world, centerX, centerY, centerZ)) {
            return new Location(world, centerX + 0.5, centerY, centerZ + 0.5);
        }

        // Search in expanding square pattern
        for (int radius = 1; radius <= searchRadius; radius++) {
            // Check the perimeter at this radius
            Location perimeterLoc = searchPerimeterAtLevel(world, centerX, centerY, centerZ, radius);
            if (perimeterLoc != null) {
                return perimeterLoc;
            }
        }

        return null;
    }

    /**
     * Search the perimeter of a square at given radius from center.
     * Checks all positions at distance = radius from center X,Z.
     */
    private Location searchPerimeterAtLevel(World world, int centerX, int centerY, int centerZ, int radius) {
        // Top and bottom edges of the square
        for (int xOffset = -radius; xOffset <= radius; xOffset++) {
            // Top edge (z = centerZ - radius)
            if (isSafeLocation(world, centerX + xOffset, centerY, centerZ - radius)) {
                return new Location(world, centerX + xOffset + 0.5, centerY, centerZ - radius + 0.5);
            }
            // Bottom edge (z = centerZ + radius)
            if (isSafeLocation(world, centerX + xOffset, centerY, centerZ + radius)) {
                return new Location(world, centerX + xOffset + 0.5, centerY, centerZ + radius + 0.5);
            }
        }

        // Left and right edges (excluding corners already checked)
        for (int zOffset = -radius + 1; zOffset <= radius - 1; zOffset++) {
            // Left edge (x = centerX - radius)
            if (isSafeLocation(world, centerX - radius, centerY, centerZ + zOffset)) {
                return new Location(world, centerX - radius + 0.5, centerY, centerZ + zOffset + 0.5);
            }
            // Right edge (x = centerX + radius)
            if (isSafeLocation(world, centerX + radius, centerY, centerZ + zOffset)) {
                return new Location(world, centerX + radius + 0.5, centerY, centerZ + zOffset + 0.5);
            }
        }

        return null;
    }

    /**
     * Check if a specific block position is safe for teleportation.
     * Safe = solid ground below, passable at feet and head, no dangerous blocks.
     */
    private boolean isSafeLocation(World world, int x, int y, int z) {
        // Check bounds
        if (y < world.getMinHeight() || y >= world.getMaxHeight() - 1) {
            return false;
        }

        Block ground = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        
        Material groundType = ground.getType();
        Material feetType = feet.getType();
        Material headType = head.getType();

        // Ground must be solid and safe (not lava, not damaging)
        if (!groundType.isSolid() || isUnsafeGround(groundType)) {
            if (debugMode && !groundType.isSolid()) {
                Text.sendDebugLog(INFO, "[EndGateway] Unsafe at (" + x + "," + y + "," + z + "): ground not solid (" + groundType + ")");
            }
            return false;
        }

        // Feet and head must be passable (not solid)
        if (!isPassable(feetType) || !isPassable(headType)) {
            if (debugMode) {
                Text.sendDebugLog(INFO, "[EndGateway] Unsafe at (" + x + "," + y + "," + z + "): feet/head not passable (feet=" + feetType + ", head=" + headType + ")");
            }
            return false;
        }

        // Extra check: ensure feet/head isn't a portal or other special block
        if (feetType == Material.NETHER_PORTAL || feetType == Material.END_PORTAL) {
            return false;
        }

        return true;
    }

    private boolean isUnsafeGround(Material material) {
        return UNSAFE_GROUND_TYPES.contains(material);
    }

    private boolean isPassable(Material material) {
        return PASSABLE_TYPES.contains(material) || !material.isSolid();
    }

    private boolean isOnCooldown(UUID playerId) {
        Long lastUse = gatewayCooldowns.get(playerId);
        if (lastUse == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastUse) < COOLDOWN_MS;
    }

    private void setCooldown(UUID playerId) {
        gatewayCooldowns.put(playerId, System.currentTimeMillis());
    }

    private String formatLocation(Location loc) {
        return String.format("[%.1f, %.1f, %.1f in %s]", loc.getX(), loc.getY(), loc.getZ(), 
            loc.getWorld() != null ? loc.getWorld().getName() : "null");
    }
}
