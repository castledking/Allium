package codes.castled.allium.pack.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackSourceTest {

    @TempDir
    Path data;

    private final List<String> warnings = new ArrayList<>();

    private static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                String[] parts = entry.split("=", 2);
                zip.putNextEntry(new ZipEntry(parts[0]));
                zip.write((parts.length > 1 ? parts[1] : "{}").getBytes());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String MODEL = "assets/allium/models/item/mob_disarmer/full.json";
    private static final String CARRIER = "assets/minecraft/models/item/golden_hoe.json";
    private static final String TEXTURE = "assets/allium/textures/item/mob_disarmer/full.png";

    @Test
    void blankMeansTheBundledPack() throws IOException {
        byte[] bundled = zip("pack.mcmeta", MODEL, CARRIER, TEXTURE + "=allium");
        PackSource source = PackSource.choose(data, "", bundled, warnings::add);
        assertFalse(source.override());
        assertArrayEquals(bundled, source.bytes());
        assertEquals(PackExport.sha1(bundled), source.sha1());
    }

    @Test
    void aLocalBuildWithOtherTexturesIsDelivered() throws IOException {
        byte[] bundled = zip("pack.mcmeta", MODEL, CARRIER, TEXTURE + "=allium");
        byte[] sf = zip("pack.mcmeta", MODEL, CARRIER, TEXTURE + "=sf-art");
        Files.createDirectories(data.resolve("pack"));
        Files.write(data.resolve("pack/allium-core-pack-sf.zip"), sf);
        PackSource source = PackSource.choose(data, "pack/allium-core-pack-sf.zip", bundled, warnings::add);
        assertTrue(source.override());
        assertArrayEquals(sf, source.bytes());
        assertEquals(PackExport.sha1(sf), source.sha1());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aPackWithoutTheCoreStructureFallsBackWithAWarning() throws IOException {
        byte[] bundled = zip("pack.mcmeta", MODEL, CARRIER, TEXTURE + "=allium");
        Files.write(data.resolve("other.zip"), zip("pack.mcmeta", TEXTURE + "=x"));
        PackSource source = PackSource.choose(data, "other.zip", bundled, warnings::add);
        assertFalse(source.override());
        assertTrue(warnings.get(0).contains("lacks the core pack's [" + MODEL + ", " + CARRIER + "]"), warnings.get(0));
    }

    @Test
    void escapingThePluginFolderAMissingFileAndANonZipFallBack() throws IOException {
        byte[] bundled = zip("pack.mcmeta", MODEL);
        assertFalse(PackSource.choose(data, "../outside.zip", bundled, warnings::add).override());
        assertFalse(PackSource.choose(data, "missing.zip", bundled, warnings::add).override());
        Files.writeString(data.resolve("notes.txt"), "not a zip");
        assertFalse(PackSource.choose(data, "notes.txt", bundled, warnings::add).override());
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("inside plugins/Allium/"));
    }
}
