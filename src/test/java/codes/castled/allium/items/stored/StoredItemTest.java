package codes.castled.allium.items.stored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Covers the parts of the stored item format that do not need a running server. Snapshot
 * serialisation goes through Bukkit's ItemFactory and is exercised in-game rather than here.
 */
class StoredItemTest {

    private static YamlConfiguration parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void idsAreLowercasedAndTrimmed() {
        assertEquals("kings_blade", StoredItem.normaliseId("  Kings_Blade  "));
        assertNull(StoredItem.normaliseId(null));
    }

    @Test
    void idsRejectAnythingThatCouldEscapeTheItemsDirectory() {
        assertTrue(StoredItem.isValidId("kings_blade"));
        assertTrue(StoredItem.isValidId("tier-2_sword9"));

        assertFalse(StoredItem.isValidId(""));
        assertFalse(StoredItem.isValidId(null));
        assertFalse(StoredItem.isValidId("kings blade"));
        assertFalse(StoredItem.isValidId("kings.blade"));
        assertFalse(StoredItem.isValidId("../evil"));
        assertFalse(StoredItem.isValidId("sub/dir"));
        // Uppercase only passes after normalisation, so callers must normalise first.
        assertFalse(StoredItem.isValidId("Kings_Blade"));
    }

    @Test
    void loadsAHandAuthoredDefinitionWithNoSnapshot() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse(
            "material: DIAMOND_SWORD\n"
            + "amount: 3\n"));

        assertEquals("plain_sword", item.getId());
        assertEquals(Material.DIAMOND_SWORD, item.getMaterial());
        assertEquals(3, item.getAmount());
        assertFalse(item.hasSnapshot());
    }

    @Test
    void alliumOptionsFallBackToDefaultsWhenAbsent() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse("material: DIAMOND_SWORD\n"));

        assertTrue(item.isNotifyReceivers());
        assertNull(item.getPermission());
        assertEquals(64, item.getMaxGive());
        assertEquals(1, item.getAmount());
    }

    @Test
    void alliumOptionsRoundTrip() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse(
            "material: DIAMOND_SWORD\n"
            + "allium:\n"
            + "  notify-receivers: false\n"
            + "  permission: allium.item.plain_sword\n"
            + "  max-give: 8\n"));

        assertFalse(item.isNotifyReceivers());
        assertEquals("allium.item.plain_sword", item.getPermission());
        assertEquals(8, item.getMaxGive());
    }

    @Test
    void anEmptyPermissionMeansNoGate() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse(
            "material: DIAMOND_SWORD\n"
            + "allium:\n"
            + "  permission: ''\n"));

        assertNull(item.getPermission());
    }

    @Test
    void degenerateAmountsAreClampedToSomethingGiveable() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse(
            "material: DIAMOND_SWORD\n"
            + "amount: 0\n"
            + "allium:\n"
            + "  max-give: 0\n"));

        assertEquals(1, item.getAmount());
        assertEquals(1, item.getMaxGive());
    }

    @Test
    void rejectsDefinitionsWithNoUsableMaterial() throws Exception {
        YamlConfiguration missing = parse("amount: 1\n");
        assertThrows(IllegalArgumentException.class, () -> StoredItem.load("broken", missing));

        YamlConfiguration unknown = parse("material: NOT_A_REAL_MATERIAL\n");
        assertThrows(IllegalArgumentException.class, () -> StoredItem.load("broken", unknown));

        YamlConfiguration air = parse("material: AIR\n");
        assertThrows(IllegalArgumentException.class, () -> StoredItem.load("broken", air));
    }

    @Test
    void rejectsUnusableIds() throws Exception {
        YamlConfiguration config = parse("material: DIAMOND_SWORD\n");
        assertThrows(IllegalArgumentException.class, () -> StoredItem.load("bad id", config));
        assertThrows(IllegalArgumentException.class, () -> StoredItem.load("../escape", config));
    }

    @Test
    void writtenConfigCarriesEveryFieldNeededToReloadIt() throws Exception {
        StoredItem item = StoredItem.load("plain_sword", parse(
            "material: DIAMOND_SWORD\n"
            + "amount: 5\n"
            + "allium:\n"
            + "  notify-receivers: false\n"
            + "  permission: allium.item.plain_sword\n"
            + "  max-give: 12\n"));

        StoredItem reloaded = StoredItem.load("plain_sword", parse(item.toConfig().saveToString()));

        assertEquals(item.getId(), reloaded.getId());
        assertEquals(item.getMaterial(), reloaded.getMaterial());
        assertEquals(item.getAmount(), reloaded.getAmount());
        assertEquals(item.isNotifyReceivers(), reloaded.isNotifyReceivers());
        assertEquals(item.getPermission(), reloaded.getPermission());
        assertEquals(item.getMaxGive(), reloaded.getMaxGive());
    }
}
