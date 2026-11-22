package codes.castled.curios;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyImportTest {
    @TempDir Path temporary;

    private Catalog catalog() throws Exception {
        var yaml = new YamlConfiguration();
        try (var input = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            yaml.load(input);
        }
        return new Catalog(yaml);
    }

    @Test
    void readsTheLegacyCardComponentsAndRelicMarkerFromEquippedSlots() throws Exception {
        Path file = temporary.resolve("legacy.yml");
        Files.writeString(file, """
                inventory:
                  ring:
                  - id: minecraft:stick
                    data:
                      abyssallib:marker: antiquities:blaze_ember_ring
                  card:
                  - id: minecraft:air
                  - id: minecraft:paper
                    data:
                      minecraft:custom_data:
                        PublicBukkitValues:
                          alliumcards:card: true
                          alliumcards:card_id: witch
                          alliumcards:tier: ULTIMATE
                          alliumcards:level: 81
                          alliumcards:xp: '5340.4'
                          alliumcards:bound: true
                          alliumcards:quality: 96
                          alliumcards:signatures: strength
                """);
        var loadout = LegacyImport.load(catalog(), file);
        assertEquals("blaze_ember_ring", loadout.get(new Catalog.Slot("ring", 0)).entry().id());
        var card = loadout.get(new Catalog.Slot("card", 1));
        assertEquals("card_witch_ultimate", card.entry().id());
        assertEquals(Map.of("level", "81", "xp", "5340.4", "bound", "true",
                "legacy_quality", "96", "legacy_signatures", "strength"), card.state());
        assertFalse(loadout.containsKey(new Catalog.Slot("card", 0)));
    }

    @Test
    void refusesAnUnknownSlotRatherThanPickingADifferentSlot() throws Exception {
        Path file = temporary.resolve("legacy.yml");
        Files.writeString(file, """
                inventory:
                  head:
                  - id: antiquities:blaze_ember_ring
                """);
        assertThrows(IllegalArgumentException.class, () -> LegacyImport.load(catalog(), file));
    }
}
