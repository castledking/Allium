package codes.castled.allium.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DestinationSafetyTest {

    private final DestinationSafety safety = new DestinationSafety();

    @Test
    void safeDestinationRetainsExactCoordinatesAndAnglesAndReturnsACopy() {
        Terrain terrain = new Terrain();
        terrain.groundY = 63;
        Location requested = new Location(terrain.world, 0.5, 64, 0.5, -75.25f, 20.5f);
        Location resolved = safety.resolve(requested, rules(false, 0, 0, true, true, false)).orElseThrow();
        assertEquals(requested, resolved);
        assertNotSame(requested, resolved);
        resolved.setX(99);
        assertEquals(0.5, requested.getX());
    }

    @Test
    void roofAboveThePlayerDoesNotCountAsGroundBelowEvenWhenFallsAreAllowed() {
        Terrain terrain = new Terrain();
        terrain.put(0, 80, 0, Material.STONE);
        when(terrain.world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(80);
        assertTrue(safety.resolve(terrain.at(0.5, 64, 0.5), rules(false, 0, 0, false, true, false)).isEmpty());
        verify(terrain.world, never()).getHighestBlockYAt(anyInt(), anyInt());
        assertTrue(terrain.reads.stream().allMatch(pos -> pos.y >= 48 && pos.y <= 65), "void proof is bounded and downward");
    }

    @Test
    void optionalFallsStillNeedEvidenceOfALandingWithinSixteenBlocksWhenAvoidingVoid() {
        Terrain terrain = new Terrain();
        Location destination = terrain.at(0.5, 64, 0.5);
        TeleportSettings allowFalls = rules(false, 0, 0, false, true, false);
        terrain.put(0, 48, 0, Material.STONE);
        assertTrue(safety.resolve(destination, allowFalls).isPresent(), "landing at the scan limit is known");
        terrain.put(0, 48, 0, Material.AIR);
        terrain.put(0, 47, 0, Material.STONE);
        assertTrue(safety.resolve(destination, allowFalls).isEmpty(), "a deeper landing is uncertain");
        assertTrue(safety.resolve(destination, rules(false, 0, 0, false, false, false)).isPresent(), "both fall checks can be explicitly disabled");
    }

    @Test
    void defaultFallPolicyRequiresAnImmediateSturdyNonGravityFloorAtEveryCorner() {
        Terrain terrain = new Terrain();
        terrain.groundY = 63;
        Location boundary = terrain.at(0.85, 64, 0.5);
        TeleportSettings settings = rules(false, 0, 0, true, true, false);
        assertTrue(safety.resolve(boundary, settings).isPresent());
        terrain.put(1, 63, 0, Material.AIR);
        assertTrue(safety.resolve(boundary, settings).isEmpty(), "one unsupported body corner is unsafe");
        terrain.put(1, 63, 0, Material.SAND);
        assertTrue(safety.resolve(boundary, settings).isEmpty(), "a falling block is not a stable landing");
        terrain.put(1, 63, 0, Material.OAK_FENCE);
        assertTrue(safety.resolve(boundary, settings).isEmpty(), "a solid material alone does not prove full top support");
    }

    @Test
    void hazardsAndCollisionAreCheckedAcrossTheBodyIncludingItsThirdVerticalBlockLayer() {
        for (Material hazard : new Material[]{Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.POWDER_SNOW}) {
            for (int y : new int[]{63, 64, 65}) {
                Terrain terrain = new Terrain();
                terrain.groundY = 63;
                terrain.put(1, y, 1, hazard);
                assertTrue(safety.resolve(terrain.at(0.85, 64, 0.85), rules(false, 0, 0, true, true, false)).isEmpty(), hazard + " at " + y);
            }
        }
        Terrain headCollision = new Terrain();
        headCollision.groundY = 63;
        headCollision.put(1, 66, 1, Material.STONE);
        assertTrue(safety.resolve(headCollision.at(0.85, 64.25, 0.85), rules(false, 0, 0, true, true, false)).isEmpty(),
                "a player at fractional Y reaches into a third block layer");
        headCollision.put(1, 66, 1, Material.AIR);
        headCollision.put(1, 64, 1, Material.STONE);
        assertTrue(safety.resolve(headCollision.at(0.85, 64.25, 0.85), rules(false, 0, 0, true, true, false)).isEmpty(), "feet overlap matters too");
    }

    @Test
    void hazardPolicyAloneCoversAllFourCornersIncludingNegativeCoordinates() {
        TeleportSettings avoidHazards = new TeleportSettings(0, 0, 100, false, true, false, 0, 0,
                true, false, false, false, false, Map.of());
        TeleportSettings allowHazards = new TeleportSettings(0, 0, 100, false, true, false, 0, 0,
                false, false, false, false, false, Map.of());
        for (int x : new int[]{-1, 0}) {
            for (int z : new int[]{-1, 0}) {
                for (int y : new int[]{63, 64, 65, 66}) {
                    Terrain terrain = new Terrain();
                    terrain.put(x, y, z, y == 63 ? Material.MAGMA_BLOCK : Material.SOUL_FIRE);
                    Location destination = terrain.at(0, 64.25, 0);
                    assertTrue(safety.resolve(destination, avoidHazards).isEmpty(), "hazard in body/floor at " + new Pos(x, y, z));
                    assertTrue(safety.resolve(destination, allowHazards).isPresent(), "hazard policy can be explicitly disabled");
                }
            }
        }
    }

    @Test
    void legacyBlockIntersectionBorderCannotAuthorizeACrossingPlayerFootprint() {
        Terrain terrain = new Terrain();
        terrain.borderRadius = 10.25;
        terrain.groundY = 63;
        when(terrain.border.isInside(any(Location.class))).thenReturn(true); // Old block-intersection result.
        assertTrue(safety.resolve(terrain.at(9.9, 64, 0.5), rules(false, 0, 0, true, true, false)).isPresent());
        assertTrue(safety.resolve(terrain.at(10.0, 64, 0.5), rules(false, 0, 0, true, true, false)).isEmpty());
        assertTrue(safety.resolve(terrain.at(-10.0, 64, 0.5), rules(false, 0, 0, true, true, false)).isEmpty());
    }

    @Test
    void buildHeightAndBorderContainTheEntirePlayerEvenWhenSafetyIsDisabled() {
        Terrain terrain = new Terrain();
        TeleportSettings disabled = new TeleportSettings(0, 0, 100, false, false, false, 0, 0,
                false, false, false, false, false, Map.of());
        assertTrue(safety.resolve(terrain.at(0.5, -64, 0.5), disabled).isPresent());
        assertTrue(safety.resolve(terrain.at(0.5, -64.01, 0.5), disabled).isEmpty());
        assertTrue(safety.resolve(terrain.at(0.5, 318.2, 0.5), disabled).isPresent());
        assertTrue(safety.resolve(terrain.at(0.5, 318.21, 0.5), disabled).isEmpty());
        terrain.borderRadius = 10;
        assertTrue(safety.resolve(terrain.at(9.6, 64, 0.5), disabled).isPresent());
        assertTrue(safety.resolve(terrain.at(9.8, 64, 0.5), disabled).isEmpty(), "a center inside the border is insufficient");
        assertTrue(terrain.reads.isEmpty(), "disabled safety still enforces bounds without reading blocks");
    }

    @Test
    void searchSelectsTheNearestSafeCandidateRatherThanPreferringTheOriginalColumn() {
        Terrain terrain = new Terrain();
        terrain.put(0, 63, 0, Material.LAVA);
        terrain.put(0, 65, 0, Material.STONE); // safe at Y=66, distance 2
        terrain.put(1, 63, 0, Material.STONE); // safe to the east, distance 1
        Location destination = new Location(terrain.world, 0.5, 64, 0.5, 33, -12);
        Location resolved = safety.resolve(destination, rules(true, 1, 2, true, true, false)).orElseThrow();
        assertEquals(1.5, resolved.getX());
        assertEquals(64, resolved.getY());
        assertEquals(0.5, resolved.getZ());
        assertEquals(33, resolved.getYaw());
        assertEquals(-12, resolved.getPitch());
        assertEquals(0.5, destination.getX());
    }

    @Test
    void adjustmentHonorsHorizontalAndVerticalBoundsAndItsDisableSwitch() {
        Terrain terrain = new Terrain();
        terrain.put(2, 63, 0, Material.STONE);
        Location destination = terrain.at(0.5, 64, 0.5);
        assertTrue(safety.resolve(destination, rules(false, 2, 2, true, true, false)).isEmpty());
        assertTrue(safety.resolve(destination, rules(true, 1, 0, true, true, false)).isEmpty());
        assertEquals(2.5, safety.resolve(destination, rules(true, 2, 0, true, true, false)).orElseThrow().getX());
        Terrain vertical = new Terrain();
        vertical.put(0, 65, 0, Material.STONE);
        assertTrue(safety.resolve(vertical.at(0.5, 64, 0.5), rules(true, 0, 1, true, true, false)).isEmpty());
        assertEquals(66, safety.resolve(vertical.at(0.5, 64, 0.5), rules(true, 0, 2, true, true, false)).orElseThrow().getY());
    }

    @Test
    void searchAndBodyChecksNeverReadOrLoadAnUnloadedNeighbor() {
        Terrain terrain = new Terrain();
        terrain.groundY = 63;
        terrain.unloaded.add(new ChunkPos(1, 0));
        assertTrue(safety.resolve(terrain.at(15.85, 64, 0.5), rules(false, 0, 0, true, true, false)).isEmpty());
        assertTrue(terrain.reads.stream().noneMatch(pos -> pos.x >= 16));
        assertTrue(safety.resolve(terrain.at(15.5, 64, 0.5), rules(false, 0, 0, true, true, false)).isPresent());
        verify(terrain.world, never()).getChunkAt(anyInt(), anyInt());
        Terrain onlyNeighborSafe = new Terrain();
        onlyNeighborSafe.unloaded.add(new ChunkPos(1, 0));
        onlyNeighborSafe.put(16, 63, 0, Material.STONE);
        assertTrue(safety.resolve(onlyNeighborSafe.at(15.5, 64, 0.5), rules(true, 1, 1, true, true, true)).isEmpty());
        verify(onlyNeighborSafe.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void onlyTheBaseChunkMayBeExplicitlyLoadedOnceWhenEnabled() {
        Terrain terrain = new Terrain();
        terrain.groundY = 63;
        terrain.unloaded.add(new ChunkPos(0, 0));
        Location destination = terrain.at(0.5, 64, 0.5);
        assertTrue(safety.resolve(destination, rules(false, 0, 0, true, true, false)).isEmpty());
        verify(terrain.world, never()).getChunkAt(anyInt(), anyInt());
        assertTrue(terrain.reads.isEmpty());
        assertTrue(safety.resolve(destination, rules(false, 0, 0, true, true, true)).isPresent());
        verify(terrain.world, times(1)).getChunkAt(0, 0);
        Terrain failedLoad = new Terrain();
        failedLoad.unloaded.add(new ChunkPos(0, 0));
        failedLoad.loadSucceeds = false;
        assertTrue(safety.resolve(failedLoad.at(0.5, 64, 0.5), rules(true, 1, 1, true, true, true)).isEmpty());
        assertTrue(failedLoad.reads.isEmpty());
        verify(failedLoad.world, times(1)).getChunkAt(0, 0);
        Terrain throwingLoad = new Terrain();
        throwingLoad.unloaded.add(new ChunkPos(0, 0));
        doThrow(new IllegalStateException("chunk unavailable")).when(throwingLoad.world).getChunkAt(0, 0);
        assertTrue(safety.resolve(throwingLoad.at(0.5, 64, 0.5), rules(false, 0, 0, true, true, true)).isEmpty());
        assertTrue(throwingLoad.reads.isEmpty());
    }

    @Test
    void negativeChunkBoundariesAreCheckedBeforeAnyBlockReads() {
        Terrain terrain = new Terrain();
        terrain.groundY = 63;
        terrain.unloaded.add(new ChunkPos(-1, 0));
        assertTrue(safety.resolve(terrain.at(0.15, 64, 0.5), rules(false, 0, 0, true, true, false)).isEmpty());
        assertTrue(terrain.reads.isEmpty());
        assertTrue(safety.resolve(terrain.at(0.5, 64, 0.5), rules(false, 0, 0, true, true, false)).isPresent());
        verify(terrain.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void allowedFallsDoNotCrossKnownHazardsOrAcceptPartialLandingSupport() {
        Terrain terrain = new Terrain();
        terrain.put(0, 60, 0, Material.STONE);
        terrain.put(0, 62, 0, Material.LAVA);
        TeleportSettings allowFalls = rules(false, 0, 0, false, true, false);
        Location destination = terrain.at(0.5, 64, 0.5);
        assertTrue(safety.resolve(destination, allowFalls).isEmpty(), "known lava before the landing is unsafe");
        terrain.put(0, 62, 0, Material.OAK_FENCE);
        assertTrue(safety.resolve(destination, allowFalls).isEmpty(), "uncertain partial obstruction fails closed");
        terrain.put(0, 62, 0, Material.AIR);
        assertTrue(safety.resolve(destination, allowFalls).isPresent());
    }

    @Test
    void invalidLocationsAreRejectedBeforeChunkAccessAndFloorScansStayWithinTheWorld() {
        Terrain terrain = new Terrain();
        TeleportSettings settings = rules(false, 0, 0, false, true, true);
        assertTrue(safety.resolve(null, settings).isEmpty());
        assertTrue(safety.resolve(new Location(null, 0, 64, 0), settings).isEmpty());
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            assertTrue(safety.resolve(terrain.at(coordinate, 64, 0.5), settings).isEmpty());
            assertTrue(safety.resolve(terrain.at(0.5, coordinate, 0.5), settings).isEmpty());
            assertTrue(safety.resolve(terrain.at(0.5, 64, coordinate), settings).isEmpty());
        }
        assertTrue(safety.resolve(new Location(terrain.world, 0.5, 64, 0.5, Float.NaN, 0), settings).isEmpty());
        assertTrue(safety.resolve(new Location(terrain.world, 0.5, 64, 0.5, 0, Float.POSITIVE_INFINITY), settings).isEmpty());
        verify(terrain.world, never()).getChunkAt(anyInt(), anyInt());
        assertTrue(safety.resolve(terrain.at(0.5, -63, 0.5), settings).isEmpty());
        assertTrue(terrain.reads.stream().allMatch(pos -> pos.y >= -64 && pos.y < 320));
    }

    private static TeleportSettings rules(boolean adjust, int radius, int vertical, boolean falls, boolean voids, boolean loadChunk) {
        return new TeleportSettings(0, 0, 100, false, true, adjust, radius, vertical,
                true, voids, true, falls, loadChunk, Map.of());
    }

    private record Pos(int x, int y, int z) { }
    private record ChunkPos(int x, int z) { }

    /** Explicit sparse terrain with a configurable ground plane, rather than mocked safety results. */
    private static final class Terrain {
        final World world = mock(World.class);
        final WorldBorder border = mock(WorldBorder.class);
        final Map<Pos, Material> blocks = new HashMap<>();
        final Set<ChunkPos> unloaded = new HashSet<>();
        final List<Pos> reads = new ArrayList<>();
        int groundY = -1000;
        double borderRadius = 30_000_000;
        boolean loadSucceeds = true;

        Terrain() {
            when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
            when(border.getSize()).thenAnswer(call -> borderRadius * 2);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getWorldBorder()).thenReturn(border);
            when(border.isInside(any(Location.class))).thenAnswer(call -> {
                Location point = call.getArgument(0);
                return Math.abs(point.getX()) < borderRadius && Math.abs(point.getZ()) < borderRadius;
            });
            when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                    !unloaded.contains(new ChunkPos(call.getArgument(0), call.getArgument(1))));
            when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> {
                if (loadSucceeds) unloaded.remove(new ChunkPos(call.getArgument(0), call.getArgument(1)));
                return null;
            });
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                Pos pos = new Pos(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                assertTrue(pos.y >= -64 && pos.y < 320, "read outside build height: " + pos);
                assertFalse(unloaded.contains(new ChunkPos(pos.x >> 4, pos.z >> 4)), "read unloaded block: " + pos);
                reads.add(pos);
                Material material = blocks.getOrDefault(pos, pos.y <= groundY ? Material.STONE : Material.AIR);
                boolean sturdy = material == Material.STONE || material == Material.SAND;
                boolean passable = switch (material) {
                    case AIR, WATER, LAVA, FIRE, SOUL_FIRE, POWDER_SNOW -> true;
                    default -> false;
                };
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(material);
                when(block.isPassable()).thenReturn(passable);
                BlockData data = mock(BlockData.class);
                when(data.isFaceSturdy(BlockFace.UP, BlockSupport.FULL)).thenReturn(sturdy);
                when(block.getBlockData()).thenReturn(data);
                return block;
            });
        }

        Location at(double x, double y, double z) {
            return new Location(world, x, y, z);
        }

        void put(int x, int y, int z, Material material) {
            blocks.put(new Pos(x, y, z), material);
        }
    }
}
