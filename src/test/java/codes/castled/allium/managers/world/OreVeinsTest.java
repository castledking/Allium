package codes.castled.allium.managers.world;

import static org.junit.jupiter.api.Assertions.*;
import codes.castled.allium.harvest.crop.def.BlockRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class OreVeinsTest {
    private OreDefinition ore(int size, int min, int max, double chance) {
        return new OreDefinition("silver", BlockRef.parse("sf:silver_ore"), BlockRef.parse("sf:deepslate_silver_ore"),
            size, min, max, chance, 2, 56, 59);
    }
    @Test void deterministicVeinsStayInsideChunkAndConfiguredHeights() {
        OreDefinition ore = ore(8, -56, 118, 1);
        for (int seed = 0; seed < 200; seed++) {
            var points = new ArrayList<OreVeins.Position>();
            int count = OreVeins.generate(seed, -77, 81, ore, ore.heights(-64, 320, false), false, p -> {
                assertTrue(p.x() >= 0 && p.x() < 16 && p.z() >= 0 && p.z() < 16);
                assertTrue(p.y() >= -56 && p.y() <= 56);
                points.add(p); return true;
            });
            assertEquals(count, points.size());
            assertEquals(points.size(), new HashSet<>(points).size());
            var again = new ArrayList<OreVeins.Position>();
            OreVeins.generate(seed, -77, 81, ore, ore.heights(-64, 320, false), false, p -> { again.add(p); return true; });
            assertEquals(points, again);
        }
    }
    @Test void singleBlockRangesAndSmallVeinsDoNotThrow() {
        for (int size : List.of(1, 2, 3, 8)) {
            OreDefinition ore = ore(size, 5, 5, 1);
            int count = OreVeins.generate(5, 0, 0, ore, ore.heights(-64, 320, false), false, p -> { assertEquals(5, p.y()); return true; });
            assertTrue(count > 0 && count <= 3 * size);
        }
    }
    @Test void impossibleHeightOrZeroChanceDoesNotTouchTerrain() {
        var ore = ore(8, 0, 64, 1);
        assertEquals(0, OreVeins.generate(0,0,0,ore,ore.heights(100,200,false),false,p -> fail("empty height")));
        var absent = ore(8,0,64,0);
        assertEquals(0, OreVeins.generate(0,0,0,absent,absent.heights(-64,320,false),false,p -> fail("zero chance")));
    }
    @Test void rejectedStoneDoesNotGrowAVeinOrCountAsPlaced() {
        var ore = ore(8,-56,16,1);
        assertEquals(0, OreVeins.generate(5,0,0,ore,ore.heights(-64,320,false),false,p -> false));
    }
    @Test void mountainousSilverKeepsSingleHighAltitudeOres() {
        var ore = ore(8,60,118,1);
        int count = OreVeins.generate(4,0,0,ore,ore.heights(-64,320,true),true,p -> true);
        assertTrue(count >= 1 && count <= 3);
        assertTrue(ore.heights(-64,320,false).empty());
    }
    @Test void configurationRejectsUnsafeParametersAndKeepsLegacyDensity() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("silver:\n  oraxen-id: silver_ore\n  min-height: -64\n  max-height: 16\n  chance-per-chunk: 0.15\n  ores-per-vein: 4\n");
        var ore = OreDefinition.read("silver",config.getConfigurationSection("silver"));
        assertEquals("sf:silver_ore",ore.block().toString());
        assertEquals(4,ore.veinSize());
        assertEquals(-64,ore.minHeight());
        assertEquals(.15,ore.chancePerChunk());
        assertThrows(IllegalArgumentException.class,() -> ore(0,0,16,1));
        assertThrows(IllegalArgumentException.class,() -> ore(8,16,0,1));
        assertThrows(IllegalArgumentException.class,() -> ore(8,0,16,Double.NaN));
    }
}
