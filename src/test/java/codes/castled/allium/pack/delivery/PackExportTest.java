package codes.castled.allium.pack.delivery;

import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.pack.ResourcePackSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackExportTest {

    @TempDir
    Path dir;

    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final PackExport.Logs logs = new PackExport.Logs(info::add, warnings::add);
    private static final byte[] PACK = "pack-bytes".getBytes();
    private static final List<AssetManifest.Tool> TOOLS = List.of(
            new AssetManifest.Tool("mob_disarmer", "allium:mob_disarmer", "golden_hoe", "Mob Disarmer", "ready", new AssetManifest.Range(1, 2), null,
                    List.of(new AssetManifest.State("full", 1, "t", "m"))),
            new AssetManifest.Tool("phantom_obliterator", "allium:phantom_obliterator", "blaze_rod", "Phantom Obliterator", "planned",
                    new AssetManifest.Range(3, 4), null, List.of(new AssetManifest.State("full", 3, "t", "m"))));

    private static ResourcePackSettings rpm(boolean dryRun) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("owner: rpm-export\nrpm-export:\n  dry-run: " + dryRun + "\n");
        return ResourcePackSettings.parse(yaml);
    }

    private Path mixer() throws Exception {
        Path mixer = dir.resolve("plugins/ResourcePackManager/mixer");
        Files.createDirectories(mixer);
        try (OutputStream out = Files.newOutputStream(mixer.resolve("Nexo_resource_pack.zip")); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("assets/minecraft/items/blaze_rod.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        // A legacy pack under a similar name, which Allium must never touch.
        try (OutputStream out = Files.newOutputStream(mixer.resolve("Allium_resource_pack.zip")); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("assets/relique/textures/x.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return mixer;
    }

    @Test
    void standaloneExportWritesThePackAndItsSha1OnlyWhenTheyChange() throws Exception {
        Path target = PackExport.exportStandalone(dir, PACK, logs);
        assertArrayEquals(PACK, Files.readAllBytes(target));
        assertEquals(PackExport.sha1(PACK) + "\n", Files.readString(target.resolveSibling("allium-core-pack.zip.sha1")));
        assertTrue(info.get(0).startsWith("Exported"));
        PackExport.exportStandalone(dir, PACK, logs);
        assertTrue(info.get(1).startsWith("Kept"), "an identical pack is not rewritten");
    }

    @Test
    void aDryRunReportsTheBlazeRodCollisionAndWritesNothing() throws Exception {
        Path mixer = mixer();
        PackExport.ExportResult result = PackExport.exportToMixer(dir.resolve("plugins"), rpm(true), PACK, TOOLS, logs);
        assertEquals(PackExport.ExportStatus.DRY_RUN, result.status());
        assertFalse(Files.exists(mixer.resolve("Allium_core_resource_pack.zip")));
        assertTrue(info.stream().anyMatch(l -> l.contains("Carrier collision ahead: Nexo_resource_pack.zip defines assets/minecraft/items/blaze_rod.json")
                && l.contains("phantom_obliterator")), info.toString());
        assertTrue(info.stream().anyMatch(l -> l.startsWith("[rpm-export dry run] Would write") && l.contains("no such file exists yet")), info.toString());
        assertEquals(List.of(), warnings);
    }

    @Test
    void anExportWritesOnlyAlliumsFileAndReconcilesStaleCoreExport() throws Exception {
        Path mixer = mixer();
        byte[] legacy = Files.readAllBytes(mixer.resolve("Allium_resource_pack.zip"));
        PackExport.ExportResult first = PackExport.exportToMixer(dir.resolve("plugins"), rpm(false), PACK, TOOLS, logs);
        assertEquals(PackExport.ExportStatus.WRITTEN, first.status());
        assertArrayEquals(PACK, Files.readAllBytes(mixer.resolve("Allium_core_resource_pack.zip")));
        assertArrayEquals(legacy, Files.readAllBytes(mixer.resolve("Allium_resource_pack.zip")), "another pack is never touched");
        try (var files = Files.list(mixer)) {
            assertEquals(3, files.count(), "no temporary file is left behind");
        }
        info.clear();
        PackExport.ExportResult second = PackExport.exportToMixer(dir.resolve("plugins"), rpm(false), PACK, TOOLS, logs);
        assertEquals(PackExport.ExportStatus.UNCHANGED, second.status());
        assertTrue(info.stream().anyMatch(l -> l.startsWith("Kept the core resource pack in ResourcePackManager's mixer")), info.toString());

        // Switching away from rpm-export removes Allium's own core ZIP while preserving foreign ZIPs.
        List<Path> removed = PackExport.reconcileUnusedCoreMixerExports(dir.resolve("plugins/Allium"), false, logs);
        assertEquals(1, removed.size());
        assertFalse(Files.exists(mixer.resolve("Allium_core_resource_pack.zip")));
        assertArrayEquals(legacy, Files.readAllBytes(mixer.resolve("Allium_resource_pack.zip")));
        assertTrue(Files.exists(mixer.resolve("Nexo_resource_pack.zip")));
    }

    @Test
    void aMissingMixerExportsNothingAndReportsMissingDirectory() throws Exception {
        PackExport.ExportResult result = PackExport.exportToMixer(dir.resolve("plugins"), rpm(false), PACK, TOOLS, logs);
        assertEquals(PackExport.ExportStatus.MISSING_DIRECTORY, result.status());
        assertTrue(warnings.get(0).contains("does not exist; nothing was exported"), warnings.toString());
        assertFalse(Files.exists(dir.resolve("plugins/ResourcePackManager")));
    }

    @Test
    void reconciliationPreservesManuallyReplacedFileWithDifferentBytes() throws Exception {
        Path mixer = mixer();
        PackExport.exportToMixer(dir.resolve("plugins"), rpm(false), PACK, TOOLS, logs);
        Path target = mixer.resolve("Allium_core_resource_pack.zip");
        byte[] customBytes = "operator-custom-pack".getBytes();
        Files.write(target, customBytes);

        warnings.clear();
        List<Path> removed = PackExport.reconcileUnusedCoreMixerExports(dir.resolve("plugins/Allium"), false, logs);
        assertTrue(removed.isEmpty());
        assertArrayEquals(customBytes, Files.readAllBytes(target), "operator-edited file must not be deleted");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("contents differ")));
    }

    @Test
    void rpmReloadReplyReflectsExplicitExportStatusInsteadOfAlwaysClaimingExport() {
        Path mixer = dir.resolve("plugins/ResourcePackManager/mixer");
        String missing = PackExport.formatRpmReloadReply(
                new PackExport.ExportResult(PackExport.ExportStatus.MISSING_DIRECTORY, mixer, null, List.of()), null);
        assertTrue(missing.contains("does not exist; nothing was exported"), missing);

        String dry = PackExport.formatRpmReloadReply(
                new PackExport.ExportResult(PackExport.ExportStatus.DRY_RUN, mixer.resolve("Allium.zip"), "abc", List.of()), null);
        assertTrue(dry.contains("Dry run only") && dry.contains("nothing was written"), dry);

        String unchanged = PackExport.formatRpmReloadReply(
                new PackExport.ExportResult(PackExport.ExportStatus.UNCHANGED, mixer.resolve("Allium.zip"), "abc", List.of()), null);
        assertTrue(unchanged.contains("already up to date"), unchanged);

        String written = PackExport.formatRpmReloadReply(
                new PackExport.ExportResult(PackExport.ExportStatus.WRITTEN, mixer.resolve("Allium.zip"), "abc",
                        List.of(mixer.resolve("Old.zip"))), null);
        assertTrue(written.contains("Re-exported") && written.contains("Removed 1 stale"), written);
    }
}
