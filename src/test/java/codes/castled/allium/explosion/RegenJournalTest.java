package codes.castled.allium.explosion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegenJournalTest {

    private record Spot(int x, int y, int z, boolean attached) implements RegenOrder.Placed {
    }

    @Test
    void solidBlocksComeBackFirstLowestLayerFirstEachFromTheRimInwards() {
        List<Spot> blocks = List.of(
                new Spot(0, 65, 0, true),   // torch on top
                new Spot(0, 64, 0, false),  // centre of the top layer
                new Spot(3, 64, 0, false),  // rim of the top layer
                new Spot(0, 63, 0, false),  // bottom layer
                new Spot(1, 63, 0, true));  // attached, bottom layer
        List<Spot> sorted = RegenOrder.sort(blocks, 0.5D, 0.5D);
        assertEquals(List.of(new Spot(0, 63, 0, false), new Spot(3, 64, 0, false), new Spot(0, 64, 0, false),
                new Spot(1, 63, 0, true), new Spot(0, 65, 0, true)), sorted);
    }

    @Test
    void aJournalReadsBackTheSameBlocksInTheSameOrderWithTheirTileData() throws Exception {
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("items", "format: 2\ndata-version: 3465\nitems: []\n");
        chest.put("slots", List.of(0, 13));
        chest.put("name", "§6Loot");
        Map<String, Object> front = new LinkedHashMap<>();
        front.put("lines", List.of("Hello", "", "world", ""));
        front.put("glowing", true);
        Map<String, Object> sign = new LinkedHashMap<>();
        sign.put("front", front);
        List<CapturedBlock> blocks = List.of(
                new CapturedBlock(10, 64, -3, "minecraft:stone", "minecraft:stone", false, null, null),
                new CapturedBlock(11, 64, -3, "minecraft:chest", "minecraft:chest[facing=north,type=single,waterlogged=false]",
                        false, null, TileSnapshot.of(chest)),
                new CapturedBlock(10, 65, -3, "minecraft:oak_sign", "minecraft:oak_sign[rotation=0,waterlogged=false]",
                        true, null, TileSnapshot.of(sign)));
        UUID id = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        RegenJournal.Entry entry = new RegenJournal.Entry(id, world, "world", 10.5D, 64.0D, -2.5D, 1234L, blocks);

        RegenJournal.Entry back = RegenJournal.decode(RegenJournal.encode(entry));

        assertEquals(id, back.id());
        assertEquals(world, back.world());
        assertEquals("world", back.worldName());
        assertEquals(10.5D, back.centerX());
        assertEquals(-2.5D, back.centerZ());
        assertEquals(1234L, back.created());
        assertEquals(3, back.blocks().size());
        for (int i = 0; i < 3; i++) {
            CapturedBlock written = blocks.get(i);
            CapturedBlock read = back.blocks().get(i);
            assertEquals(List.of(written.x(), written.y(), written.z()), List.of(read.x(), read.y(), read.z()));
            assertEquals(written.data(), read.data());
            assertEquals(written.material(), read.material());
            assertEquals(written.attached(), read.attached());
            assertNull(read.live());
        }
        assertNull(back.blocks().get(0).tile());
        assertTrue(back.blocks().get(1).hasItems());
        assertEquals(chest.get("items"), back.blocks().get(1).tile().values().get("items"));
        assertEquals(List.of(0, 13), back.blocks().get(1).tile().values().get("slots"));
        assertEquals("§6Loot", back.blocks().get(1).tile().values().get("name"));
        assertFalse(back.blocks().get(2).hasItems());
        Map<?, ?> readFront = (Map<?, ?>) ((Map<?, ?>) back.blocks().get(2).tile().values().get("front"));
        assertEquals(List.of("Hello", "", "world", ""), readFront.get("lines"));
        assertEquals(true, readFront.get("glowing"));
    }

    @Test
    void theMaterialIsTheBlockDataBeforeItsStates() {
        assertEquals("minecraft:oak_stairs", RegenJournal.materialOf("minecraft:oak_stairs[facing=east,half=bottom]"));
        assertEquals("minecraft:stone", RegenJournal.materialOf("minecraft:stone"));
    }

    @Test
    void journalsAreWrittenReadAndDeletedAndAnUnreadableOneIsSetAside(@TempDir Path folder) throws Exception {
        RegenJournal journal = new RegenJournal(folder.resolve("regeneration"));
        UUID id = UUID.randomUUID();
        journal.write(new RegenJournal.Entry(id, UUID.randomUUID(), "world", 0, 64, 0, 1L,
                List.of(new CapturedBlock(0, 64, 0, "minecraft:dirt", "minecraft:dirt", false, null, null))));
        Files.writeString(folder.resolve("regeneration").resolve(UUID.randomUUID() + ".yml"), "format: 99\n");
        List<String> warnings = new ArrayList<>();

        List<RegenJournal.Entry> read = journal.readAll(warnings::add);

        assertEquals(1, read.size());
        assertEquals(id, read.get(0).id());
        assertEquals(1, warnings.size());
        try (var files = Files.list(folder.resolve("regeneration"))) {
            assertEquals(1, files.filter(file -> file.getFileName().toString().endsWith(".yml.broken")).count());
        }
        journal.delete(id);
        assertEquals(List.of(), journal.readAll(warnings::add));
        assertEquals(1, warnings.size());
        try (var files = Files.list(folder.resolve("regeneration"))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }
}
