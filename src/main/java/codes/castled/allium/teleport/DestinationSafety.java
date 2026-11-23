package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Bounded server-thread search. The destination's chunk and the few neighbors the search reaches
 * are loaded from disk when they exist; terrain that was never generated is only generated when
 * {@code teleport.safety.load-destination-chunk} is true.
 */
public final class DestinationSafety {
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final int LANDING_SCAN = 16;

    public Optional<Location> resolve(Location requested, TeleportSettings rules) {
        Objects.requireNonNull(rules, "rules");
        if (requested == null || requested.getWorld() == null || !withinWorld(requested)) return Optional.empty();
        World world = requested.getWorld();
        int chunkX = requested.getBlockX() >> 4;
        int chunkZ = requested.getBlockZ() >> 4;
        // Homes and warps are almost always in unloaded chunks: load the destination's chunk, and
        // the neighbors the search reaches, from disk (as vanilla /tp does). New terrain is only
        // generated when load-destination-chunk allows it.
        int reach = (rules.safety() && rules.adjust() ? rules.radius() : 0) + 1;
        for (int cx = (requested.getBlockX() - reach) >> 4; cx <= (requested.getBlockX() + reach) >> 4; cx++) {
            for (int cz = (requested.getBlockZ() - reach) >> 4; cz <= (requested.getBlockZ() + reach) >> 4; cz++) {
                // Only the destination's own chunk may be generated; neighbors are loaded only if they exist.
                boolean destination = cx == chunkX && cz == chunkZ;
                if (!available(world, cx, cz, destination && rules.loadChunk()) && destination) return Optional.empty();
            }
        }
        if (safe(requested, rules)) return Optional.of(requested.clone());
        if (!rules.safety() || !rules.adjust()) return Optional.empty();
        for (Location candidate : alternatives(requested, rules)) {
            if (safe(candidate, rules)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static List<Location> alternatives(Location requested, TeleportSettings rules) {
        // At most 17 * 17 * 33 candidates. The radius and height are block offsets from the
        // requested block; distance orders the centered candidates instead of preferring a column.
        List<Location> candidates = new ArrayList<>();
        World world = requested.getWorld();
        for (int dx = -rules.radius(); dx <= rules.radius(); dx++) {
            for (int dz = -rules.radius(); dz <= rules.radius(); dz++) {
                for (int dy = -rules.vertical(); dy <= rules.vertical(); dy++) {
                    candidates.add(new Location(world, requested.getBlockX() + dx + 0.5,
                            requested.getBlockY() + dy, requested.getBlockZ() + dz + 0.5,
                            requested.getYaw(), requested.getPitch()));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Location candidate) -> candidate.distanceSquared(requested))
                .thenComparingDouble(Location::getY).thenComparingDouble(Location::getX).thenComparingDouble(Location::getZ));
        return candidates;
    }

    record RegionalResult(Optional<Location> destination, boolean ungenerated) {}
    private record ChunkPosition(int x, int z) {}
    private record ChunkScan(BitSet unsafe, boolean available, boolean ungenerated) {}
    private record Footprint(int minX, int maxX, int minZ, int maxZ) {
        Footprint(Location point) {
            this(floor(point.getX() - HALF_WIDTH), floor(Math.nextDown(point.getX() + HALF_WIDTH)),
                    floor(point.getZ() - HALF_WIDTH), floor(Math.nextDown(point.getZ() + HALF_WIDTH)));
        }
    }

    /** Public regional safety resolver for cross-region callers on Folia. */
    public CompletableFuture<Optional<Location>> resolveAsync(PluginStart plugin, Location requested, TeleportSettings rules) {
        return resolveRegional(plugin, requested, rules).thenApply(RegionalResult::destination);
    }

    /** Checks real block collision data on the region that owns each column of a candidate. */
    CompletableFuture<RegionalResult> resolveRegional(PluginStart plugin, Location requested, TeleportSettings rules) {
        if (requested == null || requested.getWorld() == null || !withinWorld(requested)) {
            return CompletableFuture.completedFuture(new RegionalResult(Optional.empty(), false));
        }
        World world = requested.getWorld();
        var primary = new ChunkPosition(requested.getBlockX() >> 4, requested.getBlockZ() >> 4);
        return scan(plugin, world, primary, List.of(requested), rules, false).thenCompose(first -> {
            if (first.ungenerated() || first.destination().isPresent() || !rules.safety() || !rules.adjust()) {
                return CompletableFuture.completedFuture(first);
            }
            return scan(plugin, world, primary, alternatives(requested, rules), rules, true);
        });
    }

    private static CompletableFuture<RegionalResult> scan(PluginStart plugin, World world, ChunkPosition primary,
                                                           List<Location> candidates, TeleportSettings rules, boolean centered) {
        Map<ChunkPosition, BitSet> byChunk = new LinkedHashMap<>();
        byChunk.put(primary, new BitSet());
        for (int i = 0; i < candidates.size(); i++) {
            var footprint = new Footprint(candidates.get(i));
            for (int x = footprint.minX() >> 4; x <= footprint.maxX() >> 4; x++) {
                for (int z = footprint.minZ() >> 4; z <= footprint.maxZ() >> 4; z++) {
                    byChunk.computeIfAbsent(new ChunkPosition(x, z), ignored -> new BitSet()).set(i);
                }
            }
        }
        var scans = byChunk.entrySet().stream().map(entry -> {
            var chunk = entry.getKey();
            boolean generate = chunk.equals(primary) && rules.loadChunk();
            return loadAndScan(plugin, world, chunk, generate, candidates, entry.getValue(), rules, centered);
        }).toList();
        return CompletableFuture.allOf(scans.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var rejected = new BitSet();
            // The primary chunk must exist even when an adjusted candidate is in a neighbor.
            var primaryScan = scans.get(0).join();
            if (!primaryScan.available()) return new RegionalResult(Optional.empty(), primaryScan.ungenerated());
            for (var scan : scans) rejected.or(scan.join().unsafe());
            int chosen = rejected.nextClearBit(0);
            return new RegionalResult(chosen < candidates.size() ? Optional.of(candidates.get(chosen).clone()) : Optional.empty(), false);
        });
    }

    private static CompletableFuture<ChunkScan> loadAndScan(PluginStart plugin, World world, ChunkPosition chunk, boolean generate,
                                                            List<Location> candidates, BitSet indices, TeleportSettings rules, boolean centered) {
        return BukkitServerThread.loadRegionalChunk(world, chunk.x(), chunk.z(), generate).thenCompose(loaded -> {
            if (loaded == null) return CompletableFuture.completedFuture(new ChunkScan(indices, false, !generate));
            return atChunk(plugin, world, chunk, () -> scanLoadedChunk(world, chunk, candidates, indices, rules, centered));
        });
    }

    private static ChunkScan scanLoadedChunk(World world, ChunkPosition chunk, List<Location> candidates,
                                             BitSet indices, TeleportSettings rules, boolean centered) {
        if (!world.isChunkLoaded(chunk.x(), chunk.z())) return new ChunkScan(indices, false, false);
        var unsafe = new BitSet();
        for (int i = indices.nextSetBit(0); i >= 0; i = indices.nextSetBit(i + 1)) {
            Location point = candidates.get(i);
            if (!withinWorld(point) || !safeInChunk(world, chunk, point, rules)) {
                unsafe.set(i);
            } else if (centered) {
                // Each centered candidate belongs to one chunk, so only its first success can win.
                break;
            }
        }
        return new ChunkScan(unsafe, true, false);
    }

    private static <T> CompletableFuture<T> atChunk(PluginStart plugin, World world, ChunkPosition chunk, Supplier<T> read) {
        var result = new CompletableFuture<T>();
        Runnable check = () -> {
            try { result.complete(read.get()); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        };
        try {
            var owner = BukkitServerThread.region(plugin, world, chunk.x(), chunk.z());
            if (owner.isServerThread()) check.run();
            else plugin.getScheduler().runOn(owner, check, () -> {}, 0);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static boolean safeInChunk(World world, ChunkPosition chunk, Location point, TeleportSettings rules) {
        if (!rules.safety()) return true;
        var footprint = new Footprint(point);
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if ((x >> 4) == chunk.x() && (z >> 4) == chunk.z() && !safeColumn(world, point, x, z, rules)) return false;
            }
        }
        return true;
    }

    /** True when the chunk is loaded, or was loaded now: from disk if generated, generated only when {@code generate}. */
    private static boolean available(World world, int cx, int cz, boolean generate) {
        if (world.isChunkLoaded(cx, cz)) return true;
        if (!generate && !world.isChunkGenerated(cx, cz)) return false;
        try {
            world.getChunkAt(cx, cz);
        } catch (RuntimeException loadFailure) {
            return false;
        }
        return world.isChunkLoaded(cx, cz);
    }

    /**
     * True when {@code requested} lies in terrain that was never generated and the rules do not
     * allow generating it, so the refusal can say that instead of "no safe destination".
     */
    public boolean ungenerated(Location requested, TeleportSettings rules) {
        World world = requested == null ? null : requested.getWorld();
        if (world == null || rules.loadChunk()) return false;
        int cx = requested.getBlockX() >> 4, cz = requested.getBlockZ() >> 4;
        return !world.isChunkLoaded(cx, cz) && !world.isChunkGenerated(cx, cz);
    }

    /**
     * Whether a player could stand at {@code point}: false unless its footprint's chunks are already
     * loaded, so it never loads terrain itself.
     */
    public boolean safe(Location point, TeleportSettings rules) {
        if (point == null || point.getWorld() == null || !withinWorld(point)) return false;
        World world = point.getWorld();
        var footprint = new Footprint(point);
        // Preflight the entire footprint before any block reads, which can otherwise load chunks.
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            }
        }
        if (!rules.safety()) return true;

        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (!safeColumn(world, point, x, z, rules)) return false;
            }
        }
        return true;
    }

    private static boolean safeColumn(World world, Location point, int x, int z, TeleportSettings rules) {
        int feetY = point.getBlockY();
        int headY = floor(Math.nextDown(point.getY() + HEIGHT));
        for (int y = feetY; y <= headY; y++) {
            Block body = world.getBlockAt(x, y, z);
            if (rules.suffocation() && !body.isPassable()) return false;
            if (rules.lava() && danger(body.getType())) return false;
        }
        int floorY = feetY - 1;
        if (floorY < world.getMinHeight()) return !rules.falls() && !rules.voids();
        Block floor = world.getBlockAt(x, floorY, z);
        if (rules.lava() && danger(floor.getType())) return false;
        if (sturdy(world, x, floorY, z, floor) || floor.getType() == Material.WATER) return true;
        return !rules.falls() && (!rules.voids() || landingBelow(world, x, floorY, z, floor, rules));
    }

    private static boolean landingBelow(World world, int x, int floorY, int z, Block floor, TeleportSettings rules) {
        // A heightmap can report a roof above the player. Only evidence below their feet counts.
        // Falls may be enabled, but an unknown landing after this bounded scan still fails closed.
        for (int depth = 0; depth < LANDING_SCAN && floorY - depth >= world.getMinHeight(); depth++) {
            int y = floorY - depth;
            Block below = depth == 0 ? floor : world.getBlockAt(x, y, z);
            if (rules.lava() && danger(below.getType())) return false;
            if (sturdy(world, x, y, z, below) || below.getType() == Material.WATER) return true;
            if (!below.isPassable()) return false; // Non-sturdy collision is not a proven landing.
        }
        return false;
    }

    private static boolean sturdy(World world, int x, int y, int z, Block block) {
        if (block.isPassable() || !block.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL)) {
            return false;
        }
        if (!block.getType().hasGravity()) {
            return true;
        }
        // A gravity block (sand, gravel, concrete powder) is stable when supported underneath by solid ground.
        for (int depth = 1; depth <= LANDING_SCAN && y - depth >= world.getMinHeight(); depth++) {
            Block below = world.getBlockAt(x, y - depth, z);
            if (below.isPassable() || danger(below.getType())) {
                return false;
            }
            if (!below.getType().hasGravity()) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinWorld(Location point) {
        if (!coordinateBounds(point)) return false;
        World world = point.getWorld();
        if (point.getY() < world.getMinHeight() || point.getY() + HEIGHT > world.getMaxHeight()) return false;
        // Legacy Spigot's isInside tests block intersection, not the precise point. Check the
        // entire player footprint geometrically too, including fractional border edges.
        var border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfSize = border.getSize() / 2;
        if (center == null || !finite(center) || !Double.isFinite(halfSize) || halfSize <= 0
                || point.getX() - HALF_WIDTH < center.getX() - halfSize
                || point.getX() + HALF_WIDTH > center.getX() + halfSize
                || point.getZ() - HALF_WIDTH < center.getZ() - halfSize
                || point.getZ() + HALF_WIDTH > center.getZ() + halfSize) return false;
        for (double x : new double[]{point.getX() - HALF_WIDTH, point.getX() + HALF_WIDTH}) {
            for (double z : new double[]{point.getZ() - HALF_WIDTH, point.getZ() + HALF_WIDTH}) {
                if (!border.isInside(new Location(world, x, point.getY(), z))) return false;
            }
        }
        return true;
    }

    private static boolean coordinateBounds(Location point) {
        // Leave room for bounded offsets before converting coordinates to block integers.
        return finite(point) && point.getX() >= Integer.MIN_VALUE + 16.0 && point.getX() <= Integer.MAX_VALUE - 16.0
                && point.getZ() >= Integer.MIN_VALUE + 16.0 && point.getZ() <= Integer.MAX_VALUE - 16.0;
    }

    private static int floor(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static boolean danger(Material material) {
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW -> true;
            default -> false;
        };
    }

    private static boolean finite(Location point) {
        return Double.isFinite(point.getX()) && Double.isFinite(point.getY()) && Double.isFinite(point.getZ())
                && Float.isFinite(point.getYaw()) && Float.isFinite(point.getPitch());
    }
}
