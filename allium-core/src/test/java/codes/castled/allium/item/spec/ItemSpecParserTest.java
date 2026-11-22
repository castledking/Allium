package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ItemSpecParserTest {
    @org.junit.jupiter.api.Test void splashSentinelsAndTurtleDurationsRetainTheirVariantMeaning(){var p=new ItemSpecParser(false);assertEquals("splash_potion",p.parse("POTION;SPLASH;180").items().get(0).key().id());assertEquals("lingering_potion",p.parse("POTION;LINGERING;180").items().get(0).key().id());assertEquals("legacy-effect",p.parse("POTION;TURTLE_MASTER;10").tokens().get(0).key());}

    @org.junit.jupiter.api.Test void legacyPotionAndSpawnerFieldsAreNotAmounts(){var parser=new ItemSpecParser(false);var potion=parser.parse("POTION;SPEED;120;2 3");assertEquals(3,potion.amount().orElseThrow());assertEquals("legacy-effect",potion.tokens().get(0).key());assertEquals("SPEED:120:2",potion.tokens().get(0).value());var spawner=parser.parse("SPAWNER;ZOMBIE");assertTrue(spawner.amount().isEmpty());assertEquals("ZOMBIE",spawner.tokens().get(0).value());}
    @org.junit.jupiter.api.Test void namespacedSemicolonEnchantTokensKeepFullRegistryKey(){var spec=new ItemSpecParser(false).parse("DIAMOND_SWORD;plugin:keen:4;sharpness:2");assertEquals("plugin:keen",spec.tokens().get(0).key());assertEquals("4",spec.tokens().get(0).value());assertEquals("sharpness",spec.tokens().get(1).key());}


    private final ItemSpecParser parser = new ItemSpecParser(false);

    @Test
    void bracketsBracesAndQuotesKeepCommasSemicolonsColonsAndSpaces() {
        String raw = "minecraft:diamond_sword[custom_name='{\"text\":\"Blade, of Testing; Mk II\"}',unbreakable={}] 2 sharpness:5";
        assertEquals(List.of("minecraft:diamond_sword[custom_name='{\"text\":\"Blade, of Testing; Mk II\"}',unbreakable={}]", "2", "sharpness:5"),
                ItemSpecLexer.splitArguments(raw));
        GiveSpec spec = parser.parse(raw);
        assertEquals(1, spec.items().size());
        ItemReference sword = spec.items().get(0);
        assertEquals(ProviderKey.minecraft("diamond_sword"), sword.key());
        assertEquals(new RawItemData(RawItemData.Kind.COMPONENTS,
                "[custom_name='{\"text\":\"Blade, of Testing; Mk II\"}',unbreakable={}]"), sword.raw().orElseThrow());
        assertEquals(2, spec.amount().orElseThrow());
        assertEquals(List.of(new MetaToken("sharpness", "5", false)), spec.tokens());

        // Spaces inside components (as chat would split them) are rejoined by the lexer.
        GiveSpec spaced = parser.parse("diamond_sword[custom_name={text:\"A b, c; d\", italic:false}] name:\"x; y, z\"");
        assertEquals("[custom_name={text:\"A b, c; d\", italic:false}]", spaced.items().get(0).raw().orElseThrow().text());
        assertEquals(new MetaToken("name", "x; y, z", true), spaced.tokens().get(0));

        GiveSpec nbt = parser.parse("diamond_sword{display:{Name:'{\"text\":\"Blade, of Testing; Mk II\"}'},Unbreakable:1b}");
        assertEquals(RawItemData.Kind.NBT, nbt.items().get(0).raw().orElseThrow().kind());
    }

    @Test
    void batchesSplitOnlyOnTopLevelCommas() {
        GiveSpec spec = parser.parse("diamond_helmet,diamond_sword[enchantments={levels:{sharpness:1,smite:2}}],allium:mob_disarmer,"
                + "craftengine:sf_ores:silver_ingot 3");
        assertEquals(List.of(ProviderKey.minecraft("diamond_helmet"), ProviderKey.minecraft("diamond_sword"),
                ProviderKey.allium("mob_disarmer"), ProviderKey.of("craftengine", "sf_ores:silver_ingot")),
                spec.items().stream().map(ItemReference::key).toList());
        assertEquals(3, spec.amount().orElseThrow());
        assertEquals(List.of("a", "b[x,y]", "'c,d'", "\"e,f\""), ItemSpecLexer.splitTopLevel("a,b[x,y],'c,d',\"e,f\"", ','));
        assertEquals(ProviderKey.of("oraxen", "Gate_Disarmer"), parser.parse("ORAXEN:Gate_Disarmer").items().get(0).key(),
                "the provider is case-insensitive, the provider id keeps its case");
    }

    @Test
    void semicolonSyntaxIsGatedButSemicolonsInsideGroupsAlwaysWork() {
        ItemSpecException off = assertThrows(ItemSpecException.class, () -> parser.parse("stick;sharpness:5;3"));
        assertTrue(off.getMessage().contains("semicolon item syntax"));
        assertEquals("[custom_name='a;b']", parser.parse("stick[custom_name='a;b']").items().get(0).raw().orElseThrow().text());
        assertEquals("x;y", parser.parse("stick name:\"x;y\"").tokens().get(0).value());

        GiveSpec legacy = new ItemSpecParser(true).parse("stick;sharpness:5;3");
        assertEquals(ProviderKey.minecraft("stick"), legacy.items().get(0).key());
        assertEquals(3, legacy.amount().orElseThrow());
        assertEquals(List.of(new MetaToken("sharpness", "5", false)), legacy.tokens());
    }

    @Test
    void registeredItemVariantWorksWithLegacySyntaxDisabled() {
        var variants = new ItemSpecParser(false, "allium:spawner_core"::equals);
        GiveSpec spec = variants.parse("allium:spawner_core;allay 2");
        assertEquals(ProviderKey.allium("spawner_core"), spec.items().get(0).key());
        assertEquals(2, spec.amount().orElseThrow());
        assertEquals(List.of(new MetaToken("variant", "allay", false)), spec.tokens());
        assertThrows(ItemSpecException.class, () -> variants.parse("allium:spawner_core;allay;fox"));
        assertThrows(ItemSpecException.class, () -> parser.parse("allium:spawner_core;allay"));
    }

    @Test
    void tokensFlagsQuotesAndApostrophes() {
        GiveSpec spec = parser.parse("player_head owner:Notch name:Bob's_Head lore:One_|\"Two\" unbreakable -e --overflow=Refuse");
        assertEquals(new MetaToken("owner", "Notch", false), spec.tokens().get(0));
        assertEquals(new MetaToken("name", "Bob's_Head", false), spec.tokens().get(1), "a mid-word apostrophe is not a quote");
        assertEquals(new MetaToken("unbreakable", null, false), spec.tokens().get(3));
        assertTrue(spec.equip());
        assertEquals(Optional.of("refuse"), spec.overflow());
        assertEquals("say \"hi\", ok", parser.parse("stick name:\"say \\\"hi\\\", ok\"").tokens().get(0).value());
        assertTrue(parser.parse("stick").amount().isEmpty());
    }

    @Test
    void errorsAreSpecificAndPositioned() {
        assertMessage("diamond_sword[unbreakable={}", "'[' is never closed (at character 14)");
        assertMessage("diamond_sword]", "unmatched ']'");
        assertMessage("diamond_sword[a=1}", "is closed by '}'");
        assertMessage("diamond_sword[a]x", "unexpected text after the raw data group");
        assertMessage("[unbreakable={}]", "missing item id");
        assertMessage("stick name:\"open", "quote \" is never closed");
        assertMessage("ci:handcuffs", "use 'allium:handcuffs'");
        assertMessage("custom:handcuffs", "retired");
        assertMessage("vanilla:stone", "use 'minecraft:stone'");
        assertMessage("ce:sf_ores:silver_ingot", "craftengine:sf_ores:silver_ingot");
        assertMessage("craftengine:silver_ingot", "<namespace>:<id>");
        assertMessage("stick 0", "between 1 and");
        assertMessage("stick 99999", "between 1 and");
        assertMessage("stick sharpness:5 sharpness:4", "given twice");
        assertMessage("stick -x", "unknown flag");
        assertMessage("stick name:", "needs a value");
        assertMessage("stick$", "is not an item reference");
        assertMessage("", "an item is required");
        assertMessage("stone,", "empty item reference");
    }

    @Test
    void batchEntriesCarryTheirOwnAmounts() {
        GiveSpec plain = parser.parse("stick,gshovel 1");
        assertEquals(List.of("stick", "gshovel"), plain.items().stream().map(ItemReference::text).toList());
        assertEquals(1, plain.amount().orElseThrow());
        assertTrue(plain.items().stream().allMatch(i -> i.amount().isEmpty()));

        GiveSpec counted = parser.parse("stick:1,diamond:2,minecraft:dirt:3,oraxen:12");
        assertEquals(List.of(ProviderKey.minecraft("stick"), ProviderKey.minecraft("diamond"), ProviderKey.minecraft("dirt"),
                ProviderKey.of("oraxen", "12")), counted.items().stream().map(ItemReference::key).toList());
        assertEquals(List.of(1, 2, 3), counted.items().stream().limit(3).map(i -> i.amount().getAsInt()).toList());
        assertTrue(counted.items().get(3).amount().isEmpty(), "oraxen:12 names an item, it is not 12 oraxen");
        assertEquals("stick", counted.items().get(0).text(), "the alias lookup sees the name without its amount");

        // An entry's own amount wins; the batch amount covers the rest.
        GiveSpec mixed = parser.parse("stick:5,diamond 2");
        assertEquals(5, mixed.items().get(0).amount().getAsInt());
        assertTrue(mixed.items().get(1).amount().isEmpty());
        assertEquals(2, mixed.amount().getAsInt());

        assertEquals(4, parser.parse("diamond:4").items().get(0).amount().getAsInt());
        GiveSpec legacyIds = parser.parse("1:1,1:1:5,35");
        assertEquals(List.of("1:1", "1:1", "35"), legacyIds.items().stream().map(ItemReference::text).toList(),
                "numeric legacy ids keep their data value");
        assertTrue(legacyIds.items().get(0).amount().isEmpty());
        assertEquals(5, legacyIds.items().get(1).amount().getAsInt());
        assertMessage("diamond:4 2", "given twice");
        assertMessage("stick:0,dirt", "between 1 and");
        assertMessage("stick;sharpness:5,dirt", "comma batch");
        GiveSpec legacy = new ItemSpecParser(true).parse("dirt;1,grass_block;2");
        assertEquals(List.of(1, 2), legacy.items().stream().map(i -> i.amount().getAsInt()).toList());
        assertMessage("dirt;1,grass_block;2", "comma batch");
    }

    private void assertMessage(String input, String fragment) {
        ItemSpecException e = assertThrows(ItemSpecException.class, () -> parser.parse(input), input);
        assertTrue(e.getMessage().contains(fragment), input + " -> " + e.getMessage());
    }
}
