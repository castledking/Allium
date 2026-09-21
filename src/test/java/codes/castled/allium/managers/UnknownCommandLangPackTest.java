package codes.castled.allium.managers;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnknownCommandLangPackTest {

    @Test
    void legacyCodesPassThrough() {
        assertEquals("§c§lERROR §8» §7Type §c/help", UnknownCommandLangPack.toLangValue("§c§lERROR §8» §7Type §c/help"));
    }

    @Test
    void hexColorIsRoundedToNearestLegacyColor() {
        assertEquals("§cHi §aThere", UnknownCommandLangPack.toLangValue("§x§f§f§4§4§4§4Hi §x§4§4§F§F§6§6There"));
    }

    @Test
    void percentIsEscaped() {
        assertEquals("100%% sure", UnknownCommandLangPack.toLangValue("100% sure"));
    }

    @Test
    void packOverridesKeyInEveryLocale() throws IOException {
        byte[] pack = UnknownCommandLangPack.buildPack("§cNope");
        Map<String, String> entries = readZip(pack);

        assertTrue(entries.containsKey("pack.mcmeta"));
        assertEquals(UnknownCommandLangPack.LOCALES.size() + 1, entries.size());
        String expected = "{\"" + UnknownCommandLangPack.TRANSLATION_KEY + "\":\"§cNope\"}";
        assertEquals(expected, entries.get("assets/minecraft/lang/en_us.json"));
        assertEquals(expected, entries.get("assets/minecraft/lang/de_de.json"));
    }

    @Test
    void packBytesAreDeterministic() throws IOException {
        assertArrayEquals(UnknownCommandLangPack.buildPack("§cNope"), UnknownCommandLangPack.buildPack("§cNope"));
    }

    private static Map<String, String> readZip(byte[] data) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
