package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ItemSpecParserTest {

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

    private void assertMessage(String input, String fragment) {
        ItemSpecException e = assertThrows(ItemSpecException.class, () -> parser.parse(input), input);
        assertTrue(e.getMessage().contains(fragment), input + " -> " + e.getMessage());
    }
}
