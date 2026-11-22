package codes.castled.allium.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.EndGateway;
import org.bukkit.event.Event;

import java.util.Map;
import java.util.Optional;

/** Bounded End return search anchored to the gateway's exit instead of the heightmapped landing. */
final class EndGatewayReturn {
    static final String ENTITY_EVENT = "com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent";
    private static final String PLAYER_EVENT = "com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent";
    private static final TeleportSettings SAFE = new TeleportSettings(0, 0, 100, false,
            true, false, 0, 0, true, true, true, true, false, Map.of());
    private final DestinationSafety safety;

    EndGatewayReturn(DestinationSafety safety) {
        this.safety = safety;
    }

    static Location exit(Event event) {
        String type = event.getClass().getName();
        if (!type.equals(PLAYER_EVENT) && !type.equals(ENTITY_EVENT)) return null;
        try {
            Object gateway = event.getClass().getMethod("getGateway").invoke(event);
            return gateway instanceof EndGateway end ? end.getExitLocation() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    Optional<Location> resolve(Location destination, Location exit, int maxDistance, int radius) {
        if (destination == null || destination.getWorld() == null
                || destination.getWorld().getEnvironment() != World.Environment.THE_END) return Optional.empty();
        World world = destination.getWorld();
        Location base = exit != null && world.equals(exit.getWorld()) && exit.getY() > world.getMinHeight()
                && exit.getY() < world.getMaxHeight() - 1 ? exit : destination;
        if (!Double.isFinite(base.getX()) || !Double.isFinite(base.getY()) || !Double.isFinite(base.getZ())
                || Math.hypot(base.getX(), base.getZ()) > Math.max(0, maxDistance)) return Optional.empty();

        int reach = Math.max(0, Math.min(8, radius));
        Location found = atLevel(base, destination, 0, reach);
        if (found != null) return Optional.of(found);
        for (int dy = 1; dy <= 16; dy++) {
            found = atLevel(base, destination, -dy, reach);
            if (found != null) return Optional.of(found);
        }
        for (int dy = 1; dy <= 3; dy++) {
            found = atLevel(base, destination, dy, reach);
            if (found != null) return Optional.of(found);
        }
        return Optional.empty();
    }

    private Location atLevel(Location base, Location orientation, int dy, int reach) {
        int y = base.getBlockY() + dy;
        World world = base.getWorld();
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) return null;
        for (int ring = 0; ring <= reach; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = base.getBlockX() + dx;
                    int z = base.getBlockZ() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Material floor = world.getBlockAt(x, y - 1, z).getType();
                    if (!floor.isSolid() || floor.name().endsWith("_LEAVES")) continue;
                    if (portal(world.getBlockAt(x, y, z).getType())
                            || portal(world.getBlockAt(x, y + 1, z).getType())) continue;
                    Location point = new Location(world, x + .5, y, z + .5, orientation.getYaw(), orientation.getPitch());
                    if (safety.safe(point, SAFE)) return point;
                }
            }
        }
        return null;
    }

    private static boolean portal(Material material) {
        return material == Material.END_GATEWAY || material == Material.END_PORTAL || material == Material.NETHER_PORTAL;
    }
}
