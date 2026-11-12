package codes.castled.allium.tool.felltree;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TreeScannerTest {

    private static final List<TreeFamily> FAMILIES = List.of(TreeFamily.ALL.get("overworld_logs"), TreeFamily.ALL.get("pale_oak"));
    private static final TreeScanner.Limits LIMITS = new TreeScanner.Limits(256, 8, 48, 4, true);

    /** A flat grass world from y = 0 up, with whatever the test places. */
    private static final class World implements BlockView {
        final Map<TreeScanner.Pos, String> blocks = new HashMap<>();
        final Set<TreeScanner.Pos> persistent = new HashSet<>();
        final Set<Integer> unloadedChunkX = new HashSet<>();

        void set(int x, int y, int z, String material) {
            blocks.put(new TreeScanner.Pos(x, y, z), material);
        }

        @Override
        public @Nullable String material(int x, int y, int z) {
            if (unloadedChunkX.contains(x >> 4)) return null;
            String m = blocks.get(new TreeScanner.Pos(x, y, z));
            return m != null ? m : (y <= 0 ? "grass_block" : "air");
        }

        @Override
        public @Nullable Leaf leaf(int x, int y, int z) {
            String m = material(x, y, z);
            return m != null && m.endsWith("_leaves") ? new Leaf(persistent.contains(new TreeScanner.Pos(x, y, z)), 1) : null;
        }

        public int minY() { return -64; }

        public int maxY() { return 320; }
    }

    /** An oak: a trunk of {@code height} logs from y = 1, and a leaf layer around its top two logs. */
    private static World oak(int height, boolean playerPlacedLeaves) {
        World w = new World();
        for (int y = 1; y <= height; y++) w.set(0, y, 0, "oak_log");
        for (int y = height - 1; y <= height + 1; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    TreeScanner.Pos pos = new TreeScanner.Pos(x, y, z);
                    if ((x != 0 || z != 0 || y > height) && !w.blocks.containsKey(pos)) {
                        w.blocks.put(pos, "oak_leaves");
                        if (playerPlacedLeaves) w.persistent.add(pos);
                    }
                }
            }
        }
        return w;
    }

    private static TreeScanner.Scan scan(World w, int x, int y, int z) {
        return TreeScanner.scan(w, new TreeScanner.Pos(x, y, z), FAMILIES, LIMITS);
    }

    @Test
    void aNaturalTreeFellsEveryLogButTheBrokenOneBottomUp() {
        TreeScanner.Scan scan = scan(oak(5, false), 0, 1, 0);
        assertEquals(TreeScanner.Result.FELL, scan.result(), scan.reason());
        assertEquals(5, scan.size());
        assertEquals(List.of(2, 3, 4, 5), scan.logs().stream().map(TreeScanner.Pos::y).toList());
        // Breaking a middle log fells the whole tree too.
        assertEquals(4, scan(oak(5, false), 0, 3, 0).logs().size());
    }

    @Test
    void aPlayerBuiltLogStructureIsNeverFelled() {
        World bare = new World();
        for (int y = 1; y <= 4; y++) bare.set(0, y, 0, "oak_log");
        assertEquals(TreeScanner.Result.NOT_A_TREE, scan(bare, 0, 1, 0).result(), "no leaves at all");
        TreeScanner.Scan placed = scan(oak(5, true), 0, 1, 0);
        assertEquals(TreeScanner.Result.NOT_A_TREE, placed.result());
        assertTrue(placed.reason().contains("natural"), "player-placed leaves are persistent: " + placed.reason());
    }

    @Test
    void aBuildingBlockTouchingAnyLogStopsTheFell() {
        World w = oak(5, false);
        w.set(1, 2, 0, "oak_planks");
        TreeScanner.Scan scan = scan(w, 0, 1, 0);
        assertEquals(TreeScanner.Result.NOT_A_TREE, scan.result());
        assertTrue(scan.reason().contains("oak_planks"), scan.reason());
        World natural = oak(5, false);
        natural.set(1, 2, 0, "vine");
        natural.set(-1, 1, 0, "torchflower");
        assertEquals(TreeScanner.Result.FELL, scan(natural, 0, 1, 0).result(), "vines and flowers are not buildings");
    }

    @Test
    void overTheCapNothingIsFelledAtAll() {
        World w = oak(30, false);
        TreeScanner.Scan atCap = TreeScanner.scan(w, new TreeScanner.Pos(0, 1, 0), FAMILIES, new TreeScanner.Limits(30, 8, 48, 4, true));
        assertEquals(TreeScanner.Result.FELL, atCap.result(), atCap.reason());
        assertEquals(29, atCap.logs().size());
        TreeScanner.Scan over = TreeScanner.scan(w, new TreeScanner.Pos(0, 1, 0), FAMILIES, new TreeScanner.Limits(29, 8, 48, 4, true));
        assertEquals(TreeScanner.Result.TOO_LARGE, over.result());
        assertTrue(over.logs().isEmpty());
    }

    @Test
    void speciesDoNotMergeAndStrippedLogsCountOnlyWhenAllowed() {
        World w = oak(5, false);
        w.set(1, 3, 0, "birch_log");
        TreeScanner.Scan scan = scan(w, 0, 1, 0);
        assertEquals(TreeScanner.Result.FELL, scan.result());
        assertFalse(scan.logs().contains(new TreeScanner.Pos(1, 3, 0)), "a birch log next to the oak is not part of it");
        World stripped = oak(5, false);
        stripped.set(0, 2, 0, "stripped_oak_log");
        assertEquals(5, scan(stripped, 0, 1, 0).size());
        TreeScanner.Scan strict = TreeScanner.scan(stripped, new TreeScanner.Pos(0, 1, 0), FAMILIES, new TreeScanner.Limits(256, 8, 48, 4, false));
        assertEquals(TreeScanner.Result.NOT_A_TREE, strict.result(),
                "without stripped, the stripped log splits the trunk and the stump below it has no leaves: " + strict.reason());
    }

    @Test
    void aCreakingHeartInTheTrunkMeansNoFell() {
        World w = new World();
        for (int y = 1; y <= 5; y++) w.set(0, y, 0, y == 3 ? "creaking_heart" : "pale_oak_log");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (x != 0 || z != 0) w.set(x, 5, z, "pale_oak_leaves");
        TreeScanner.Scan scan = scan(w, 0, 1, 0);
        assertEquals(TreeScanner.Result.NOT_A_TREE, scan.result());
        assertTrue(scan.reason().contains("creaking_heart"), scan.reason());
    }

    @Test
    void aFloatingTrunkAndUnloadedChunksAreNotFelled() {
        World floating = oak(5, false);
        floating.set(0, 0, 0, "stone");
        assertEquals(TreeScanner.Result.NOT_A_TREE, scan(floating, 0, 1, 0).result(), "the lowest log must stand on ground");
        World w = oak(5, false);
        w.set(16, 2, 0, "oak_log");
        w.unloadedChunkX.add(1);
        TreeScanner.Scan scan = scan(w, 0, 1, 0);
        assertEquals(TreeScanner.Result.FELL, scan.result());
        assertTrue(scan.logs().stream().allMatch(p -> p.x() < 16), "a log in an unloaded chunk is never read");
    }

    @Test
    void theRadiusBoundsTheSearch() {
        World w = oak(5, false);
        for (int x = 1; x <= 12; x++) w.set(x, 1, 0, "oak_log");
        TreeScanner.Scan scan = scan(w, 0, 1, 0);
        assertEquals(TreeScanner.Result.FELL, scan.result());
        assertTrue(scan.logs().stream().allMatch(p -> Math.abs(p.x()) <= 8), "nothing beyond max-radius 8");
    }
}
