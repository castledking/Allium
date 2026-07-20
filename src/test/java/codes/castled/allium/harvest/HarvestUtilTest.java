package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.def.DropSpec;
import codes.castled.allium.harvest.crop.def.FootprintDefinition;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.random.MutableWeightedTable;
import codes.castled.allium.harvest.spawner.SpawnerModelRegistry;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import codes.castled.allium.harvest.util.Durations;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HarvestUtilTest {

    // ==================== durations ====================

    @Test
    void durationParsing() {
        assertEquals(30 * 60_000L, Durations.parseMillis("30m"));
        assertEquals(90 * 60_000L, Durations.parseMillis("1h30m"));
        assertEquals(45_000L, Durations.parseMillis("45s"));
        assertEquals(45_000L, Durations.parseMillis("45"));
        assertEquals(500L, Durations.parseMillis("500ms"));
        assertEquals(86_400_000L, Durations.parseMillis("1d"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("abc"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis(""));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("10x"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("0s"));
        assertEquals("1h30m", Durations.format(90 * 60_000L));
    }

    // ==================== position keys ====================

    @Test
    void blockPositionKeyEqualityAndChunkMath() {
        UUID world = UUID.randomUUID();
        BlockPositionKey a = new BlockPositionKey(world, 17, 64, -3);
        BlockPositionKey b = new BlockPositionKey(world, 17, 64, -3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new BlockPositionKey(world, 17, 65, -3));
        assertNotEquals(a, new BlockPositionKey(UUID.randomUUID(), 17, 64, -3));
        assertEquals(new ChunkKey(world, 1, -1), a.chunkKey());
        assertEquals(new BlockPositionKey(world, 18, 64, -2), a.offset(1, 0, 1));
    }

    // ==================== item refs ====================

    @Test
    void itemRefParsing() {
        assertEquals(new ItemRef("nexo", "tomato"), ItemRef.parse("nexo:tomato"));
        assertEquals(new ItemRef("minecraft", "carrot"), ItemRef.parse("minecraft:CARROT"));
        assertEquals(new ItemRef("minecraft", "carrot"), ItemRef.parse("vanilla:CARROT"));
        assertEquals(new ItemRef("minecraft", "carrot"), ItemRef.parse("carrot"));
        assertThrows(IllegalArgumentException.class, () -> ItemRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ItemRef.parse("nexo:"));
    }

    // ==================== footprints ====================

    @Test
    void footprintAlwaysIncludesAnchorAndResolvesOffsets() {
        FootprintDefinition footprint = new FootprintDefinition(List.of(
            new FootprintDefinition.Cell(1, 0, 0),
            new FootprintDefinition.Cell(0, 0, 1),
            new FootprintDefinition.Cell(1, 0, 1)));
        assertEquals(4, footprint.cells().size(), "anchor cell is implicit");
        UUID world = UUID.randomUUID();
        BlockPositionKey anchor = new BlockPositionKey(world, 10, 64, 10);
        List<BlockPositionKey> cells = footprint.resolve(anchor);
        assertTrue(cells.contains(anchor));
        assertTrue(cells.contains(new BlockPositionKey(world, 11, 64, 11)));
        assertTrue(FootprintDefinition.SINGLE.isSingleCell());
    }

    // ==================== drop specs ====================

    @Test
    void dropSpecValidationAndRolling() {
        ItemRef item = ItemRef.parse("minecraft:carrot");
        assertThrows(IllegalArgumentException.class, () -> new DropSpec(item, 0, 1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new DropSpec(item, 3, 2, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new DropSpec(item, 1, 2, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new DropSpec(item, 1, 2, 1.5));
        DropSpec range = new DropSpec(item, 2, 4, 1.0);
        Random random = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            int amount = range.rollAmount(random);
            assertTrue(amount >= 2 && amount <= 4);
        }
    }

    // ==================== weighted table modifiers ====================

    @Test
    void mutableWeightedTableTracksBaseAndModifiedWeights() {
        MutableWeightedTable<String> table = new MutableWeightedTable<>();
        table.put("regular", 65, "regular");
        table.put("silver", 25, "silver");
        table.multiplyWeight("regular", 0.5);
        table.addWeight("silver", 10);
        table.multiplyWeight("missing", 3.0); // unknown ids ignored
        assertEquals(65, table.entries().get("regular").baseWeight());
        assertEquals(32.5, table.entries().get("regular").weight());
        assertEquals(35, table.entries().get("silver").weight());
        table.multiplyWeight("regular", -4);
        assertEquals(0, table.entries().get("regular").weight(), "weights clamp at zero");
    }

    // ==================== spawner model variants ====================

    @Test
    void spawnerVariantSelectionPicksHighestQualifyingThreshold() {
        ItemRef base = ItemRef.parse("nexo:model_1");
        ItemRef mid = ItemRef.parse("nexo:model_16");
        ItemRef top = ItemRef.parse("nexo:model_64");
        SpawnerModelRegistry.Model model = new SpawnerModelRegistry.Model(
            base, 1.0F, new double[] {0.5, 0, 0.5}, 0.0F, 48.0F, false,
            List.of(
                new SpawnerModelRegistry.Model.Variant(64, top),
                new SpawnerModelRegistry.Model.Variant(16, mid),
                new SpawnerModelRegistry.Model.Variant(1, base)));
        assertEquals(base, model.itemForStack(1));
        assertEquals(base, model.itemForStack(15));
        assertEquals(mid, model.itemForStack(16));
        assertEquals(mid, model.itemForStack(63));
        assertEquals(top, model.itemForStack(64));
        assertEquals(top, model.itemForStack(1000));
    }
}
