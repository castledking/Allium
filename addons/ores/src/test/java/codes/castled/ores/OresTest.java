package codes.castled.ores;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OresTest {
    private static OreDefinition salt() {
        return new OreDefinition("salt", BlockRef.parse("sf:salt_ore"), BlockRef.parse("sf:deepslate_salt_ore"),
                8, 0, 64, 1.0, 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    void veinsAreDeterministicAndStayInTheirChunkAndHeights() {
        OreDefinition ore = salt();
        var heights = ore.heights(-64, 320, false);
        List<OreVeins.Position> first = new ArrayList<>(), second = new ArrayList<>();
        int placed = OreVeins.generate(42L, 3, -7, ore, heights, false, p -> first.add(p));
        OreVeins.generate(42L, 3, -7, ore, heights, false, p -> second.add(p));
        assertEquals(first, second);
        assertTrue(placed > 0);
        Set<OreVeins.Position> unique = new HashSet<>(first);
        assertEquals(first.size(), unique.size());
        for (var p : first) {
            assertTrue(p.x() >= 0 && p.x() < 16 && p.z() >= 0 && p.z() < 16, p.toString());
            assertTrue(p.y() >= 0 && p.y() <= 64, p.toString());
        }
    }

    @Test
    void chanceZeroAndEmptyHeightsPlaceNothing() {
        var never = new OreDefinition("salt", BlockRef.parse("sf:salt_ore"), BlockRef.parse("sf:salt_ore"),
                8, 0, 64, 0.0, 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(0, OreVeins.generate(1L, 0, 0, never, never.heights(-64, 320, false), false, p -> true));
        var high = salt();
        assertTrue(high.heights(100, 320, false).empty());
    }

    @Test
    void lowlandsCapTheHeightAndOresNeedACustomBlock() {
        var silver = new OreDefinition("silver", BlockRef.parse("sf:silver_ore"), BlockRef.parse("sf:deepslate_silver_ore"),
                8, -64, 118, 0.15, 2, 56, 59);
        assertEquals(56, silver.heights(-64, 320, false).max());
        assertEquals(118, silver.heights(-64, 320, true).max());
        assertThrows(IllegalArgumentException.class, () -> new OreDefinition("x", BlockRef.parse("stone"),
                BlockRef.parse("sf:x"), 8, 0, 1, 0.5, 2, 1, 1));
        assertTrue(OreGeneration.isMountainous("JAGGED_PEAKS"));
        assertFalse(OreGeneration.isMountainous("PLAINS"));
    }

    @Test
    void pregenCursorCoversTheSquareOnce() {
        var cursor = new PregenCursor(10, -3, 2);
        Set<PregenCursor.ChunkPosition> seen = new HashSet<>();
        while (cursor.hasNext()) assertTrue(seen.add(cursor.next()));
        assertEquals(25, seen.size());
        assertEquals(25, cursor.total());
        assertTrue(seen.contains(new PregenCursor.ChunkPosition(12, -1)));
        assertThrows(IllegalArgumentException.class, () -> new PregenCursor(0, 0, 0));
    }

    /** SF's legacy ore-generation and pregen sections, as its config.yml has them. */
    private static final String SF = """
            version: 0.1.5a
            ore-generation:
              enabled: false
              auto-generate: false
              worlds:
              - Resource
              - world
              jade:
                enabled: true
              ores:
                salt:
                  enabled: true
                  block: sf:salt_ore
                  deepslate-block: sf:deepslate_salt_ore
                  vein-size: 8
                  min-height: 0
                  max-height: 64
                  chance-per-chunk: 0.35
                  veins-per-chunk: 2
                silver:
                  enabled: true
                  block: sf:silver_ore
                  deepslate-block: sf:deepslate_silver_ore
                  vein-size: 8
                  min-height: -64
                  max-height: 16
                  lowland-max-height: 56
                  single-above: 59
                  chance-per-chunk: 0.15
                  veins-per-chunk: 2
            pregen:
              chunks-per-tick: 4
              delay-ticks: 1
              generate-ores: false
            """;

    @Test
    void legacySettingsMapOntoTheBundledConfig() throws Exception {
        var legacy = new YamlConfiguration();
        legacy.loadFromString(SF);
        var target = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml")),
                StandardCharsets.UTF_8)) {
            target.load(reader);
        }
        assertTrue(LegacyOres.present(legacy));
        List<String> written = LegacyOres.apply(legacy, target);
        assertTrue(written.contains("worlds") && written.contains("ores.silver"), written.toString());
        assertFalse(target.getBoolean("enabled"));
        assertFalse(target.getBoolean("auto-generate"));
        assertEquals(List.of("Resource", "world"), target.getStringList("worlds"));
        assertEquals(4, target.getInt("pregen.chunks-per-tick"));
        assertFalse(target.getBoolean("pregen.generate-ores"));
        assertEquals(-64, target.getInt("ores.silver.min-height"));
        assertEquals(16, target.getInt("ores.silver.max-height"));
        // SF has no galena section, so the bundled one does not linger.
        assertFalse(target.isSet("ores.galena"));
        var settings = Settings.read(target, message -> fail(message));
        assertEquals(2, settings.ores().size());
        assertFalse(target.isSet("ore-generation"));
    }

    @Test
    void legacysFirstOreLayoutIsRecognised() throws Exception {
        var legacy = new YamlConfiguration();
        legacy.loadFromString("""
                ore-generation:
                  enabled: true
                  salt:
                    oraxen-id: salt_ore
                    ores-per-vein: 5
                """);
        var target = new YamlConfiguration();
        LegacyOres.apply(legacy, target);
        assertEquals("salt_ore", target.getString("ores.salt.oraxen-id"));
        var ore = OreDefinition.read("salt", target.getConfigurationSection("ores.salt"));
        assertEquals("sf:salt_ore", ore.block().toString());
        assertEquals(5, ore.veinSize());
    }
}
