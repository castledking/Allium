package codes.castled.allium.rtp;

import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.TeleportSettings;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Finds one random spot: a candidate in the area's ring, its chunk loaded (and generated) off the
 * server thread where possible, then a server-thread check of the world border, height limits,
 * excluded biomes, blocks to avoid, {@link DestinationSafety} and claims. Tries are sequential, so a
 * search never loads more than one chunk at a time.
 */
final class RtpSearch {

    /** Blocks kept clear of the world border, so the player's footprint and neighbours fit inside. */
    static final double BORDER_MARGIN = 8;
    static final int NETHER_MIN_Y = 32;
    static final int NETHER_MAX_Y = 120;

    private final TaskScheduler scheduler;
    private final DestinationSafety safety;
    private final Supplier<ProtectionService> protection;

    RtpSearch(TaskScheduler scheduler, DestinationSafety safety, Supplier<ProtectionService> protection) {
        this.scheduler = scheduler;
        this.safety = safety;
        this.protection = protection;
    }

    /**
     * Server thread. Completes on the server thread with a safe spot, or null after {@code attempts}
     * tries. {@code player} is who will land there (for claims); null skips the claim check, which is
     * then made when the spot is used. {@code biomes}, when given, are the only biomes accepted
     * ({@code /rtp biome}); otherwise {@code excluded-biomes} are refused.
     */
    CompletableFuture<Location> find(World world, RtpSettings.Area area, RtpSettings settings, @Nullable Player player,
                                     TeleportSettings rules, int attempts, @Nullable Set<String> biomes) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        attempt(world, area, settings, player, rules, 0, attempts, biomes, result);
        return result;
    }

    private void attempt(World world, RtpSettings.Area area, RtpSettings settings, @Nullable Player player,
                         TeleportSettings rules, int tried, int attempts, @Nullable Set<String> biomes,
                         CompletableFuture<Location> result) {
        if (tried >= attempts || result.isDone()) {
            result.complete(null);
            return;
        }
        int[] column = candidate(world, area, settings, biomes);
        if (column == null) {
            attempt(world, area, settings, player, rules, tried + 1, attempts, biomes, result);
            return;
        }
        int bx = column[0];
        int bz = column[1];
        AsyncChunks.load(world, bx >> 4, bz >> 4, scheduler).whenComplete((chunk, error) -> onServer(() -> {
            Location spot = null;
            if (error == null && player != null && !player.isOnline()) {
                result.complete(null);
                return;
            }
            if (error == null) {
                try {
                    spot = evaluate(world, bx, bz, area, settings, player, rules, biomes);
                } catch (RuntimeException failed) {
                    spot = null;
                }
            }
            if (spot != null) {
                result.complete(spot);
            } else {
                attempt(world, area, settings, player, rules, tried + 1, attempts, biomes, result);
            }
        }));
    }

    /**
     * A column inside the ring and the world border. Where the server can tell a spot's generated biome
     * without loading it (Paper), up to {@code biome-scan} columns are probed for an acceptable biome
     * first, so no chunk is generated just to find an ocean. Null when none qualifies.
     */
    @Nullable
    int[] candidate(World world, RtpSettings.Area area, RtpSettings settings, @Nullable Set<String> biomes) {
        WorldBorder border = world.getWorldBorder();
        Location borderCenter = border.getCenter();
        double cx = area.centerX() != null ? area.centerX() : borderCenter.getX();
        double cz = area.centerZ() != null ? area.centerZ() : borderCenter.getZ();
        double max = area.maxRange() != null ? area.maxRange() : settings.defaultMaxRange();
        boolean probe = BiomeProbe.available() && (biomes != null || !settings.excludedBiomes().isEmpty());
        int probeY = world.getEnvironment() == World.Environment.NORMAL ? world.getSeaLevel() : 64;
        for (int scan = 0; scan < (probe ? settings.biomeScan() : 1); scan++) {
            double[] offset = RtpGeometry.offset(area.shape(), area.minRange(), max, ThreadLocalRandom.current());
            int bx = (int) Math.floor(cx + offset[0]);
            int bz = (int) Math.floor(cz + offset[1]);
            if (!insideBorder(border, bx + 0.5, bz + 0.5)) continue;
            if (probe) {
                String biome = BiomeProbe.computed(world, bx, probeY, bz);
                if (biome != null && !acceptedBiome(biome, settings, biomes)) continue;
            }
            return new int[]{bx, bz};
        }
        return null;
    }

    static boolean acceptedBiome(String biome, RtpSettings settings, @Nullable Set<String> biomes) {
        return biomes != null ? biomes.contains(biome) : !settings.excludedBiomes().contains(biome);
    }

    private void onServer(Runnable task) {
        if (scheduler.isServerThread()) {
            task.run();
        } else {
            scheduler.runOnServer(task);
        }
    }

    private static boolean insideBorder(WorldBorder border, double x, double z) {
        Location center = border.getCenter();
        double half = border.getSize() / 2 - BORDER_MARGIN;
        return half > 0 && Math.abs(x - center.getX()) <= half && Math.abs(z - center.getZ()) <= half;
    }

    /** Server thread, with the column's chunk loaded: the spot to stand on at (bx, bz), or null. */
    @Nullable
    Location evaluate(World world, int bx, int bz, RtpSettings.Area area, RtpSettings settings, @Nullable Player player,
                      TeleportSettings rules, @Nullable Set<String> biomes) {
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        int minY = area.minY() != null ? area.minY() : nether ? NETHER_MIN_Y : world.getMinHeight() + 1;
        int maxY = area.maxY() != null ? area.maxY() : nether ? NETHER_MAX_Y : world.getMaxHeight() - 2;
        float yaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
        Location spot = nether || area.maxY() != null && area.maxY() < world.getMaxHeight() - 2
                ? scanUp(world, bx, bz, minY, maxY, rules, yaw)
                : surface(world, bx, bz, yaw);
        if (spot == null || spot.getBlockY() < minY || spot.getBlockY() > maxY) return null;
        Block floor = world.getBlockAt(bx, spot.getBlockY() - 1, bz);
        if (RtpGeometry.matches(settings.avoidBlocks(), floor.getType().name())) return null;
        // Through Keyed: Biome is an enum on 1.20 and an interface on 26.x, and both are Keyed.
        org.bukkit.Keyed biomeType = world.getBiome(bx, spot.getBlockY(), bz);
        String biome = RtpSettings.biomeKey(biomeType.getKey().getKey());
        if (!acceptedBiome(biome, settings, biomes)) return null;
        if (!safety.safe(spot, rules)) return null;
        if (player != null && !claimFree(player, spot, settings)) return null;
        return spot;
    }

    /** The top of the column, ignoring leaves so a player lands under a tree rather than in it. */
    @Nullable
    private static Location surface(World world, int bx, int bz, float yaw) {
        int top = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top < world.getMinHeight()) return null;
        return new Location(world, bx + 0.5, top + 1, bz + 0.5, yaw, 0);
    }

    /** The lowest safe standing spot between minY and maxY: caves in the nether, below a configured ceiling. */
    @Nullable
    private Location scanUp(World world, int bx, int bz, int minY, int maxY, TeleportSettings rules, float yaw) {
        for (int y = Math.max(minY, world.getMinHeight() + 1); y <= maxY; y++) {
            Location candidate = new Location(world, bx + 0.5, y, bz + 0.5, yaw, 0);
            if (!world.getBlockAt(bx, y - 1, bz).isPassable() && safety.safe(candidate, rules)) return candidate;
        }
        return null;
    }

    /** Whether claim plugins (through Allium's protection hooks) let the player build there. */
    boolean claimFree(Player player, Location spot, RtpSettings settings) {
        if (!settings.avoidClaims()) return true;
        ProtectionService service = protection.get();
        return service == null || service.checkBlockBreak(player, spot, "rtp", null).allowed();
    }
}
