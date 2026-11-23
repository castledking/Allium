package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;

/** The destination next to the first block in the player's sight. */
public final class TeleportSight {
    private static final double RANGE = 128;
    private static final double EDGE_EPSILON = 0.000_05;

    private TeleportSight() {}

    /** Called from the player's owner. Folia walks the ray in order through loaded chunk owners. */
    public static CompletableFuture<Location> destination(PluginStart plugin, Player player) {
        if (!BukkitServerThread.isFolia()) {
            var position = player.getLocation();
            return CompletableFuture.completedFuture(landing(player.rayTraceBlocks(RANGE, FluidCollisionMode.NEVER), position));
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        return new Trace(plugin, eye, direction).scan(0);
    }

    private record Trace(PluginStart plugin, Location eye, Vector direction) {
        CompletableFuture<Location> scan(double distance) {
            if (distance >= RANGE) return CompletableFuture.completedFuture(null);
            World world = eye.getWorld();
            double startDistance = distance + EDGE_EPSILON;
            Location start = eye.clone().add(direction.clone().multiply(startDistance));
            // An eye exactly on a chunk edge belongs to the chunk in the direction of travel.
            int chunkX = start.getBlockX() >> 4;
            int chunkZ = start.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return CompletableFuture.completedFuture(null);
            double end = Math.min(RANGE, Math.min(
                    exitDistance(eye.getX(), direction.getX(), chunkX),
                    exitDistance(eye.getZ(), direction.getZ(), chunkZ)));
            if (end <= startDistance) return CompletableFuture.completedFuture(null);

            var result = new CompletableFuture<Location>();
            Runnable trace = () -> {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        result.complete(null);
                        return;
                    }
                    // Bukkit's clip slightly extends its end point. Keep that extension in this chunk.
                    double length = end - EDGE_EPSILON - startDistance;
                    Location destination = length > 0
                            ? landing(world.rayTraceBlocks(start, direction, length, FluidCollisionMode.NEVER), eye)
                            : null;
                    if (destination != null) result.complete(destination);
                    else scan(end).whenComplete((next, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(next);
                    });
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            };
            var owner = BukkitServerThread.region(plugin, world, chunkX, chunkZ);
            if (owner.isServerThread()) trace.run();
            else if (plugin.getScheduler().runOn(owner, trace, () -> result.complete(null), 0) == TaskHandle.NONE) result.complete(null);
            return result;
        }
    }

    private static double exitDistance(double origin, double direction, int chunk) {
        if (Math.abs(direction) < 1e-12) return Double.POSITIVE_INFINITY;
        double edge = (chunk + (direction > 0 ? 1 : 0)) * 16.0;
        return (edge - origin) / direction;
    }

    private static Location landing(RayTraceResult hit, Location facing) {
        if (hit == null || hit.getHitBlock() == null) return null;
        var block = hit.getHitBlock();
        var face = hit.getHitBlockFace() == null ? BlockFace.UP : hit.getHitBlockFace();
        return new Location(block.getWorld(), block.getX() + face.getModX() + .5, block.getY() + face.getModY(),
                block.getZ() + face.getModZ() + .5, facing.getYaw(), facing.getPitch());
    }
}
