package codes.castled.allium.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MixerAuditTest {

    @TempDir
    Path mixer;

    private void zip(String name, String... entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(mixer.resolve(name)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("{}".getBytes());
                zip.closeEntry();
            }
        }
    }

    private static AssetManifest.Tool tool(String id, String carrier, String status) {
        return new AssetManifest.Tool(id, "allium:" + id, carrier, id, status, new AssetManifest.Range(1, 2), null,
                List.of(new AssetManifest.State("full", 1, "t", "m")));
    }

    @Test
    void reportsCarrierFilesInOtherPacksAndSeparatesShippedFromPlannedTools() throws IOException {
        zip("Nexo_resource_pack.zip", "assets/minecraft/items/blaze_rod.json", "assets/nexo/models/x.json");
        zip("Other.zip", "overlay_1_21_4/assets/minecraft/models/item/golden_hoe.json", "pack.mcmeta");
        zip("Allium_core_resource_pack.zip", "assets/minecraft/models/item/golden_hoe.json");
        Files.writeString(mixer.resolve("notes.txt"), "not a pack");
        List<AssetManifest.Tool> tools = List.of(tool("mob_disarmer", "golden_hoe", "ready"), tool("phantom_obliterator", "blaze_rod", "planned"));
        MixerAudit.Report report = MixerAudit.audit(mixer, "Allium_core_resource_pack.zip", tools);
        assertEquals(List.of("Nexo_resource_pack.zip", "Other.zip"), report.scanned(), "Allium's own file is skipped");
        assertEquals(List.of(new MixerAudit.Finding("golden_hoe", "mob_disarmer", true, "Other.zip",
                "overlay_1_21_4/assets/minecraft/models/item/golden_hoe.json")), report.current(), "overlays count");
        assertEquals(List.of(new MixerAudit.Finding("blaze_rod", "phantom_obliterator", false, "Nexo_resource_pack.zip",
                "assets/minecraft/items/blaze_rod.json")), report.planned());
        assertEquals(List.of(), report.unreadable());
    }

    @Test
    void anUnreadablePackIsReportedNotFatal() throws IOException {
        Files.writeString(mixer.resolve("Broken.zip"), "this is not a zip");
        MixerAudit.Report report = MixerAudit.audit(mixer, "Allium_core_resource_pack.zip", List.of(tool("mob_disarmer", "golden_hoe", "ready")));
        assertEquals(1, report.unreadable().size());
        assertTrue(report.unreadable().get(0).startsWith("Broken.zip ("), report.unreadable().get(0));
    }
}
