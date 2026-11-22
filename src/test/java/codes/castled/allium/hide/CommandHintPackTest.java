package codes.castled.allium.hide;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CommandHintPackTest {
    @TempDir Path temp;

    @Test
    void mergesAllLocalesAndPreservesPackBrandingAndOtherTranslations() throws Exception {
        String metadata = """
                {"pack":{"description":"Allium","pack_format":15},"overlays":{"entries":[
                {"directory":"modern","formats":{"min_inclusive":46,"max_inclusive":100}}]}}
                """;
        String english = "assets/minecraft/lang/en_us.json";
        byte[] base = pack(Map.of(
                "pack.mcmeta", metadata,
                "pack.png", "icon",
                english, "{\"existing\":\"base\",\"command.unknown.command\":\"old\"}",
                "modern/" + english, "{\"existing\":\"overlay\"}"));
        byte[] merged = CommandHintPack.merge(base, "&c&lOops 100%");
        assertArrayEquals(merged, CommandHintPack.merge(base, "&c&lOops 100%"));
        assertSame(base, CommandHintPack.merge(base, ""));
        Map<String, String> files = unpack(merged);
        assertEquals(metadata, files.get("pack.mcmeta"));
        assertEquals("icon", files.get("pack.png"));
        var locales = files.entrySet().stream().filter(e -> e.getKey().startsWith("assets/minecraft/lang/")).toList();
        assertEquals(142, locales.size());
        for (var locale : locales) {
            assertEquals("§c§lOops 100%%", JsonParser.parseString(locale.getValue()).getAsJsonObject()
                    .get("command.unknown.command").getAsString(), locale.getKey());
        }
        var root = JsonParser.parseString(files.get(english)).getAsJsonObject();
        var overlay = JsonParser.parseString(files.get("modern/" + english)).getAsJsonObject();
        assertEquals("base", root.get("existing").getAsString());
        assertEquals("overlay", overlay.get("existing").getAsString());
        assertEquals("§c§lOops 100%%", overlay.get("command.unknown.command").getAsString());
    }

    @Test
    void removesOnlyOwnedHelpersAndEmptyGeneratedDirectory() throws Exception {
        Path generated = Files.createDirectory(temp.resolve("generated"));
        Path mixer = Files.createDirectory(temp.resolve("mixer"));
        Path helper = generated.resolve(CommandHintPack.FILE);
        Path oldExport = mixer.resolve(CommandHintPack.FILE);
        byte[] owned = pack(Map.of("pack.mcmeta", "{\"allium-command-hints\":1}"));
        Files.write(helper, owned);
        Files.writeString(oldExport, "foreign");
        CommandHintPack.cleanup(generated, mixer);
        assertFalse(Files.exists(generated));
        assertEquals("foreign", Files.readString(oldExport));

        Files.createDirectory(generated);
        Path other = Files.writeString(generated.resolve("other.zip"), "keep");
        Files.write(oldExport, owned);
        CommandHintPack.cleanup(generated, mixer);
        assertFalse(Files.exists(oldExport));
        assertEquals("keep", Files.readString(other));
    }

    @Test
    void roundsHexButPreservesDecorations() {
        String text = CommandHintPack.languageText("<bold><#ff0000>Hi</#ff0000></bold>");
        assertTrue(text.contains("§l"));
        assertFalse(text.contains("§x"));
        assertTrue(text.endsWith("Hi"));
        assertThrows(IllegalArgumentException.class, () -> CommandHintPack.merge(new byte[0], "x".repeat(4097)));
    }

    private static byte[] pack(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> unpack(byte[] bytes) throws IOException {
        var files = new HashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
