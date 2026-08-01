package codes.castled.allium.items.stored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Covers reading and writing the {@code vanilla:} block. Capturing from and applying to a live
 * ItemStack needs Bukkit's registries and ItemFactory, so those paths are exercised in-game.
 */
class VanillaMetaTest {

    private static YamlConfiguration parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    private static VanillaMeta readBlock(String yaml) throws InvalidConfigurationException {
        return VanillaMeta.read(parse(yaml).getConfigurationSection("vanilla"));
    }

    /** Writes a model out and reads it back through real YAML text, as a reload would. */
    private static VanillaMeta roundTrip(VanillaMeta model) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        model.write(config.createSection("vanilla"));
        return VanillaMeta.read(parse(config.saveToString()).getConfigurationSection("vanilla"));
    }

    @Test
    void anAbsentBlockReadsAsNullSoTheSnapshotKeepsControl() {
        assertNull(VanillaMeta.read(null));
    }

    @Test
    void anEmptyBlockLeavesEveryFieldUnset() throws Exception {
        VanillaMeta model = readBlock("vanilla:\n  {}\n");

        assertNotNull(model);
        assertNull(model.getName());
        assertNull(model.getLore());
        assertNull(model.getUnbreakable());
        assertNull(model.getEnchants());
        assertNull(model.getAttributes());
        assertNull(model.getFlags());
        assertNull(model.getRarity());
    }

    @Test
    void readsScalarFields() throws Exception {
        VanillaMeta model = readBlock(
            "vanilla:\n"
            + "  name: \"&6King's Blade\"\n"
            + "  unbreakable: true\n"
            + "  damage: 12\n"
            + "  max-durability: 2031\n"
            + "  max-stack-size: 1\n"
            + "  custom-model-data: 1001\n"
            + "  item-model: \"nexo:kings_blade\"\n"
            + "  rarity: EPIC\n"
            + "  repair-cost: 5\n"
            + "  hide-tooltip: false\n"
            + "  glint: true\n");

        assertEquals("&6King's Blade", model.getName());
        assertEquals(Boolean.TRUE, model.getUnbreakable());
        assertEquals(12, model.getDamage());
        assertEquals(2031, model.getMaxDurability());
        assertEquals(1, model.getMaxStackSize());
        assertEquals(1001, model.getCustomModelData());
        assertEquals("nexo:kings_blade", model.getItemModel());
        assertEquals("EPIC", model.getRarity());
        assertEquals(5, model.getRepairCost());
        assertEquals(Boolean.FALSE, model.getHideTooltip());
        assertEquals(Boolean.TRUE, model.getGlint());
    }

    @Test
    void readsLoreEnchantsFlagsAndAttributes() throws Exception {
        VanillaMeta model = readBlock(
            "vanilla:\n"
            + "  lore:\n"
            + "    - \"&7Forged beneath the ash.\"\n"
            + "    - \"\"\n"
            + "  flags:\n"
            + "    - HIDE_ATTRIBUTES\n"
            + "  enchants:\n"
            + "    sharpness: 10\n"
            + "    minecraft:unbreaking: 3\n"
            + "  attributes:\n"
            + "    - attribute: attack_damage\n"
            + "      amount: 12.5\n"
            + "      operation: ADD_NUMBER\n"
            + "      slot: hand\n"
            + "      id: \"allium:kings_blade_damage\"\n");

        assertEquals(List.of("&7Forged beneath the ash.", ""), model.getLore());
        assertEquals(List.of("HIDE_ATTRIBUTES"), model.getFlags());
        assertEquals(2, model.getEnchants().size());
        assertEquals(10, model.getEnchants().get("sharpness"));
        assertEquals(3, model.getEnchants().get("minecraft:unbreaking"));

        assertEquals(1, model.getAttributes().size());
        VanillaMeta.AttributeEntry entry = model.getAttributes().get(0);
        assertEquals("attack_damage", entry.getAttribute());
        assertEquals(12.5D, entry.getAmount());
        assertEquals("ADD_NUMBER", entry.getOperation());
        assertEquals("hand", entry.getSlot());
        assertEquals("allium:kings_blade_damage", entry.getId());
    }

    @Test
    void everythingSurvivesAWriteAndReloadUnchanged() throws Exception {
        VanillaMeta original = readBlock(
            "vanilla:\n"
            + "  name: \"&6King's Blade\"\n"
            + "  lore:\n"
            + "    - \"&7Forged beneath the ash.\"\n"
            + "  unbreakable: true\n"
            + "  damage: 3\n"
            + "  max-durability: 2031\n"
            + "  max-stack-size: 1\n"
            + "  custom-model-data: 1001\n"
            + "  item-model: \"nexo:kings_blade\"\n"
            + "  rarity: EPIC\n"
            + "  repair-cost: 5\n"
            + "  hide-tooltip: true\n"
            + "  glint: false\n"
            + "  flags:\n"
            + "    - HIDE_ATTRIBUTES\n"
            + "    - HIDE_ENCHANTS\n"
            + "  enchants:\n"
            + "    sharpness: 10\n"
            + "  attributes:\n"
            + "    - attribute: attack_damage\n"
            + "      amount: 12.5\n"
            + "      operation: ADD_NUMBER\n"
            + "      slot: hand\n"
            + "      id: \"allium:kings_blade_damage\"\n");

        VanillaMeta reloaded = roundTrip(original);

        assertEquals(original.getName(), reloaded.getName());
        assertEquals(original.getLore(), reloaded.getLore());
        assertEquals(original.getUnbreakable(), reloaded.getUnbreakable());
        assertEquals(original.getDamage(), reloaded.getDamage());
        assertEquals(original.getMaxDurability(), reloaded.getMaxDurability());
        assertEquals(original.getMaxStackSize(), reloaded.getMaxStackSize());
        assertEquals(original.getCustomModelData(), reloaded.getCustomModelData());
        assertEquals(original.getItemModel(), reloaded.getItemModel());
        assertEquals(original.getRarity(), reloaded.getRarity());
        assertEquals(original.getRepairCost(), reloaded.getRepairCost());
        assertEquals(original.getHideTooltip(), reloaded.getHideTooltip());
        assertEquals(original.getGlint(), reloaded.getGlint());
        assertEquals(original.getFlags(), reloaded.getFlags());
        assertEquals(original.getEnchants(), reloaded.getEnchants());

        assertEquals(1, reloaded.getAttributes().size());
        VanillaMeta.AttributeEntry entry = reloaded.getAttributes().get(0);
        assertEquals("attack_damage", entry.getAttribute());
        assertEquals(12.5D, entry.getAmount());
        assertEquals("ADD_NUMBER", entry.getOperation());
        assertEquals("hand", entry.getSlot());
        assertEquals("allium:kings_blade_damage", entry.getId());
    }

    @Test
    void unsetFieldsAreLeftOutOfTheWrittenFile() throws Exception {
        VanillaMeta model = readBlock("vanilla:\n  name: \"&6Plain\"\n");

        YamlConfiguration config = new YamlConfiguration();
        model.write(config.createSection("vanilla"));
        String yaml = config.saveToString();

        assertTrue(yaml.contains("name:"));
        assertFalse(yaml.contains("unbreakable"));
        assertFalse(yaml.contains("enchants"));
        assertFalse(yaml.contains("attributes"));
        assertFalse(yaml.contains("rarity"));
    }

    /**
     * An item with an empty attribute-modifier component hides its default attributes, which is a
     * different item from one with no component at all. The empty list has to survive a reload.
     */
    @Test
    void anEmptyAttributeListIsNotTheSameAsNoAttributeList() throws Exception {
        VanillaMeta explicit = readBlock("vanilla:\n  attributes: []\n");
        assertNotNull(explicit.getAttributes());
        assertTrue(explicit.getAttributes().isEmpty());
        assertNotNull(roundTrip(explicit).getAttributes());

        VanillaMeta absent = readBlock("vanilla:\n  name: x\n");
        assertNull(absent.getAttributes());
        assertNull(roundTrip(absent).getAttributes());
    }

    @Test
    void anEmptyLoreListIsNotTheSameAsNoLore() throws Exception {
        VanillaMeta explicit = readBlock("vanilla:\n  lore: []\n");
        assertNotNull(explicit.getLore());
        assertTrue(explicit.getLore().isEmpty());
        assertNotNull(roundTrip(explicit).getLore());

        VanillaMeta absent = readBlock("vanilla:\n  name: x\n");
        assertNull(absent.getLore());
        assertNull(roundTrip(absent).getLore());
    }

    @Test
    void attributeEntriesToleratePartialHandWrittenLines() throws Exception {
        VanillaMeta model = readBlock(
            "vanilla:\n"
            + "  attributes:\n"
            + "    - attribute: attack_speed\n"
            + "      amount: -2\n");

        VanillaMeta.AttributeEntry entry = model.getAttributes().get(0);
        assertEquals("attack_speed", entry.getAttribute());
        assertEquals(-2.0D, entry.getAmount());
        // Left for applyTo to default to ADD_NUMBER / ANY / a generated key.
        assertNull(entry.getOperation());
        assertNull(entry.getSlot());
        assertNull(entry.getId());
    }
}
